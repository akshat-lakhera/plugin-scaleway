package io.kestra.plugin.scaleway.storage;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scaleway.storage.models.S3Object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.s3.S3Client;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "List all objects under a prefix and log how many were found",
            full = true,
            code = """
                id: audit_scaleway_storage
                namespace: company.team

                tasks:
                  - id: list_objects
                    type: io.kestra.plugin.scaleway.storage.List
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                    prefix: reports/

                  - id: log_count
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ outputs.list_objects.objects | length }} objects under reports/"
                """
        ),
        @Example(
            title = "List only CSV files, ignoring folder placeholders",
            full = true,
            code = """
                id: scaleway_storage_list_csv
                namespace: company.team

                tasks:
                  - id: list_csv
                    type: io.kestra.plugin.scaleway.storage.List
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                    prefix: exports/
                    regexp: ".*\\\\.csv"
                    filter: FILES
                """
        )
    },
    metrics = {
        @Metric(
            name = "objects.count",
            type = Counter.TYPE,
            unit = "objects",
            description = "Number of objects returned by the listing."
        )
    }
)
@Schema(
    title = "List objects in a Scaleway Object Storage bucket",
    description = "Lists objects with an optional prefix, delimiter and regexp filter. Pages through the bucket until maxKeys keys were read or the bucket is exhausted."
)
public class List extends AbstractS3 implements RunnableTask<List.Output> {
    @Schema(
        title = "Bucket name",
        description = "Name of the bucket to list."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Key prefix",
        description = "Only list keys that start with this prefix."
    )
    @PluginProperty(group = "source")
    private Property<String> prefix;

    @Schema(
        title = "Delimiter",
        description = "Character used to group keys, usually /. Keys containing the delimiter after the prefix are not listed."
    )
    @PluginProperty(group = "processing")
    private Property<String> delimiter;

    @Schema(
        title = "Start after",
        description = "Only list keys that come after this key in lexicographical order."
    )
    @PluginProperty(group = "source")
    private Property<String> startAfter;

    @Schema(
        title = "Maximum number of keys",
        description = "Upper bound on the keys read across all pages, before the regexp and filter are applied."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxKeys = Property.ofValue(1000);

    @Schema(
        title = "Regexp filter",
        description = "Only return objects whose full key matches this regular expression, for example .*\\.csv"
    )
    @PluginProperty(group = "processing")
    private Property<String> regexp;

    @Schema(
        title = "Object type filter",
        description = "Return files, directory placeholders (keys ending with /) or both."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Filter> filter = Property.ofValue(Filter.BOTH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(this.bucket).as(String.class).orElseThrow();

        try (S3Client client = this.client(runContext)) {
            java.util.List<S3Object> objects = S3Service.list(
                runContext,
                client,
                rBucket,
                this.prefix,
                this.delimiter,
                this.startAfter,
                this.maxKeys,
                this.regexp,
                this.filter
            );

            runContext.metric(Counter.of("objects.count", objects.size()));
            runContext.logger().debug("Found {} objects in bucket '{}'", objects.size(), rBucket);

            return Output.builder()
                .objects(objects)
                .build();
        }
    }

    public enum Filter {
        FILES,
        DIRECTORY,
        BOTH
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @JsonInclude
        @Schema(title = "Listed objects")
        private final java.util.List<S3Object> objects;
    }
}
