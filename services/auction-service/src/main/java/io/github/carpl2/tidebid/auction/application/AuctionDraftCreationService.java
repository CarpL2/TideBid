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
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class AuctionDraftCreationService {

    private static final BigDecimal MAXIMUM_AMOUNT = new BigDecimal("99999999999999999.99");
    private static final Set<String> CATEGORIES = Set.of(
            "ELECTRONICS", "COLLECTIBLES", "ART", "FASHION", "HOME", "SPORTS", "OTHER"
    );

    private final AuctionImageVerificationService imageVerificationService;
    private final AuctionDraftTransaction draftTransaction;
    private final IdGenerator idGenerator;
    private final AuctionImageProperties imageProperties;
    private final AuctionTimingProperties timingProperties;
    private final Clock clock;

    public AuctionDraftCreationService(
            AuctionImageVerificationService imageVerificationService,
            AuctionDraftTransaction draftTransaction,
            IdGenerator idGenerator,
            AuctionImageProperties imageProperties,
            AuctionTimingProperties timingProperties,
            Clock clock
    ) {
        this.imageVerificationService = imageVerificationService;
        this.draftTransaction = draftTransaction;
        this.idGenerator = idGenerator;
        this.imageProperties = imageProperties;
        this.timingProperties = timingProperties;
        this.clock = clock;
    }

    public AuctionDraftTransaction.CreatedDraft create(CreateDraftCommand command) {
        ValidatedDraft draft = validate(command);
        List<AuctionDraftTransaction.ImageBinding> bindings = verifyImages(
                draft.sellerId(),
                draft.imageObjectKeys()
        );
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateTiming(draft.startAt(), draft.endAt(), now);
        long itemId = nextId();
        long auctionId = nextId();
        AuctionItem item = new AuctionItem(
                itemId,
                draft.sellerId(),
                draft.title(),
                draft.description(),
                draft.category(),
                draft.itemCondition(),
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
                draft.sellerId(),
                draft.startPrice(),
                draft.bidIncrement(),
                draft.depositAmount(),
                null,
                null,
                0,
                draft.startAt(),
                draft.endAt(),
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

    private ValidatedDraft validate(CreateDraftCommand command) {
        if (command == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "command must not be null");
        }
        if (command.sellerId() <= 0) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "sellerId must be positive");
        }
        String title = requireText(command.title(), 2, 80, "title");
        String description = requireText(command.description(), 10, 2000, "description");
        String category = command.category() == null
                ? ""
                : command.category().trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "category is not supported");
        }
        if (command.itemCondition() == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "itemCondition must not be null");
        }

        BigDecimal startPrice = requireAmount(command.startPrice(), "startPrice");
        BigDecimal bidIncrement = requireAmount(command.bidIncrement(), "bidIncrement");
        BigDecimal depositAmount = requireAmount(command.depositAmount(), "depositAmount");
        Instant startAt = requireTime(command.startAt(), "startAt");
        Instant endAt = requireTime(command.endAt(), "endAt");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateTiming(startAt, endAt, now);

        List<String> imageObjectKeys = normalizeImageKeys(command.imageObjectKeys());
        return new ValidatedDraft(
                command.sellerId(), title, description, category, command.itemCondition(),
                startPrice, bidIncrement, depositAmount, startAt, endAt, imageObjectKeys
        );
    }

    private void validateTiming(Instant startAt, Instant endAt, Instant now) {
        if (startAt.isBefore(now.plus(timingProperties.minimumLeadTime()))) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, "startAt is too early");
        }
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, "endAt must be after startAt");
        }
        if (java.time.Duration.between(startAt, endAt).compareTo(timingProperties.maximumDuration()) > 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, "auction duration is too long");
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

    private static String requireText(String value, int minimum, int maximum, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < minimum || normalized.length() > maximum) {
            throw new BusinessException(
                    AuctionErrorCode.ASSET_INVALID,
                    name + " must contain " + minimum + " to " + maximum + " characters"
            );
        }
        return normalized;
    }

    private static BigDecimal requireAmount(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAXIMUM_AMOUNT) > 0) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_AMOUNT_INVALID,
                    name + " must be positive and fit DECIMAL(19,2)"
            );
        }
        return value.setScale(2);
    }

    private static Instant requireTime(Instant value, String name) {
        if (value == null) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, name + " must not be null");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
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

    private record ValidatedDraft(
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
        private ValidatedDraft {
            Objects.requireNonNull(imageObjectKeys, "imageObjectKeys must not be null");
        }
    }
}
