package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore;
import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore.AccountSnapshot;
import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore.WalletSnapshot;
import io.github.carpl2.tidebid.account.domain.AccountErrorCode;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"local-db", "nacos"})
public class AccountSelfService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AccountSelfQueryStore queryStore;

    public AccountSelfService(AccountSelfQueryStore queryStore) {
        this.queryStore = queryStore;
    }

    public CurrentAccount currentAccount(AuthenticatedUser identity) {
        AccountSnapshot account = requireActiveAccount(identity);
        return new CurrentAccount(
                account.userId(),
                account.username(),
                account.nickname(),
                account.roles()
        );
    }

    public WalletBalance currentWallet(AuthenticatedUser identity) {
        AccountSnapshot account = requireActiveAccount(identity);
        WalletSnapshot wallet = queryStore.findWalletByUserId(account.userId())
                .orElseThrow(() -> new IllegalStateException("Wallet is missing for the authenticated account"));
        return new WalletBalance(
                wallet.userId(),
                wallet.availableBalance(),
                wallet.frozenBalance()
        );
    }

    public CurrentAccount requireRole(AuthenticatedUser identity, Role requiredRole) {
        CurrentAccount account = currentAccount(identity);
        if (!account.roles().contains(requiredRole)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return account;
    }

    private AccountSnapshot requireActiveAccount(AuthenticatedUser identity) {
        if (identity == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        AccountSnapshot account = queryStore.findAccountById(identity.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
        if (!account.username().equals(identity.subject())) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        if (!ACTIVE_STATUS.equals(account.status())) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_DISABLED);
        }
        if (account.roles().isEmpty()) {
            throw new IllegalStateException("Authenticated account has no role");
        }
        return account;
    }
}
