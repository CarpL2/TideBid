package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.AuctionImageVerificationService.VerifiedUpload;
import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class AuctionDraftCreationService {

    private final AuctionImageVerificationService imageVerificationService;
    private final AuctionDraftTransaction draftTransaction;
    private final IdGenerator idGenerator;
    private final AuctionImageProperties imageProperties;
    private final AuctionDraftFieldsValidator fieldsValidator;
    private final Clock clock;

    public AuctionDraftCreationService(
            AuctionImageVerificationService imageVerificationService,
            AuctionDraftTransaction draftTransaction,
            IdGenerator idGenerator,
            AuctionImageProperties imageProperties,
            AuctionDraftFieldsValidator fieldsValidator,
            Clock clock
    ) {
        this.imageVerificationService = imageVerificationService;
        this.draftTransaction = draftTransaction;
        this.idGenerator = idGenerator;
        this.imageProperties = imageProperties;
        this.fieldsValidator = fieldsValidator;
        this.clock = clock;
    }

    public AuctionDraftTransaction.CreatedDraft create(CreateDraftCommand command) {
        if (command == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "command must not be null");
        }
        if (command.sellerId() <= 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "sellerId must be positive");
        }
        AuctionDraftFieldsValidator.ValidatedFields fields = fieldsValidator.validate(
                command.title(), command.description(), command.category(), command.itemCondition(),
                command.startPrice(), command.bidIncrement(), command.depositAmount(),
                command.startAt(), command.endAt()
        );
        List<String> imageObjectKeys = normalizeImageKeys(command.imageObjectKeys());
        List<AuctionDraftTransaction.ImageBinding> bindings = verifyImages(
                command.sellerId(),
                imageObjectKeys
        );
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        fieldsValidator.validateTiming(fields.startAt(), fields.endAt(), now);
        long itemId = nextId();
        long auctionId = nextId();
        AuctionItem item = new AuctionItem(
                itemId,
                command.sellerId(),
                fields.title(),
                fields.description(),
                fields.category(),
                fields.itemCondition(),
                AuctionItemReviewStatus.DRAFT,
                0,
                0,
                null,
                null,
                now,
                now
        );
        AuctionSession session = new AuctionSession(
                auctionId,
                itemId,
                command.sellerId(),
                fields.startPrice(),
                fields.bidIncrement(),
                fields.depositAmount(),
                null,
                null,
                0,
                fields.startAt(),
                fields.endAt(),
                AuctionSessionStatus.DRAFT,
                0,
                now,
                now
        );
        try {
            return draftTransaction.create(item, session, bindings, now);
        } catch (AuctionDraftTransaction.ImageBindingConflictException exception) {
            throw new BusinessException(AuctionErrorCode.IMAGE_INVALID, exception.getMessage());
        }
    }

    private List<AuctionDraftTransaction.ImageBinding> verifyImages(long sellerId, List<String> objectKeys) {
        List<AuctionDraftTransaction.ImageBinding> bindings = new ArrayList<>(objectKeys.size());
        for (int index = 0; index < objectKeys.size(); index++) {
            VerifiedUpload verified = imageVerificationService.verifyPendingUpload(sellerId, objectKeys.get(index));
            bindings.add(new AuctionDraftTransaction.ImageBinding(
                    verified.imageId(), sellerId, verified.objectKey(), index
            ));
        }
        return List.copyOf(bindings);
    }

    private List<String> normalizeImageKeys(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()
                || objectKeys.size() > imageProperties.maxImagesPerItem()) {
            throw new BusinessException(
                    AuctionErrorCode.IMAGE_INVALID,
                    "imageObjectKeys must contain 1 to " + imageProperties.maxImagesPerItem() + " images"
            );
        }
        List<String> normalized = new ArrayList<>(objectKeys.size());
        Set<String> unique = new HashSet<>();
        try {
            for (String objectKey : objectKeys) {
                String controlled = ObjectStoragePort.requireControlledObjectKey(objectKey);
                if (!unique.add(controlled)) {
                    throw new IllegalArgumentException("imageObjectKeys must not contain duplicates");
                }
                normalized.add(controlled);
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(AuctionErrorCode.IMAGE_INVALID, exception.getMessage());
        }
        return List.copyOf(normalized);
    }

    private long nextId() {
        long id = idGenerator.nextId();
        if (id <= 0) {
            throw new IllegalStateException("idGenerator returned a non-positive id");
        }
        return id;
    }

    public record CreateDraftCommand(
            long sellerId,
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            Instant startAt,
            Instant endAt,
            List<String> imageObjectKeys
    ) {
    }

}
