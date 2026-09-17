package io.github.carpl2.tidebid.account.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("wallet_hold")
public class WalletHoldEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String holdNo;
    private Long userId;
    private String businessType;
    private BigDecimal amount;
    private BigDecimal capturedAmount;
    private BigDecimal releasedAmount;
    private String status;
    private String settlementEventId;
    private Instant settledAt;
    @Version
    @TableField(fill = FieldFill.INSERT)
    private Long version;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHoldNo() {
        return holdNo;
    }

    public void setHoldNo(String holdNo) {
        this.holdNo = holdNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getCapturedAmount() { return capturedAmount; }
    public void setCapturedAmount(BigDecimal capturedAmount) { this.capturedAmount = capturedAmount; }
    public BigDecimal getReleasedAmount() { return releasedAmount; }
    public void setReleasedAmount(BigDecimal releasedAmount) { this.releasedAmount = releasedAmount; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSettlementEventId() { return settlementEventId; }
    public void setSettlementEventId(String settlementEventId) { this.settlementEventId = settlementEventId; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
