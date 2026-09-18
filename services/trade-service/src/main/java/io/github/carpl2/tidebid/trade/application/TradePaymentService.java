package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.trade.application.port.AccountDebitPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"local-db", "nacos"})
public class TradePaymentService {

    private final TradePaymentTransaction transaction;
    private final AccountDebitPort account;

    public TradePaymentService(TradePaymentTransaction transaction, AccountDebitPort account) {
        this.transaction = transaction;
        this.account = account;
    }

    public PaymentAttemptSnapshot pay(long buyerId, long orderId, String requestId, String traceId) {
        TradePaymentTransaction.StartResult start = transaction.begin(buyerId, orderId, requestId);
        if (!start.shouldCallAccount()) {
            return start.attempt();
        }
        AccountDebitPort.DebitResult result = account.debit(new AccountDebitPort.DebitCommand(
                start.attempt().paymentNo(), buyerId, orderId, start.attempt().amount(), requestId, traceId));
        return transaction.apply(start.attempt().id(), result, traceId);
    }
}
