package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Validates untrusted browser messages before they reach a WebSocket handler. */
public final class RealtimeClientMessageDecoder {

    private static final Map<RealtimeMessageType, Class<?>> PAYLOAD_TYPES = Map.of(
            RealtimeMessageType.SUBSCRIBE, RealtimeSubscribe.class,
            RealtimeMessageType.UNSUBSCRIBE, RealtimeUnsubscribe.class,
            RealtimeMessageType.PING, RealtimePing.class
    );

    private final ObjectMapper objectMapper;
    private final int maxMessageBytes;

    public RealtimeClientMessageDecoder(ObjectMapper objectMapper) {
        this(objectMapper, RealtimeProtocol.MAX_CLIENT_MESSAGE_BYTES);
    }

    public RealtimeClientMessageDecoder(ObjectMapper objectMapper, int maxMessageBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        this.maxMessageBytes = maxMessageBytes;
    }

    public RealtimeClientMessage<?> decode(byte[] messageBody) {
        if (messageBody == null || messageBody.length == 0) {
            throw rejected(RejectionReason.MALFORMED_MESSAGE, "message body must not be empty", null);
        }
        if (messageBody.length > maxMessageBytes) {
            throw rejected(RejectionReason.MESSAGE_TOO_LARGE,
                    "message body exceeds " + maxMessageBytes + " bytes", null);
        }

        JsonNode root = readTree(messageBody);
        if (!root.isObject()) {
            throw rejected(RejectionReason.MALFORMED_MESSAGE, "message must be a JSON object", null);
        }

        RealtimeMessageType type = readType(root);
        int protocolVersion = readVersion(root);
        if (protocolVersion != RealtimeProtocol.PROTOCOL_VERSION) {
            throw rejected(RejectionReason.UNKNOWN_PROTOCOL_VERSION,
                    "unsupported protocolVersion: " + protocolVersion, null);
        }
        JsonNode payload = root.get("payload");
        if (payload == null || payload.isNull()) {
            throw rejected(RejectionReason.MISSING_PAYLOAD, "payload must not be missing or null", null);
        }

        JavaType messageType = objectMapper.getTypeFactory()
                .constructParametricType(RealtimeClientMessage.class, PAYLOAD_TYPES.get(type));
        try {
            return objectMapper.readerFor(messageType).readValue(root);
        } catch (IOException | IllegalArgumentException exception) {
            throw rejected(RejectionReason.MALFORMED_MESSAGE, "message is invalid", exception);
        }
    }

    private JsonNode readTree(byte[] messageBody) {
        try {
            return objectMapper.readTree(messageBody);
        } catch (IOException exception) {
            throw rejected(RejectionReason.MALFORMED_MESSAGE, "message body is not valid JSON", exception);
        }
    }

    private static RealtimeMessageType readType(JsonNode root) {
        JsonNode value = root.get("type");
        if (value == null || !value.isTextual()) {
            throw rejected(RejectionReason.MALFORMED_MESSAGE, "type must be a string", null);
        }
        try {
            RealtimeMessageType type = RealtimeMessageType.valueOf(value.textValue());
            if (!type.isClientMessage()) {
                throw new IllegalArgumentException("not a client type");
            }
            return type;
        } catch (IllegalArgumentException exception) {
            throw rejected(RejectionReason.UNKNOWN_MESSAGE_TYPE,
                    "unsupported client message type: " + value.textValue(), null);
        }
    }

    private static int readVersion(JsonNode root) {
        JsonNode value = root.get("protocolVersion");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw rejected(RejectionReason.MALFORMED_MESSAGE,
                    "protocolVersion must be an integer", null);
        }
        return value.intValue();
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
        UNKNOWN_MESSAGE_TYPE,
        UNKNOWN_PROTOCOL_VERSION,
        MISSING_PAYLOAD,
        MALFORMED_MESSAGE
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
