package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("auction_session")
public class AuctionSessionEntity extends VersionedAuditEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long itemId;
    private Long sellerId;
    private BigDecimal startPrice;
    private BigDecimal bidIncrement;
    private BigDecimal depositAmount;
    private BigDecimal currentPrice;
    private Long currentBidderId;
    private Long bidCount;
    private Instant startAt;
    private Instant endAt;
    private String status;
    private Long winnerId;
    private Long winningBidId;
    private BigDecimal finalPrice;
    private Instant closedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public BigDecimal getStartPrice() { return startPrice; }
    public void setStartPrice(BigDecimal startPrice) { this.startPrice = startPrice; }
    public BigDecimal getBidIncrement() { return bidIncrement; }
    public void setBidIncrement(BigDecimal bidIncrement) { this.bidIncrement = bidIncrement; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }
    public Long getCurrentBidderId() { return currentBidderId; }
    public void setCurrentBidderId(Long currentBidderId) { this.currentBidderId = currentBidderId; }
    public Long getBidCount() { return bidCount; }
    public void setBidCount(Long bidCount) { this.bidCount = bidCount; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getWinnerId() { return winnerId; }
    public void setWinnerId(Long winnerId) { this.winnerId = winnerId; }
    public Long getWinningBidId() { return winningBidId; }
    public void setWinningBidId(Long winningBidId) { this.winningBidId = winningBidId; }
    public BigDecimal getFinalPrice() { return finalPrice; }
    public void setFinalPrice(BigDecimal finalPrice) { this.finalPrice = finalPrice; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
