package io.github.carpl2.tidebid.account.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("wallet_ledger")
public class WalletLedgerEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long walletId;
    private String businessNo;
    private String ledgerType;
    private BigDecimal availableDelta;
    private BigDecimal frozenDelta;
    private BigDecimal availableBalanceAfter;
    private BigDecimal frozenBalanceAfter;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWalletId() {
        return walletId;
    }

    public void setWalletId(Long walletId) {
        this.walletId = walletId;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public void setBusinessNo(String businessNo) {
        this.businessNo = businessNo;
    }

    public String getLedgerType() {
        return ledgerType;
    }

    public void setLedgerType(String ledgerType) {
        this.ledgerType = ledgerType;
    }

    public BigDecimal getAvailableDelta() {
        return availableDelta;
    }

    public void setAvailableDelta(BigDecimal availableDelta) {
        this.availableDelta = availableDelta;
    }

    public BigDecimal getFrozenDelta() {
        return frozenDelta;
    }

    public void setFrozenDelta(BigDecimal frozenDelta) {
        this.frozenDelta = frozenDelta;
    }

    public BigDecimal getAvailableBalanceAfter() {
        return availableBalanceAfter;
    }

    public void setAvailableBalanceAfter(BigDecimal availableBalanceAfter) {
        this.availableBalanceAfter = availableBalanceAfter;
    }

    public BigDecimal getFrozenBalanceAfter() {
        return frozenBalanceAfter;
    }

    public void setFrozenBalanceAfter(BigDecimal frozenBalanceAfter) {
        this.frozenBalanceAfter = frozenBalanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
