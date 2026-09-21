package io.github.carpl2.tidebid.realtime.application.service;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeTicketStore;
import io.github.carpl2.tidebid.security.AuthenticatedUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class RealtimeTicketApplicationService {

    private static final int TICKET_BYTES = 32;
    private static final Base64.Encoder TICKET_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final HexFormat HEX = HexFormat.of();

    private final RealtimeTicketStore store;
    private final Duration ttl;
    private final Duration rateLimitWindow;
    private final int rateLimitMaxRequests;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public RealtimeTicketApplicationService(
            RealtimeTicketStore store,
            Duration ttl,
            Duration rateLimitWindow,
            int rateLimitMaxRequests,
            Clock clock,
            SecureRandom secureRandom
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.rateLimitWindow = Objects.requireNonNull(rateLimitWindow, "rateLimitWindow must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        if (ttl.isNegative() || ttl.isZero() || rateLimitWindow.isNegative() || rateLimitWindow.isZero()) {
            throw new IllegalArgumentException("ticket durations must be positive");
        }
        if (rateLimitMaxRequests < 1) {
            throw new IllegalArgumentException("rateLimitMaxRequests must be positive");
        }
        this.rateLimitMaxRequests = rateLimitMaxRequests;
    }

    public RealtimeTicketIssue issue(AuthenticatedUser user, String sourceIp) {
        Objects.requireNonNull(user, "user must not be null");
        String normalizedIp = normalizeSourceIp(sourceIp);
        RealtimeTicketStore.TicketRateLimitDecision decision;
        try {
            decision = store.acquireRateLimit(
                    user.userId(), normalizedIp, rateLimitWindow, rateLimitMaxRequests);
        } catch (RealtimeTicketStoreUnavailableException exception) {
            throw serviceUnavailable(exception);
        }
        if (!decision.allowed()) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }

        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        String ticket = TICKET_ENCODER.encodeToString(bytes);
        Instant issuedAt = clock.instant();
        RealtimeTicketIdentity identity = new RealtimeTicketIdentity(user.userId(), user.roles(), issuedAt);
        try {
            store.save(digest(ticket), identity, ttl);
        } catch (RealtimeTicketStoreUnavailableException exception) {
            throw serviceUnavailable(exception);
        }
        return new RealtimeTicketIssue(ticket, issuedAt.plus(ttl));
    }

    public Optional<RealtimeTicketIdentity> consume(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        try {
            return store.consume(digest(ticket));
        } catch (RealtimeTicketStoreUnavailableException exception) {
            throw serviceUnavailable(exception);
        }
    }

    public static String digest(String ticket) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeSourceIp(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank()) {
            return "unknown";
        }
        String normalized = sourceIp.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static BusinessException serviceUnavailable(RealtimeTicketStoreUnavailableException exception) {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
