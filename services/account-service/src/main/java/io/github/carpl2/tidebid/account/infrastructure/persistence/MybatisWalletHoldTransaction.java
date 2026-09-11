package io.github.carpl2.tidebid.account.infrastructure.persistence;

import io.github.carpl2.tidebid.account.application.port.DuplicateWalletHoldException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountDisabledException;
import io.github.carpl2.tidebid.account.application.port.WalletAccountNotFoundException;
import io.github.carpl2.tidebid.account.application.port.WalletBalanceInsufficientException;
import io.github.carpl2.tidebid.account.application.port.WalletHoldRepository;
import io.github.carpl2.tidebid.account.application.port.WalletHoldTransaction;
import io.github.carpl2.tidebid.account.application.port.WalletNotFoundException;
import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletLedgerType;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Component
@Profile({"local-db", "nacos"})
public class MybatisWalletHoldTransaction implements WalletHoldTransaction {

    private final WalletHoldRepository walletHoldRepository;
    private final UserAccountMapper userAccountMapper;
    private final WalletAccountMapper walletAccountMapper;
    private final WalletLedgerMapper walletLedgerMapper;
    private final Clock clock;

    public MybatisWalletHoldTransaction(
            WalletHoldRepository walletHoldRepository,
            UserAccountMapper userAccountMapper,
            WalletAccountMapper walletAccountMapper,
            WalletLedgerMapper walletLedgerMapper,
            Clock clock
    ) {
        this.walletHoldRepository = walletHoldRepository;
        this.userAccountMapper = userAccountMapper;
        this.walletAccountMapper = walletAccountMapper;
        this.walletLedgerMapper = walletLedgerMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WalletHold create(HoldData holdData) {
        WalletHold existing = walletHoldRepository.findByHoldNo(holdData.holdNo()).orElse(null);
        if (existing != null) {
            return existing;
        }

        UserAccountEntity account = userAccountMapper.selectById(holdData.userId());
        if (account == null) {
            throw new WalletAccountNotFoundException();
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new WalletAccountDisabledException();
        }

        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(holdData.userId());
        if (wallet == null) {
            throw new WalletNotFoundException();
        }

        WalletHold hold;
        try {
            hold = walletHoldRepository.insertHeld(
                    holdData.holdNo(),
                    holdData.userId(),
                    holdData.businessType(),
                    holdData.amount()
            );
        } catch (DuplicateKeyException exception) {
            throw new DuplicateWalletHoldException(exception);
        }

        Instant now = clock.instant();
        if (walletAccountMapper.holdAvailableBalance(holdData.userId(), holdData.amount(), now) != 1) {
            throw new WalletBalanceInsufficientException();
        }
        WalletAccountEntity updatedWallet = walletAccountMapper.selectByUserId(holdData.userId());
        if (updatedWallet == null) {
            throw new IllegalStateException("Updated wallet could not be reloaded");
        }

        WalletLedgerEntity ledger = new WalletLedgerEntity();
        ledger.setWalletId(updatedWallet.getId());
        ledger.setBusinessNo(holdData.holdNo());
        ledger.setLedgerType(WalletLedgerType.AUCTION_DEPOSIT_HOLD.name());
        ledger.setAvailableDelta(holdData.amount().negate());
        ledger.setFrozenDelta(holdData.amount());
        ledger.setAvailableBalanceAfter(updatedWallet.getAvailableBalance());
        ledger.setFrozenBalanceAfter(updatedWallet.getFrozenBalance());
        if (walletLedgerMapper.insert(ledger) != 1) {
            throw new IllegalStateException("Expected one inserted wallet hold ledger");
        }
        return hold;
    }
}
