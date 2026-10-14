package io.kestra.plugin.scaleway.storage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.scaleway.storage.models.Bucket;
import io.kestra.plugin.scaleway.storage.models.S3Object;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

// Runs against the real Scaleway endpoint, only when SCW_ACCESS_KEY and SCW_SECRET_KEY are set.
@KestraTest
@EnabledIfEnvironmentVariable(named = "SCW_ACCESS_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SCW_SECRET_KEY", matches = ".+")
class ScalewayLiveTest {
    private static final String REGION = Optional.ofNullable(System.getenv("SCW_DEFAULT_REGION")).orElse("fr-par");

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storageInterface;

    @Test
    void bucketAndObjectLifecycle() throws Exception {
        String bucket = "kestra-it-" + IdUtils.create().toLowerCase();

        run(connection(CreateBucket.builder()).id("create").type(CreateBucket.class.getName()).bucket(Property.ofValue(bucket)).build());
        try {
            ListBuckets.Output buckets = run(connection(ListBuckets.builder()).id("buckets").type(ListBuckets.class.getName()).build());
            assertThat(buckets.getBuckets().stream().map(Bucket::getName).toList(), hasItem(bucket));

            URI source = storageInterface.put(
                TenantService.MAIN_TENANT,
                null,
                URI.create("/" + IdUtils.create() + "/report.csv"),
                new ByteArrayInputStream("id,revenue\n1,1000\n".getBytes(StandardCharsets.UTF_8))
            );
            run(
                connection(Upload.builder()).id("upload").type(Upload.class.getName())
                    .bucket(Property.ofValue(bucket))
                    .key(Property.ofValue("reports/report.csv"))
                    .from(Property.ofValue(source.toString()))
                    .build()
            );

            run(
                connection(Copy.builder()).id("copy").type(Copy.class.getName())
                    .from(Copy.CopyObjectFrom.builder().bucket(Property.ofValue(bucket)).key(Property.ofValue("reports/report.csv")).build())
                    .to(Copy.CopyObject.builder().key(Property.ofValue("archive/report.csv")).build())
                    .build()
            );

            List.Output listed = run(
                connection(List.builder()).id("list").type(List.class.getName())
                    .bucket(Property.ofValue(bucket))
                    .build()
            );
            assertThat(listed.getObjects().stream().map(S3Object::getKey).toList(), containsInAnyOrder("reports/report.csv", "archive/report.csv"));

            Download.Output downloaded = run(
                connection(Download.builder()).id("download").type(Download.class.getName())
                    .bucket(Property.ofValue(bucket))
                    .key(Property.ofValue("archive/report.csv"))
                    .build()
            );
            try (var inputStream = storageInterface.get(TenantService.MAIN_TENANT, null, downloaded.getUri())) {
                assertThat(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8), is("id,revenue\n1,1000\n"));
            }

            run(
                connection(Delete.builder()).id("delete").type(Delete.class.getName())
                    .bucket(Property.ofValue(bucket))
                    .key(Property.ofValue("reports/report.csv"))
                    .build()
            );
        } finally {
            run(
                connection(DeleteBucket.builder()).id("delete_bucket").type(DeleteBucket.class.getName())
                    .bucket(Property.ofValue(bucket))
                    .force(Property.ofValue(true))
                    .build()
            );
        }
    }

    private <T extends AbstractS3.AbstractS3Builder<?, ?>> T connection(T builder) {
        builder
            .accessKey(Property.ofValue(System.getenv("SCW_ACCESS_KEY")))
            .secretKey(Property.ofValue(System.getenv("SCW_SECRET_KEY")))
            .region(Property.ofValue(REGION));
        return builder;
    }

    private <O extends io.kestra.core.models.tasks.Output> O run(Task task) throws Exception {
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        @SuppressWarnings("unchecked")
        var runnable = (io.kestra.core.models.tasks.RunnableTask<O>) task;
        return runnable.run(runContext);
    }
}
