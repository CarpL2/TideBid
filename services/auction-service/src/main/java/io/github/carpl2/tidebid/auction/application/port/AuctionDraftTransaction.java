package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;

import java.time.Instant;
import java.util.List;

public interface AuctionDraftTransaction {

    CreatedDraft create(
            AuctionItem item,
            AuctionSession session,
            List<ImageBinding> imageBindings,
            Instant boundAt
    );

    UpdatedDraft update(AuctionItem item, AuctionSession session);

    record ImageBinding(long imageId, long ownerId, String objectKey, int sortOrder) {
        public ImageBinding {
            if (imageId <= 0 || ownerId <= 0) {
                throw new IllegalArgumentException("imageId and ownerId must be positive");
            }
            objectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
            if (sortOrder < 0 || sortOrder > 8) {
                throw new IllegalArgumentException("sortOrder must be between 0 and 8");
            }
        }
    }

    record CreatedDraft(AuctionItem item, AuctionSession session, List<AuctionItemImage> images) {
        public CreatedDraft {
            if (item == null || session == null) {
                throw new IllegalArgumentException("item and session must not be null");
            }
            images = List.copyOf(images);
        }
    }

    record UpdatedDraft(AuctionItem item, AuctionSession session) {
        public UpdatedDraft {
            if (item == null || session == null) {
                throw new IllegalArgumentException("item and session must not be null");
            }
        }
    }

    final class ImageBindingConflictException extends RuntimeException {
        public ImageBindingConflictException(String message) {
            super(message);
        }
    }

    final class DraftUpdateConflictException extends RuntimeException {
        public DraftUpdateConflictException(String message) {
            super(message);
        }
    }
}
