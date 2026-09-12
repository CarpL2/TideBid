package io.github.carpl2.tidebid.auction.support;

import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeObjectStorageAdapter implements ObjectStoragePort {

    private final Map<String, StoredObjectMetadata> objects = new ConcurrentHashMap<>();

    @Override
    public SignedUpload signUpload(UploadSigningRequest request) {
        return new SignedUpload(
                fakeUrl("upload", request.objectKey()),
                Map.of(
                        "Content-Type", request.contentType(),
                        "Content-Length", Long.toString(request.contentLength())
                ),
                request.expiresAt()
        );
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String objectKey) {
        return Optional.ofNullable(objects.get(ObjectStoragePort.requireControlledObjectKey(objectKey)));
    }

    @Override
    public void deleteControlledObject(String objectKey) {
        objects.remove(ObjectStoragePort.requireControlledObjectKey(objectKey));
    }

    @Override
    public SignedRead signRead(ReadSigningRequest request) {
        return new SignedRead(fakeUrl("read", request.objectKey()), request.expiresAt());
    }

    public void store(StoredObjectMetadata metadata) {
        objects.put(metadata.objectKey(), metadata);
    }

    private static URI fakeUrl(String operation, String objectKey) {
        return URI.create("https://object-storage.invalid/" + objectKey + "?operation=" + operation);
    }
}
