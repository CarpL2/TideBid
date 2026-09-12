package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageCleanupProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Profile({"local-db", "nacos"})
public class AuctionPendingImageCleanupService {

    private static final Pattern GENERATED_SUFFIX = Pattern.compile(
            "users/[1-9][0-9]*/[0-9]{6}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,8}"
    );

    private final AuctionItemRepository itemRepository;
    private final ObjectStoragePort objectStorage;
    private final AuctionStorageProperties storageProperties;
    private final AuctionImageCleanupProperties cleanupProperties;
    private final Clock clock;

    public AuctionPendingImageCleanupService(
            AuctionItemRepository itemRepository,
            ObjectStoragePort objectStorage,
            AuctionStorageProperties storageProperties,
            AuctionImageCleanupProperties cleanupProperties,
            Clock clock
    ) {
        this.itemRepository = itemRepository;
        this.objectStorage = objectStorage;
        this.storageProperties = storageProperties;
        this.cleanupProperties = cleanupProperties;
        this.clock = clock;
    }

    public CleanupResult cleanupBatch() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant createdBefore = now.minus(storageProperties.pendingRetention());
        List<AuctionItemImage> candidates = itemRepository.findPendingImageCleanupCandidates(
                now,
                createdBefore,
                cleanupProperties.batchSize()
        );

        int deleted = 0;
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        for (AuctionItemImage candidate : candidates) {
            if (!isGeneratedObjectKey(candidate.objectKey())) {
                skipped++;
                continue;
            }
            try {
                objectStorage.deleteControlledObject(candidate.objectKey());
            } catch (RuntimeException exception) {
                failed++;
                continue;
            }
            deleted++;
            if (itemRepository.expirePendingImage(candidate.id(), now, createdBefore, now)) {
                expired++;
            }
        }
        return new CleanupResult(candidates.size(), deleted, expired, skipped, failed);
    }

    private boolean isGeneratedObjectKey(String objectKey) {
        final String controlled;
        try {
            controlled = ObjectStoragePort.requireControlledObjectKey(objectKey);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String prefix = storageProperties.objectKeyPrefix() + "/";
        return controlled.startsWith(prefix)
                && GENERATED_SUFFIX.matcher(controlled.substring(prefix.length())).matches();
    }

    public record CleanupResult(int candidates, int deleted, int expired, int skipped, int failed) {
        public CleanupResult {
            if (candidates < 0 || deleted < 0 || expired < 0 || skipped < 0 || failed < 0
                    || deleted > candidates || expired > deleted || skipped + deleted > candidates) {
                throw new IllegalArgumentException("cleanup counters are inconsistent");
            }
        }
    }
}
