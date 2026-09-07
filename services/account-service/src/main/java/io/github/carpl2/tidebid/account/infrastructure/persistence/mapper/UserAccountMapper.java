package io.github.carpl2.tidebid.account.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    @Select("SELECT EXISTS(SELECT 1 FROM user_account WHERE username = #{username})")
    boolean existsByUsername(@Param("username") String username);
}
