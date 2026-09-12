package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

@Component
public class AuctionDraftFieldsValidator {

    private static final BigDecimal MAXIMUM_AMOUNT = new BigDecimal("99999999999999999.99");
    private static final Set<String> CATEGORIES = Set.of(
            "ELECTRONICS", "COLLECTIBLES", "ART", "FASHION", "HOME", "SPORTS", "OTHER"
    );

    private final AuctionTimingProperties timingProperties;
    private final Clock clock;

    public AuctionDraftFieldsValidator(AuctionTimingProperties timingProperties, Clock clock) {
        this.timingProperties = timingProperties;
        this.clock = clock;
    }

    public ValidatedFields validate(
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            Instant startAt,
            Instant endAt
    ) {
        String normalizedTitle = requireText(title, 2, 80, "title");
        String normalizedDescription = requireText(description, 10, 2000, "description");
        String normalizedCategory = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalizedCategory)) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "category is not supported");
        }
        if (itemCondition == null) {
            throw new BusinessException(AuctionErrorCode.ASSET_INVALID, "itemCondition must not be null");
        }
        BigDecimal normalizedStartPrice = requireAmount(startPrice, "startPrice");
        BigDecimal normalizedBidIncrement = requireAmount(bidIncrement, "bidIncrement");
        BigDecimal normalizedDepositAmount = requireAmount(depositAmount, "depositAmount");
        Instant normalizedStartAt = requireTime(startAt, "startAt");
        Instant normalizedEndAt = requireTime(endAt, "endAt");
        validateTiming(normalizedStartAt, normalizedEndAt, now());
        return new ValidatedFields(
                normalizedTitle,
                normalizedDescription,
                normalizedCategory,
                itemCondition,
                normalizedStartPrice,
                normalizedBidIncrement,
                normalizedDepositAmount,
                normalizedStartAt,
                normalizedEndAt
        );
    }

    public void validateTiming(Instant startAt, Instant endAt, Instant now) {
        if (startAt.isBefore(now.plus(timingProperties.minimumLeadTime()))) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, "startAt is too early");
        }
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, "endAt must be after startAt");
        }
        if (Duration.between(startAt, endAt).compareTo(timingProperties.maximumDuration()) > 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_TIME_INVALID, "auction duration is too long");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
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

    public record ValidatedFields(
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            Instant startAt,
            Instant endAt
    ) {
    }
}
