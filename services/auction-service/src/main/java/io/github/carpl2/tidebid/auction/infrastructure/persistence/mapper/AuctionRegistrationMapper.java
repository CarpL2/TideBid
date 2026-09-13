package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionRegistrationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AuctionRegistrationMapper extends BaseMapper<AuctionRegistrationEntity> {

    @Update("""
            UPDATE auction_registration
            SET status = 'REGISTERED',
                failure_code = NULL,
                attempt_count = attempt_count + 1,
                next_retry_at = NULL,
                last_attempt_at = #{attemptedAt},
                lease_owner = NULL,
                lease_until = NULL,
                registered_at = #{attemptedAt},
                updated_at = #{attemptedAt},
                version = version + 1
            WHERE id = #{registrationId}
              AND status = 'PENDING_HOLD'
            """)
    int markRegistered(
            @Param("registrationId") long registrationId,
            @Param("attemptedAt") Instant attemptedAt
    );

    @Update("""
            UPDATE auction_registration
            SET status = 'FAILED',
                failure_code = #{failureCode},
                attempt_count = attempt_count + 1,
                next_retry_at = NULL,
                last_attempt_at = #{attemptedAt},
                lease_owner = NULL,
                lease_until = NULL,
                registered_at = NULL,
                updated_at = #{attemptedAt},
                version = version + 1
            WHERE id = #{registrationId}
              AND status = 'PENDING_HOLD'
            """)
    int markFailed(
            @Param("registrationId") long registrationId,
            @Param("failureCode") String failureCode,
            @Param("attemptedAt") Instant attemptedAt
    );

    @Update("""
            UPDATE auction_registration
            SET attempt_count = attempt_count + 1,
                next_retry_at = #{nextRetryAt},
                last_attempt_at = #{attemptedAt},
                lease_owner = NULL,
                lease_until = NULL,
                updated_at = #{attemptedAt},
                version = version + 1
            WHERE id = #{registrationId}
              AND status = 'PENDING_HOLD'
            """)
    int scheduleRetry(
            @Param("registrationId") long registrationId,
            @Param("attemptedAt") Instant attemptedAt,
            @Param("nextRetryAt") Instant nextRetryAt
    );
}
