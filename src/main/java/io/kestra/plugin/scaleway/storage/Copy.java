package io.kestra.plugin.scaleway.storage;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Copy an object to another bucket",
            full = true,
            code = """
                id: scaleway_storage_copy
                namespace: company.team

                tasks:
                  - id: copy
                    type: io.kestra.plugin.scaleway.storage.Copy
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    from:
                      bucket: my-data-lake
                      key: reports/report.csv
                    to:
                      bucket: my-archive
                      key: 2024/report.csv
                """
        ),
        @Example(
            title = "Move an object within the same bucket",
            full = true,
            code = """
                id: scaleway_storage_move
                namespace: company.team

                tasks:
                  - id: move
                    type: io.kestra.plugin.scaleway.storage.Copy
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    from:
                      bucket: my-data-lake
                      key: incoming/data.json
                    to:
                      key: processed/data.json
                    delete: true
                """
        )
    }
)
@Schema(
    title = "Copy an object in Scaleway Object Storage",
    description = "Copies an object within a bucket or across buckets of the same region, server side. Set delete to true to move the object instead."
)
public class Copy extends AbstractS3 implements RunnableTask<Copy.Output> {
    @Schema(
        title = "Source object",
        description = "Bucket, key and optional version ID of the object to copy."
    )
    @NotNull
    @PluginProperty(group = "source")
    private CopyObjectFrom from;

    @Schema(
        title = "Destination object",
        description = "Bucket and key to copy to. Each one defaults to the source value when omitted."
    )
    @NotNull
    @PluginProperty(group = "destination")
    private CopyObject to;

    @Schema(
        title = "Delete the source object",
        description = "When true, the source object is deleted after a successful copy."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> delete = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rSourceBucket = runContext.render(this.from.getBucket()).as(String.class).orElseThrow();
        var rSourceKey = runContext.render(this.from.getKey()).as(String.class).orElseThrow();
        var rDestinationBucket = runContext.render(this.to.getBucket()).as(String.class).orElse(rSourceBucket);
        var rDestinationKey = runContext.render(this.to.getKey()).as(String.class).orElse(rSourceKey);

        if (rSourceBucket.equals(rDestinationBucket) && rSourceKey.equals(rDestinationKey)) {
            throw new IllegalArgumentException("The source and destination of the copy are the same object: " + rSourceBucket + "/" + rSourceKey);
        }

        var builder = CopyObjectRequest.builder()
            .sourceBucket(rSourceBucket)
            .sourceKey(rSourceKey)
            .destinationBucket(rDestinationBucket)
            .destinationKey(rDestinationKey);
        var rSourceVersionId = runContext.render(this.from.getVersionId()).as(String.class);
        rSourceVersionId.ifPresent(builder::sourceVersionId);

        try (S3Client client = this.client(runContext)) {
            runContext.logger().debug("Copying '{}/{}' to '{}/{}'", rSourceBucket, rSourceKey, rDestinationBucket, rDestinationKey);
            CopyObjectResponse response = client.copyObject(builder.build());

            if (runContext.render(this.delete).as(Boolean.class).orElse(false)) {
                var deleteBuilder = DeleteObjectRequest.builder()
                    .bucket(rSourceBucket)
                    .key(rSourceKey);
                rSourceVersionId.ifPresent(deleteBuilder::versionId);
                client.deleteObject(deleteBuilder.build());
            }

            return Output.builder()
                .bucket(rDestinationBucket)
                .key(rDestinationKey)
                .eTag(response.copyObjectResult().eTag())
                .versionId(response.versionId())
                .build();
        }
    }

    @SuperBuilder(toBuilder = true)
    @Getter
    @NoArgsConstructor
    public static class CopyObject {
        @Schema(title = "Bucket name")
        @PluginProperty(group = "main")
        private Property<String> bucket;

        @Schema(title = "Object key")
        @PluginProperty(group = "main")
        private Property<String> key;
    }

    @SuperBuilder(toBuilder = true)
    @Getter
    @NoArgsConstructor
    public static class CopyObjectFrom extends CopyObject {
        @Schema(
            title = "Version ID",
            description = "Specific version of the source object to copy."
        )
        @PluginProperty(group = "advanced")
        private Property<String> versionId;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Destination bucket name")
        private final String bucket;

        @Schema(title = "Destination object key")
        private final String key;

        @Schema(title = "ETag of the new object")
        private final String eTag;

        @Schema(title = "Version ID of the new object", description = "Only set when versioning is enabled on the destination bucket.")
        private final String versionId;
    }
}
