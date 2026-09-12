package io.github.carpl2.tidebid.auction;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.auction.application.AuctionImagePreviewService;
import io.github.carpl2.tidebid.auction.application.AuctionImageVerificationService;
import io.github.carpl2.tidebid.auction.application.AuctionObjectKeyFactory;
import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
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
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.auction.support.FakeObjectStorageAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tidebid.auction.account-client.internal-token=test-internal-token-with-at-least-32-characters"
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

            AuctionImagePreviewService.ImagePreview preview =
                    new AuctionImagePreviewService(itemRepository, storage, storageProperties, clock)
                            .createOwnerPreview(stored.ownerId(), stored.objectKey());
            assertThat(preview.objectKey()).isEqualTo(stored.objectKey());
            assertThat(preview.expiresAt()).isEqualTo(
                    clock.instant().truncatedTo(ChronoUnit.MICROS).plus(storageProperties.readUrlTtl())
            );
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
}
