package io.kestra.plugin.scaleway.storage;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scaleway.storage.models.Bucket;

import io.swagger.v3.oas.annotations.media.Schema;
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
            title = "List the buckets of a region",
            full = true,
            code = """
                id: scaleway_storage_list_buckets
                namespace: company.team

                tasks:
                  - id: list_buckets
                    type: io.kestra.plugin.scaleway.storage.ListBuckets
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.list_buckets.buckets | jq('.[].name') }}"
                """
        )
    },
    metrics = {
        @Metric(
            name = "buckets.count",
            type = Counter.TYPE,
            unit = "buckets",
            description = "Number of buckets returned."
        )
    }
)
@Schema(
    title = "List Scaleway Object Storage buckets",
    description = "Lists the buckets of the configured region in the Scaleway project that owns the API key."
)
public class ListBuckets extends AbstractS3 implements RunnableTask<ListBuckets.Output> {
    @Override
    public Output run(RunContext runContext) throws Exception {
        try (S3Client client = this.client(runContext)) {
            java.util.List<Bucket> buckets = client.listBucketsPaginator().buckets().stream()
                .map(Bucket::of)
                .toList();

            runContext.metric(Counter.of("buckets.count", buckets.size()));

            return Output.builder()
                .buckets(buckets)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @JsonInclude
        @Schema(title = "Buckets")
        private final java.util.List<Bucket> buckets;
    }
}
