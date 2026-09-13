package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionRegistrationQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionRegistrationResponse {

    private AuctionRegistrationResponse() {
    }

    public record Page(int page, int size, long total, long totalPages, List<Detail> items) {
        static Page from(AuctionRegistrationQueryService.PageResult source) {
            return new Page(
                    source.page(), source.size(), source.total(), source.totalPages(),
                    source.items().stream().map(Detail::from).toList()
            );
        }
    }

    public record Detail(
            String registrationId,
            String auctionId,
            BigDecimal depositAmount,
            AuctionRegistrationStatus status,
            String failureCode,
            Instant registeredAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        static Detail from(AuctionRegistration source) {
            return new Detail(
                    Long.toString(source.id()), Long.toString(source.auctionId()), source.depositAmount(),
                    source.status(), source.failureCode(), source.registeredAt(), source.createdAt(), source.updatedAt()
            );
        }

        static Detail from(AuctionRegistrationQueryService.RegistrationView source) {
            return new Detail(
                    Long.toString(source.registrationId()), Long.toString(source.auctionId()), source.depositAmount(),
                    source.status(), source.failureCode(), source.registeredAt(), source.createdAt(), source.updatedAt()
            );
        }
    }
}
