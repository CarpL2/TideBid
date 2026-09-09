package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.security.Role;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

public interface AccountSelfQueryStore {

    Optional<AccountSnapshot> findAccountById(long userId);

    Optional<WalletSnapshot> findWalletByUserId(long userId);

    record AccountSnapshot(
            long userId,
            String username,
            String nickname,
            String status,
            Set<Role> roles
    ) {
        public AccountSnapshot {
            roles = Set.copyOf(roles);
        }
    }

    record WalletSnapshot(
            long userId,
            BigDecimal availableBalance,
            BigDecimal frozenBalance
    ) {
    }
}
