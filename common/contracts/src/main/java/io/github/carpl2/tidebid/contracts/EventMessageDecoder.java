package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validates the untrusted message boundary before materializing an event envelope.
 */
public final class EventMessageDecoder {

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final Map<String, Map<Integer, Class<?>>> payloadTypes;
    private final int maxMessageBytes;

    public EventMessageDecoder(ObjectMapper objectMapper) {
        this(objectMapper, stageThreePayloadTypes(), DEFAULT_MAX_MESSAGE_BYTES);
    }

    public EventMessageDecoder(
            ObjectMapper objectMapper,
            Map<String, Map<Integer, Class<?>>> payloadTypes,
            int maxMessageBytes
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.payloadTypes = immutableRegistry(payloadTypes);
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        this.maxMessageBytes = maxMessageBytes;
    }

    public EventEnvelope<?> decode(byte[] messageBody) {
        if (messageBody == null || messageBody.length == 0) {
            throw rejected(RejectionReason.MALFORMED_ENVELOPE, "message body must not be empty", null);
        }
        if (messageBody.length > maxMessageBytes) {
            throw rejected(
                    RejectionReason.MESSAGE_TOO_LARGE,
                    "message body exceeds " + maxMessageBytes + " bytes",
                    null
            );
        }

        JsonNode root = readTree(messageBody);
        if (!root.isObject()) {
            throw rejected(RejectionReason.MALFORMED_ENVELOPE, "message envelope must be a JSON object", null);
        }

        String eventType = requiredText(root, "eventType");
        Map<Integer, Class<?>> versions = payloadTypes.get(eventType);
        if (versions == null) {
            throw rejected(RejectionReason.UNKNOWN_EVENT_TYPE, "unsupported eventType: " + eventType, null);
        }

        int schemaVersion = requiredPositiveInteger(root, "schemaVersion");
        Class<?> payloadType = versions.get(schemaVersion);
        if (payloadType == null) {
            throw rejected(
                    RejectionReason.UNKNOWN_SCHEMA_VERSION,
                    "unsupported schemaVersion " + schemaVersion + " for eventType " + eventType,
                    null
            );
        }

        JsonNode payload = root.get("payload");
        if (payload == null || payload.isNull()) {
            throw rejected(RejectionReason.MISSING_PAYLOAD, "payload must not be missing or null", null);
        }

        JavaType envelopeType = objectMapper.getTypeFactory()
                .constructParametricType(EventEnvelope.class, payloadType);
        try {
            return objectMapper.readerFor(envelopeType).readValue(root);
        } catch (IOException | IllegalArgumentException exception) {
            throw rejected(RejectionReason.MALFORMED_ENVELOPE, "message envelope is invalid", exception);
        }
    }

    private JsonNode readTree(byte[] messageBody) {
        try {
            return objectMapper.readTree(messageBody);
        } catch (IOException exception) {
            throw rejected(RejectionReason.MALFORMED_ENVELOPE, "message body is not valid JSON", exception);
        }
    }

    private static String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw rejected(
                    RejectionReason.MALFORMED_ENVELOPE,
                    fieldName + " must be a non-blank string",
                    null
            );
        }
        return value.textValue();
    }

    private static int requiredPositiveInteger(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw rejected(
                    RejectionReason.MALFORMED_ENVELOPE,
                    fieldName + " must be a positive integer",
                    null
            );
        }
        return value.intValue();
    }

    private static Map<String, Map<Integer, Class<?>>> stageThreePayloadTypes() {
        Map<String, Map<Integer, Class<?>>> registry = new LinkedHashMap<>();
        register(registry, BidAcceptedEvent.EVENT_TYPE, BidAcceptedEvent.SCHEMA_VERSION, BidAcceptedEvent.class);
        register(registry, CloseAuctionCommand.EVENT_TYPE, CloseAuctionCommand.SCHEMA_VERSION, CloseAuctionCommand.class);
        register(registry, AuctionClosedSoldEvent.EVENT_TYPE, AuctionClosedSoldEvent.SCHEMA_VERSION, AuctionClosedSoldEvent.class);
        register(registry, AuctionClosedUnsoldEvent.EVENT_TYPE, AuctionClosedUnsoldEvent.SCHEMA_VERSION, AuctionClosedUnsoldEvent.class);
        register(registry, DepositSettlementRequestedEvent.EVENT_TYPE, DepositSettlementRequestedEvent.SCHEMA_VERSION, DepositSettlementRequestedEvent.class);
        register(registry, WalletHoldSettledEvent.EVENT_TYPE, WalletHoldSettledEvent.SCHEMA_VERSION, WalletHoldSettledEvent.class);
        register(registry, OrderPaymentTimeoutCommand.EVENT_TYPE, OrderPaymentTimeoutCommand.SCHEMA_VERSION, OrderPaymentTimeoutCommand.class);
        register(registry, OrderPaidEvent.EVENT_TYPE, OrderPaidEvent.SCHEMA_VERSION, OrderPaidEvent.class);
        register(registry, OrderPaymentTimedOutEvent.EVENT_TYPE, OrderPaymentTimedOutEvent.SCHEMA_VERSION, OrderPaymentTimedOutEvent.class);
        register(registry, SellerCreditRequestedEvent.EVENT_TYPE, SellerCreditRequestedEvent.SCHEMA_VERSION, SellerCreditRequestedEvent.class);
        register(registry, SellerCreditedEvent.EVENT_TYPE, SellerCreditedEvent.SCHEMA_VERSION, SellerCreditedEvent.class);
        return registry;
    }

    private static void register(
            Map<String, Map<Integer, Class<?>>> registry,
            String eventType,
            int schemaVersion,
            Class<?> payloadType
    ) {
        registry.computeIfAbsent(eventType, ignored -> new LinkedHashMap<>())
                .put(schemaVersion, payloadType);
    }

    private static Map<String, Map<Integer, Class<?>>> immutableRegistry(
            Map<String, Map<Integer, Class<?>>> registry
    ) {
        Objects.requireNonNull(registry, "payloadTypes must not be null");
        Map<String, Map<Integer, Class<?>>> copy = new LinkedHashMap<>();
        registry.forEach((eventType, versions) -> copy.put(
                Objects.requireNonNull(eventType, "eventType must not be null"),
                Map.copyOf(Objects.requireNonNull(versions, "versions must not be null"))
        ));
        return Map.copyOf(copy);
    }

    private static MessageRejectedException rejected(
            RejectionReason reason,
            String message,
            Throwable cause
    ) {
        return new MessageRejectedException(reason, message, cause);
    }

    public enum RejectionReason {
        MESSAGE_TOO_LARGE,
        UNKNOWN_EVENT_TYPE,
        UNKNOWN_SCHEMA_VERSION,
        MISSING_PAYLOAD,
        MALFORMED_ENVELOPE
    }

    public static final class MessageRejectedException extends IllegalArgumentException {

        private final RejectionReason reason;

        private MessageRejectedException(RejectionReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public RejectionReason reason() {
            return reason;
        }
    }
}
