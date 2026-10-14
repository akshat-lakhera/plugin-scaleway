package io.kestra.plugin.scaleway.storage;

import java.net.URI;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Download an object to Kestra internal storage",
            full = true,
            code = """
                id: scaleway_storage_download
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.scaleway.storage.Download
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                    key: reports/report.csv

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Downloaded {{ outputs.download.contentLength }} bytes to {{ outputs.download.uri }}"
                """
        )
    },
    metrics = {
        @Metric(
            name = "file.size",
            type = Counter.TYPE,
            unit = "bytes",
            description = "Size of the downloaded file."
        )
    }
)
@Schema(
    title = "Download an object from Scaleway Object Storage",
    description = "Downloads a single object from a bucket and stores it in Kestra internal storage."
)
public class Download extends AbstractS3 implements RunnableTask<Download.Output> {
    @Schema(
        title = "Bucket name",
        description = "Name of the bucket holding the object."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Object key",
        description = "Full key of the object to download."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> key;

    @Schema(
        title = "Version ID",
        description = "Specific version of the object to download when versioning is enabled on the bucket."
    )
    @PluginProperty(group = "advanced")
    private Property<String> versionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(this.bucket).as(String.class).orElseThrow();
        var rKey = runContext.render(this.key).as(String.class).orElseThrow();

        var builder = GetObjectRequest.builder()
            .bucket(rBucket)
            .key(rKey);
        runContext.render(this.versionId).as(String.class).ifPresent(builder::versionId);

        try (S3Client client = this.client(runContext)) {
            runContext.logger().debug("Downloading '{}/{}'", rBucket, rKey);
            Output output = S3Service.download(runContext, client, builder.build());

            runContext.metric(Counter.of("file.size", output.getContentLength()));

            return output;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the downloaded file")
        private final URI uri;

        @Schema(title = "Size of the object in bytes")
        private final Long contentLength;

        @Schema(title = "Content type of the object")
        private final String contentType;

        @Schema(title = "ETag of the object")
        private final String eTag;

        @Schema(title = "Version ID of the object", description = "Only set when versioning is enabled on the bucket.")
        private final String versionId;

        @Schema(title = "User-defined metadata of the object")
        private final Map<String, String> metadata;
    }
}
