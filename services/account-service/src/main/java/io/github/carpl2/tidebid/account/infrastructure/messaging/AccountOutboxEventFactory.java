package io.github.carpl2.tidebid.account.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
@Profile({"local-db", "nacos"})
public class AccountOutboxEventFactory {

    private static final String PRODUCER = "tidebid-account-service";
    private final ObjectMapper objectMapper;

    public AccountOutboxEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JdbcAccountOutboxRepository.NewOutboxEvent walletHoldSettled(
            WalletHoldSettledEvent payload,
            String traceId
    ) {
        UUID eventId = deterministicSettlementResultEventId(payload.holdNo(), payload.settlementType().name());
        EventEnvelope<WalletHoldSettledEvent> envelope = new EventEnvelope<>(
                eventId, WalletHoldSettledEvent.EVENT_TYPE, WalletHoldSettledEvent.SCHEMA_VERSION,
                payload.settledAt(), PRODUCER, traceId, payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return new JdbcAccountOutboxRepository.NewOutboxEvent(
                    eventId.toString(), "WALLET_HOLD", payload.holdNo(), WalletHoldSettledEvent.EVENT_TYPE,
                    WalletHoldSettledEvent.SCHEMA_VERSION, RocketMqTopology.ACCOUNT_EVENTS_TOPIC,
                    json, sha256(json), payload.settledAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Wallet settlement result could not be encoded", exception);
        }
    }

    public static UUID deterministicSettlementResultEventId(String holdNo, String settlementType) {
        return UUID.nameUUIDFromBytes(("tidebid:wallet-hold-settled:v1:" + holdNo + ":" + settlementType)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
