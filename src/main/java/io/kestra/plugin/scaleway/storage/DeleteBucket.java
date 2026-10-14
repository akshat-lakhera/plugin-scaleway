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
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Delete an empty bucket",
            full = true,
            code = """
                id: scaleway_storage_delete_bucket
                namespace: company.team

                tasks:
                  - id: delete_bucket
                    type: io.kestra.plugin.scaleway.storage.DeleteBucket
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-temporary-bucket
                """
        ),
        @Example(
            title = "Empty a bucket, including every object version, then delete it",
            full = true,
            code = """
                id: scaleway_storage_force_delete_bucket
                namespace: company.team

                tasks:
                  - id: delete_bucket
                    type: io.kestra.plugin.scaleway.storage.DeleteBucket
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-temporary-bucket
                    force: true
                """
        )
    }
)
@Schema(
    title = "Delete a Scaleway Object Storage bucket",
    description = "Deletes a bucket. S3 only deletes empty buckets, so set force to true to first delete every object, object version and delete marker it contains."
)
public class DeleteBucket extends AbstractS3 implements RunnableTask<DeleteBucket.Output> {
    @Schema(
        title = "Bucket name",
        description = "Name of the bucket to delete."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Empty the bucket first",
        description = "When true, every object version and delete marker is deleted before the bucket itself. This cannot be undone."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> force = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(this.bucket).as(String.class).orElseThrow();
        var rForce = runContext.render(this.force).as(Boolean.class).orElse(false);

        try (S3Client client = this.client(runContext)) {
            long deletedObjects = rForce ? emptyBucket(runContext, client, rBucket) : 0;

            runContext.logger().debug("Deleting bucket '{}'", rBucket);
            client.deleteBucket(DeleteBucketRequest.builder().bucket(rBucket).build());

            return Output.builder()
                .bucket(rBucket)
                .deletedObjects(deletedObjects)
                .build();
        }
    }

    private long emptyBucket(RunContext runContext, S3Client client, String bucket) {
        long deleted = 0;
        // Deleting one key at a time avoids DeleteObjects, whose mandatory request checksum is not handled the same way by every S3-compatible provider.
        for (ListObjectVersionsResponse page : client.listObjectVersionsPaginator(ListObjectVersionsRequest.builder().bucket(bucket).build())) {
            for (var version : page.versions()) {
                deleteVersion(client, bucket, version.key(), version.versionId());
                deleted++;
            }
            for (var marker : page.deleteMarkers()) {
                deleteVersion(client, bucket, marker.key(), marker.versionId());
                deleted++;
            }
        }
        runContext.logger().info("Deleted {} object versions from bucket '{}'", deleted, bucket);
        return deleted;
    }

    private static void deleteVersion(S3Client client, String bucket, String key, String versionId) {
        client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(versionId)
                .build()
        );
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Bucket name")
        private final String bucket;

        @Schema(title = "Number of object versions and delete markers deleted before the bucket")
        private final Long deletedObjects;
    }
}
