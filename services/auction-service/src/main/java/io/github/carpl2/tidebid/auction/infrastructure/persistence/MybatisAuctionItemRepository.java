package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemImageEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionReviewEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionItemImageMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionItemMapper;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionReviewMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@Profile({"local-db", "nacos"})
public class MybatisAuctionItemRepository implements AuctionItemRepository {

    private final AuctionItemMapper itemMapper;
    private final AuctionItemImageMapper imageMapper;
    private final AuctionReviewMapper reviewMapper;

    public MybatisAuctionItemRepository(
            AuctionItemMapper itemMapper,
            AuctionItemImageMapper imageMapper,
            AuctionReviewMapper reviewMapper
    ) {
        this.itemMapper = itemMapper;
        this.imageMapper = imageMapper;
        this.reviewMapper = reviewMapper;
    }

    @Override
    public AuctionItem insertItem(AuctionItem item) {
        AuctionItemEntity entity = AuctionPersistenceMapping.toEntity(item);
        requireSingleRow(itemMapper.insert(entity), "auction item insert");
        return findItemById(entity.getId()).orElseThrow(() -> missingAfterInsert("auction item"));
    }

    @Override
    public Optional<AuctionItem> findItemById(long itemId) {
        requirePositive(itemId, "itemId");
        return Optional.ofNullable(itemMapper.selectById(itemId)).map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public AuctionItemImage insertImage(AuctionItemImage image) {
        AuctionItemImageEntity entity = AuctionPersistenceMapping.toEntity(image);
        requireSingleRow(imageMapper.insert(entity), "auction image insert");
        return Optional.ofNullable(imageMapper.selectById(entity.getId()))
                .map(AuctionPersistenceMapping::toDomain)
                .orElseThrow(() -> missingAfterInsert("auction image"));
    }

    @Override
    public Optional<AuctionItemImage> findImageByObjectKey(String objectKey) {
        String normalized = requireText(objectKey, 512, "objectKey");
        return Optional.ofNullable(imageMapper.selectOne(new LambdaQueryWrapper<AuctionItemImageEntity>()
                        .eq(AuctionItemImageEntity::getObjectKey, normalized)))
                .map(AuctionPersistenceMapping::toDomain);
    }

    @Override
    public ImageBindingResult bindPendingImage(
            long imageId,
            long ownerId,
            long itemId,
            int sortOrder,
            Instant boundAt
    ) {
        requirePositive(imageId, "imageId");
        requirePositive(ownerId, "ownerId");
        requirePositive(itemId, "itemId");
        if (sortOrder < 0 || sortOrder > 8) {
            throw new IllegalArgumentException("sortOrder must be between 0 and 8");
        }
        if (boundAt == null) {
            throw new IllegalArgumentException("boundAt must not be null");
        }
        try {
            return imageMapper.bindPending(imageId, ownerId, itemId, sortOrder, boundAt) == 1
                    ? ImageBindingResult.BOUND
                    : ImageBindingResult.NOT_PENDING;
        } catch (DuplicateKeyException exception) {
            return ImageBindingResult.POSITION_OCCUPIED;
        }
    }

    @Override
    public AuctionReview insertReview(AuctionReview review) {
        AuctionReviewEntity entity = AuctionPersistenceMapping.toEntity(review);
        requireSingleRow(reviewMapper.insert(entity), "auction review insert");
        return Optional.ofNullable(reviewMapper.selectById(entity.getId()))
                .map(AuctionPersistenceMapping::toDomain)
                .orElseThrow(() -> missingAfterInsert("auction review"));
    }

    @Override
    public Optional<AuctionReview> findReview(long itemId, int submissionVersion) {
        requirePositive(itemId, "itemId");
        if (submissionVersion <= 0) {
            throw new IllegalArgumentException("submissionVersion must be positive");
        }
        return Optional.ofNullable(reviewMapper.selectOne(new LambdaQueryWrapper<AuctionReviewEntity>()
                        .eq(AuctionReviewEntity::getItemId, itemId)
                        .eq(AuctionReviewEntity::getSubmissionVersion, submissionVersion)))
                .map(AuctionPersistenceMapping::toDomain);
    }

    static void requireSingleRow(int affectedRows, String action) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected one affected row for " + action);
        }
    }

    static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static String requireText(String value, int maximumLength, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " has an invalid length");
        }
        return normalized;
    }

    private static IllegalStateException missingAfterInsert(String aggregate) {
        return new IllegalStateException("Inserted " + aggregate + " could not be reloaded");
    }
}
