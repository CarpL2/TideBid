package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.DuplicateWalletHoldException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountDisabledException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountNotFoundException;
import io.github.carpl2.tidebid.account.application.port.WalletBalanceInsufficientException;
import io.github.carpl2.tidebid.account.application.port.WalletHoldRepository;
import io.github.carpl2.tidebid.account.application.port.WalletHoldTransaction;
import io.github.carpl2.tidebid.account.application.port.WalletHoldTransaction.HoldData;
import io.github.carpl2.tidebid.account.application.port.WalletNotFoundException;
import io.github.carpl2.tidebid.account.domain.AccountErrorCode;
import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Profile({"local-db", "nacos"})
public class WalletHoldService {

    private static final WalletHoldBusinessType BUSINESS_TYPE = WalletHoldBusinessType.AUCTION_DEPOSIT;

    private final WalletHoldRepository walletHoldRepository;
    private final WalletHoldTransaction walletHoldTransaction;

    public WalletHoldService(
            WalletHoldRepository walletHoldRepository,
            WalletHoldTransaction walletHoldTransaction
    ) {
        this.walletHoldRepository = walletHoldRepository;
        this.walletHoldTransaction = walletHoldTransaction;
    }

    public WalletHold hold(HoldWalletFundsCommand command) {
        HoldData holdData = validate(command);
        WalletHold existing = walletHoldRepository.findByHoldNo(holdData.holdNo()).orElse(null);
        if (existing != null) {
            return requireSamePayload(existing, holdData);
        }

        try {
            return requireSamePayload(walletHoldTransaction.create(holdData), holdData);
        } catch (DuplicateWalletHoldException exception) {
            WalletHold committed = walletHoldRepository.findByHoldNo(holdData.holdNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "Duplicate wallet hold could not be reloaded",
                            exception
                    ));
            return requireSamePayload(committed, holdData);
        } catch (WalletAccountNotFoundException exception) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND);
        } catch (WalletAccountDisabledException exception) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_DISABLED);
        } catch (WalletNotFoundException exception) {
            throw new BusinessException(AccountErrorCode.WALLET_NOT_FOUND);
        } catch (WalletBalanceInsufficientException exception) {
            throw new BusinessException(AccountErrorCode.WALLET_INSUFFICIENT_BALANCE);
        }
    }

    private static HoldData validate(HoldWalletFundsCommand command) {
        if (command == null) {
            throw invalid("Wallet hold request is required");
        }
        String holdNo;
        BigDecimal amount;
        try {
            holdNo = WalletHold.requireHoldNo(command.holdNo());
            amount = WalletHold.requireAmount(command.amount());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid(exception.getMessage());
        }
        if (command.userId() <= 0) {
            throw invalid("userId must be positive");
        }
        return new HoldData(holdNo, command.userId(), BUSINESS_TYPE, amount);
    }

    private static WalletHold requireSamePayload(WalletHold hold, HoldData expected) {
        if (hold.userId() != expected.userId()
                || hold.businessType() != expected.businessType()
                || hold.amount().compareTo(expected.amount()) != 0) {
            throw new BusinessException(AccountErrorCode.WALLET_HOLD_IDEMPOTENCY_CONFLICT);
        }
        return hold;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_ARGUMENT, message);
    }
}
