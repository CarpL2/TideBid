package io.github.carpl2.tidebid.auction.api;

public record CreateUploadIntentRequest(
        String originalFilename,
        String contentType,
        long contentLength,
        String checksumSha256
) {
}
