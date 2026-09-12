package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService.UploadIntent;

import java.time.Instant;
import java.util.Map;

public record UploadIntentResponse(
        String imageId,
        String objectKey,
        String uploadUrl,
        Map<String, String> requiredHeaders,
        Instant expiresAt
) {

    static UploadIntentResponse from(UploadIntent intent) {
        return new UploadIntentResponse(
                Long.toString(intent.imageId()),
                intent.objectKey(),
                intent.upload().url().toString(),
                intent.upload().requiredHeaders(),
                intent.upload().expiresAt()
        );
    }

    @Override
    public String toString() {
        return "UploadIntentResponse[imageId=" + imageId
                + ", objectKey=" + objectKey
                + ", uploadUrl=[REDACTED], requiredHeaderNames=" + requiredHeaders.keySet()
                + ", expiresAt=" + expiresAt + "]";
    }
}
