package io.github.carpl2.tidebid.auction;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.AuctionImagePreviewService;
import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionBidService;
import io.github.carpl2.tidebid.auction.application.AuctionDetailQueryService;
import io.github.carpl2.tidebid.auction.application.AuctionImageVerificationService;
import io.github.carpl2.tidebid.auction.application.AuctionObjectKeyFactory;
import io.github.carpl2.tidebid.auction.application.AuctionPendingImageCleanupService;
import io.github.carpl2.tidebid.auction.application.AuctionRegistrationCreationService;
import io.github.carpl2.tidebid.auction.application.AuctionDraftCreationService;
import io.github.carpl2.tidebid.auction.application.AuctionDraftFieldsValidator;
import io.github.carpl2.tidebid.auction.application.AuctionDraftUpdateService;
import io.github.carpl2.tidebid.auction.application.AuctionSubmissionService;
import io.github.carpl2.tidebid.auction.application.AuctionReviewService;
import io.github.carpl2.tidebid.auction.application.AuctionSessionOpeningService;
import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService;
import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidTransaction;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRecoveryTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationResultTransaction;
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
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionRegistrationEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.BidRecordEntity;
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
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.auction.infrastructure.scheduling.AuctionCloseCommandReconciler;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.CloseAuctionCommand;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
                "tidebid.auction.storage.enabled=false",
                "tidebid.auction.timing.opening-scan-enabled=false",
                "tidebid.auction.registration-recovery.enabled=false",
                "tidebid.auction.close-scheduling.scan-interval=5m",
                "tidebid.scheduling.enabled=false"
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
    @Autowired private AuctionBidTransaction bidTransaction;
    @Autowired private AuctionDraftUpdateService draftUpdateService;
    @Autowired private AuctionSubmissionTransaction submissionTransaction;
    @Autowired private AuctionReviewTransaction reviewTransaction;
    @Autowired private AuctionAssetQueryService assetQueryService;
    @Autowired private AuctionBidService bidService;
    @Autowired private AuctionDetailQueryService detailQueryService;
    @Autowired private AuctionRegistrationCreationService registrationCreationService;
    @Autowired private AuctionRegistrationRecoveryTransaction registrationRecoveryTransaction;
    @Autowired private AuctionRegistrationResultTransaction registrationResultTransaction;
    @Autowired private AuctionReviewService reviewService;
    @Autowired private AuctionSessionOpeningService sessionOpeningService;
    @Autowired private IdGenerator idGenerator;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcAuctionOutboxRepository outboxRepository;
    @Autowired private AuctionCloseCommandReconciler closeCommandReconciler;

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
        Instant oldCreatedAt = Instant.parse("2000-01-01T00:00:00Z");
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
                new AuctionImageCleanupProperties(Duration.ofMinutes(1), 1),
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
            String closeEventId = AuctionOutboxEventFactory
                    .deterministicCloseEventId(auctionId, session.endAt())
                    .toString();
            assertThat(outboxRepository.findByEventId(closeEventId)).get().satisfies(outbox -> {
                assertThat(outbox.eventType()).isEqualTo(CloseAuctionCommand.EVENT_TYPE);
                assertThat(outbox.aggregateId()).isEqualTo(Long.toString(auctionId));
                assertThat(outbox.deliverAt()).isEqualTo(session.endAt());
                assertThat(outbox.payload()).contains("\"expectedEndAt\"");
            });

            jdbc.update("DELETE FROM auction_outbox WHERE event_id = ?", closeEventId);
            assertThat(closeCommandReconciler.reconcile()).isPositive();
            assertThat(closeCommandReconciler.reconcile()).isZero();
            assertThat(countAuctionOutbox(auctionId, CloseAuctionCommand.EVENT_TYPE)).isOne();

            assertThatThrownBy(() -> reviewService.review(
                    new AuctionReviewService.ReviewCommand(
                            IdWorker.getId(), itemId, 1, AuctionReviewDecision.APPROVED, null
                    )
            )).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.ASSET_STATE_CONFLICT));
            assertThat(itemRepository.findReview(itemId, 1)).contains(approved.review());
        } finally {
            deleteAuctionOutbox(auctionId);
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
            assertThat(countAuctionOutbox(auctionId, CloseAuctionCommand.EVENT_TYPE)).isZero();
        } finally {
            deleteAuctionOutbox(auctionId);
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
    void awaitingCloseCasHonorsEndTimeVersionAndTerminalState() {
        long sellerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                endedAt.minus(Duration.ofHours(2)), endedAt, 4L, endedAt
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, endedAt));
            sessionRepository.insertSession(opened);

            assertThat(sessionRepository.markOpenSessionAwaitingClose(
                    auctionId, opened.version(), endedAt.minusNanos(1_000)
            )).isFalse();
            assertThat(sessionRepository.markOpenSessionAwaitingClose(
                    auctionId, opened.version() - 1, endedAt
            )).isFalse();
            assertThat(sessionRepository.markOpenSessionAwaitingClose(
                    auctionId, opened.version(), endedAt
            )).isTrue();

            AuctionSession awaitingClose = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(awaitingClose.status()).isEqualTo(AuctionSessionStatus.AWAITING_CLOSE);
            assertThat(awaitingClose.version()).isEqualTo(opened.version() + 1);
            assertThat(awaitingClose.updatedAt()).isEqualTo(endedAt);
            assertThat(sessionRepository.markOpenSessionAwaitingClose(
                    auctionId, awaitingClose.version(), endedAt
            )).isFalse();
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void bidTransactionAtomicallyUpdatesTheSessionAndInsertsTheBidRecord() {
        long sellerId = IdWorker.getId();
        long bidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long bidId = IdWorker.getId();
        Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                acceptedAt.minus(Duration.ofHours(1)), acceptedAt.plus(Duration.ofHours(1)), 5L, acceptedAt
        );
        BidRecord bid = new BidRecord(
                bidId, auctionId, bidderId, "bid-request-" + bidId,
                new BigDecimal("125.00"), null, 1L, acceptedAt
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, acceptedAt));
            sessionRepository.insertSession(opened);

            AuctionBidTransaction.AcceptedBid accepted = bidTransaction.accept(bid, opened.version());

            assertThat(accepted.bid()).usingRecursiveComparison().isEqualTo(bid);
            assertThat(accepted.session().currentPrice()).isEqualByComparingTo("125.00");
            assertThat(accepted.session().currentBidderId()).isEqualTo(bidderId);
            assertThat(accepted.session().bidCount()).isEqualTo(1L);
            assertThat(accepted.session().version()).isEqualTo(opened.version() + 1);
            assertThat(accepted.session().updatedAt()).isEqualTo(acceptedAt);
            assertThat(sessionRepository.findBid(bidderId, bid.requestId())).contains(bid);
        } finally {
            bidMapper.deleteById(bidId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void bidHistoryUsesAuctionScopedDescendingSequencePagination() {
        long sellerId = IdWorker.getId();
        long firstBidderId = IdWorker.getId();
        long secondBidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), 1L, now
        );
        BidRecord first = new BidRecord(
                IdWorker.getId(), auctionId, firstBidderId, "history-first-" + auctionId,
                new BigDecimal("100.00"), null, 1L, now.plusSeconds(1)
        );
        BidRecord second = new BidRecord(
                IdWorker.getId(), auctionId, secondBidderId, "history-second-" + auctionId,
                new BigDecimal("110.00"), new BigDecimal("100.00"), 2L, now.plusSeconds(2)
        );
        BidRecord third = new BidRecord(
                IdWorker.getId(), auctionId, firstBidderId, "history-third-" + auctionId,
                new BigDecimal("130.00"), new BigDecimal("110.00"), 3L, now.plusSeconds(3)
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);
            sessionRepository.insertBid(first);
            sessionRepository.insertBid(second);
            sessionRepository.insertBid(third);

            AuctionSessionRepository.BidPage firstPage =
                    sessionRepository.findBidsByAuction(auctionId, 0, 2);
            AuctionSessionRepository.BidPage secondPage =
                    sessionRepository.findBidsByAuction(auctionId, 2, 2);

            assertThat(firstPage.total()).isEqualTo(3L);
            assertThat(firstPage.bids()).extracting(BidRecord::sequenceNo)
                    .containsExactly(3L, 2L);
            assertThat(secondPage.total()).isEqualTo(3L);
            assertThat(secondPage.bids()).extracting(BidRecord::sequenceNo)
                    .containsExactly(1L);
            assertThat(sessionRepository.findBidsByAuction(IdWorker.getId(), 0, 20).bids()).isEmpty();
        } finally {
            bidMapper.delete(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId));
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void bidTransactionRejectsStaleVersionAndEndBoundaryWithoutWritingABid() {
        long sellerId = IdWorker.getId();
        long bidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant endAt = now.plusSeconds(30);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId, now.minusSeconds(30), endAt, 3L, now
        );
        BidRecord staleBid = new BidRecord(
                IdWorker.getId(), auctionId, bidderId, "stale-bid-request",
                new BigDecimal("100.00"), null, 1L, now
        );
        BidRecord endedBid = new BidRecord(
                IdWorker.getId(), auctionId, bidderId, "ended-bid-request",
                new BigDecimal("100.00"), null, 1L, endAt
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);

            assertThatThrownBy(() -> bidTransaction.accept(staleBid, opened.version() - 1))
                    .isInstanceOf(AuctionBidTransaction.BidConflictException.class);
            assertThatThrownBy(() -> bidTransaction.accept(endedBid, opened.version()))
                    .isInstanceOf(AuctionBidTransaction.BidConflictException.class);

            assertThat(sessionRepository.findBid(bidderId, staleBid.requestId())).isEmpty();
            assertThat(sessionRepository.findBid(bidderId, endedBid.requestId())).isEmpty();
            assertThat(sessionRepository.findSessionById(auctionId)).contains(opened);
        } finally {
            bidMapper.deleteById(staleBid.id());
            bidMapper.deleteById(endedBid.id());
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void duplicateBidInsertRollsBackTheSessionCas() {
        long sellerId = IdWorker.getId();
        long bidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long existingBidId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                new BigDecimal("100.00"), bidderId, 1L,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)),
                AuctionSessionStatus.OPEN, 6L, now.minus(Duration.ofHours(2)), now.minusSeconds(1)
        );
        BidRecord existing = new BidRecord(
                existingBidId, auctionId, bidderId, "duplicate-bid-request",
                new BigDecimal("100.00"), null, 1L, now.minusSeconds(1)
        );
        BidRecord duplicate = new BidRecord(
                IdWorker.getId(), auctionId, bidderId, existing.requestId(),
                new BigDecimal("110.00"), new BigDecimal("100.00"), 2L, now
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);
            sessionRepository.insertBid(existing);

            assertThatThrownBy(() -> bidTransaction.accept(duplicate, opened.version()))
                    .isInstanceOf(AuctionBidTransaction.DuplicateBidException.class);

            assertThat(sessionRepository.findSessionById(auctionId)).contains(opened);
            assertThat(sessionRepository.findBid(bidderId, existing.requestId())).contains(existing);
        } finally {
            bidMapper.deleteById(duplicate.id());
            bidMapper.deleteById(existingBidId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void concurrentBidsAgainstOneVersionProduceExactlyOneWinner() throws Exception {
        long sellerId = IdWorker.getId();
        long firstBidderId = IdWorker.getId();
        long secondBidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), 8L, now
        );
        BidRecord firstBid = new BidRecord(
                IdWorker.getId(), auctionId, firstBidderId, "concurrent-first-bid",
                new BigDecimal("100.00"), null, 1L, now
        );
        BidRecord secondBid = new BidRecord(
                IdWorker.getId(), auctionId, secondBidderId, "concurrent-second-bid",
                new BigDecimal("150.00"), null, 1L, now
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<String> first = executor.submit(() -> acceptBidConcurrently(
                        ready, start, firstBid, opened.version()
                ));
                Future<String> second = executor.submit(() -> acceptBidConcurrently(
                        ready, start, secondBid, opened.version()
                ));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            }

            List<BidRecordEntity> storedBids = bidMapper.selectList(
                    new LambdaQueryWrapper<BidRecordEntity>()
                            .eq(BidRecordEntity::getAuctionId, auctionId)
            );
            assertThat(storedBids).hasSize(1);
            AuctionSession winner = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(winner.bidCount()).isEqualTo(1L);
            assertThat(winner.version()).isEqualTo(opened.version() + 1);
            assertThat(winner.currentBidderId()).isEqualTo(storedBids.getFirst().getBidderId());
            assertThat(winner.currentPrice()).isEqualByComparingTo(storedBids.getFirst().getAmount());
        } finally {
            bidMapper.delete(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId));
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void multipleConcurrentBidRoundsKeepSessionAndHistoryConsistent() throws Exception {
        int rounds = 5;
        long sellerId = IdWorker.getId();
        long firstBidderId = IdWorker.getId();
        long secondBidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), 12L, now
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                for (int round = 1; round <= rounds; round++) {
                    AuctionSession before = sessionRepository.findSessionById(auctionId).orElseThrow();
                    long sequenceNo = before.bidCount() + 1;
                    BigDecimal minimumAmount = before.minimumNextBid();
                    BidRecord firstBid = new BidRecord(
                            IdWorker.getId(), auctionId, firstBidderId, "multi-round-first-" + round,
                            minimumAmount, before.currentPrice(), sequenceNo, now.plusMillis(round)
                    );
                    BidRecord secondBid = new BidRecord(
                            IdWorker.getId(), auctionId, secondBidderId, "multi-round-second-" + round,
                            minimumAmount.add(new BigDecimal("5.00")), before.currentPrice(), sequenceNo,
                            now.plusMillis(round)
                    );
                    CountDownLatch ready = new CountDownLatch(2);
                    CountDownLatch start = new CountDownLatch(1);
                    Future<String> first = executor.submit(() -> acceptBidConcurrently(
                            ready, start, firstBid, before.version()
                    ));
                    Future<String> second = executor.submit(() -> acceptBidConcurrently(
                            ready, start, secondBid, before.version()
                    ));

                    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                            .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
                }
            }

            AuctionSession settled = sessionRepository.findSessionById(auctionId).orElseThrow();
            AuctionSessionRepository.BidPage history = sessionRepository.findBidsByAuction(auctionId, 0, 20);
            assertThat(history.total()).isEqualTo(rounds);
            assertThat(countAuctionOutbox(auctionId, BidAcceptedEvent.EVENT_TYPE)).isEqualTo(rounds);
            assertThat(history.bids()).hasSize(rounds);
            assertThat(history.bids())
                    .extracting(BidRecord::sequenceNo)
                    .containsExactly(5L, 4L, 3L, 2L, 1L);

            BidRecord latestBid = history.bids().getFirst();
            assertThat(settled.currentPrice()).isEqualByComparingTo(latestBid.amount());
            assertThat(settled.currentBidderId()).isEqualTo(latestBid.bidderId());
            assertThat(settled.bidCount()).isEqualTo(rounds);
            assertThat(settled.version()).isEqualTo(opened.version() + rounds);

            BigDecimal precedingAmount = null;
            for (int index = history.bids().size() - 1; index >= 0; index--) {
                BidRecord bid = history.bids().get(index);
                assertThat(bid.sequenceNo()).isEqualTo(history.bids().size() - index);
                if (precedingAmount == null) {
                    assertThat(bid.previousPrice()).isNull();
                } else {
                    assertThat(bid.previousPrice()).isEqualByComparingTo(precedingAmount);
                }
                precedingAmount = bid.amount();
            }
        } finally {
            deleteAuctionOutbox(auctionId);
            bidMapper.delete(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId));
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void bidServiceReturnsOnePersistedResultForIdempotentRetries() {
        long sellerId = IdWorker.getId();
        long bidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long registrationId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), 4L, now
        );
        AuctionRegistration registered = new AuctionRegistration(
                registrationId, "REGISTRATION:" + registrationId, auctionId, bidderId,
                new BigDecimal("50.00"), AuctionRegistrationStatus.REGISTERED, null, 1,
                null, now.minusSeconds(30), null, null, now.minusSeconds(30), 1L,
                now.minusSeconds(60), now.minusSeconds(30)
        );
        String requestId = "service-bid-" + bidderId;

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);
            registrationRepository.insert(registered);

            assertThatThrownBy(() -> bidService.place(new AuctionBidService.PlaceBidCommand(
                    bidderId, auctionId, "too-low-" + bidderId, new BigDecimal("99.99")
            ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.BID_TOO_LOW));
            assertThat(countAuctionOutbox(auctionId, BidAcceptedEvent.EVENT_TYPE)).isZero();

            BidRecord first = bidService.place(new AuctionBidService.PlaceBidCommand(
                    bidderId, auctionId, requestId, new BigDecimal("100")
            ));
            BidRecord repeated = bidService.place(new AuctionBidService.PlaceBidCommand(
                    bidderId, auctionId, requestId, new BigDecimal("100.0")
            ));

            assertThat(repeated).isEqualTo(first);
            assertThat(bidMapper.selectCount(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId))).isEqualTo(1L);
            AuctionSession stored = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(stored.currentPrice()).isEqualByComparingTo("100.00");
            assertThat(stored.currentBidderId()).isEqualTo(bidderId);
            assertThat(stored.bidCount()).isEqualTo(1L);
            assertThat(stored.version()).isEqualTo(opened.version() + 1);

            assertThatThrownBy(() -> bidService.place(new AuctionBidService.PlaceBidCommand(
                    bidderId, auctionId, requestId, new BigDecimal("110.00")
            ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.IDEMPOTENCY_CONFLICT)
            );
            assertThat(sessionRepository.findSessionById(auctionId)).contains(stored);
            assertThat(countAuctionOutbox(auctionId, BidAcceptedEvent.EVENT_TYPE)).isOne();
        } finally {
            deleteAuctionOutbox(auctionId);
            bidMapper.delete(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId));
            registrationMapper.deleteById(registrationId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void concurrentSameBidRequestReturnsOneIdempotentResult() throws Exception {
        long sellerId = IdWorker.getId();
        long bidderId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long registrationId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession opened = openSession(
                auctionId, itemId, sellerId,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), 2L, now
        );
        AuctionRegistration registered = new AuctionRegistration(
                registrationId, "REGISTRATION:" + registrationId, auctionId, bidderId,
                new BigDecimal("50.00"), AuctionRegistrationStatus.REGISTERED, null, 1,
                null, now.minusSeconds(30), null, null, now.minusSeconds(30), 1L,
                now.minusSeconds(60), now.minusSeconds(30)
        );
        AuctionBidService.PlaceBidCommand command = new AuctionBidService.PlaceBidCommand(
                bidderId, auctionId, "concurrent-idempotent-bid", new BigDecimal("100.00")
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(opened);
            registrationRepository.insert(registered);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<BidRecord> first = executor.submit(() -> placeBidConcurrently(ready, start, command));
                Future<BidRecord> second = executor.submit(() -> placeBidConcurrently(ready, start, command));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS))
                        .isEqualTo(second.get(10, TimeUnit.SECONDS));
            }

            assertThat(bidMapper.selectCount(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId))).isEqualTo(1L);
            AuctionSession stored = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(stored.currentPrice()).isEqualByComparingTo("100.00");
            assertThat(stored.bidCount()).isEqualTo(1L);
            assertThat(stored.version()).isEqualTo(opened.version() + 1);
        } finally {
            deleteAuctionOutbox(auctionId);
            bidMapper.delete(new LambdaQueryWrapper<BidRecordEntity>()
                    .eq(BidRecordEntity::getAuctionId, auctionId));
            registrationMapper.deleteById(registrationId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    private long countAuctionOutbox(long auctionId, String eventType) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auction_outbox
                WHERE aggregate_type = 'AUCTION'
                  AND aggregate_id = ?
                  AND event_type = ?
                """, Long.class, Long.toString(auctionId), eventType);
        return count == null ? 0 : count;
    }

    private void deleteAuctionOutbox(long auctionId) {
        jdbc.update("DELETE FROM auction_outbox WHERE aggregate_type = 'AUCTION' AND aggregate_id = ?",
                Long.toString(auctionId));
    }

    @Test
    void assetDetailCatchesUpPastEndSessionWithoutSkippingLifecycleStates() {
        long sellerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant startAt = now.minus(Duration.ofHours(2));
        AuctionSession scheduled = new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                startAt, now.minusSeconds(1), AuctionSessionStatus.SCHEDULED, 9L,
                startAt.minusSeconds(60), startAt.minusSeconds(30)
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(scheduled);

            AuctionAssetQueryService.AssetDetail detail = assetQueryService.findDetail(
                    sellerId, false, itemId
            );

            assertThat(detail.sessionStatus()).isEqualTo(AuctionSessionStatus.AWAITING_CLOSE);
            assertThat(detail.sessionVersion()).isEqualTo(scheduled.version() + 2);
            AuctionSession stored = sessionRepository.findSessionById(auctionId).orElseThrow();
            assertThat(stored.status()).isEqualTo(AuctionSessionStatus.AWAITING_CLOSE);
            assertThat(stored.version()).isEqualTo(scheduled.version() + 2);
        } finally {
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void lobbyRepositoryFiltersStatesAndPaginatesSameStartTimeByAuctionId() {
        long sellerId = IdWorker.getId();
        long firstItemId = IdWorker.getId();
        long secondItemId = IdWorker.getId();
        long thirdItemId = IdWorker.getId();
        long draftItemId = IdWorker.getId();
        long firstAuctionId = IdWorker.getId();
        long secondAuctionId = IdWorker.getId();
        long thirdAuctionId = IdWorker.getId();
        long draftAuctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant commonStartAt = Instant.parse("2099-01-01T00:00:00Z");
        long totalBefore = sessionRepository.findLobbySessions(0, 1).total();

        try {
            itemRepository.insertItem(approvedItem(firstItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(secondItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(thirdItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(draftItemId, sellerId, now));
            sessionRepository.insertSession(lobbySession(
                    firstAuctionId, firstItemId, sellerId, commonStartAt, AuctionSessionStatus.SCHEDULED, now
            ));
            sessionRepository.insertSession(lobbySession(
                    secondAuctionId, secondItemId, sellerId, commonStartAt, AuctionSessionStatus.OPEN, now
            ));
            sessionRepository.insertSession(lobbySession(
                    thirdAuctionId, thirdItemId, sellerId, commonStartAt, AuctionSessionStatus.AWAITING_CLOSE, now
            ));
            sessionRepository.insertSession(lobbySession(
                    draftAuctionId, draftItemId, sellerId, commonStartAt, AuctionSessionStatus.DRAFT, now
            ));

            AuctionSessionRepository.LobbySessionPage firstPage =
                    sessionRepository.findLobbySessions(Math.toIntExact(totalBefore), 2);
            AuctionSessionRepository.LobbySessionPage secondPage =
                    sessionRepository.findLobbySessions(Math.toIntExact(totalBefore + 2), 2);

            assertThat(firstPage.total()).isEqualTo(totalBefore + 3);
            assertThat(secondPage.total()).isEqualTo(totalBefore + 3);
            assertThat(firstPage.sessions()).extracting(AuctionSession::id)
                    .containsExactly(firstAuctionId, secondAuctionId);
            assertThat(secondPage.sessions()).extracting(AuctionSession::id)
                    .containsExactly(thirdAuctionId);
            assertThat(firstPage.sessions()).extracting(AuctionSession::status)
                    .containsExactly(AuctionSessionStatus.SCHEDULED, AuctionSessionStatus.OPEN);
            assertThat(secondPage.sessions()).extracting(AuctionSession::status)
                    .containsExactly(AuctionSessionStatus.AWAITING_CLOSE);
        } finally {
            sessionMapper.deleteById(draftAuctionId);
            sessionMapper.deleteById(thirdAuctionId);
            sessionMapper.deleteById(secondAuctionId);
            sessionMapper.deleteById(firstAuctionId);
            itemMapper.deleteById(draftItemId);
            itemMapper.deleteById(thirdItemId);
            itemMapper.deleteById(secondItemId);
            itemMapper.deleteById(firstItemId);
        }
    }

    @Test
    void auctionDetailReadsApprovedItemOrderedImagesAndOnlyCurrentBuyersRegistration() {
        long sellerId = IdWorker.getId();
        long buyerId = IdWorker.getId();
        long strangerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long firstImageId = IdWorker.getId();
        long secondImageId = IdWorker.getId();
        long registrationId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant startAt = now.minusSeconds(1);
        AuctionSession session = scheduledSession(auctionId, itemId, sellerId, startAt, 1L, now);
        AuctionRegistration registration = new AuctionRegistration(
                registrationId, "REGISTRATION:" + registrationId, auctionId, buyerId,
                session.depositAmount(), AuctionRegistrationStatus.REGISTERED, null, 1,
                null, now.minusSeconds(30), null, null, now.minusSeconds(30), 1L,
                now.minusSeconds(60), now.minusSeconds(30)
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(session);
            itemRepository.insertImage(boundImage(secondImageId, itemId, sellerId, 1, now));
            itemRepository.insertImage(boundImage(firstImageId, itemId, sellerId, 0, now));
            registrationRepository.insert(registration);

            AuctionDetailQueryService.AuctionDetail buyerDetail = detailQueryService.find(buyerId, auctionId);
            AuctionDetailQueryService.AuctionDetail strangerDetail = detailQueryService.find(strangerId, auctionId);
            AuctionDetailQueryService.AuctionDetail sellerDetail = detailQueryService.find(sellerId, auctionId);

            assertThat(buyerDetail.images()).extracting(AuctionDetailQueryService.ImageView::imageId)
                    .containsExactly(firstImageId, secondImageId);
            assertThat(buyerDetail.sessionStatus()).isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(sessionRepository.findSessionById(auctionId).orElseThrow().status())
                    .isEqualTo(AuctionSessionStatus.OPEN);
            assertThat(buyerDetail.minimumNextBid()).isEqualByComparingTo(session.startPrice());
            assertThat(buyerDetail.myRegistration().registrationId()).isEqualTo(registrationId);
            assertThat(buyerDetail.myRegistration().status()).isEqualTo(AuctionRegistrationStatus.REGISTERED);
            assertThat(strangerDetail.myRegistration()).isNull();
            assertThat(sellerDetail.ownedByCurrentUser()).isTrue();
            assertThat(sellerDetail.myRegistration()).isNull();
        } finally {
            registrationMapper.deleteById(registrationId);
            imageMapper.deleteById(secondImageId);
            imageMapper.deleteById(firstImageId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void registrationRepositoryPagesOnlyTheRequestedBuyerInNewestFirstOrder() {
        long sellerId = IdWorker.getId();
        long buyerId = IdWorker.getId();
        long otherBuyerId = IdWorker.getId();
        long firstItemId = IdWorker.getId();
        long secondItemId = IdWorker.getId();
        long otherItemId = IdWorker.getId();
        long firstAuctionId = IdWorker.getId();
        long secondAuctionId = IdWorker.getId();
        long otherAuctionId = IdWorker.getId();
        long firstRegistrationId = IdWorker.getId();
        long secondRegistrationId = IdWorker.getId();
        long otherRegistrationId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        try {
            itemRepository.insertItem(approvedItem(firstItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(secondItemId, sellerId, now));
            itemRepository.insertItem(approvedItem(otherItemId, sellerId, now));
            sessionRepository.insertSession(scheduledSession(firstAuctionId, firstItemId, sellerId, now.plusSeconds(60), 0, now));
            sessionRepository.insertSession(scheduledSession(secondAuctionId, secondItemId, sellerId, now.plusSeconds(60), 0, now));
            sessionRepository.insertSession(scheduledSession(otherAuctionId, otherItemId, sellerId, now.plusSeconds(60), 0, now));
            registrationRepository.insert(pendingRegistration(
                    firstRegistrationId, firstAuctionId, buyerId, now.minusSeconds(2)
            ));
            registrationRepository.insert(pendingRegistration(
                    secondRegistrationId, secondAuctionId, buyerId, now.minusSeconds(1)
            ));
            registrationRepository.insert(pendingRegistration(
                    otherRegistrationId, otherAuctionId, otherBuyerId, now
            ));

            AuctionRegistrationRepository.RegistrationPage page =
                    registrationRepository.findByBidder(buyerId, 0, 10);

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.registrations()).extracting(AuctionRegistration::id)
                    .containsExactly(secondRegistrationId, firstRegistrationId);
            assertThat(page.registrations()).extracting(AuctionRegistration::bidderId)
                    .containsOnly(buyerId);
        } finally {
            registrationMapper.deleteById(otherRegistrationId);
            registrationMapper.deleteById(secondRegistrationId);
            registrationMapper.deleteById(firstRegistrationId);
            sessionMapper.deleteById(otherAuctionId);
            sessionMapper.deleteById(secondAuctionId);
            sessionMapper.deleteById(firstAuctionId);
            itemMapper.deleteById(otherItemId);
            itemMapper.deleteById(secondItemId);
            itemMapper.deleteById(firstItemId);
        }
    }

    @Test
    void concurrentRegistrationCreationPersistsOneStablePendingHold() throws Exception {
        long sellerId = IdWorker.getId();
        long buyerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuctionSession session = scheduledSession(
                auctionId, itemId, sellerId, now.plus(Duration.ofHours(1)), 1L, now
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(session);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<AuctionRegistrationCreationService.RegistrationCreationResult> results;
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<AuctionRegistrationCreationService.RegistrationCreationResult> first = executor.submit(
                        () -> registerConcurrently(ready, start, buyerId, auctionId)
                );
                Future<AuctionRegistrationCreationService.RegistrationCreationResult> second = executor.submit(
                        () -> registerConcurrently(ready, start, buyerId, auctionId)
                );
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(AuctionRegistrationCreationService.RegistrationCreationResult::created)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results.get(0).registration().id()).isEqualTo(results.get(1).registration().id());
            assertThat(results.get(0).registration().registrationNo())
                    .isEqualTo(results.get(1).registration().registrationNo());
            AuctionRegistration stored = registrationRepository.findByAuctionAndBidder(auctionId, buyerId)
                    .orElseThrow();
            assertThat(stored.status()).isEqualTo(AuctionRegistrationStatus.PENDING_HOLD);
            assertThat(stored.registrationNo()).isEqualTo("REGISTRATION:" + stored.id());
            assertThat(stored.depositAmount()).isEqualByComparingTo(session.depositAmount());
            assertThat(stored.attemptCount()).isZero();
            assertThat(stored.nextRetryAt()).isAfter(stored.createdAt());
            assertThat(registrationMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuctionRegistrationEntity>()
                            .eq(AuctionRegistrationEntity::getAuctionId, auctionId)
                            .eq(AuctionRegistrationEntity::getBidderId, buyerId)
            )).isEqualTo(1L);
        } finally {
            registrationRepository.findByAuctionAndBidder(auctionId, buyerId)
                    .ifPresent(registration -> registrationMapper.deleteById(registration.id()));
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void registrationHoldResultsUseConditionalStateTransitions() {
        long sellerId = IdWorker.getId();
        long buyerId = IdWorker.getId();
        long registeredItemId = IdWorker.getId();
        long registeredAuctionId = IdWorker.getId();
        long registeredId = IdWorker.getId();
        long failedItemId = IdWorker.getId();
        long failedAuctionId = IdWorker.getId();
        long failedId = IdWorker.getId();
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant firstAttemptAt = createdAt.plusSeconds(1);
        Instant retryAt = firstAttemptAt.plusSeconds(5);
        Instant secondAttemptAt = firstAttemptAt.plusSeconds(2);
        String failureCode = "ACCOUNT_WALLET_INSUFFICIENT_BALANCE";

        try {
            itemRepository.insertItem(approvedItem(registeredItemId, sellerId, createdAt));
            sessionRepository.insertSession(scheduledSession(
                    registeredAuctionId,
                    registeredItemId,
                    sellerId,
                    createdAt.plus(Duration.ofHours(1)),
                    1L,
                    createdAt
            ));
            registrationRepository.insert(pendingRegistration(
                    registeredId, registeredAuctionId, buyerId, createdAt
            ));

            AuctionRegistration scheduled = registrationResultTransaction.scheduleRetry(
                    registeredId, firstAttemptAt, retryAt
            );
            assertThat(scheduled.status()).isEqualTo(AuctionRegistrationStatus.PENDING_HOLD);
            assertThat(scheduled.attemptCount()).isEqualTo(1);
            assertThat(scheduled.lastAttemptAt()).isEqualTo(firstAttemptAt);
            assertThat(scheduled.nextRetryAt()).isEqualTo(retryAt);

            AuctionRegistration registered = registrationResultTransaction.markRegistered(
                    registeredId, secondAttemptAt
            );
            assertThat(registered.status()).isEqualTo(AuctionRegistrationStatus.REGISTERED);
            assertThat(registered.attemptCount()).isEqualTo(2);
            assertThat(registered.registeredAt()).isEqualTo(secondAttemptAt);
            assertThat(registered.nextRetryAt()).isNull();

            AuctionRegistration afterLateUnknown = registrationResultTransaction.scheduleRetry(
                    registeredId, secondAttemptAt.plusSeconds(1), secondAttemptAt.plusSeconds(6)
            );
            assertThat(afterLateUnknown).isEqualTo(registered);

            itemRepository.insertItem(approvedItem(failedItemId, sellerId, createdAt));
            sessionRepository.insertSession(scheduledSession(
                    failedAuctionId,
                    failedItemId,
                    sellerId,
                    createdAt.plus(Duration.ofHours(1)),
                    1L,
                    createdAt
            ));
            registrationRepository.insert(pendingRegistration(failedId, failedAuctionId, buyerId, createdAt));

            AuctionRegistration failed = registrationResultTransaction.markFailed(
                    failedId, failureCode, firstAttemptAt
            );
            assertThat(failed.status()).isEqualTo(AuctionRegistrationStatus.FAILED);
            assertThat(failed.failureCode()).isEqualTo(failureCode);
            assertThat(failed.attemptCount()).isEqualTo(1);
            assertThat(failed.registeredAt()).isNull();
        } finally {
            registrationMapper.deleteById(failedId);
            registrationMapper.deleteById(registeredId);
            sessionMapper.deleteById(failedAuctionId);
            sessionMapper.deleteById(registeredAuctionId);
            itemMapper.deleteById(failedItemId);
            itemMapper.deleteById(registeredItemId);
        }
    }

    @Test
    void concurrentRecoveryWorkersClaimOneDueRegistrationAndExpiredLeaseCanBeReclaimed() throws Exception {
        long sellerId = IdWorker.getId();
        long buyerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long registrationId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant leaseUntil = now.plusSeconds(30);

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(scheduledSession(
                    auctionId, itemId, sellerId, now.plus(Duration.ofHours(1)), 1L, now
            ));
            registrationRepository.insert(pendingRegistration(
                    registrationId, auctionId, buyerId, now, now.minusSeconds(1)
            ));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<List<AuctionRegistration>> claims;
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<List<AuctionRegistration>> first = executor.submit(
                        () -> claimConcurrently(ready, start, now, "recovery-worker-one", leaseUntil)
                );
                Future<List<AuctionRegistration>> second = executor.submit(
                        () -> claimConcurrently(ready, start, now, "recovery-worker-two", leaseUntil)
                );
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            }

            assertThat(claims.get(0).size() + claims.get(1).size()).isEqualTo(1);
            AuctionRegistration leased = registrationRepository.findById(registrationId).orElseThrow();
            assertThat(leased.leaseOwner()).isIn("recovery-worker-one", "recovery-worker-two");
            assertThat(leased.leaseUntil()).isEqualTo(leaseUntil);
            assertThat(registrationRecoveryTransaction.claimDue(
                    now.plusSeconds(1), "recovery-worker-three", now.plusSeconds(31), 10
            )).isEmpty();

            List<AuctionRegistration> reclaimed = registrationRecoveryTransaction.claimDue(
                    leaseUntil, "recovery-worker-three", leaseUntil.plusSeconds(30), 10
            );
            assertThat(reclaimed).hasSize(1);
            assertThat(reclaimed.getFirst().id()).isEqualTo(registrationId);
            assertThat(reclaimed.getFirst().leaseOwner()).isEqualTo("recovery-worker-three");
        } finally {
            registrationMapper.deleteById(registrationId);
            sessionMapper.deleteById(auctionId);
            itemMapper.deleteById(itemId);
        }
    }

    @Test
    void exhaustedRegistrationRemainsDiagnosableAndLeavesTheRecoveryQueue() {
        long sellerId = IdWorker.getId();
        long buyerId = IdWorker.getId();
        long itemId = IdWorker.getId();
        long auctionId = IdWorker.getId();
        long registrationId = IdWorker.getId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        AuctionRegistration pending = new AuctionRegistration(
                registrationId, "REGISTRATION:" + registrationId, auctionId, buyerId,
                new BigDecimal("50.00"), AuctionRegistrationStatus.PENDING_HOLD, null, 7,
                now.minusSeconds(1), now.minusSeconds(5), null, null, null, 7,
                now.minusSeconds(60), now.minusSeconds(5)
        );

        try {
            itemRepository.insertItem(approvedItem(itemId, sellerId, now));
            sessionRepository.insertSession(scheduledSession(
                    auctionId, itemId, sellerId, now.plus(Duration.ofHours(1)), 1L, now
            ));
            registrationRepository.insert(pending);
            assertThat(registrationRecoveryTransaction.claimDue(
                    now, "exhaustion-worker", now.plusSeconds(30), 10
            )).hasSize(1);

            AuctionRegistration exhausted = registrationResultTransaction.markRecoveryExhausted(
                    registrationId, now.plusSeconds(1)
            );

            assertThat(exhausted.status()).isEqualTo(AuctionRegistrationStatus.PENDING_HOLD);
            assertThat(exhausted.failureCode()).isNull();
            assertThat(exhausted.attemptCount()).isEqualTo(8);
            assertThat(exhausted.lastAttemptAt()).isEqualTo(now.plusSeconds(1));
            assertThat(exhausted.nextRetryAt()).isNull();
            assertThat(exhausted.leaseOwner()).isNull();
            assertThat(exhausted.leaseUntil()).isNull();
            assertThat(registrationRecoveryTransaction.claimDue(
                    now.plus(Duration.ofDays(1)), "later-worker", now.plus(Duration.ofDays(1)).plusSeconds(30), 10
            )).isEmpty();
        } finally {
            registrationMapper.deleteById(registrationId);
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

    private AuctionRegistrationCreationService.RegistrationCreationResult registerConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            long bidderId,
            long auctionId
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent registration start timed out");
        }
        return registrationCreationService.createPending(
                new AuctionRegistrationCreationService.CreateRegistrationCommand(bidderId, auctionId)
        );
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

    private String acceptBidConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            BidRecord bid,
            long expectedVersion
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent bid start timed out");
        }
        try {
            bidTransaction.accept(bid, expectedVersion);
            return "SUCCESS";
        } catch (AuctionBidTransaction.BidConflictException exception) {
            return "CONFLICT";
        }
    }

    private BidRecord placeBidConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            AuctionBidService.PlaceBidCommand command
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent idempotent bid start timed out");
        }
        return bidService.place(command);
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

    private static AuctionSession openSession(
            long auctionId,
            long itemId,
            long sellerId,
            Instant startAt,
            Instant endAt,
            long version,
            Instant now
    ) {
        return new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                startAt, endAt, AuctionSessionStatus.OPEN, version,
                startAt.minusSeconds(60), now.minusSeconds(1)
        );
    }

    private static AuctionSession lobbySession(
            long auctionId,
            long itemId,
            long sellerId,
            Instant startAt,
            AuctionSessionStatus status,
            Instant now
    ) {
        return new AuctionSession(
                auctionId, itemId, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0, startAt, startAt.plus(Duration.ofHours(2)), status, 0L,
                now.minusSeconds(180), now.minusSeconds(60)
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

    private static AuctionRegistration pendingRegistration(
            long registrationId,
            long auctionId,
            long bidderId,
            Instant now
    ) {
        return pendingRegistration(registrationId, auctionId, bidderId, now, null);
    }

    private static AuctionRegistration pendingRegistration(
            long registrationId,
            long auctionId,
            long bidderId,
            Instant now,
            Instant nextRetryAt
    ) {
        return new AuctionRegistration(
                registrationId,
                "REGISTRATION:" + registrationId,
                auctionId,
                bidderId,
                new BigDecimal("50.00"),
                AuctionRegistrationStatus.PENDING_HOLD,
                null,
                0,
                nextRetryAt,
                null,
                null,
                null,
                null,
                0,
                now,
                now
        );
    }

    private List<AuctionRegistration> claimConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            Instant now,
            String leaseOwner,
            Instant leaseUntil
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return registrationRecoveryTransaction.claimDue(now, leaseOwner, leaseUntil, 10);
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
