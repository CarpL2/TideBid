package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Profile({"local-db", "nacos"})
public class AuctionAssetQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAXIMUM_PAGE_SIZE = 100;

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;
    private final ObjectStoragePort objectStorage;
    private final AuctionStorageProperties storageProperties;
    private final AuctionSessionLifecycleService sessionLifecycleService;
    private final Clock clock;

    public AuctionAssetQueryService(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository,
            ObjectStoragePort objectStorage,
            AuctionStorageProperties storageProperties,
            AuctionSessionLifecycleService sessionLifecycleService,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
        this.objectStorage = objectStorage;
        this.storageProperties = storageProperties;
        this.sessionLifecycleService = sessionLifecycleService;
        this.clock = clock;
    }

    public PageResult<AssetSummary> findMine(long sellerId, int page, int size) {
        requirePositive(sellerId, "sellerId");
        int offset = pageOffset(page, size);
        AuctionItemRepository.SellerItemPage storedPage =
                itemRepository.findItemsBySeller(sellerId, offset, size);
        if (storedPage.items().isEmpty()) {
            return new PageResult<>(page, size, storedPage.total(), List.of());
        }

        List<Long> itemIds = storedPage.items().stream().map(AuctionItem::id).toList();
        Map<Long, AuctionSession> sessions = sessionRepository.findSessionsByItemIds(itemIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        AuctionSession::itemId,
                        Function.identity(),
                        (first, second) -> {
                            throw new IllegalStateException("Auction item has multiple sessions");
                        }
                ));
        Map<Long, List<AuctionItemImage>> images = imagesByItem(
                itemRepository.findBoundImagesByItemIds(itemIds)
        );
        Instant previewExpiresAt = previewExpiresAt();
        List<AssetSummary> items = storedPage.items().stream()
                .map(item -> summary(item, requireSession(sessions, item.id()), images.get(item.id()), previewExpiresAt))
                .toList();
        return new PageResult<>(page, size, storedPage.total(), items);
    }

    public PageResult<LobbySummary> findLobby(int page, int size) {
        int offset = pageOffset(page, size);
        AuctionSessionRepository.LobbySessionPage storedPage =
                sessionRepository.findLobbySessions(offset, size);
        if (storedPage.sessions().isEmpty()) {
            return new PageResult<>(page, size, storedPage.total(), List.of());
        }

        List<AuctionSession> sessions = storedPage.sessions().stream()
                .map(sessionLifecycleService::advanceToCurrentState)
                .toList();
        List<Long> itemIds = sessions.stream().map(AuctionSession::itemId).toList();
        Map<Long, AuctionItem> items = itemRepository.findItemsByIds(itemIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        AuctionItem::id,
                        Function.identity(),
                        (first, second) -> {
                            throw new IllegalStateException("Auction item was returned more than once");
                        }
                ));
        Map<Long, List<AuctionItemImage>> images = imagesByItem(
                itemRepository.findBoundImagesByItemIds(itemIds)
        );
        Instant previewExpiresAt = previewExpiresAt();
        List<LobbySummary> summaries = sessions.stream()
                .map(session -> lobbySummary(
                        requireApprovedItem(items, session), session, images.get(session.itemId()), previewExpiresAt
                ))
                .toList();
        return new PageResult<>(page, size, storedPage.total(), summaries);
    }

    public AssetDetail findDetail(long requesterId, boolean administrator, long itemId) {
        requirePositive(requesterId, "requesterId");
        requirePositive(itemId, "itemId");
        AuctionItem item = itemRepository.findItemById(itemId)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.ASSET_NOT_FOUND));
        boolean owner = item.sellerId() == requesterId;
        boolean approved = item.reviewStatus() == AuctionItemReviewStatus.APPROVED;
        boolean pendingAdministrator = administrator
                && item.reviewStatus() == AuctionItemReviewStatus.PENDING_REVIEW;
        if (!owner && !approved && !pendingAdministrator) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }

        AuctionSession session = sessionRepository.findSessionByItemId(itemId)
                .orElseThrow(() -> new IllegalStateException("Auction item has no session"));
        if (session.sellerId() != item.sellerId()) {
            throw new IllegalStateException("Auction item and session sellers do not match");
        }
        session = sessionLifecycleService.advanceToCurrentState(session);
        Instant previewExpiresAt = previewExpiresAt();
        List<ImageView> images = itemRepository.findBoundImagesByItemIds(List.of(itemId)).stream()
                .map(image -> imageView(item, image, previewExpiresAt))
                .toList();
        ReviewFeedback review = owner || administrator
                ? itemRepository.findLatestReview(itemId).map(AuctionAssetQueryService::reviewFeedback).orElse(null)
                : null;
        return detail(item, session, images, review);
    }

    public PageResult<AdminReviewSummary> findPendingReviews(boolean administrator, int page, int size) {
        if (!administrator) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        int offset = pageOffset(page, size);
        AuctionItemRepository.PendingReviewPage storedPage =
                itemRepository.findPendingReviewItems(offset, size);
        if (storedPage.items().isEmpty()) {
            return new PageResult<>(page, size, storedPage.total(), List.of());
        }

        List<Long> itemIds = storedPage.items().stream().map(AuctionItem::id).toList();
        Map<Long, AuctionSession> sessions = sessionRepository.findSessionsByItemIds(itemIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        AuctionSession::itemId,
                        Function.identity(),
                        (first, second) -> {
                            throw new IllegalStateException("Auction item has multiple sessions");
                        }
                ));
        Map<Long, List<AuctionItemImage>> images = imagesByItem(
                itemRepository.findBoundImagesByItemIds(itemIds)
        );
        Instant previewExpiresAt = previewExpiresAt();
        List<AdminReviewSummary> items = storedPage.items().stream()
                .map(item -> adminReviewSummary(
                        item, requireSession(sessions, item.id()), images.get(item.id()), previewExpiresAt
                ))
                .toList();
        return new PageResult<>(page, size, storedPage.total(), items);
    }

    private AssetSummary summary(
            AuctionItem item,
            AuctionSession session,
            List<AuctionItemImage> images,
            Instant previewExpiresAt
    ) {
        AuctionItemImage cover = images == null || images.isEmpty() ? null : images.getFirst();
        return new AssetSummary(
                item.id(), session.id(), item.title(), item.category(), item.itemCondition(),
                item.reviewStatus(), session.status(), session.startPrice(), session.currentPrice(),
                session.startAt(), session.endAt(), item.version(), session.version(),
                cover == null ? null : imageView(item, cover, previewExpiresAt), item.createdAt(), item.updatedAt()
        );
    }

    private LobbySummary lobbySummary(
            AuctionItem item,
            AuctionSession session,
            List<AuctionItemImage> images,
            Instant previewExpiresAt
    ) {
        AuctionItemImage cover = images == null || images.isEmpty() ? null : images.getFirst();
        return new LobbySummary(
                item.id(), session.id(), item.title(), item.category(), item.itemCondition(), session.status(),
                session.startPrice(), session.currentPrice(),
                session.currentPrice() == null ? session.startPrice() : session.currentPrice(),
                session.currentPrice() == null
                        ? session.startPrice()
                        : session.currentPrice().add(session.bidIncrement()),
                session.bidCount(), session.startAt(), session.endAt(),
                cover == null ? null : lobbyCover(item, cover, previewExpiresAt)
        );
    }

    private static AssetDetail detail(
            AuctionItem item,
            AuctionSession session,
            List<ImageView> images,
            ReviewFeedback review
    ) {
        return new AssetDetail(
                item.id(), session.id(), item.sellerId(), item.title(), item.description(), item.category(),
                item.itemCondition(), item.reviewStatus(), item.submissionVersion(), session.status(),
                session.startPrice(), session.bidIncrement(), session.depositAmount(), session.currentPrice(),
                session.bidCount(), session.startAt(), session.endAt(), item.version(), session.version(),
                item.submittedAt(), item.approvedAt(), item.createdAt(), item.updatedAt(), images, review
        );
    }

    private AdminReviewSummary adminReviewSummary(
            AuctionItem item,
            AuctionSession session,
            List<AuctionItemImage> images,
            Instant previewExpiresAt
    ) {
        if (item.reviewStatus() != AuctionItemReviewStatus.PENDING_REVIEW
                || session.status() != AuctionSessionStatus.DRAFT
                || session.sellerId() != item.sellerId()) {
            throw new IllegalStateException("Pending review item has an inconsistent auction session");
        }
        AuctionItemImage cover = images == null || images.isEmpty() ? null : images.getFirst();
        return new AdminReviewSummary(
                item.id(), session.id(), item.sellerId(), item.title(), item.category(), item.itemCondition(),
                item.reviewStatus(), item.submissionVersion(), session.startPrice(), session.bidIncrement(),
                session.depositAmount(), session.startAt(), session.endAt(), item.version(), session.version(),
                item.submittedAt(), cover == null ? null : imageView(item, cover, previewExpiresAt)
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
                image.id(), image.objectKey(), image.originalFilename(), image.contentType(),
                image.contentLength(), image.sortOrder(), previewUrl, expiresAt
        );
    }

    private LobbyCover lobbyCover(AuctionItem item, AuctionItemImage image, Instant previewExpiresAt) {
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
        return new LobbyCover(image.id(), image.contentType(), previewUrl, expiresAt);
    }

    private Instant previewExpiresAt() {
        if (!storageProperties.enabled()) {
            return null;
        }
        return clock.instant().truncatedTo(ChronoUnit.MICROS).plus(storageProperties.readUrlTtl());
    }

    private static Map<Long, List<AuctionItemImage>> imagesByItem(List<AuctionItemImage> images) {
        Map<Long, List<AuctionItemImage>> grouped = new LinkedHashMap<>();
        for (AuctionItemImage image : images) {
            if (image.itemId() == null) {
                throw new IllegalStateException("Bound image has no item ID");
            }
            grouped.computeIfAbsent(image.itemId(), ignored -> new ArrayList<>()).add(image);
        }
        grouped.replaceAll((ignored, value) -> List.copyOf(value));
        return Map.copyOf(grouped);
    }

    private static AuctionSession requireSession(Map<Long, AuctionSession> sessions, long itemId) {
        AuctionSession session = sessions.get(itemId);
        if (session == null) {
            throw new IllegalStateException("Auction item has no session");
        }
        return session;
    }

    private static AuctionItem requireApprovedItem(Map<Long, AuctionItem> items, AuctionSession session) {
        AuctionItem item = items.get(session.itemId());
        if (item == null) {
            throw new IllegalStateException("Lobby auction has no item");
        }
        if (item.reviewStatus() != AuctionItemReviewStatus.APPROVED
                || item.sellerId() != session.sellerId()) {
            throw new IllegalStateException("Lobby auction has an inconsistent approved item");
        }
        return item;
    }

    private static ReviewFeedback reviewFeedback(AuctionReview review) {
        return new ReviewFeedback(
                review.submissionVersion(), review.decision(), review.comment(), review.reviewedAt()
        );
    }

    private static int pageOffset(int page, int size) {
        if (page < 1 || size < 1 || size > MAXIMUM_PAGE_SIZE) {
            throw new BusinessException(
                    AuctionErrorCode.ASSET_INVALID,
                    "page must be positive and size must be between 1 and " + MAXIMUM_PAGE_SIZE
            );
        }
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "page offset is too large");
        }
        return (int) offset;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, name + " must be positive");
        }
    }

    public record PageResult<T>(int page, int size, long total, long totalPages, List<T> items) {
        public PageResult(int page, int size, long total, List<T> items) {
            this(page, size, total, total == 0 ? 0 : ((total - 1) / size) + 1, List.copyOf(items));
        }
    }

    public record AssetSummary(
            long itemId,
            long auctionId,
            String title,
            String category,
            AuctionItemCondition itemCondition,
            AuctionItemReviewStatus reviewStatus,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal currentPrice,
            Instant startAt,
            Instant endAt,
            long itemVersion,
            long sessionVersion,
            ImageView coverImage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record LobbySummary(
            long itemId,
            long auctionId,
            String title,
            String category,
            AuctionItemCondition itemCondition,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal currentPrice,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            Instant startAt,
            Instant endAt,
            LobbyCover coverImage
    ) {
    }

    public record LobbyCover(
            long imageId,
            String contentType,
            URI previewUrl,
            Instant previewExpiresAt
    ) {
    }

    public record AssetDetail(
            long itemId,
            long auctionId,
            long sellerId,
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            AuctionItemReviewStatus reviewStatus,
            int submissionVersion,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            BigDecimal currentPrice,
            long bidCount,
            Instant startAt,
            Instant endAt,
            long itemVersion,
            long sessionVersion,
            Instant submittedAt,
            Instant approvedAt,
            Instant createdAt,
            Instant updatedAt,
            List<ImageView> images,
            ReviewFeedback latestReview
    ) {
        public AssetDetail {
            images = List.copyOf(images);
        }
    }

    public record ImageView(
            long imageId,
            String objectKey,
            String originalFilename,
            String contentType,
            long contentLength,
            int sortOrder,
            URI previewUrl,
            Instant previewExpiresAt
    ) {
    }

    public record ReviewFeedback(
            int submissionVersion,
            AuctionReviewDecision decision,
            String comment,
            Instant reviewedAt
    ) {
    }

    public record AdminReviewSummary(
            long itemId,
            long auctionId,
            long sellerId,
            String title,
            String category,
            AuctionItemCondition itemCondition,
            AuctionItemReviewStatus reviewStatus,
            int submissionVersion,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            Instant startAt,
            Instant endAt,
            long itemVersion,
            long sessionVersion,
            Instant submittedAt,
            ImageView coverImage
    ) {
    }
}
