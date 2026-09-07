package io.github.carpl2.tidebid.account.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.toolkit.Constants;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Uses the MyBatis-Plus marker for fill metadata, but deliberately avoids BaseMapper's single-ID API
 * because user_role is identified by the (user_id, role_code) pair.
 */
@Mapper
public interface UserRoleMapper extends com.baomidou.mybatisplus.core.mapper.Mapper<UserRoleEntity> {

    @Insert("""
            INSERT INTO user_role (user_id, role_code, created_at)
            VALUES (#{et.userId}, #{et.roleCode}, #{et.createdAt})
            """)
    int insert(@Param(Constants.ENTITY) UserRoleEntity entity);

    @Select("""
            SELECT user_id, role_code, created_at
            FROM user_role
            WHERE user_id = #{userId}
            ORDER BY role_code
            """)
    List<UserRoleEntity> selectByUserId(@Param("userId") long userId);

    @Delete("""
            DELETE FROM user_role
            WHERE user_id = #{userId} AND role_code = #{roleCode}
            """)
    int delete(@Param("userId") long userId, @Param("roleCode") String roleCode);
}
