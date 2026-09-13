package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class AuctionDetailQueryService {

    private static final Set<AuctionSessionStatus> VISIBLE_STATUSES = Set.of(
            AuctionSessionStatus.SCHEDULED,
            AuctionSessionStatus.OPEN,
            AuctionSessionStatus.AWAITING_CLOSE
    );

    private final AuctionSessionRepository sessionRepository;
    private final AuctionItemRepository itemRepository;
    private final AuctionRegistrationRepository registrationRepository;
    private final AuctionSessionLifecycleService lifecycleService;
    private final ObjectStoragePort objectStorage;
    private final AuctionStorageProperties storageProperties;
    private final Clock clock;

    public AuctionDetailQueryService(
            AuctionSessionRepository sessionRepository,
            AuctionItemRepository itemRepository,
            AuctionRegistrationRepository registrationRepository,
            AuctionSessionLifecycleService lifecycleService,
            ObjectStoragePort objectStorage,
            AuctionStorageProperties storageProperties,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.registrationRepository = registrationRepository;
        this.lifecycleService = lifecycleService;
        this.objectStorage = objectStorage;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    public AuctionDetail find(long requesterId, long auctionId) {
        requirePositive(requesterId, "requesterId");
        requirePositive(auctionId, "auctionId");
        AuctionSession session = sessionRepository.findSessionById(auctionId)
                .map(lifecycleService::advanceToCurrentState)
                .filter(current -> VISIBLE_STATUSES.contains(current.status()))
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        AuctionItem item = itemRepository.findItemById(session.itemId())
                .filter(stored -> stored.reviewStatus() == AuctionItemReviewStatus.APPROVED)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        if (item.sellerId() != session.sellerId()) {
            throw new IllegalStateException("Auction item and session sellers do not match");
        }

        Instant previewExpiresAt = previewExpiresAt();
        List<ImageView> images = itemRepository.findBoundImagesByItemIds(List.of(item.id())).stream()
                .map(image -> imageView(item, image, previewExpiresAt))
                .toList();
        RegistrationView myRegistration = item.sellerId() == requesterId
                ? null
                : registrationRepository.findByAuctionAndBidder(auctionId, requesterId)
                        .map(AuctionDetailQueryService::registrationView)
                        .orElse(null);
        return new AuctionDetail(
                item.id(), session.id(), item.title(), item.description(), item.category(), item.itemCondition(),
                session.status(), session.startPrice(), session.bidIncrement(), session.depositAmount(),
                session.currentPrice(), session.displayPrice(), session.minimumNextBid(), session.bidCount(), session.startAt(),
                session.endAt(), item.sellerId() == requesterId, images, myRegistration
        );
    }

    private ImageView imageView(AuctionItem item, AuctionItemImage image, Instant previewExpiresAt) {
        if (!Long.valueOf(item.id()).equals(image.itemId()) || image.ownerId() != item.sellerId()) {
            throw new IllegalStateException("Auction image does not belong to the item and seller");
        }
        URI previewUrl = null;
        Instant expiresAt = null;
        if (previewExpiresAt != null) {
            ObjectStoragePort.SignedRead signedRead = objectStorage.signRead(
                    new ObjectStoragePort.ReadSigningRequest(image.objectKey(), previewExpiresAt)
            );
            previewUrl = signedRead.url();
            expiresAt = signedRead.expiresAt();
        }
        return new ImageView(
                image.id(), image.contentType(), image.contentLength(), image.sortOrder(), previewUrl, expiresAt
        );
    }

    private Instant previewExpiresAt() {
        return storageProperties.enabled()
                ? clock.instant().truncatedTo(ChronoUnit.MICROS).plus(storageProperties.readUrlTtl())
                : null;
    }

    private static RegistrationView registrationView(AuctionRegistration registration) {
        return new RegistrationView(
                registration.id(), registration.status(), registration.failureCode(), registration.registeredAt()
        );
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, name + " must be positive");
        }
    }

    public record AuctionDetail(
            long itemId,
            long auctionId,
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            BigDecimal currentPrice,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            Instant startAt,
            Instant endAt,
            boolean ownedByCurrentUser,
            List<ImageView> images,
            RegistrationView myRegistration
    ) {
        public AuctionDetail {
            images = List.copyOf(images);
        }
    }

    public record ImageView(
            long imageId,
            String contentType,
            long contentLength,
            int sortOrder,
            URI previewUrl,
            Instant previewExpiresAt
    ) {
    }

    public record RegistrationView(
            long registrationId,
            AuctionRegistrationStatus status,
            String failureCode,
            Instant registeredAt
    ) {
    }
}
