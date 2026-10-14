package io.kestra.plugin.scaleway.storage;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class S3ServiceTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void defaultsToScalewayRegionalEndpoint() throws Exception {
        ListBuckets task = ListBuckets.builder()
            .id(IdUtils.create())
            .type(ListBuckets.class.getName())
            .accessKey(Property.ofValue("SCWXXXXXXXXXXXXXXXXX"))
            .secretKey(Property.ofValue("secret"))
            .region(Property.ofExpression("{{ inputs.region }}"))
            .build();

        try (S3Client client = task.client(TestsUtils.mockRunContext(runContextFactory, task, Map.of("region", "nl-ams")))) {
            assertThat(client.serviceClientConfiguration().endpointOverride().orElseThrow(), is(URI.create("https://s3.nl-ams.scw.cloud")));
            assertThat(client.serviceClientConfiguration().region(), is(Region.of("nl-ams")));
        }
    }

    @Test
    void endpointOverrideWins() throws Exception {
        ListBuckets task = ListBuckets.builder()
            .id(IdUtils.create())
            .type(ListBuckets.class.getName())
            .accessKey(Property.ofValue("SCWXXXXXXXXXXXXXXXXX"))
            .secretKey(Property.ofValue("secret"))
            .region(Property.ofValue("fr-par"))
            .endpointOverride(Property.ofValue("http://localhost:9000"))
            .build();

        try (S3Client client = task.client(runContext(task))) {
            assertThat(client.serviceClientConfiguration().endpointOverride().orElseThrow(), is(URI.create("http://localhost:9000")));
        }
    }

    @Test
    void blankRegionIsRejected() {
        ListBuckets task = ListBuckets.builder()
            .id(IdUtils.create())
            .type(ListBuckets.class.getName())
            .accessKey(Property.ofValue("SCWXXXXXXXXXXXXXXXXX"))
            .secretKey(Property.ofValue("secret"))
            .region(Property.ofValue(" "))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.client(runContext(task)));
    }

    @Test
    void listFollowsContinuationTokensUpToMaxKeys() throws Exception {
        PagedClient client = new PagedClient(1000, true);

        var objects = S3Service.list(listRunContext(), client, "bucket", null, null, null, Property.ofValue(2500), null, null);

        assertThat(objects, hasSize(2500));
        assertThat(client.requests, hasSize(3));
        assertThat(client.requests.getFirst().maxKeys(), is(1000));
        assertThat(client.requests.getFirst().continuationToken(), nullValue());
        assertThat(client.requests.get(1).continuationToken(), is("token-1"));
        assertThat(objects.getLast().getKey(), is("key-2499"));
    }

    @Test
    void listStopsWhenTruncatedWithoutToken() throws Exception {
        PagedClient client = new PagedClient(1000, false);

        var objects = S3Service.list(listRunContext(), client, "bucket", null, null, null, Property.ofValue(5000), null, null);

        assertThat(objects, hasSize(1000));
        assertThat(client.requests, hasSize(1));
    }

    @Test
    void listRejectsNonPositiveMaxKeys() {
        PagedClient client = new PagedClient(1000, true);

        assertThrows(
            IllegalArgumentException.class,
            () -> S3Service.list(listRunContext(), client, "bucket", null, null, null, Property.ofValue(0), null, null)
        );
    }

    private RunContext listRunContext() {
        List task = List.builder()
            .id(IdUtils.create())
            .type(List.class.getName())
            .accessKey(Property.ofValue("SCWXXXXXXXXXXXXXXXXX"))
            .secretKey(Property.ofValue("secret"))
            .region(Property.ofValue("fr-par"))
            .bucket(Property.ofValue("bucket"))
            .build();
        return runContext(task);
    }

    private RunContext runContext(io.kestra.core.models.tasks.Task task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    // Serves an endless bucket in fixed-size pages, always reporting more keys.
    private static class PagedClient implements S3Client {
        private final int pageSize;
        private final boolean withToken;
        private final java.util.List<ListObjectsV2Request> requests = new ArrayList<>();

        PagedClient(int pageSize, boolean withToken) {
            this.pageSize = pageSize;
            this.withToken = withToken;
        }

        @Override
        public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            int page = requests.size();
            requests.add(request);
            var contents = IntStream.range(page * pageSize, (page + 1) * pageSize)
                .mapToObj(i -> S3Object.builder().key("key-" + i).build())
                .toList();
            return ListObjectsV2Response.builder()
                .contents(contents)
                .isTruncated(true)
                .nextContinuationToken(withToken ? "token-" + (page + 1) : null)
                .build();
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }
}
