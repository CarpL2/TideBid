package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.application.port.DuplicateUsernameException;
import io.github.carpl2.tidebid.account.domain.WalletLedgerType;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAccountRegistrationStore implements AccountRegistrationStore {

    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;
    private final WalletAccountMapper walletAccountMapper;
    private final WalletLedgerMapper walletLedgerMapper;

    public MybatisAccountRegistrationStore(
            UserAccountMapper userAccountMapper,
            UserRoleMapper userRoleMapper,
            WalletAccountMapper walletAccountMapper,
            WalletLedgerMapper walletLedgerMapper
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
        this.walletAccountMapper = walletAccountMapper;
        this.walletLedgerMapper = walletLedgerMapper;
    }

    @Override
    public boolean usernameExists(String canonicalUsername) {
        return userAccountMapper.existsByUsername(canonicalUsername);
    }

    @Override
    @Transactional
    public long create(RegistrationData registrationData) {
        UserAccountEntity user = new UserAccountEntity();
        user.setUsername(registrationData.canonicalUsername());
        user.setPasswordHash(registrationData.passwordHash());
        user.setNickname(registrationData.nickname());
        user.setStatus("ACTIVE");
        try {
            requireSingleRow(userAccountMapper.insert(user), "user account");
        } catch (DuplicateKeyException exception) {
            throw new DuplicateUsernameException(exception);
        }

        UserRoleEntity role = new UserRoleEntity();
        role.setUserId(user.getId());
        role.setRoleCode("USER");
        requireSingleRow(userRoleMapper.insert(role), "user role");

        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(user.getId());
        wallet.setAvailableBalance(registrationData.initialAvailableBalance());
        wallet.setFrozenBalance(registrationData.initialFrozenBalance());
        requireSingleRow(walletAccountMapper.insert(wallet), "wallet account");

        WalletLedgerEntity ledger = new WalletLedgerEntity();
        ledger.setWalletId(wallet.getId());
        ledger.setBusinessNo("REGISTER_INIT:" + user.getId());
        ledger.setLedgerType(WalletLedgerType.INITIAL_CREDIT.name());
        ledger.setAvailableDelta(registrationData.initialAvailableBalance());
        ledger.setFrozenDelta(registrationData.initialFrozenBalance());
        ledger.setAvailableBalanceAfter(registrationData.initialAvailableBalance());
        ledger.setFrozenBalanceAfter(registrationData.initialFrozenBalance());
        requireSingleRow(walletLedgerMapper.insert(ledger), "initial wallet ledger");

        return user.getId();
    }

    private static void requireSingleRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected one inserted row for " + operation);
        }
    }
}
