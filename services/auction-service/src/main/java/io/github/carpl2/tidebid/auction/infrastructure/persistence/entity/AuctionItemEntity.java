package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("auction_item")
public class AuctionItemEntity extends VersionedAuditEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long sellerId;
    private String title;
    private String description;
    private String category;
    private String itemCondition;
    private String reviewStatus;
    private Integer submissionVersion;
    private Instant submittedAt;
    private Instant approvedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getItemCondition() { return itemCondition; }
    public void setItemCondition(String itemCondition) { this.itemCondition = itemCondition; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public Integer getSubmissionVersion() { return submissionVersion; }
    public void setSubmissionVersion(Integer submissionVersion) { this.submissionVersion = submissionVersion; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
