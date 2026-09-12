package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("bid_record")
public class BidRecordEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long auctionId;
    private Long bidderId;
    private String requestId;
    private BigDecimal amount;
    private BigDecimal previousPrice;
    private Long sequenceNo;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getBidderId() { return bidderId; }
    public void setBidderId(Long bidderId) { this.bidderId = bidderId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getPreviousPrice() { return previousPrice; }
    public void setPreviousPrice(BigDecimal previousPrice) { this.previousPrice = previousPrice; }
    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long sequenceNo) { this.sequenceNo = sequenceNo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
