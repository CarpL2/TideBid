package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class AuctionUtcAuditMetaObjectHandler implements MetaObjectHandler {

    private final Clock clock;

    public AuctionUtcAuditMetaObjectHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now(clock);
        strictInsertFill(metaObject, "version", Long.class, 0L);
        strictInsertFill(metaObject, "createdAt", Instant.class, now);
        strictInsertFill(metaObject, "updatedAt", Instant.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", Instant.now(clock), metaObject);
    }
}
