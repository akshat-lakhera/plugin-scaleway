package io.kestra.plugin.scaleway.storage.models;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Bucket {
    @Schema(title = "Name", description = "The bucket name.")
    String name;

    @Schema(title = "Creation date", description = "Timestamp of the bucket creation.")
    Instant creationDate;

    public static Bucket of(software.amazon.awssdk.services.s3.model.Bucket bucket) {
        return Bucket.builder()
            .name(bucket.name())
            .creationDate(bucket.creationDate())
            .build();
    }
}
