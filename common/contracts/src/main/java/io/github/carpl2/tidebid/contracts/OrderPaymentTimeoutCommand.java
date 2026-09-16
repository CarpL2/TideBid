package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;

/**
 * Delayed trigger whose expected deadline must be revalidated against the current order row.
 */
public record OrderPaymentTimeoutCommand(
        @JsonSerialize(using = ToStringSerializer.class) long orderId,
        Instant expectedPaymentDeadline
) {

    public static final String EVENT_TYPE = "order.payment-timeout";
    public static final int SCHEMA_VERSION = 1;

    public OrderPaymentTimeoutCommand {
        ContractRules.positive(orderId, "orderId");
        expectedPaymentDeadline = ContractRules.instant(expectedPaymentDeadline, "expectedPaymentDeadline");
    }
}
