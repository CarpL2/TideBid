package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionRegistrationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T08:00:00Z");
    private final AuctionRegistrationRepository repository = mock(AuctionRegistrationRepository.class);
    private final AuctionRegistrationQueryService service = new AuctionRegistrationQueryService(repository);

    @Test
    void returnsStableOwnedPageWithoutInternalRecoveryFields() {
        when(repository.findByBidder(42L, 10, 10)).thenReturn(
                new AuctionRegistrationRepository.RegistrationPage(List.of(registration(101L, 42L)), 21L)
        );

        AuctionRegistrationQueryService.PageResult result = service.findMine(42L, 2, 10);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.registrationId()).isEqualTo(101L);
            assertThat(item.auctionId()).isEqualTo(88L);
            assertThat(item.status()).isEqualTo(AuctionRegistrationStatus.PENDING_HOLD);
        });
        verify(repository).findByBidder(42L, 10, 10);
    }

    @Test
    void hidesAnotherUsersRegistrationAsNotFound() {
        when(repository.findById(101L)).thenReturn(Optional.of(registration(101L, 99L)));

        assertThatThrownBy(() -> service.findMine(42L, 101L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.REGISTRATION_NOT_FOUND));
    }

    @Test
    void validatesPaginationBeforeCallingRepository() {
        assertThatThrownBy(() -> service.findMine(42L, 0, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findMine(42L, 1, 101))
                .isInstanceOf(BusinessException.class);
    }

    private static AuctionRegistration registration(long id, long bidderId) {
        return new AuctionRegistration(
                id, "REG-20260913-000101", 88L, bidderId, new BigDecimal("50.00"),
                AuctionRegistrationStatus.PENDING_HOLD, null, 1, NOW.plusSeconds(10), NOW,
                null, null, null, 1L, NOW.minusSeconds(5), NOW
        );
    }
}
