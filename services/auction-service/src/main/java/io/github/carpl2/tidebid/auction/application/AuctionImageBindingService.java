package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.AuctionImageVerificationService.VerifiedUpload;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository.ImageBindingResult;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Profile({"local-db", "nacos"})
public class AuctionImageBindingService {

    private final AuctionItemRepository itemRepository;
    private final AuctionImageVerificationService verificationService;
    private final AuctionImageProperties imageProperties;
    private final Clock clock;

    public AuctionImageBindingService(
            AuctionItemRepository itemRepository,
            AuctionImageVerificationService verificationService,
            AuctionImageProperties imageProperties,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.verificationService = verificationService;
        this.imageProperties = imageProperties;
        this.clock = clock;
    }

    public AuctionItemImage bind(BindImageCommand command) {
        validate(command);
        AuctionItem item = itemRepository.findItemById(command.itemId())
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.ASSET_NOT_FOUND));
        if (item.sellerId() != command.ownerId()) {
            throw new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED);
        }
        if (item.reviewStatus() != AuctionItemReviewStatus.DRAFT
                && item.reviewStatus() != AuctionItemReviewStatus.REJECTED) {
            throw new BusinessException(AuctionErrorCode.ASSET_STATE_CONFLICT);
        }

        VerifiedUpload verified = verificationService.verifyPendingUpload(
                command.ownerId(),
                command.objectKey()
        );
        Instant boundAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ImageBindingResult result = itemRepository.bindPendingImage(
                verified.imageId(),
                command.ownerId(),
                command.itemId(),
                command.sortOrder(),
                boundAt
        );
        if (result == ImageBindingResult.POSITION_OCCUPIED) {
            throw imageInvalid("Image position is already occupied");
        }
        if (result != ImageBindingResult.BOUND) {
            throw imageInvalid("Upload intent was concurrently bound or expired");
        }

        AuctionItemImage bound = itemRepository.findImageByObjectKey(verified.objectKey())
                .orElseThrow(() -> new IllegalStateException("Bound image could not be reloaded"));
        if (bound.storageStatus() != AuctionImageStatus.BOUND
                || !Long.valueOf(command.itemId()).equals(bound.itemId())
                || !Integer.valueOf(command.sortOrder()).equals(bound.sortOrder())) {
            throw new IllegalStateException("Bound image has an unexpected database state");
        }
        return bound;
    }

    private void validate(BindImageCommand command) {
        if (command == null) {
            throw imageInvalid("command must not be null");
        }
        if (command.ownerId() <= 0 || command.itemId() <= 0) {
            throw imageInvalid("ownerId and itemId must be positive");
        }
        if (command.sortOrder() < 0 || command.sortOrder() >= imageProperties.maxImagesPerItem()) {
            throw imageInvalid("sortOrder exceeds the configured image limit");
        }
    }

    private static BusinessException imageInvalid(String message) {
        return new BusinessException(AuctionErrorCode.IMAGE_INVALID, message);
    }

    public record BindImageCommand(long ownerId, long itemId, String objectKey, int sortOrder) {
    }
}
