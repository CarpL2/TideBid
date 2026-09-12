package io.github.carpl2.tidebid.auction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("auction_item_image")
public class AuctionItemImageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long itemId;
    private Long ownerId;
    private String objectKey;
    private String originalFilename;
    private String contentType;
    private Long contentLength;
    private String contentSha256;
    private Integer sortOrder;
    private String storageStatus;
    private Instant uploadExpiresAt;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getContentLength() { return contentLength; }
    public void setContentLength(Long contentLength) { this.contentLength = contentLength; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getStorageStatus() { return storageStatus; }
    public void setStorageStatus(String storageStatus) { this.storageStatus = storageStatus; }
    public Instant getUploadExpiresAt() { return uploadExpiresAt; }
    public void setUploadExpiresAt(Instant uploadExpiresAt) { this.uploadExpiresAt = uploadExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
