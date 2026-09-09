package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.security.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAccountAuthenticationStore implements AccountAuthenticationStore {

    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;

    public MybatisAccountAuthenticationStore(
            UserAccountMapper userAccountMapper,
            UserRoleMapper userRoleMapper
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    public Optional<StoredAccount> findByCanonicalUsername(String canonicalUsername) {
        UserAccountEntity user = userAccountMapper.selectByUsername(canonicalUsername);
        if (user == null) {
            return Optional.empty();
        }
        Set<Role> roles = userRoleMapper.selectByUserId(user.getId()).stream()
                .map(UserRoleEntity::getRoleCode)
                .map(Role::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new StoredAccount(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getStatus(),
                roles
        ));
    }
}
