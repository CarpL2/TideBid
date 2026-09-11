package io.github.carpl2.tidebid.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.account.application.HoldWalletFundsCommand;
import io.github.carpl2.tidebid.account.application.WalletHoldService;
import io.github.carpl2.tidebid.account.application.port.AccountRegistrationStore;
import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldStatus;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletHoldEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletHoldMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class WalletHoldIntegrationTest {

    @Autowired
    private WalletHoldService walletHoldService;

    @Autowired
    private AccountRegistrationStore registrationStore;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletHoldMapper walletHoldMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Test
    void successfulHoldMovesBalanceOnceAndSamePayloadReturnsOriginalResult() {
        TestAccount account = createAccount("1000.00");
        String holdNo = uniqueHoldNo();
        try {
            WalletHold first = walletHoldService.hold(command(holdNo, account.userId(), "250.00"));
            WalletHold repeated = walletHoldService.hold(command(holdNo, account.userId(), "250.0"));

            assertThat(first.status()).isEqualTo(WalletHoldStatus.HELD);
            assertThat(repeated).isEqualTo(first);
            assertWallet(account.userId(), "750.00", "250.00", 1L);
            assertThat(findHolds(account.userId())).hasSize(1);

            WalletLedgerEntity ledger = findLedger(holdNo);
            assertThat(ledger).isNotNull();
            assertThat(ledger.getWalletId()).isEqualTo(account.walletId());
            assertThat(ledger.getLedgerType()).isEqualTo("AUCTION_DEPOSIT_HOLD");
            assertThat(ledger.getAvailableDelta()).isEqualByComparingTo("-250.00");
            assertThat(ledger.getFrozenDelta()).isEqualByComparingTo("250.00");
            assertThat(ledger.getAvailableBalanceAfter()).isEqualByComparingTo("750.00");
            assertThat(ledger.getFrozenBalanceAfter()).isEqualByComparingTo("250.00");
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void reusedHoldNumberWithDifferentPayloadReturnsConflictWithoutAnotherWrite() {
        TestAccount firstAccount = createAccount("1000.00");
        TestAccount secondAccount = createAccount("1000.00");
        String holdNo = uniqueHoldNo();
        try {
            walletHoldService.hold(command(holdNo, firstAccount.userId(), "200.00"));

            assertBusinessError(
                    () -> walletHoldService.hold(command(holdNo, firstAccount.userId(), "201.00")),
                    "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT"
            );
            assertBusinessError(
                    () -> walletHoldService.hold(command(holdNo, secondAccount.userId(), "200.00")),
                    "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT"
            );

            assertWallet(firstAccount.userId(), "800.00", "200.00", 1L);
            assertWallet(secondAccount.userId(), "1000.00", "0.00", 0L);
            assertThat(findHolds(firstAccount.userId())).hasSize(1);
            assertThat(findHolds(secondAccount.userId())).isEmpty();
            assertThat(findLedgers(holdNo)).hasSize(1);
        } finally {
            deleteAccount(firstAccount.userId());
            deleteAccount(secondAccount.userId());
        }
    }

    @Test
    void insufficientBalanceRollsBackHoldWalletAndLedger() {
        TestAccount account = createAccount("100.00");
        String holdNo = uniqueHoldNo();
        try {
            assertBusinessError(
                    () -> walletHoldService.hold(command(holdNo, account.userId(), "100.01")),
                    "ACCOUNT_WALLET_INSUFFICIENT_BALANCE"
            );

            assertWallet(account.userId(), "100.00", "0.00", 0L);
            assertThat(findHolds(account.userId())).isEmpty();
            assertThat(findLedger(holdNo)).isNull();
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void concurrentHoldsNeverMakeAvailableBalanceNegativeOrLoseVersionUpdates() throws Exception {
        TestAccount account = createAccount("1000.00");
        int attempts = 8;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<String> holdNumbers = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            holdNumbers.add(uniqueHoldNo());
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            List<Future<String>> futures = new ArrayList<>();
            for (String holdNo : holdNumbers) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent hold start timed out");
                    }
                    try {
                        walletHoldService.hold(command(holdNo, account.userId(), "300.00"));
                        return "SUCCESS";
                    } catch (BusinessException exception) {
                        return exception.errorCode().code();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn("SUCCESS"::equals).hasSize(3);
            assertThat(outcomes)
                    .filteredOn(code -> !"SUCCESS".equals(code))
                    .containsOnly("ACCOUNT_WALLET_INSUFFICIENT_BALANCE")
                    .hasSize(5);
            assertWallet(account.userId(), "100.00", "900.00", 3L);
            assertThat(findHolds(account.userId())).hasSize(3);
            assertThat(countHoldLedgers(holdNumbers)).isEqualTo(3);
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void concurrentSamePayloadUsesOneHoldAndMovesBalanceOnlyOnce() throws Exception {
        TestAccount account = createAccount("1000.00");
        String holdNo = uniqueHoldNo();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<WalletHold> first = executor.submit(() -> holdConcurrently(
                    ready,
                    start,
                    holdNo,
                    account.userId(),
                    "200.00"
            ));
            Future<WalletHold> second = executor.submit(() -> holdConcurrently(
                    ready,
                    start,
                    holdNo,
                    account.userId(),
                    "200.0"
            ));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            WalletHold firstResult = first.get(30, TimeUnit.SECONDS);
            WalletHold secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
            assertWallet(account.userId(), "800.00", "200.00", 1L);
            assertThat(findHolds(account.userId())).hasSize(1);
            assertThat(findLedgers(holdNo)).hasSize(1);
        } finally {
            deleteAccount(account.userId());
        }
    }

    @Test
    void ledgerFailureRollsBackInsertedHoldAndWalletMutation() {
        TestAccount account = createAccount("1000.00");
        String holdNo = uniqueHoldNo();
        try {
            WalletLedgerEntity collision = new WalletLedgerEntity();
            collision.setWalletId(account.walletId());
            collision.setBusinessNo(holdNo);
            collision.setLedgerType("INITIAL_CREDIT");
            collision.setAvailableDelta(BigDecimal.ZERO);
            collision.setFrozenDelta(BigDecimal.ZERO);
            collision.setAvailableBalanceAfter(new BigDecimal("1000.00"));
            collision.setFrozenBalanceAfter(BigDecimal.ZERO);
            assertThat(walletLedgerMapper.insert(collision)).isEqualTo(1);

            assertThatThrownBy(() -> walletHoldService.hold(command(
                    holdNo,
                    account.userId(),
                    "400.00"
            ))).isInstanceOf(RuntimeException.class);

            assertWallet(account.userId(), "1000.00", "0.00", 0L);
            assertThat(findHolds(account.userId())).isEmpty();
            assertThat(findLedgers(holdNo)).hasSize(1);
        } finally {
            deleteAccount(account.userId());
        }
    }

    private TestAccount createAccount(String initialBalance) {
        String username = "wh" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long userId = registrationStore.create(new AccountRegistrationStore.RegistrationData(
                username,
                "$2a$12$wallet.hold.integration.placeholder.hash",
                "Wallet hold integration",
                new BigDecimal(initialBalance),
                BigDecimal.ZERO
        ));
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        return new TestAccount(userId, wallet.getId());
    }

    private WalletHold holdConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            String holdNo,
            long userId,
            String amount
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent idempotent hold start timed out");
        }
        return walletHoldService.hold(command(holdNo, userId, amount));
    }

    private static HoldWalletFundsCommand command(String holdNo, long userId, String amount) {
        return new HoldWalletFundsCommand(holdNo, userId, new BigDecimal(amount));
    }

    private void assertWallet(long userId, String available, String frozen, long version) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(available);
        assertThat(wallet.getFrozenBalance()).isEqualByComparingTo(frozen);
        assertThat(wallet.getVersion()).isEqualTo(version);
        assertThat(wallet.getAvailableBalance()).isNotNegative();
    }

    private List<WalletHoldEntity> findHolds(long userId) {
        return walletHoldMapper.selectList(new LambdaQueryWrapper<WalletHoldEntity>()
                .eq(WalletHoldEntity::getUserId, userId));
    }

    private WalletLedgerEntity findLedger(String businessNo) {
        return walletLedgerMapper.selectOne(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getBusinessNo, businessNo));
    }

    private List<WalletLedgerEntity> findLedgers(String businessNo) {
        return walletLedgerMapper.selectList(new LambdaQueryWrapper<WalletLedgerEntity>()
                .eq(WalletLedgerEntity::getBusinessNo, businessNo));
    }

    private long countHoldLedgers(List<String> holdNumbers) {
        return walletLedgerMapper.selectCount(new LambdaQueryWrapper<WalletLedgerEntity>()
                .in(WalletLedgerEntity::getBusinessNo, holdNumbers));
    }

    private void deleteAccount(long userId) {
        WalletAccountEntity wallet = walletAccountMapper.selectByUserId(userId);
        walletHoldMapper.delete(new LambdaQueryWrapper<WalletHoldEntity>()
                .eq(WalletHoldEntity::getUserId, userId));
        if (wallet != null) {
            walletLedgerMapper.delete(new LambdaQueryWrapper<WalletLedgerEntity>()
                    .eq(WalletLedgerEntity::getWalletId, wallet.getId()));
            walletAccountMapper.deleteById(wallet.getId());
        }
        for (UserRoleEntity role : userRoleMapper.selectByUserId(userId)) {
            userRoleMapper.delete(userId, role.getRoleCode());
        }
        userAccountMapper.deleteById(userId);
    }

    private static void assertBusinessError(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo(code));
    }

    private static String uniqueHoldNo() {
        return "REGISTRATION:" + UUID.randomUUID().toString().replace("-", "");
    }

    private record TestAccount(long userId, long walletId) {
    }
}
