package io.kestra.plugin.scaleway.storage.models;

import java.net.URI;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
public class S3Object {
    @With
    @Schema(title = "URI", description = "Internal storage URI of the downloaded object, set only when the object was downloaded.")
    URI uri;

    @Schema(title = "Key", description = "The object key within the bucket.")
    String key;

    @Schema(title = "ETag", description = "The entity tag of the object.")
    String etag;

    @Schema(title = "Size", description = "The object size in bytes.")
    Long size;

    @Schema(title = "Last modified", description = "Timestamp of the last modification of the object.")
    Instant lastModified;

    @Schema(title = "Storage class", description = "The storage class of the object, for example STANDARD, ONEZONE_IA or GLACIER.")
    String storageClass;

    public static S3Object of(software.amazon.awssdk.services.s3.model.S3Object object) {
        return S3Object.builder()
            .key(object.key())
            .etag(object.eTag())
            .size(object.size())
            .lastModified(object.lastModified())
            .storageClass(object.storageClassAsString())
            .build();
    }
}
