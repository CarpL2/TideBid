package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.AccountSelfQueryStore;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.security.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAccountSelfQueryStore implements AccountSelfQueryStore {

    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;
    private final WalletAccountMapper walletAccountMapper;

    public MybatisAccountSelfQueryStore(
            UserAccountMapper userAccountMapper,
            UserRoleMapper userRoleMapper,
            WalletAccountMapper walletAccountMapper
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
        this.walletAccountMapper = walletAccountMapper;
    }

    @Override
    public Optional<AccountSnapshot> findAccountById(long userId) {
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        Set<Role> roles = userRoleMapper.selectByUserId(userId).stream()
                .map(UserRoleEntity::getRoleCode)
                .map(Role::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new AccountSnapshot(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getStatus(),
                roles
        ));
    }

    @Override
    public Optional<WalletSnapshot> findWalletByUserId(long userId) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        if (wallet == null) {
            return Optional.empty();
        }
        return Optional.of(new WalletSnapshot(
                wallet.getUserId(),
                wallet.getAvailableBalance(),
                wallet.getFrozenBalance()
        ));
    }
}
