package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class AuctionRegistrationQueryService {

    public static final int MAXIMUM_PAGE_SIZE = 100;

    private final AuctionRegistrationRepository repository;

    public AuctionRegistrationQueryService(AuctionRegistrationRepository repository) {
        this.repository = repository;
    }

    public PageResult findMine(long bidderId, int page, int size) {
        requirePositive(bidderId, "bidderId");
        int offset = pageOffset(page, size);
        AuctionRegistrationRepository.RegistrationPage stored = repository.findByBidder(bidderId, offset, size);
        return new PageResult(
                page,
                size,
                stored.total(),
                stored.total() == 0 ? 0 : ((stored.total() - 1) / size) + 1,
                stored.registrations().stream().map(RegistrationView::from).toList()
        );
    }

    public RegistrationView findMine(long bidderId, long registrationId) {
        requirePositive(bidderId, "bidderId");
        requirePositive(registrationId, "registrationId");
        AuctionRegistration registration = repository.findById(registrationId)
                .filter(candidate -> candidate.bidderId() == bidderId)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.REGISTRATION_NOT_FOUND));
        return RegistrationView.from(registration);
    }

    private static int pageOffset(int page, int size) {
        if (page < 1 || size < 1 || size > MAXIMUM_PAGE_SIZE) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_INVALID,
                    "page must be positive and size must be between 1 and " + MAXIMUM_PAGE_SIZE
            );
        }
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, "page offset is too large");
        }
        return (int) offset;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, name + " must be positive");
        }
    }

    public record PageResult(int page, int size, long total, long totalPages, List<RegistrationView> items) {
        public PageResult {
            items = List.copyOf(items);
        }
    }

    public record RegistrationView(
            long registrationId,
            long auctionId,
            BigDecimal depositAmount,
            AuctionRegistrationStatus status,
            String failureCode,
            Instant registeredAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        static RegistrationView from(AuctionRegistration source) {
            return new RegistrationView(
                    source.id(), source.auctionId(), source.depositAmount(), source.status(), source.failureCode(),
                    source.registeredAt(), source.createdAt(), source.updatedAt()
            );
        }
    }
}
