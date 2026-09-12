package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionDraftTransaction implements AuctionDraftTransaction {

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;

    public MybatisAuctionDraftTransaction(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public CreatedDraft create(
            AuctionItem item,
            AuctionSession session,
            List<ImageBinding> imageBindings,
            Instant boundAt
    ) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(session, "session must not be null");
        imageBindings = List.copyOf(imageBindings);
        Objects.requireNonNull(boundAt, "boundAt must not be null");
        if (session.itemId() != item.id() || session.sellerId() != item.sellerId()) {
            throw new IllegalArgumentException("session does not belong to the item and seller");
        }
        if (imageBindings.isEmpty()) {
            throw new IllegalArgumentException("at least one image binding is required");
        }

        AuctionItem storedItem = itemRepository.insertItem(item);
        AuctionSession storedSession = sessionRepository.insertSession(session);
        List<AuctionItemImage> storedImages = new ArrayList<>(imageBindings.size());
        for (ImageBinding binding : imageBindings) {
            AuctionItemRepository.ImageBindingResult result = itemRepository.bindPendingImage(
                    binding.imageId(),
                    binding.ownerId(),
                    item.id(),
                    binding.sortOrder(),
                    boundAt
            );
            if (result != AuctionItemRepository.ImageBindingResult.BOUND) {
                throw new ImageBindingConflictException("Image could not be bound: " + result);
            }
            AuctionItemImage storedImage = itemRepository.findImageByObjectKey(binding.objectKey())
                    .orElseThrow(() -> new IllegalStateException("Bound image could not be reloaded"));
            if (!Long.valueOf(item.id()).equals(storedImage.itemId())) {
                throw new IllegalStateException("Bound image belongs to an unexpected item");
            }
            storedImages.add(storedImage);
        }
        return new CreatedDraft(storedItem, storedSession, storedImages);
    }
}
