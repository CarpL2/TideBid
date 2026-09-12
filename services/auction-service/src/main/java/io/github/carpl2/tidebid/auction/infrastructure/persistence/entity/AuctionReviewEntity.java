package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("auction_review")
public class AuctionReviewEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long itemId;
    private Integer submissionVersion;
    private Long reviewerId;
    private String decision;
    private String comment;
    private Instant reviewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Integer getSubmissionVersion() { return submissionVersion; }
    public void setSubmissionVersion(Integer submissionVersion) { this.submissionVersion = submissionVersion; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
