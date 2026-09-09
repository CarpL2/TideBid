package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.AccountRoleStore;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.security.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAccountRoleStore implements AccountRoleStore {

    private final UserRoleMapper userRoleMapper;

    public MybatisAccountRoleStore(UserRoleMapper userRoleMapper) {
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    public void addRole(long userId, Role role) {
        UserRoleEntity entity = new UserRoleEntity();
        entity.setUserId(userId);
        entity.setRoleCode(role.name());
        if (userRoleMapper.insert(entity) != 1) {
            throw new IllegalStateException("Expected one inserted row for user role");
        }
    }
}
