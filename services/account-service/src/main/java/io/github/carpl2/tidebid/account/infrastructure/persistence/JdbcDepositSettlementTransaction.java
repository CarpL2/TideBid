package io.github.carpl2.tidebid.account.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.account.application.port.DepositSettlementTransaction;
import io.github.carpl2.tidebid.account.domain.WalletLedgerType;
import io.github.carpl2.tidebid.account.infrastructure.messaging.AccountOutboxEventFactory;
import io.github.carpl2.tidebid.account.infrastructure.messaging.JdbcAccountOutboxRepository;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettlementStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Profile({"local-db", "nacos"})
public class JdbcDepositSettlementTransaction implements DepositSettlementTransaction {

    private final JdbcTemplate jdbc;
    private final JdbcAccountOutboxRepository outbox;
    private final AccountOutboxEventFactory eventFactory;
    private final Clock clock;

    public JdbcDepositSettlementTransaction(
            DataSource dataSource,
            JdbcAccountOutboxRepository outbox,
            AccountOutboxEventFactory eventFactory,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.outbox = outbox;
        this.eventFactory = eventFactory;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WalletHoldSettledEvent settle(
            UUID sourceEventId,
            String traceId,
            DepositSettlementRequestedEvent request
    ) {
        HoldRow hold = lockHold(request.holdNo());
        validateIdentity(hold, request);
        if ("RELEASED".equals(hold.status()) || "CAPTURED".equals(hold.status())) {
            validateExistingIntent(hold, request);
            return existingResult(hold, request);
        }
        if (!"HELD".equals(hold.status())) {
            throw new DepositSettlementConflictException("wallet hold already has an incompatible terminal result");
        }

        return request.settlementType() == DepositSettlementType.RELEASE
                ? release(sourceEventId, traceId, request, hold)
                : capture(sourceEventId, traceId, request, hold);
    }

    private WalletHoldSettledEvent release(
            UUID sourceEventId,
            String traceId,
            DepositSettlementRequestedEvent request,
            HoldRow hold
    ) {
        Instant now = clock.instant();
        int holdChanged = jdbc.update("""
                UPDATE wallet_hold
                SET status = 'RELEASED', captured_amount = 0.00, released_amount = amount,
                    settlement_event_id = ?, settled_at = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'HELD' AND version = ?
                """, sourceEventId.toString(), Timestamp.from(now), Timestamp.from(now), hold.id(), hold.version());
        if (holdChanged != 1) {
            throw new DepositSettlementConflictException("wallet hold changed concurrently");
        }
        int walletChanged = jdbc.update("""
                UPDATE wallet_account
                SET available_balance = available_balance + ?, frozen_balance = frozen_balance - ?,
                    version = version + 1, updated_at = ?
                WHERE user_id = ? AND frozen_balance >= ?
                """, hold.amount(), hold.amount(), Timestamp.from(now), hold.userId(), hold.amount());
        if (walletChanged != 1) {
            throw new IllegalStateException("wallet frozen balance is inconsistent with its hold");
        }
        WalletRow wallet = loadWallet(hold.userId());
        UUID resultEventId = AccountOutboxEventFactory.deterministicSettlementResultEventId(
                hold.holdNo(), DepositSettlementType.RELEASE.name());
        jdbc.update("""
                INSERT INTO wallet_ledger (
                    id, wallet_id, business_no, ledger_type, available_delta, frozen_delta,
                    available_balance_after, frozen_balance_after, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, IdWorker.getId(), wallet.id(), resultEventId.toString(),
                WalletLedgerType.AUCTION_DEPOSIT_RELEASE.name(), hold.amount(), hold.amount().negate(),
                wallet.available(), wallet.frozen(), Timestamp.from(now));

        WalletHoldSettledEvent result = new WalletHoldSettledEvent(
                DepositSettlementType.RELEASE, WalletHoldSettlementStatus.RELEASED,
                request.auctionId(), null, request.userId(), request.holdNo(), request.holdAmount(),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), request.holdAmount(), now);
        outbox.enqueue(eventFactory.walletHoldSettled(result, traceId), now);
        return result;
    }

    private WalletHoldSettledEvent capture(
            UUID sourceEventId,
            String traceId,
            DepositSettlementRequestedEvent request,
            HoldRow hold
    ) {
        Instant now = clock.instant();
        BigDecimal capturedAmount = hold.amount().min(request.captureTargetAmount());
        BigDecimal releasedAmount = hold.amount().subtract(capturedAmount);
        int holdChanged = jdbc.update("""
                UPDATE wallet_hold
                SET status = 'CAPTURED', captured_amount = ?, released_amount = ?,
                    settlement_event_id = ?, settled_at = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'HELD' AND version = ?
                """, capturedAmount, releasedAmount, sourceEventId.toString(), Timestamp.from(now),
                Timestamp.from(now), hold.id(), hold.version());
        if (holdChanged != 1) {
            throw new DepositSettlementConflictException("wallet hold changed concurrently");
        }
        int walletChanged = jdbc.update("""
                UPDATE wallet_account
                SET available_balance = available_balance + ?, frozen_balance = frozen_balance - ?,
                    version = version + 1, updated_at = ?
                WHERE user_id = ? AND frozen_balance >= ?
                """, releasedAmount, hold.amount(), Timestamp.from(now), hold.userId(), hold.amount());
        if (walletChanged != 1) {
            throw new IllegalStateException("wallet frozen balance is inconsistent with its hold");
        }
        WalletRow wallet = loadWallet(hold.userId());
        UUID resultEventId = AccountOutboxEventFactory.deterministicSettlementResultEventId(
                hold.holdNo(), DepositSettlementType.CAPTURE.name());
        jdbc.update("""
                INSERT INTO wallet_ledger (
                    id, wallet_id, business_no, ledger_type, available_delta, frozen_delta,
                    available_balance_after, frozen_balance_after, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, IdWorker.getId(), wallet.id(), resultEventId.toString(),
                WalletLedgerType.AUCTION_DEPOSIT_CAPTURE.name(), releasedAmount, hold.amount().negate(),
                wallet.available(), wallet.frozen(), Timestamp.from(now));

        WalletHoldSettledEvent result = new WalletHoldSettledEvent(
                DepositSettlementType.CAPTURE, WalletHoldSettlementStatus.CAPTURED,
                request.auctionId(), request.orderId(), request.userId(), request.holdNo(), request.holdAmount(),
                request.captureTargetAmount(), capturedAmount, releasedAmount, now);
        outbox.enqueue(eventFactory.walletHoldSettled(result, traceId), now);
        return result;
    }

    private HoldRow lockHold(String holdNo) {
        List<HoldRow> rows = jdbc.query("""
                SELECT id, hold_no, user_id, amount, status, captured_amount, released_amount,
                       settlement_event_id, settled_at, version
                FROM wallet_hold WHERE hold_no = ? FOR UPDATE
                """, (row, number) -> new HoldRow(
                row.getLong("id"), row.getString("hold_no"), row.getLong("user_id"),
                row.getBigDecimal("amount"), row.getString("status"), row.getBigDecimal("captured_amount"),
                row.getBigDecimal("released_amount"), row.getString("settlement_event_id"),
                row.getTimestamp("settled_at") == null ? null : row.getTimestamp("settled_at").toInstant(),
                row.getLong("version")), holdNo);
        if (rows.size() != 1) {
            throw new DepositSettlementConflictException("wallet hold does not exist");
        }
        return rows.getFirst();
    }

    private WalletRow loadWallet(long userId) {
        List<WalletRow> rows = jdbc.query("""
                SELECT id, available_balance, frozen_balance FROM wallet_account WHERE user_id = ?
                """, (row, number) -> new WalletRow(row.getLong("id"), row.getBigDecimal("available_balance"),
                row.getBigDecimal("frozen_balance")), userId);
        if (rows.size() != 1) {
            throw new IllegalStateException("wallet account does not exist");
        }
        return rows.getFirst();
    }

    private static void validateIdentity(HoldRow hold, DepositSettlementRequestedEvent request) {
        if (hold.userId() != request.userId() || hold.amount().compareTo(request.holdAmount()) != 0) {
            throw new DepositSettlementConflictException("settlement request does not match the wallet hold");
        }
    }

    private static WalletHoldSettledEvent existingResult(HoldRow hold, DepositSettlementRequestedEvent request) {
        DepositSettlementType settlementType = "RELEASED".equals(hold.status())
                ? DepositSettlementType.RELEASE
                : DepositSettlementType.CAPTURE;
        WalletHoldSettlementStatus status = WalletHoldSettlementStatus.valueOf(hold.status());
        return new WalletHoldSettledEvent(
                settlementType, status, request.auctionId(), request.orderId(), request.userId(), request.holdNo(),
                request.holdAmount(), request.captureTargetAmount(), hold.capturedAmount(),
                hold.releasedAmount(), hold.settledAt());
    }

    private void validateExistingIntent(HoldRow hold, DepositSettlementRequestedEvent request) {
        if (!hold.status().equals(request.settlementType() == DepositSettlementType.RELEASE
                ? WalletHoldSettlementStatus.RELEASED.name()
                : WalletHoldSettlementStatus.CAPTURED.name())) {
            throw new DepositSettlementConflictException("wallet hold already has an incompatible terminal result");
        }
        String resultEventId = AccountOutboxEventFactory.deterministicSettlementResultEventId(
                hold.holdNo(), request.settlementType().name()).toString();
        List<MapRow> rows = jdbc.query("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.auctionId')) AS auction_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.orderId')) AS order_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.userId')) AS user_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.holdNo')) AS hold_no,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.holdAmount')) AS hold_amount,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.captureTargetAmount')) AS capture_target_amount
                FROM account_outbox WHERE event_id = ?
                """, (row, number) -> new MapRow(
                row.getString("auction_id"), row.getString("order_id"), row.getString("user_id"),
                row.getString("hold_no"), row.getString("hold_amount"),
                row.getString("capture_target_amount")), resultEventId);
        if (rows.size() != 1) {
            throw new DepositSettlementConflictException("stored settlement result is missing its durable event");
        }
        MapRow original = rows.getFirst();
        if (!Long.toString(request.auctionId()).equals(original.auctionId())
                || !nullableLongEquals(request.orderId(), original.orderId())
                || !Long.toString(request.userId()).equals(original.userId())
                || !request.holdNo().equals(original.holdNo())
                || request.holdAmount().compareTo(new BigDecimal(original.holdAmount())) != 0
                || request.captureTargetAmount().compareTo(new BigDecimal(original.captureTargetAmount())) != 0) {
            throw new DepositSettlementConflictException("wallet hold settlement was replayed with different intent");
        }
    }

    private static boolean nullableLongEquals(Long expected, String actual) {
        return expected == null
                ? actual == null || "null".equals(actual)
                : Long.toString(expected).equals(actual);
    }

    private record HoldRow(long id, String holdNo, long userId, BigDecimal amount, String status,
                           BigDecimal capturedAmount, BigDecimal releasedAmount,
                           String settlementEventId, Instant settledAt, long version) { }
    private record WalletRow(long id, BigDecimal available, BigDecimal frozen) { }
    private record MapRow(String auctionId, String orderId, String userId, String holdNo,
                          String holdAmount, String captureTargetAmount) { }

    public static final class DepositSettlementConflictException extends RuntimeException {
        public DepositSettlementConflictException(String message) { super(message); }
    }
}
