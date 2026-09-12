package io.github.carpl2.tidebid.auction.infrastructure.storage;

import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "tidebid.auction.storage",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class UnconfiguredObjectStorageAdapter implements ObjectStoragePort {

    @Override
    public SignedUpload signUpload(UploadSigningRequest request) {
        throw unavailable();
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String objectKey) {
        throw unavailable();
    }

    @Override
    public void deleteControlledObject(String objectKey) {
        throw unavailable();
    }

    @Override
    public SignedRead signRead(ReadSigningRequest request) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(AuctionErrorCode.STORAGE_UNAVAILABLE);
    }
}
