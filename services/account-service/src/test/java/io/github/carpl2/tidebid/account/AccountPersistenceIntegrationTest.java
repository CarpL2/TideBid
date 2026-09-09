package io.github.carpl2.tidebid.account;

import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.UserRoleEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletAccountEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.entity.WalletLedgerEntity;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.UserRoleMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletAccountMapper;
import io.github.carpl2.tidebid.account.infrastructure.persistence.mapper.WalletLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-db")
@Import(AccountJwtTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TIDEBID_ACCOUNT_DB_PASSWORD", matches = ".+")
class AccountPersistenceIntegrationTest {

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void mapsAllAccountTablesRejectsStaleUpdatesAndRollsBack() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "it_" + suffix;
        String businessNo = "IT_INIT_" + suffix;
        Instant earliestExpectedTime = Instant.now().minusSeconds(5);
        AtomicReference<Long> userId = new AtomicReference<>();
        AtomicReference<Long> walletId = new AtomicReference<>();
        AtomicReference<Long> ledgerId = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            UserAccountEntity user = new UserAccountEntity();
            user.setUsername(username);
            user.setPasswordHash("{bcrypt}integration-test-placeholder");
            user.setNickname("integration-test");
            user.setStatus("ACTIVE");

            assertThat(userAccountMapper.insert(user)).isEqualTo(1);
            assertThat(user.getId()).isPositive();
            assertThat(user.getVersion()).isZero();
            assertThat(user.getCreatedAt()).isAfterOrEqualTo(earliestExpectedTime);
            assertThat(user.getUpdatedAt()).isAfterOrEqualTo(earliestExpectedTime);
            userId.set(user.getId());

            UserRoleEntity role = new UserRoleEntity();
            role.setUserId(user.getId());
            role.setRoleCode("USER");
            assertThat(userRoleMapper.insert(role)).isEqualTo(1);
            assertThat(role.getCreatedAt()).isAfterOrEqualTo(earliestExpectedTime);

            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setUserId(user.getId());
            wallet.setAvailableBalance(new BigDecimal("10000.00"));
            wallet.setFrozenBalance(new BigDecimal("0.00"));
            assertThat(walletAccountMapper.insert(wallet)).isEqualTo(1);
            assertThat(wallet.getId()).isPositive();
            assertThat(wallet.getVersion()).isZero();
            walletId.set(wallet.getId());

            WalletLedgerEntity ledger = new WalletLedgerEntity();
            ledger.setWalletId(wallet.getId());
            ledger.setBusinessNo(businessNo);
            ledger.setLedgerType("INITIAL_CREDIT");
            ledger.setAvailableDelta(new BigDecimal("10000.00"));
            ledger.setFrozenDelta(new BigDecimal("0.00"));
            ledger.setAvailableBalanceAfter(new BigDecimal("10000.00"));
            ledger.setFrozenBalanceAfter(new BigDecimal("0.00"));
            assertThat(walletLedgerMapper.insert(ledger)).isEqualTo(1);
            assertThat(ledger.getId()).isPositive();
            assertThat(ledger.getCreatedAt()).isAfterOrEqualTo(earliestExpectedTime);
            ledgerId.set(ledger.getId());

            UserAccountEntity storedUser = userAccountMapper.selectById(user.getId());
            assertThat(storedUser.getUsername()).isEqualTo(username);
            List<UserRoleEntity> roles = userRoleMapper.selectByUserId(user.getId());
            assertThat(roles).extracting(UserRoleEntity::getRoleCode).containsExactly("USER");
            assertThat(roles.getFirst().getCreatedAt()).isNotNull();

            WalletAccountEntity firstWriter = walletAccountMapper.selectById(wallet.getId());
            WalletAccountEntity staleWriter = copyWallet(firstWriter);
            firstWriter.setAvailableBalance(new BigDecimal("9000.00"));
            assertThat(walletAccountMapper.updateById(firstWriter)).isEqualTo(1);
            assertThat(firstWriter.getVersion()).isEqualTo(1L);

            staleWriter.setAvailableBalance(new BigDecimal("8000.00"));
            assertThat(walletAccountMapper.updateById(staleWriter)).isZero();

            WalletAccountEntity afterConflict = walletAccountMapper.selectById(wallet.getId());
            assertThat(afterConflict.getVersion()).isEqualTo(1L);
            assertThat(afterConflict.getAvailableBalance()).isEqualByComparingTo("9000.00");
            assertThat(afterConflict.getUpdatedAt()).isAfterOrEqualTo(wallet.getUpdatedAt());

            WalletLedgerEntity storedLedger = walletLedgerMapper.selectById(ledger.getId());
            assertThat(storedLedger.getBusinessNo()).isEqualTo(businessNo);
            assertThat(storedLedger.getAvailableBalanceAfter()).isEqualByComparingTo("10000.00");

            status.setRollbackOnly();
        });

        assertThat(userAccountMapper.selectById(userId.get())).isNull();
        assertThat(userRoleMapper.selectByUserId(userId.get())).isEmpty();
        assertThat(walletAccountMapper.selectById(walletId.get())).isNull();
        assertThat(walletLedgerMapper.selectById(ledgerId.get())).isNull();
    }

    private static WalletAccountEntity copyWallet(WalletAccountEntity source) {
        WalletAccountEntity copy = new WalletAccountEntity();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setAvailableBalance(source.getAvailableBalance());
        copy.setFrozenBalance(source.getFrozenBalance());
        copy.setVersion(source.getVersion());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
