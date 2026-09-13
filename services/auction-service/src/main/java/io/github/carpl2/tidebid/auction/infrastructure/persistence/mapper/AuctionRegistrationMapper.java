package io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionRegistrationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AuctionRegistrationMapper extends BaseMapper<AuctionRegistrationEntity> {

    @Select("SELECT COUNT(*) FROM auction_registration WHERE bidder_id = #{bidderId}")
    long countByBidder(@Param("bidderId") long bidderId);

    @Select("""
            SELECT *
            FROM auction_registration
            WHERE bidder_id = #{bidderId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AuctionRegistrationEntity> findByBidder(
            @Param("bidderId") long bidderId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id
            FROM auction_registration
            WHERE status = 'PENDING_HOLD'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
              AND (lease_until IS NULL OR lease_until <= #{now})
            ORDER BY next_retry_at ASC, id ASC
            LIMIT #{batchSize}
            """)
    List<Long> findDueRecoveryIds(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Update("""
            UPDATE auction_registration
            SET lease_owner = #{leaseOwner},
                lease_until = #{leaseUntil},
                updated_at = #{now},
                version = version + 1
            WHERE id = #{registrationId}
              AND status = 'PENDING_HOLD'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
              AND (lease_until IS NULL OR lease_until <= #{now})
            """)
    int claimForRecovery(
            @Param("registrationId") long registrationId,
            @Param("now") Instant now,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil
    );

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

    @Update("""
            UPDATE auction_registration
            SET attempt_count = attempt_count + 1,
                next_retry_at = NULL,
                last_attempt_at = #{attemptedAt},
                lease_owner = NULL,
                lease_until = NULL,
                updated_at = #{attemptedAt},
                version = version + 1
            WHERE id = #{registrationId}
              AND status = 'PENDING_HOLD'
            """)
    int markRecoveryExhausted(
            @Param("registrationId") long registrationId,
            @Param("attemptedAt") Instant attemptedAt
    );
}
