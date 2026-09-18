package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.DuplicateWalletDebitException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountDisabledException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountNotFoundException;
import io.github.carpl2.tidebid.account.application.port.WalletDebitRepository;
import io.github.carpl2.tidebid.account.application.port.WalletDebitTransaction;
import io.github.carpl2.tidebid.account.application.port.WalletDebitTransaction.DebitData;
import io.github.carpl2.tidebid.account.application.port.WalletNotFoundException;
import io.github.carpl2.tidebid.account.domain.AccountErrorCode;
import io.github.carpl2.tidebid.account.domain.WalletDebit;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Profile({"local-db", "nacos"})
public class WalletDebitService {

    private final WalletDebitRepository repository;
    private final WalletDebitTransaction transaction;

    public WalletDebitService(WalletDebitRepository repository, WalletDebitTransaction transaction) {
        this.repository = repository;
        this.transaction = transaction;
    }

    public WalletDebit debit(CreateWalletDebitCommand command) {
        DebitData data = validate(command);
        WalletDebit existing = repository.findByPaymentNo(data.paymentNo()).orElse(null);
        if (existing != null) {
            return requireSamePayload(existing, data);
        }
        try {
            return requireSamePayload(transaction.create(data), data);
        } catch (DuplicateWalletDebitException exception) {
            WalletDebit committed = repository.findByPaymentNo(data.paymentNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "Duplicate wallet debit could not be reloaded", exception));
            return requireSamePayload(committed, data);
        } catch (WalletAccountNotFoundException exception) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND);
        } catch (WalletAccountDisabledException exception) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_DISABLED);
        } catch (WalletNotFoundException exception) {
            throw new BusinessException(AccountErrorCode.WALLET_NOT_FOUND);
        }
    }

    public WalletDebit findByPaymentNo(String paymentNo) {
        String normalized;
        try {
            normalized = WalletDebit.requirePaymentNo(paymentNo);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
        return repository.findByPaymentNo(normalized)
                .orElseThrow(() -> new BusinessException(AccountErrorCode.WALLET_DEBIT_NOT_FOUND));
    }

    private static DebitData validate(CreateWalletDebitCommand command) {
        if (command == null) {
            throw invalid("Wallet debit request is required");
        }
        String paymentNo;
        BigDecimal amount;
        try {
            paymentNo = WalletDebit.requirePaymentNo(command.paymentNo());
            amount = WalletDebit.requireAmount(command.amount());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid(exception.getMessage());
        }
        if (command.userId() <= 0 || command.orderId() <= 0) {
            throw invalid("userId and orderId must be positive");
        }
        return new DebitData(paymentNo, command.userId(), command.orderId(), amount);
    }

    private static WalletDebit requireSamePayload(WalletDebit debit, DebitData expected) {
        if (debit.userId() != expected.userId()
                || debit.orderId() != expected.orderId()
                || debit.amount().compareTo(expected.amount()) != 0) {
            throw new BusinessException(AccountErrorCode.WALLET_DEBIT_IDEMPOTENCY_CONFLICT);
        }
        return debit;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_ARGUMENT, message);
    }
}
