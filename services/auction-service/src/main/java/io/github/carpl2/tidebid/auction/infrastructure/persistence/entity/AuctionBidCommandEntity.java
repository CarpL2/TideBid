package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("auction_bid_command")
public class AuctionBidCommandEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long auctionId;
    private Long actorId;
    private String requestId;
    private String commandType;
    private String payloadHash;
    private String status;
    private Integer resultBidCount;
    private BigDecimal resultPrice;
    private Boolean resultLeading;
    private Long firstSequenceNo;
    private Long lastSequenceNo;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    private Instant completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getCommandType() { return commandType; }
    public void setCommandType(String commandType) { this.commandType = commandType; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getResultBidCount() { return resultBidCount; }
    public void setResultBidCount(Integer resultBidCount) { this.resultBidCount = resultBidCount; }
    public BigDecimal getResultPrice() { return resultPrice; }
    public void setResultPrice(BigDecimal resultPrice) { this.resultPrice = resultPrice; }
    public Boolean getResultLeading() { return resultLeading; }
    public void setResultLeading(Boolean resultLeading) { this.resultLeading = resultLeading; }
    public Long getFirstSequenceNo() { return firstSequenceNo; }
    public void setFirstSequenceNo(Long firstSequenceNo) { this.firstSequenceNo = firstSequenceNo; }
    public Long getLastSequenceNo() { return lastSequenceNo; }
    public void setLastSequenceNo(Long lastSequenceNo) { this.lastSequenceNo = lastSequenceNo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
