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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Delete an object from a bucket",
            full = true,
            code = """
                id: scaleway_storage_delete
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.scaleway.storage.Delete
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                    key: reports/old-report.csv
                """
        )
    }
)
@Schema(
    title = "Delete an object from Scaleway Object Storage",
    description = "Deletes a single object by key. Deleting a key that does not exist succeeds, as with any S3 endpoint."
)
public class Delete extends AbstractS3 implements RunnableTask<Delete.Output> {
    @Schema(
        title = "Bucket name",
        description = "Name of the bucket holding the object."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Object key",
        description = "Full key of the object to delete."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> key;

    @Schema(
        title = "Version ID",
        description = "Permanently delete this specific version instead of adding a delete marker on a versioned bucket."
    )
    @PluginProperty(group = "advanced")
    private Property<String> versionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(this.bucket).as(String.class).orElseThrow();
        var rKey = runContext.render(this.key).as(String.class).orElseThrow();

        var builder = DeleteObjectRequest.builder()
            .bucket(rBucket)
            .key(rKey);
        runContext.render(this.versionId).as(String.class).ifPresent(builder::versionId);

        try (S3Client client = this.client(runContext)) {
            runContext.logger().debug("Deleting '{}/{}'", rBucket, rKey);
            DeleteObjectResponse response = client.deleteObject(builder.build());

            return Output.builder()
                .bucket(rBucket)
                .key(rKey)
                .versionId(response.versionId())
                .deleteMarker(response.deleteMarker())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Bucket name")
        private final String bucket;

        @Schema(title = "Object key")
        private final String key;

        @Schema(title = "Version ID of the deleted object or of the created delete marker")
        private final String versionId;

        @Schema(title = "Whether the deletion created or removed a delete marker")
        private final Boolean deleteMarker;
    }
}
