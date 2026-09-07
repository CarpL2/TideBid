package io.github.carpl2.tidebid.account.infrastructure.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class UtcAuditMetaObjectHandler implements MetaObjectHandler {

    private final Clock clock;

    public UtcAuditMetaObjectHandler(Clock clock) {
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
        // A loaded entity already contains the previous timestamp, so strictUpdateFill would keep it.
        setFieldValByName("updatedAt", Instant.now(clock), metaObject);
    }
}
