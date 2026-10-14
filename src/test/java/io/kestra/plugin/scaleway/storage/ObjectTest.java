package io.kestra.plugin.scaleway.storage;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectTest extends AbstractObjectStorageTest {
    @Test
    void uploadAndDownload() throws Exception {
        String bucket = createBucket();

        Upload upload = connection(Upload.builder())
            .id(IdUtils.create())
            .type(Upload.class.getName())
            .bucket(Property.ofValue(bucket))
            .key(Property.ofValue("reports/2024/report.csv"))
            .from(Property.ofValue(putInStorage("id,revenue\n1,1000\n").toString()))
            .contentType(Property.ofValue("text/csv"))
            .metadata(Property.ofValue(Map.of("source", "kestra")))
            .build();
        Upload.Output uploaded = upload.run(runContext(upload));

        assertThat(uploaded.getBucket(), is(bucket));
        assertThat(uploaded.getKey(), is("reports/2024/report.csv"));
        assertThat(uploaded.getETag(), notNullValue());

        Download download = connection(Download.builder())
            .id(IdUtils.create())
            .type(Download.class.getName())
            .bucket(Property.ofValue(bucket))
            .key(Property.ofValue("reports/2024/report.csv"))
            .build();
        Download.Output downloaded = download.run(runContext(download));

        assertThat(readStorage(downloaded.getUri()), is("id,revenue\n1,1000\n"));
        assertThat(downloaded.getUri().getPath(), endsWith(".csv"));
        assertThat(downloaded.getContentLength(), is(18L));
        assertThat(downloaded.getContentType(), is("text/csv"));
        assertThat(downloaded.getETag(), is(uploaded.getETag()));
        assertThat(downloaded.getMetadata(), hasEntry("source", "kestra"));
    }

    @Test
    void propertiesAreRendered() throws Exception {
        String bucket = createBucket();

        Upload upload = connection(Upload.builder())
            .id(IdUtils.create())
            .type(Upload.class.getName())
            .bucket(Property.ofExpression("{{ inputs.bucket }}"))
            .key(Property.ofExpression("{{ inputs.prefix }}/file.txt"))
            .from(Property.ofExpression("{{ inputs.file }}"))
            .build();
        var variables = Map.<String, Object>of(
            "bucket", bucket,
            "prefix", "rendered",
            "file", putInStorage("rendered content").toString()
        );
        Upload.Output uploaded = upload.run(TestsUtils.mockRunContext(runContextFactory, upload, variables));

        assertThat(uploaded.getBucket(), is(bucket));
        assertThat(uploaded.getKey(), is("rendered/file.txt"));
        assertThat(listKeys(bucket), contains("rendered/file.txt"));
    }

    @Test
    void downloadMissingKeyFails() throws Exception {
        String bucket = createBucket();

        Download download = connection(Download.builder())
            .id(IdUtils.create())
            .type(Download.class.getName())
            .bucket(Property.ofValue(bucket))
            .key(Property.ofValue("missing.txt"))
            .build();

        assertThrows(NoSuchKeyException.class, () -> download.run(runContext(download)));
    }

    @Test
    void delete() throws Exception {
        String bucket = createBucket();
        upload(bucket, "keep.txt", "keep");
        upload(bucket, "remove.txt", "remove");

        Delete delete = connection(Delete.builder())
            .id(IdUtils.create())
            .type(Delete.class.getName())
            .bucket(Property.ofValue(bucket))
            .key(Property.ofValue("remove.txt"))
            .build();
        Delete.Output output = delete.run(runContext(delete));

        assertThat(output.getKey(), is("remove.txt"));
        assertThat(listKeys(bucket), contains("keep.txt"));
    }

    @Test
    void copyToAnotherBucket() throws Exception {
        String source = createBucket();
        String destination = createBucket();
        upload(source, "data/file.json", "{\"a\":1}");

        Copy copy = connection(Copy.builder())
            .id(IdUtils.create())
            .type(Copy.class.getName())
            .from(Copy.CopyObjectFrom.builder().bucket(Property.ofValue(source)).key(Property.ofValue("data/file.json")).build())
            .to(Copy.CopyObject.builder().bucket(Property.ofValue(destination)).key(Property.ofValue("copied/file.json")).build())
            .build();
        Copy.Output output = copy.run(runContext(copy));

        assertThat(output.getBucket(), is(destination));
        assertThat(output.getKey(), is("copied/file.json"));
        assertThat(output.getETag(), notNullValue());
        assertThat(listKeys(source), contains("data/file.json"));
        assertThat(listKeys(destination), contains("copied/file.json"));
    }

    @Test
    void moveWithinBucket() throws Exception {
        String bucket = createBucket();
        upload(bucket, "incoming/file.txt", "moved");

        Copy move = connection(Copy.builder())
            .id(IdUtils.create())
            .type(Copy.class.getName())
            .from(Copy.CopyObjectFrom.builder().bucket(Property.ofValue(bucket)).key(Property.ofValue("incoming/file.txt")).build())
            .to(Copy.CopyObject.builder().key(Property.ofValue("processed/file.txt")).build())
            .delete(Property.ofValue(true))
            .build();
        Copy.Output output = move.run(runContext(move));

        assertThat(output.getBucket(), is(bucket));
        assertThat(listKeys(bucket), contains("processed/file.txt"));
    }

    @Test
    void copyOntoItselfIsRejected() throws Exception {
        String bucket = createBucket();
        upload(bucket, "file.txt", "content");

        Copy copy = connection(Copy.builder())
            .id(IdUtils.create())
            .type(Copy.class.getName())
            .from(Copy.CopyObjectFrom.builder().bucket(Property.ofValue(bucket)).key(Property.ofValue("file.txt")).build())
            .to(Copy.CopyObject.builder().build())
            .delete(Property.ofValue(true))
            .build();

        assertThrows(IllegalArgumentException.class, () -> copy.run(runContext(copy)));
        assertThat(listKeys(bucket), contains("file.txt"));
    }
}
