package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore;
import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore.AccountSnapshot;
import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore.WalletSnapshot;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountSelfServiceTest {

    private static final AuthenticatedUser IDENTITY =
            new AuthenticatedUser(101L, "alice_01", Set.of(Role.USER));

    @Test
    void returnsCurrentAccountAndWalletFromPersistentSnapshots() {
        AccountSelfService service = new AccountSelfService(store(
                account("ACTIVE", "alice_01"),
                wallet()
        ));

        CurrentAccount account = service.currentAccount(IDENTITY);
        WalletBalance wallet = service.currentWallet(IDENTITY);

        assertThat(account.userId()).isEqualTo(101L);
        assertThat(account.username()).isEqualTo("alice_01");
        assertThat(account.nickname()).isEqualTo("Alice");
        assertThat(account.roles()).containsExactly(Role.USER);
        assertThat(wallet.userId()).isEqualTo(101L);
        assertThat(wallet.availableBalance()).isEqualByComparingTo("10000.00");
        assertThat(wallet.frozenBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void missingAccountAndSubjectMismatchAreUnauthenticated() {
        AccountSelfService missing = new AccountSelfService(store(Optional.empty(), wallet()));
        AccountSelfService mismatch = new AccountSelfService(store(
                account("ACTIVE", "another_user"),
                wallet()
        ));

        assertUnauthenticated(() -> missing.currentAccount(IDENTITY));
        assertUnauthenticated(() -> mismatch.currentAccount(IDENTITY));
    }

    @Test
    void disabledAccountIsForbidden() {
        AccountSelfService service = new AccountSelfService(store(
                account("DISABLED", "alice_01"),
                wallet()
        ));

        assertThatThrownBy(() -> service.currentWallet(IDENTITY))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode().code()).isEqualTo("ACCOUNT_DISABLED");
                    assertThat(exception.errorCode().httpStatus()).isEqualTo(403);
                });
    }

    @Test
    void missingWalletIsAnInternalConsistencyFailure() {
        AccountSelfService service = new AccountSelfService(store(
                account("ACTIVE", "alice_01"),
                Optional.empty()
        ));

        assertThatThrownBy(() -> service.currentWallet(IDENTITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wallet is missing");
    }

    private static void assertUnauthenticated(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode().code()).isEqualTo("COMMON_UNAUTHENTICATED");
                    assertThat(exception.errorCode().httpStatus()).isEqualTo(401);
                });
    }

    private static Optional<AccountSnapshot> account(String status, String username) {
        return Optional.of(new AccountSnapshot(101L, username, "Alice", status, Set.of(Role.USER)));
    }

    private static Optional<WalletSnapshot> wallet() {
        return Optional.of(new WalletSnapshot(
                101L,
                new BigDecimal("10000.00"),
                new BigDecimal("0.00")
        ));
    }

    private static AccountSelfQueryStore store(
            Optional<AccountSnapshot> account,
            Optional<WalletSnapshot> wallet
    ) {
        return new AccountSelfQueryStore() {
            @Override
            public Optional<AccountSnapshot> findAccountById(long userId) {
                return account;
            }

            @Override
            public Optional<WalletSnapshot> findWalletByUserId(long userId) {
                return wallet;
            }
        };
    }
}
