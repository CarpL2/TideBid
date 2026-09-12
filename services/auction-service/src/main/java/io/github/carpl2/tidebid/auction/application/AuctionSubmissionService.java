package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSubmissionTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class AuctionSubmissionService {

    private static final int MINIMUM_IMAGE_COUNT = 1;
    private static final int MAXIMUM_IMAGE_COUNT = 9;

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;
    private final AuctionImageVerificationService imageVerificationService;
    private final AuctionDraftFieldsValidator fieldsValidator;
    private final AuctionSubmissionTransaction submissionTransaction;
    private final Clock clock;

    public AuctionSubmissionService(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository,
            AuctionImageVerificationService imageVerificationService,
            AuctionDraftFieldsValidator fieldsValidator,
            AuctionSubmissionTransaction submissionTransaction,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
        this.imageVerificationService = imageVerificationService;
        this.fieldsValidator = fieldsValidator;
        this.submissionTransaction = submissionTransaction;
        this.clock = clock;
    }

    public AuctionSubmissionTransaction.SubmittedAuction submit(SubmitCommand command) {
        validateCommand(command);
        AuctionItem item = itemRepository.findItemById(command.itemId())
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.ASSET_NOT_FOUND));
        if (item.sellerId() != command.sellerId()) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        if (item.reviewStatus() != AuctionItemReviewStatus.DRAFT
                && item.reviewStatus() != AuctionItemReviewStatus.REJECTED) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }
        AuctionSession session = sessionRepository.findSessionByItemId(item.id())
                .orElseThrow(() -> new IllegalStateException("Auction item has no session"));
        if (session.sellerId() != item.sellerId() || session.status() != AuctionSessionStatus.DRAFT) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }
        if (item.version() != command.expectedItemVersion()
                || session.version() != command.expectedSessionVersion()) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT, "Auction draft version is stale");
        }

        List<AuctionItemImage> images = itemRepository.findBoundImagesByItemIds(List.of(item.id()));
        validateImageSequence(images, item);
        for (AuctionItemImage image : images) {
            imageVerificationService.verifyBoundImage(item.sellerId(), item.id(), image);
        }

        Instant submittedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        fieldsValidator.validateTiming(session.startAt(), session.endAt(), submittedAt);
        try {
            return submissionTransaction.submit(
                    item.id(), item.sellerId(), item.version(), session.version(), submittedAt
            );
        } catch (AuctionSubmissionTransaction.SubmissionConflictException exception) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT, exception.getMessage());
        }
    }

    private static void validateImageSequence(List<AuctionItemImage> images, AuctionItem item) {
        if (images.size() < MINIMUM_IMAGE_COUNT || images.size() > MAXIMUM_IMAGE_COUNT) {
            throw new BusinessException(
                    AuctionErrorCode.IMAGE_INVALID,
                    "Auction submission requires between 1 and 9 bound images"
            );
        }
        for (int index = 0; index < images.size(); index++) {
            AuctionItemImage image = images.get(index);
            if (!Long.valueOf(item.id()).equals(image.itemId())
                    || image.ownerId() != item.sellerId()
                    || image.sortOrder() == null
                    || image.sortOrder() != index) {
                throw new BusinessException(
                        AuctionErrorCode.IMAGE_INVALID,
                        "Bound images must belong to the seller and use contiguous sort order"
                );
            }
        }
    }

    private static void validateCommand(SubmitCommand command) {
        if (command == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "command must not be null");
        }
        if (command.sellerId() <= 0 || command.itemId() <= 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "sellerId and itemId must be positive");
        }
        if (command.expectedItemVersion() < 0 || command.expectedSessionVersion() < 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "expected versions must not be negative");
        }
    }

    public record SubmitCommand(
            long sellerId,
            long itemId,
            long expectedItemVersion,
            long expectedSessionVersion
    ) {
    }
}
