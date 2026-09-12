package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("auction_registration")
public class AuctionRegistrationEntity extends VersionedAuditEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String registrationNo;
    private Long auctionId;
    private Long bidderId;
    private BigDecimal depositAmount;
    private String status;
    private String failureCode;
    private Integer attemptCount;
    private Instant nextRetryAt;
    private Instant lastAttemptAt;
    private String leaseOwner;
    private Instant leaseUntil;
    private Instant registeredAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getBidderId() { return bidderId; }
    public void setBidderId(Long bidderId) { this.bidderId = bidderId; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
