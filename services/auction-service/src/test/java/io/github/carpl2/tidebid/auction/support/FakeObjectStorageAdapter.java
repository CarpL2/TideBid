package io.github.carpl2.tidebid.auction.support;

import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FakeObjectStorageAdapter implements ObjectStoragePort {

    private final Map<String, StoredObjectMetadata> objects = new ConcurrentHashMap<>();
    private final List<String> headRequests = new CopyOnWriteArrayList<>();
    private final List<ReadSigningRequest> readRequests = new CopyOnWriteArrayList<>();
    private final List<String> deleteRequests = new CopyOnWriteArrayList<>();

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
        String controlledObjectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        headRequests.add(controlledObjectKey);
        return Optional.ofNullable(objects.get(controlledObjectKey));
    }

    @Override
    public void deleteControlledObject(String objectKey) {
        String controlledObjectKey = ObjectStoragePort.requireControlledObjectKey(objectKey);
        deleteRequests.add(controlledObjectKey);
        objects.remove(controlledObjectKey);
    }

    @Override
    public SignedRead signRead(ReadSigningRequest request) {
        readRequests.add(request);
        return new SignedRead(fakeUrl("read", request.objectKey()), request.expiresAt());
    }

    public void store(StoredObjectMetadata metadata) {
        objects.put(metadata.objectKey(), metadata);
    }

    public List<String> headRequests() {
        return List.copyOf(headRequests);
    }

    public List<ReadSigningRequest> readRequests() {
        return List.copyOf(readRequests);
    }

    public List<String> deleteRequests() {
        return List.copyOf(deleteRequests);
    }

    private static URI fakeUrl(String operation, String objectKey) {
        return URI.create("https://object-storage.invalid/" + objectKey + "?operation=" + operation);
    }
}
