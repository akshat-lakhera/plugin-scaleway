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
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Create a bucket in the Paris region",
            full = true,
            code = """
                id: scaleway_storage_create_bucket
                namespace: company.team

                tasks:
                  - id: create_bucket
                    type: io.kestra.plugin.scaleway.storage.CreateBucket
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                """
        )
    }
)
@Schema(
    title = "Create a Scaleway Object Storage bucket",
    description = "Creates a bucket in the configured region, inside the Scaleway project that owns the API key."
)
public class CreateBucket extends AbstractS3 implements RunnableTask<CreateBucket.Output> {
    @Schema(
        title = "Bucket name",
        description = "Name of the bucket to create. Bucket names are unique across all Scaleway users."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Canned ACL",
        description = "Canned ACL applied to the bucket, for example private or public-read."
    )
    @PluginProperty(group = "advanced")
    private Property<String> acl;

    @Schema(
        title = "Enable Object Lock",
        description = "Enable Object Lock on the new bucket, which also enables versioning."
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> objectLockEnabled;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(this.bucket).as(String.class).orElseThrow();

        var builder = CreateBucketRequest.builder()
            .bucket(rBucket);
        runContext.render(this.acl).as(String.class).ifPresent(builder::acl);
        runContext.render(this.objectLockEnabled).as(Boolean.class).ifPresent(builder::objectLockEnabledForBucket);

        try (S3Client client = this.client(runContext)) {
            runContext.logger().debug("Creating bucket '{}'", rBucket);
            // the SDK sets the location constraint from the client region
            CreateBucketResponse response = client.createBucket(builder.build());

            return Output.builder()
                .bucket(rBucket)
                .location(response.location())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Bucket name")
        private final String bucket;

        @Schema(title = "Bucket location returned by the endpoint")
        private final String location;
    }
}
