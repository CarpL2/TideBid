package io.github.carpl2.tidebid.auction;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.auction.application.AuctionImagePreviewService;
import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionImageVerificationService;
import io.github.carpl2.tidebid.auction.application.AuctionObjectKeyFactory;
import io.github.carpl2.tidebid.auction.application.AuctionPendingImageCleanupService;
import io.github.carpl2.tidebid.auction.application.AuctionDraftCreationService;
import io.github.carpl2.tidebid.auction.application.AuctionDraftFieldsValidator;
import io.github.carpl2.tidebid.auction.application.AuctionDraftUpdateService;
import io.github.carpl2.tidebid.auction.application.AuctionSubmissionService;
import io.github.carpl2.tidebid.auction.application.AuctionReviewService;
import io.github.carpl2.tidebid.auction.application.AuctionSessionOpeningService;
import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService;
import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionReviewTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSubmissionTransaction;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionItemImageMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionItemMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionRegistrationMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionReviewMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.BidRecordMapper;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageCleanupProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.auction.infrastructure.scheduling.AuctionSessionOpeningJob;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tidebid.auction.account-client.internal-token=test-internal-token-with-at-least-32-characters",
                "tidebid.auction.timing.opening-scan-interval=1m"
        }
)
@ActiveProfiles("local-db")
@EnabledIfEnvironmentVariable(named = "TIDEBID_AUCTION_DB_PASSWORD", matches = ".+")
class AuctionPersistenceIntegrationTest {

    @Autowired private AuctionItemRepository itemRepository;
    @Autowired private AuctionSessionRepository sessionRepository;
    @Autowired private AuctionRegistrationRepository registrationRepository;
    @Autowired private AuctionItemMapper itemMapper;
    @Autowired private AuctionItemImageMapper imageMapper;
    @Autowired private AuctionReviewMapper reviewMapper;
    @Autowired private AuctionSessionMapper sessionMapper;
    @Autowired private AuctionRegistrationMapper registrationMapper;
    @Autowired private BidRecordMapper bidMapper;
    @Autowired private AuctionImageProperties imageProperties;
    @Autowired private AuctionStorageProperties storageProperties;
    @Autowired private AuctionTimingProperties timingProperties;
    @Autowired private AuctionDraftTransaction draftTransaction;
    @Autowired private AuctionDraftUpdateService draftUpdateService;
    @Autowired private AuctionSubmissionTransaction submissionTransaction;
    @Autowired private AuctionReviewTransaction reviewTransaction;
    @Autowired private AuctionAssetQueryService assetQueryService;
    @Autowired private AuctionReviewService reviewService;
    @Autowired private AuctionSessionOpeningService sessionOpeningService;
    @Autowired private AuctionSessionOpeningJob sessionOpeningJob;
    @Autowired private IdGenerator idGenerator;
    @Autowired private Clock clock;

    @Test
    void uploadIntentServicePersistsAPendingImageInMySql() {
        FakeObjectStorageAdapter storage = new FakeObjectStorageAdapter();
        AuctionUploadIntentService service = new AuctionUploadIntentService(
                itemRepository,
                storage,
                IdWorker::getId,
                new AuctionObjectKeyFactory(),
                imageProperties,
                storageProperties,
                clock
        );

        AuctionUploadIntentService.UploadIntent intent = service.create(
                new AuctionUploadIntentService.UploadIntentCommand(
                        IdWorker.getId(), "auction-photo.webp", "image/webp", 4096, null
                )
        );

        try {
            AuctionItemImage stored = itemRepository.findImageByObjectKey(intent.objectKey()).orElseThrow();
            assertThat(stored.id()).isEqualTo(intent.imageId());
            assertThat(stored.storageStatus()).isEqualTo(AuctionImageStatus.PENDING);
            assertThat(stored.itemId()).isNull();
            assertThat(stored.sortOrder()).isNull();
            assertThat(stored.uploadExpiresAt()).isEqualTo(intent.upload().expiresAt());

            storage.store(new ObjectStoragePort.StoredObjectMetadata(
                    stored.objectKey(),
                    stored.contentType(),
                    stored.contentLength(),
                    stored.contentSha256()
            ));
            AuctionImageVerificationService.VerifiedUpload verified =
                    new AuctionImageVerificationService(itemRepository, storage, clock)
                            .verifyPendingUpload(stored.ownerId(), stored.objectKey());
            assertThat(verified.imageId()).isEqualTo(stored.id());

            Instant previewExpiresNotBefore = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    .plus(storageProperties.readUrlTtl());
            AuctionImagePreviewService.ImagePreview preview =
                    new AuctionImagePreviewService(itemRepository, storage, storageProperties, clock)
                            .createOwnerPreview(stored.ownerId(), stored.objectKey());
            Instant previewExpiresNotAfter = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    .plus(storageProperties.readUrlTtl());
            assertThat(preview.objectKey()).isEqualTo(stored.objectKey());
            assertThat(preview.expiresAt()).isBetween(previewExpiresNotBefore, previewExpiresNotAfter);
            assertThat(storage.readRequests()).containsExactly(
                    new ObjectStoragePort.ReadSigningRequest(stored.objectKey(), preview.expiresAt())
            );
        } finally {
            imageMapper.deleteById(intent.imageId());
        }
    }

    @Test
    void repositoriesRoundTripTheCompleteAuctionPersistenceGraph() {
        long itemId = IdWorker.getId();
        long imageId = IdWorker.getId();
        long reviewId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long registrationId = IdWorker.getId();
        long bidId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long bidderId = IdWorker.getId();
        long reviewerId = IdWorker.getId();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(7200);
        Instant submittedAt = createdAt.plusMillis(100);
        Instant approvedAt = createdAt.plusMillis(200);
        Instant startAt = approvedAt.plusSeconds(3600);
        Instant endAt = approvedAt.plusSeconds(7200);
        Instant bidAt = startAt.plusSeconds(1);

        AuctionItem item = new AuctionItem(
                itemId, sellerId, "Mechanical keyboard", "A hot-swappable mechanical keyboard",
                "ELECTRONICS", AuctionItemCondition.LIKE_NEW, AuctionItemReviewStatus.APPROVED,
                1, 0, submittedAt, approvedAt, createdAt, approvedAt
        );
        AuctionItemImage image = new AuctionItemImage(
                imageId, itemId, sellerId, "dev/users/test/item.webp", "item.webp",
                "image/webp", 4096, null, 0, AuctionImageStatus.BOUND,
                createdAt.plusSeconds(600), createdAt, approvedAt
        );
        AuctionReview review = new AuctionReview(
                reviewId, itemId, 1, reviewerId, AuctionReviewDecision.APPROVED,
                "The listing satisfies the platform requirements", approvedAt
        );
        AuctionSession session = new AuctionSession(
                auctionId, itemId, sellerId, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal("100.00"), bidderId, 1,
                startAt, endAt, AuctionSessionStatus.OPEN, 0, createdAt, bidAt
        );
        AuctionRegistration registration = new AuctionRegistration(
                registrationId, "REGISTRATION:" + registrationId, auctionId, bidderId,
                new BigDecimal("50.00"), AuctionRegistrationStatus.REGISTERED, null, 1,
                null, approvedAt, null, null, approvedAt, 0, createdAt, approvedAt
        );
        BidRecord bid = new BidRecord(
                bidId, auctionId, bidderId, "request_" + bidId, new BigDecimal("100.00"),
                null, 1, bidAt
        );

        try {
            assertThat(itemRepository.insertItem(item)).usingRecursiveComparison().isEqualTo(item);
            assertThat(itemRepository.insertImage(image)).usingRecursiveComparison().isEqualTo(image);
            assertThat(itemRepository.insertReview(review)).usingRecursiveComparison().isEqualTo(review);
            assertThat(sessionRepository.insertSession(session)).usingRecursiveComparison().isEqualTo(session);
            assertThat(registrationRepository.insert(registration)).usingRecursiveComparison().isEqualTo(registration);
            assertThat(sessionRepository.insertBid(bid)).usingRecursiveComparison().isEqualTo(bid);

            assertThat(itemRepository.findImageByObjectKey(image.objectKey())).contains(image);
            assertThat(itemRepository.findReview(itemId, 1)).contains(review);
            assertThat(sessionRepository.findSessionByItemId(itemId)).contains(session);
            assertThat(registrationRepository.findByRegistrationNo(registration.registrationNo()))
                    .contains(registration);
            assertThat(registrationRepository.findByAuctionAndBidder(auctionId, bidderId))
                    .contains(registration);
            assertThat(sessionRepository.findBid(bidderId, bid.requestId())).contains(bid);
        } finally {
            bidMapper.deleteById(bidId);
            registrationMapper.deleteById(registrationId);
            sessionMapper.deleteById(auctionId);
            reviewMapper.deleteById(reviewId);
            imageMapper.deleteById(imageId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void assignIdAuditFillAndOptimisticLockAreActive() {
        AuctionItemEntity entity = new AuctionItemEntity();
        entity.setSellerId(IdWorker.getId());
        entity.setTitle("Original title");
        entity.setDescription("A listing used to verify optimistic locking");
        entity.setCategory("OTHER");
        entity.setItemCondition("GOOD");
        entity.setReviewStatus("DRAFT");
        entity.setSubmissionVersion(0);

        try {
            assertThat(itemMapper.insert(entity)).isEqualTo(1);
            assertThat(entity.getId()).isPositive();
            assertThat(entity.getVersion()).isZero();
            assertThat(entity.getCreatedAt()).isNotNull();
            assertThat(entity.getUpdatedAt()).isNotNull();

            AuctionItemEntity firstWriter = itemMapper.selectById(entity.getId());
            AuctionItemEntity staleWriter = itemMapper.selectById(entity.getId());
            firstWriter.setTitle("First writer wins");
            staleWriter.setTitle("Stale writer must fail");

            assertThat(itemMapper.updateById(firstWriter)).isEqualTo(1);
            assertThat(firstWriter.getVersion()).isEqualTo(1L);
            assertThat(itemMapper.updateById(staleWriter)).isZero();

            AuctionItemEntity stored = itemMapper.selectById(entity.getId());
            assertThat(stored.getTitle()).isEqualTo("First writer wins");
            assertThat(stored.getVersion()).isEqualTo(1L);
        } finally {
            if (entity.getId() != null) {
                itemMapper.deleteById(entity.getId());
            }
        }
    }

    @Test
    void pendingImageCanBeBoundOnlyOnceAndItemPositionStaysUnique() {
        long firstItemId = IdWorker.getId();
        long secondItemId = IdWorker.getId();
        long firstImageId = IdWorker.getId();
        long secondImageId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem firstItem = draftItem(firstItemId, sellerId, now);
        AuctionItem secondItem = draftItem(secondItemId, sellerId, now);
        AuctionItemImage firstImage = pendingImage(firstImageId, sellerId, "first.webp", now);
        AuctionItemImage secondImage = pendingImage(secondImageId, sellerId, "second.webp", now);

        try {
            itemRepository.insertItem(firstItem);
            itemRepository.insertItem(secondItem);
            itemRepository.insertImage(firstImage);
            itemRepository.insertImage(secondImage);

            assertThat(itemRepository.bindPendingImage(
                    firstImageId, sellerId, firstItemId, 0, now
            )).isEqualTo(AuctionItemRepository.ImageBindingResult.BOUND);
            assertThat(itemRepository.bindPendingImage(
                    firstImageId, sellerId, secondItemId, 0, now
            )).isEqualTo(AuctionItemRepository.ImageBindingResult.NOT_PENDING);
            assertThat(itemRepository.bindPendingImage(
                    secondImageId, sellerId, firstItemId, 0, now
            )).isEqualTo(AuctionItemRepository.ImageBindingResult.POSITION_OCCUPIED);

            AuctionItemImage stored = itemRepository.findImageByObjectKey(firstImage.objectKey()).orElseThrow();
            assertThat(stored.storageStatus()).isEqualTo(AuctionImageStatus.BOUND);
            assertThat(stored.itemId()).isEqualTo(firstItemId);
            assertThat(stored.sortOrder()).isZero();
        } finally {
            imageMapper.deleteById(secondImageId);
            imageMapper.deleteById(firstImageId);
            itemMapper.deleteById(secondItemId);
            itemMapper.deleteById(firstItemId);
        }
    }

    @Test
    void cleanupExpiresOnlyOldPendingGeneratedObjects() {
        long expiredImageId = IdWorker.getId();
        long freshImageId = IdWorker.getId();
        long boundImageId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant oldCreatedAt = now.minus(Duration.ofHours(25));
        Instant freshCreatedAt = now.minus(Duration.ofMinutes(1));
        AuctionItem item = draftItem(itemId, sellerId, oldCreatedAt);
        AuctionItemImage expiredPending = cleanupImage(
                expiredImageId,
                null,
                sellerId,
                "123e4567-e89b-12d3-a456-426614174001.webp",
                null,
                AuctionImageStatus.PENDING,
                oldCreatedAt
        );
        AuctionItemImage freshPending = cleanupImage(
                freshImageId,
                null,
                sellerId,
                "123e4567-e89b-12d3-a456-426614174002.webp",
                null,
                AuctionImageStatus.PENDING,
                freshCreatedAt
        );
        AuctionItemImage bound = cleanupImage(
                boundImageId,
                itemId,
                sellerId,
                "123e4567-e89b-12d3-a456-426614174003.webp",
                0,
                AuctionImageStatus.BOUND,
                oldCreatedAt
        );
        FakeObjectStorageAdapter storage = new FakeObjectStorageAdapter();
        AuctionPendingImageCleanupService cleanupService = new AuctionPendingImageCleanupService(
                itemRepository,
                storage,
                storageProperties,
                new AuctionImageCleanupProperties(Duration.ofMinutes(1), 50),
                clock
        );

        try {
            itemRepository.insertItem(item);
            itemRepository.insertImage(expiredPending);
            itemRepository.insertImage(freshPending);
            itemRepository.insertImage(bound);

            AuctionPendingImageCleanupService.CleanupResult result = cleanupService.cleanupBatch();

            assertThat(result).isEqualTo(
                    new AuctionPendingImageCleanupService.CleanupResult(1, 1, 1, 0, 0)
            );
            assertThat(storage.deleteRequests()).containsExactly(expiredPending.objectKey());
            assertThat(itemRepository.findImageByObjectKey(expiredPending.objectKey()).orElseThrow().storageStatus())
                    .isEqualTo(AuctionImageStatus.EXPIRED);
            assertThat(itemRepository.findImageByObjectKey(freshPending.objectKey()).orElseThrow().storageStatus())
                    .isEqualTo(AuctionImageStatus.PENDING);
            assertThat(itemRepository.findImageByObjectKey(bound.objectKey()).orElseThrow().storageStatus())
                    .isEqualTo(AuctionImageStatus.BOUND);
        } finally {
            imageMapper.deleteById(boundImageId);
            imageMapper.deleteById(freshImageId);
            imageMapper.deleteById(expiredImageId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void draftCreationPersistsItemSessionAndVerifiedImagesAtomically() {
        long sellerId = IdWorker.getId();
        long firstImageId = IdWorker.getId();
        long secondImageId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItemImage firstImage = pendingImage(firstImageId, sellerId, "draft-first.webp", now);
        AuctionItemImage secondImage = pendingImage(secondImageId, sellerId, "draft-second.webp", now);
        FakeObjectStorageAdapter storage = new FakeObjectStorageAdapter();
        storage.store(metadata(firstImage));
        storage.store(metadata(secondImage));
        AuctionDraftCreationService service = new AuctionDraftCreationService(
                new AuctionImageVerificationService(itemRepository, storage, clock),
                draftTransaction,
                idGenerator,
                imageProperties,
                new AuctionDraftFieldsValidator(timingProperties, clock),
                clock
        );
        AuctionDraftTransaction.CreatedDraft created = null;

        try {
            itemRepository.insertImage(firstImage);
            itemRepository.insertImage(secondImage);
            created = service.create(new AuctionDraftCreationService.CreateDraftCommand(
                    sellerId,
                    "Mechanical keyboard",
                    "A keyboard created through the transactional draft workflow",
                    "electronics",
                    AuctionItemCondition.GOOD,
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    new BigDecimal("50.00"),
                    now.plus(Duration.ofMinutes(2)),
                    now.plus(Duration.ofHours(2)),
                    java.util.List.of(firstImage.objectKey(), secondImage.objectKey())
            ));

            assertThat(itemRepository.findItemById(created.item().id())).contains(created.item());
            assertThat(sessionRepository.findSessionByItemId(created.item().id())).contains(created.session());
            assertThat(created.session().status()).isEqualTo(AuctionSessionStatus.DRAFT);
            assertThat(created.images()).extracting(AuctionItemImage::sortOrder).containsExactly(0, 1);
            long createdItemId = created.item().id();
            assertThat(created.images()).allMatch(image ->
                    image.storageStatus() == AuctionImageStatus.BOUND
                            && Long.valueOf(createdItemId).equals(image.itemId())
            );
        } finally {
            imageMapper.deleteById(secondImageId);
            imageMapper.deleteById(firstImageId);
            if (created != null) {
                sessionMapper.deleteById(created.session().id());
                itemMapper.deleteById(created.item().id());
            }
        }
    }

    @Test
    void draftTransactionRollsBackItemSessionAndEarlierImageBindingOnLaterConflict() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long imageId = IdWorker.getId();
        long missingImageId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = draftItem(itemId, sellerId, now);
        AuctionSession session = new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                now.plus(Duration.ofMinutes(2)), now.plus(Duration.ofHours(2)),
                AuctionSessionStatus.DRAFT, 0, now, now
        );
        AuctionItemImage image = pendingImage(imageId, sellerId, "rollback.webp", now);

        try {
            itemRepository.insertImage(image);

            assertThatThrownBy(() -> draftTransaction.create(
                    item,
                    session,
                    java.util.List.of(
                            new AuctionDraftTransaction.ImageBinding(imageId, sellerId, image.objectKey(), 0),
                            new AuctionDraftTransaction.ImageBinding(
                                    missingImageId,
                                    sellerId,
                                    "dev/users/" + sellerId + "/202609/missing.webp",
                                    1
                            )
                    ),
                    now
            )).isInstanceOf(AuctionDraftTransaction.ImageBindingConflictException.class);

            assertThat(itemRepository.findItemById(itemId)).isEmpty();
            assertThat(sessionRepository.findSessionById(auctionId)).isEmpty();
            AuctionItemImage rolledBack = itemRepository.findImageByObjectKey(image.objectKey()).orElseThrow();
            assertThat(rolledBack.storageStatus()).isEqualTo(AuctionImageStatus.PENDING);
            assertThat(rolledBack.itemId()).isNull();
        } finally {
            imageMapper.deleteById(imageId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void draftUpdatePersistsItemAndSessionWithIndependentOptimisticVersions() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = draftItem(itemId, sellerId, now);
        AuctionSession session = draftSession(auctionId, itemId, sellerId, now, 0L);

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);

            AuctionDraftTransaction.UpdatedDraft updated = draftUpdateService.update(
                    new AuctionDraftUpdateService.UpdateDraftCommand(
                            sellerId, itemId, 0L, 0L,
                            "Updated mechanical keyboard",
                            "The seller updated both auction item and session fields",
                            "electronics",
                            AuctionItemCondition.LIKE_NEW,
                            new BigDecimal("120"),
                            new BigDecimal("20"),
                            new BigDecimal("60"),
                            now.plus(Duration.ofMinutes(3)),
                            now.plus(Duration.ofHours(3))
                    )
            );

            assertThat(updated.item().title()).isEqualTo("Updated mechanical keyboard");
            assertThat(updated.item().category()).isEqualTo("ELECTRONICS");
            assertThat(updated.item().version()).isEqualTo(1L);
            assertThat(updated.session().startPrice()).isEqualByComparingTo("120.00");
            assertThat(updated.session().bidIncrement()).isEqualByComparingTo("20.00");
            assertThat(updated.session().depositAmount()).isEqualByComparingTo("60.00");
            assertThat(updated.session().version()).isEqualTo(1L);
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void draftUpdateRollsBackItemWhenSessionVersionConflicts() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem originalItem = draftItem(itemId, sellerId, now);
        AuctionSession originalSession = draftSession(auctionId, itemId, sellerId, now, 0L);
        AuctionItem changedItem = new AuctionItem(
                originalItem.id(), originalItem.sellerId(),
                "This title must roll back", originalItem.description(), originalItem.category(),
                originalItem.itemCondition(), originalItem.reviewStatus(), originalItem.submissionVersion(),
                originalItem.version(), originalItem.submittedAt(), originalItem.approvedAt(),
                originalItem.createdAt(), now
        );
        AuctionSession staleSession = new AuctionSession(
                originalSession.id(), originalSession.itemId(), originalSession.sellerId(),
                new BigDecimal("120.00"), originalSession.bidIncrement(), originalSession.depositAmount(),
                originalSession.currentPrice(), originalSession.currentBidderId(), originalSession.bidCount(),
                originalSession.startAt(), originalSession.endAt(), originalSession.status(),
                99L, originalSession.createdAt(), now
        );

        try {
            itemRepository.insertItem(originalItem);
            sessionRepository.insertSession(originalSession);

            assertThatThrownBy(() -> draftTransaction.update(changedItem, staleSession))
                    .isInstanceOf(AuctionDraftTransaction.DraftUpdateConflictException.class);

            AuctionItem rolledBackItem = itemRepository.findItemById(itemId).orElseThrow();
            assertThat(rolledBackItem.title()).isEqualTo(originalItem.title());
            assertThat(rolledBackItem.version()).isZero();
            assertThat(sessionRepository.findSessionById(auctionId).orElseThrow().version()).isZero();
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void sellerAssetQueryUsesStablePaginationAndKeepsSellerDataIsolated() {
        long sellerId = IdWorker.getId();
        long anotherSellerId = IdWorker.getId();
        long firstItemId = IdWorker.getId();
        long secondItemId = IdWorker.getId();
        long anotherSellerItemId = IdWorker.getId();
        long firstAuctionId = IdWorker.getId();
        long secondAuctionId = IdWorker.getId();
        long anotherSellerAuctionId = IdWorker.getId();
        long firstImageId = IdWorker.getId();
        long secondImageId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long lowerItemId = Math.min(firstItemId, secondItemId);
        long higherItemId = Math.max(firstItemId, secondItemId);

        try {
            itemRepository.insertItem(draftItem(lowerItemId, sellerId, now));
            itemRepository.insertItem(draftItem(higherItemId, sellerId, now));
            itemRepository.insertItem(draftItem(anotherSellerItemId, anotherSellerId, now));
            sessionRepository.insertSession(draftSession(firstAuctionId, lowerItemId, sellerId, now, 0L));
            sessionRepository.insertSession(draftSession(secondAuctionId, higherItemId, sellerId, now, 0L));
            sessionRepository.insertSession(draftSession(
                    anotherSellerAuctionId, anotherSellerItemId, anotherSellerId, now, 0L
            ));
            itemRepository.insertImage(boundImage(firstImageId, lowerItemId, sellerId, 0, now));
            itemRepository.insertImage(boundImage(secondImageId, higherItemId, sellerId, 0, now));

            AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AssetSummary> firstPage =
                    assetQueryService.findMine(sellerId, 1, 1);
            AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AssetSummary> secondPage =
                    assetQueryService.findMine(sellerId, 2, 1);

            assertThat(firstPage.total()).isEqualTo(2);
            assertThat(firstPage.totalPages()).isEqualTo(2);
            assertThat(firstPage.items()).extracting(AuctionAssetQueryService.AssetSummary::itemId)
                    .containsExactly(higherItemId);
            assertThat(secondPage.items()).extracting(AuctionAssetQueryService.AssetSummary::itemId)
                    .containsExactly(lowerItemId);
            assertThat(firstPage.items().getFirst().coverImage()).isNotNull();
            assertThat(firstPage.items().getFirst().coverImage().previewUrl()).isNull();
            assertThat(assetQueryService.findDetail(sellerId, false, lowerItemId).sellerId())
                    .isEqualTo(sellerId);
        } finally {
            imageMapper.deleteById(secondImageId);
            imageMapper.deleteById(firstImageId);
            sessionMapper.deleteById(anotherSellerAuctionId);
            sessionMapper.deleteById(secondAuctionId);
            sessionMapper.deleteById(firstAuctionId);
            itemMapper.deleteById(anotherSellerItemId);
            itemMapper.deleteById(higherItemId);
            itemMapper.deleteById(lowerItemId);
        }
    }

    @Test
    void administratorPendingReviewQueryUsesSubmissionOrderAndExcludesDrafts() {
        long sellerId = IdWorker.getId();
        long firstGeneratedItemId = IdWorker.getId();
        long secondGeneratedItemId = IdWorker.getId();
        long draftItemId = IdWorker.getId();
        long firstAuctionId = IdWorker.getId();
        long secondAuctionId = IdWorker.getId();
        long draftAuctionId = IdWorker.getId();
        long firstImageId = IdWorker.getId();
        long secondImageId = IdWorker.getId();
        long lowerItemId = Math.min(firstGeneratedItemId, secondGeneratedItemId);
        long higherItemId = Math.max(firstGeneratedItemId, secondGeneratedItemId);
        Instant submittedAt = Instant.parse("2001-01-01T00:00:00Z");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        try {
            itemRepository.insertItem(pendingReviewItem(lowerItemId, sellerId, submittedAt));
            itemRepository.insertItem(pendingReviewItem(higherItemId, sellerId, submittedAt));
            itemRepository.insertItem(draftItem(draftItemId, sellerId, now));
            sessionRepository.insertSession(draftSession(firstAuctionId, lowerItemId, sellerId, now, 0L));
            sessionRepository.insertSession(draftSession(secondAuctionId, higherItemId, sellerId, now, 0L));
            sessionRepository.insertSession(draftSession(draftAuctionId, draftItemId, sellerId, now, 0L));
            itemRepository.insertImage(boundImage(firstImageId, lowerItemId, sellerId, 0, now));
            itemRepository.insertImage(boundImage(secondImageId, higherItemId, sellerId, 0, now));

            AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AdminReviewSummary> page =
                    assetQueryService.findPendingReviews(true, 1, 2);

            assertThat(page.total()).isGreaterThanOrEqualTo(2L);
            assertThat(page.items()).extracting(AuctionAssetQueryService.AdminReviewSummary::itemId)
                    .containsExactly(lowerItemId, higherItemId);
            assertThat(page.items()).extracting(AuctionAssetQueryService.AdminReviewSummary::reviewStatus)
                    .containsOnly(AuctionItemReviewStatus.PENDING_REVIEW);
            assertThat(page.items()).extracting(summary -> summary.coverImage().imageId())
                    .containsExactly(firstImageId, secondImageId);
            assertThat(page.items()).extracting(summary -> summary.coverImage().previewUrl())
                    .containsOnlyNulls();
        } finally {
            imageMapper.deleteById(secondImageId);
            imageMapper.deleteById(firstImageId);
            sessionMapper.deleteById(draftAuctionId);
            sessionMapper.deleteById(secondAuctionId);
            sessionMapper.deleteById(firstAuctionId);
            itemMapper.deleteById(draftItemId);
            itemMapper.deleteById(higherItemId);
            itemMapper.deleteById(lowerItemId);
        }
    }

    @Test
    void submissionPersistsPendingReviewAndLocksTheSubmittedDraft() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long imageId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = draftItem(itemId, sellerId, now);
        AuctionSession session = draftSession(auctionId, itemId, sellerId, now, 0L);
        AuctionItemImage image = boundImage(imageId, itemId, sellerId, 0, now);
        FakeObjectStorageAdapter storage = new FakeObjectStorageAdapter();
        storage.store(metadata(image));
        AuctionSubmissionService service = new AuctionSubmissionService(
                itemRepository,
                sessionRepository,
                new AuctionImageVerificationService(itemRepository, storage, clock),
                new AuctionDraftFieldsValidator(timingProperties, clock),
                submissionTransaction,
                clock
        );

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);
            itemRepository.insertImage(image);

            AuctionSubmissionTransaction.SubmittedAuction submitted = service.submit(
                    new AuctionSubmissionService.SubmitCommand(sellerId, itemId, 0L, 0L)
            );

            assertThat(submitted.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.PENDING_REVIEW);
            assertThat(submitted.item().submissionVersion()).isEqualTo(1);
            assertThat(submitted.item().version()).isEqualTo(1L);
            assertThat(submitted.item().submittedAt()).isNotNull();
            assertThat(submitted.session().status()).isEqualTo(AuctionSessionStatus.DRAFT);
            assertThat(submitted.session().version()).isZero();
            assertThat(storage.headRequests()).containsExactly(image.objectKey());

            assertThatThrownBy(() -> draftUpdateService.update(
                    new AuctionDraftUpdateService.UpdateDraftCommand(
                            sellerId, itemId, 1L, 0L,
                            "Locked title", "The submitted snapshot must no longer be editable",
                            "ELECTRONICS", AuctionItemCondition.GOOD,
                            new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                            now.plus(Duration.ofMinutes(3)), now.plus(Duration.ofHours(2))
                    )
            )).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.ASSET_STATE_CONFLICT));
        } finally {
            imageMapper.deleteById(imageId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void approvalAtomicallyPersistsReviewAndSchedulesSession() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long reviewerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = pendingReviewItem(itemId, sellerId, now.minusSeconds(60));
        AuctionSession session = draftSession(auctionId, itemId, sellerId, now, 0L);

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);

            AuctionReviewTransaction.ReviewedAuction approved = reviewService.review(
                    new AuctionReviewService.ReviewCommand(
                            reviewerId, itemId, 1, AuctionReviewDecision.APPROVED, "  Approved for auction  "
                    )
            );

            assertThat(approved.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.APPROVED);
            assertThat(approved.item().approvedAt()).isEqualTo(approved.review().reviewedAt());
            assertThat(approved.item().version()).isEqualTo(2L);
            assertThat(approved.session().status()).isEqualTo(AuctionSessionStatus.SCHEDULED);
            assertThat(approved.session().version()).isEqualTo(1L);
            assertThat(approved.review().decision()).isEqualTo(AuctionReviewDecision.APPROVED);
            assertThat(approved.review().reviewerId()).isEqualTo(reviewerId);
            assertThat(approved.review().comment()).isEqualTo("Approved for auction");
            assertThat(itemRepository.findReview(itemId, 1)).contains(approved.review());

            assertThatThrownBy(() -> reviewService.review(
                    new AuctionReviewService.ReviewCommand(
                            IdWorker.getId(), itemId, 1, AuctionReviewDecision.APPROVED, null
                    )
            )).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.ASSET_STATE_CONFLICT));
            assertThat(itemRepository.findReview(itemId, 1)).contains(approved.review());
        } finally {
            itemRepository.findReview(itemId, 1).ifPresent(review -> reviewMapper.deleteById(review.id()));
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void approvalRollsBackItemWhenSessionCasFails() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long reviewerId = IdWorker.getId();
        long reviewId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = pendingReviewItem(itemId, sellerId, now.minusSeconds(60));
        AuctionSession storedSession = draftSession(auctionId, itemId, sellerId, now, 0L);
        AuctionSession staleSession = new AuctionSession(
                storedSession.id(), storedSession.itemId(), storedSession.sellerId(),
                storedSession.startPrice(), storedSession.bidIncrement(), storedSession.depositAmount(),
                null, null, 0L, storedSession.startAt(), storedSession.endAt(),
                AuctionSessionStatus.DRAFT, 1L, storedSession.createdAt(), storedSession.updatedAt()
        );
        AuctionReview review = new AuctionReview(
                reviewId, itemId, 1, reviewerId, AuctionReviewDecision.APPROVED, null, now
        );

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(storedSession);

            assertThatThrownBy(() -> reviewTransaction.approve(review, item.version(), staleSession))
                    .isInstanceOf(AuctionReviewTransaction.ReviewConflictException.class);

            assertThat(itemRepository.findItemById(itemId).orElseThrow().reviewStatus())
                    .isEqualTo(AuctionItemReviewStatus.PENDING_REVIEW);
            assertThat(itemRepository.findItemById(itemId).orElseThrow().version()).isEqualTo(1L);
            assertThat(sessionRepository.findSessionById(auctionId).orElseThrow().status())
                    .isEqualTo(AuctionSessionStatus.DRAFT);
            assertThat(itemRepository.findReview(itemId, 1)).isEmpty();
        } finally {
            reviewMapper.deleteById(reviewId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void scheduledSessionCasRequiresDueTimeCurrentVersionAndScheduledState() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant startAt = now.plusSeconds(60);
        AuctionItem item = approvedItem(itemId, sellerId, now);
        AuctionSession session = scheduledSession(auctionId, itemId, sellerId, startAt, 3L, now);

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);

            assertThat(sessionRepository.openScheduledSession(auctionId, session.version(), now)).isFalse();
            assertThat(sessionRepository.openScheduledSession(auctionId, session.version() - 1, startAt)).isFalse();
            assertThat(sessionRepository.openScheduledSession(auctionId, session.version(), startAt)).isTrue();

            AuctionSession opened = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(opened.status()).isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(opened.version()).isEqualTo(session.version() + 1);
            assertThat(opened.updatedAt()).isEqualTo(startAt);

            assertThat(sessionRepository.openScheduledSession(auctionId, opened.version(), startAt)).isFalse();
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void concurrentScheduledSessionCasAllowsExactlyOneWinner() throws Exception {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = approvedItem(itemId, sellerId, now);
        AuctionSession session = scheduledSession(
                auctionId, itemId, sellerId, now.minusSeconds(1), 1L, now
        );

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<Boolean> first = executor.submit(() -> openSessionConcurrently(
                        ready, start, auctionId, session.version(), now
                ));
                Future<Boolean> second = executor.submit(() -> openSessionConcurrently(
                        ready, start, auctionId, session.version(), now
                ));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }

            AuctionSession opened = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(opened.status()).isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(opened.version()).isEqualTo(session.version() + 1);
            assertThat(opened.updatedAt()).isEqualTo(now);
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void openingScanReadsDueSessionsInStableBatchesAndLeavesFutureSessionScheduled() {
        long sellerId = IdWorker.getId();
        long olderItemId = IdWorker.getId();
        long newerItemId = IdWorker.getId();
        long futureItemId = IdWorker.getId();
        long olderAuctionId = IdWorker.getId();
        long newerAuctionId = IdWorker.getId();
        long futureAuctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession older = scheduledSession(
                olderAuctionId, olderItemId, sellerId, now.minusSeconds(120), 1L, now
        );
        AuctionSession newer = scheduledSession(
                newerAuctionId, newerItemId, sellerId, now.minusSeconds(60), 4L, now
        );
        AuctionSession future = scheduledSession(
                futureAuctionId, futureItemId, sellerId, now.plusSeconds(60), 2L, now
        );

        try {
            itemRepository.insertItem(approvedItem(olderItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(newerItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(futureItemId, sellerId, now));
            sessionRepository.insertSession(older);
            sessionRepository.insertSession(newer);
            sessionRepository.insertSession(future);

            assertThat(sessionRepository.findDueScheduledSessions(now, 1))
                    .extracting(AuctionSession::id)
                    .containsExactly(olderAuctionId);
            assertThat(sessionOpeningJob).isNotNull();

            assertThat(sessionOpeningService.openDueSessions())
                    .isEqualTo(new AuctionSessionOpeningService.OpeningResult(2, 2, 0));
            assertThat(sessionRepository.findSessionById(olderAuctionId).orElseThrow().status())
                    .isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(sessionRepository.findSessionById(newerAuctionId).orElseThrow().status())
                    .isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(sessionRepository.findSessionById(futureAuctionId).orElseThrow().status())
                    .isEqualTo(AuctionSessionStatus.SCHEDULED);
        } finally {
            sessionMapper.deleteById(olderAuctionId);
            sessionMapper.deleteById(newerAuctionId);
            sessionMapper.deleteById(futureAuctionId);
            itemMapper.deleteById(olderItemId);
            itemMapper.deleteById(newerItemId);
            itemMapper.deleteById(futureItemId);
        }
    }

    @Test
    void assetDetailLazilyOpensADueScheduledSessionUsingMySqlCas() {
        long sellerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession scheduled = scheduledSession(
                auctionId, itemId, sellerId, now.minusSeconds(1), 6L, now
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(scheduled);

            AuctionAssetQueryService.AssetDetail detail = assetQueryService.findDetail(
                    sellerId, false, itemId
            );

            assertThat(detail.sessionStatus()).isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(detail.sessionVersion()).isEqualTo(scheduled.version() + 1);
            AuctionSession stored = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(stored.status()).isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(stored.version()).isEqualTo(scheduled.version() + 1);
            assertThat(stored.updatedAt()).isAfterOrEqualTo(now);
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void rejectionPersistsReasonAndAllowsEditThenNewSubmissionVersion() {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        long reviewerId = IdWorker.getId();
        long imageId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = draftItem(itemId, sellerId, now);
        AuctionSession session = draftSession(auctionId, itemId, sellerId, now, 0L);
        AuctionItemImage image = boundImage(imageId, itemId, sellerId, 0, now);
        FakeObjectStorageAdapter storage = new FakeObjectStorageAdapter();
        storage.store(metadata(image));
        AuctionSubmissionService submissionService = new AuctionSubmissionService(
                itemRepository,
                sessionRepository,
                new AuctionImageVerificationService(itemRepository, storage, clock),
                new AuctionDraftFieldsValidator(timingProperties, clock),
                submissionTransaction,
                clock
        );

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);
            itemRepository.insertImage(image);
            AuctionSubmissionTransaction.SubmittedAuction firstSubmission = submissionService.submit(
                    new AuctionSubmissionService.SubmitCommand(sellerId, itemId, 0L, 0L)
            );

            AuctionReviewTransaction.ReviewedAuction rejected = reviewService.review(
                    new AuctionReviewService.ReviewCommand(
                            reviewerId, itemId, 1, AuctionReviewDecision.REJECTED, "  Add clearer photos  "
                    )
            );

            assertThat(rejected.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.REJECTED);
            assertThat(rejected.item().approvedAt()).isNull();
            assertThat(rejected.item().version()).isEqualTo(2L);
            assertThat(rejected.session().status()).isEqualTo(AuctionSessionStatus.DRAFT);
            assertThat(rejected.session().version()).isZero();
            assertThat(rejected.review().comment()).isEqualTo("Add clearer photos");

            Instant revisedStart = now.plus(Duration.ofMinutes(4));
            AuctionDraftTransaction.UpdatedDraft revised = draftUpdateService.update(
                    new AuctionDraftUpdateService.UpdateDraftCommand(
                            sellerId, itemId, rejected.item().version(), rejected.session().version(),
                            "Mechanical keyboard with clearer photos",
                            "Updated listing after the administrator requested clearer auction photos",
                            "ELECTRONICS", AuctionItemCondition.GOOD,
                            new BigDecimal("110.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                            revisedStart, revisedStart.plus(Duration.ofHours(2))
                    )
            );
            AuctionSubmissionTransaction.SubmittedAuction secondSubmission = submissionService.submit(
                    new AuctionSubmissionService.SubmitCommand(
                            sellerId, itemId, revised.item().version(), revised.session().version()
                    )
            );

            assertThat(firstSubmission.item().submissionVersion()).isEqualTo(1);
            assertThat(secondSubmission.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.PENDING_REVIEW);
            assertThat(secondSubmission.item().submissionVersion()).isEqualTo(2);
            assertThat(secondSubmission.session().status()).isEqualTo(AuctionSessionStatus.DRAFT);
            assertThat(itemRepository.findReview(itemId, 1)).contains(rejected.review());
        } finally {
            itemRepository.findReview(itemId, 1).ifPresent(review -> reviewMapper.deleteById(review.id()));
            imageMapper.deleteById(imageId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void concurrentAdministratorsCanPersistOnlyOneDecisionForOneSubmission() throws Exception {
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long sellerId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionItem item = pendingReviewItem(itemId, sellerId, now.minusSeconds(60));
        AuctionSession session = draftSession(auctionId, itemId, sellerId, now, 0L);
        AuctionReview approval = new AuctionReview(
                IdWorker.getId(), itemId, 1, IdWorker.getId(),
                AuctionReviewDecision.APPROVED, null, now
        );
        AuctionReview rejection = new AuctionReview(
                IdWorker.getId(), itemId, 1, IdWorker.getId(),
                AuctionReviewDecision.REJECTED, "Needs clearer photos", now
        );

        try {
            itemRepository.insertItem(item);
            sessionRepository.insertSession(session);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<String> first = executor.submit(() -> reviewConcurrently(
                        ready, start, () -> reviewTransaction.approve(approval, item.version(), session)
                ));
                Future<String> second = executor.submit(() -> reviewConcurrently(
                        ready, start, () -> reviewTransaction.reject(rejection, item.version(), session)
                ));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            }

            AuctionReview storedReview = itemRepository.findReview(itemId, 1).orElseThrow();
            AuctionItem storedItem = itemRepository.findItemById(itemId).orElseThrow();
            AuctionSession storedSession = sessionRepository.findSessionById(auctionId).orElseThrow();
            if (storedReview.decision() == AuctionReviewDecision.APPROVED) {
                assertThat(storedItem.reviewStatus()).isEqualTo(AuctionItemReviewStatus.APPROVED);
                assertThat(storedSession.status()).isEqualTo(AuctionSessionStatus.SCHEDULED);
            } else {
                assertThat(storedItem.reviewStatus()).isEqualTo(AuctionItemReviewStatus.REJECTED);
                assertThat(storedSession.status()).isEqualTo(AuctionSessionStatus.DRAFT);
            }
        } finally {
            itemRepository.findReview(itemId, 1).ifPresent(review -> reviewMapper.deleteById(review.id()));
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    private static String reviewConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            Supplier<AuctionReviewTransaction.ReviewedAuction> operation
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent review start timed out");
        }
        try {
            operation.get();
            return "SUCCESS";
        } catch (AuctionReviewTransaction.ReviewConflictException exception) {
            return "CONFLICT";
        }
    }

    private boolean openSessionConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            long auctionId,
            long expectedVersion,
            Instant openedAt
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent session opening timed out");
        }
        return sessionRepository.openScheduledSession(auctionId, expectedVersion, openedAt);
    }

    private static AuctionItem draftItem(long itemId, long sellerId, Instant now) {
        return new AuctionItem(
                itemId,
                sellerId,
                "Mechanical keyboard",
                "A keyboard used to verify atomic image binding",
                "ELECTRONICS",
                AuctionItemCondition.GOOD,
                AuctionItemReviewStatus.DRAFT,
                0,
                0,
                null,
                null,
                now.minusSeconds(60),
                now.minusSeconds(60)
        );
    }

    private static AuctionItem pendingReviewItem(long itemId, long sellerId, Instant submittedAt) {
        return new AuctionItem(
                itemId,
                sellerId,
                "Submitted mechanical keyboard",
                "A submitted auction item waiting for an administrator decision",
                "ELECTRONICS",
                AuctionItemCondition.GOOD,
                AuctionItemReviewStatus.PENDING_REVIEW,
                1,
                1,
                submittedAt,
                null,
                submittedAt.minusSeconds(60),
                submittedAt
        );
    }

    private static AuctionItem approvedItem(long itemId, long sellerId, Instant now) {
        return new AuctionItem(
                itemId,
                sellerId,
                "Approved mechanical keyboard",
                "An approved auction item used to verify session lifecycle transitions",
                "ELECTRONICS",
                AuctionItemCondition.GOOD,
                AuctionItemReviewStatus.APPROVED,
                1,
                2,
                now.minusSeconds(120),
                now.minusSeconds(60),
                now.minusSeconds(180),
                now.minusSeconds(60)
        );
    }

    private static AuctionSession scheduledSession(
            long auctionId,
            long itemId,
            long sellerId,
            Instant startAt,
            long version,
            Instant now
    ) {
        return new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                startAt, startAt.plus(Duration.ofHours(2)),
                AuctionSessionStatus.SCHEDULED, version, now.minusSeconds(180), now.minusSeconds(60)
        );
    }

    private static AuctionSession draftSession(
            long auctionId,
            long itemId,
            long sellerId,
            Instant now,
            long version
    ) {
        return new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                now.plus(Duration.ofMinutes(2)), now.plus(Duration.ofHours(2)),
                AuctionSessionStatus.DRAFT, version, now, now
        );
    }

    private static AuctionItemImage pendingImage(
            long imageId,
            long ownerId,
            String filename,
            Instant now
    ) {
        return new AuctionItemImage(
                imageId,
                null,
                ownerId,
                "dev/users/" + ownerId + "/202609/" + filename,
                filename,
                "image/webp",
                4096,
                null,
                null,
                AuctionImageStatus.PENDING,
                now.plusSeconds(60),
                now.minusSeconds(60),
                now.minusSeconds(60)
        );
    }

    private static AuctionItemImage boundImage(
            long imageId,
            long itemId,
            long ownerId,
            int sortOrder,
            Instant now
    ) {
        return new AuctionItemImage(
                imageId,
                itemId,
                ownerId,
                "dev/users/" + ownerId + "/202609/query-" + imageId + ".webp",
                "query.webp",
                "image/webp",
                4096,
                null,
                sortOrder,
                AuctionImageStatus.BOUND,
                now.plusSeconds(60),
                now.minusSeconds(60),
                now.minusSeconds(60)
        );
    }

    private static AuctionItemImage cleanupImage(
            long imageId,
            Long itemId,
            long ownerId,
            String filename,
            Integer sortOrder,
            AuctionImageStatus status,
            Instant createdAt
    ) {
        return new AuctionItemImage(
                imageId,
                itemId,
                ownerId,
                "dev/users/" + ownerId + "/202609/" + filename,
                filename,
                "image/webp",
                4096,
                null,
                sortOrder,
                status,
                createdAt.plus(Duration.ofMinutes(10)),
                createdAt,
                createdAt
        );
    }

    private static ObjectStoragePort.StoredObjectMetadata metadata(AuctionItemImage image) {
        return new ObjectStoragePort.StoredObjectMetadata(
                image.objectKey(), image.contentType(), image.contentLength(), image.contentSha256()
        );
    }
}
