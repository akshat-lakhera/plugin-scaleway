package io.kestra.plugin.scaleway.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.scaleway.storage.models.S3Object;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ListTest extends AbstractObjectStorageTest {
    private String bucket;

    @BeforeEach
    void setUp() throws Exception {
        bucket = createBucket();
        upload(bucket, "root.txt", "root");
        upload(bucket, "reports/a.csv", "a");
        upload(bucket, "reports/b.csv", "bb");
        upload(bucket, "reports/c.json", "c");
        upload(bucket, "reports/2024/d.csv", "d");
        upload(bucket, "reports/folder/", "");
    }

    @Test
    void all() throws Exception {
        List task = list().build();
        List.Output output = task.run(runContext(task));

        assertThat(keys(output), containsInAnyOrder(
            "root.txt", "reports/a.csv", "reports/b.csv", "reports/c.json", "reports/2024/d.csv", "reports/folder/"
        ));
        S3Object object = output.getObjects().stream().filter(o -> o.getKey().equals("reports/b.csv")).findFirst().orElseThrow();
        assertThat(object.getSize(), is(2L));
        assertThat(object.getEtag(), notNullValue());
        assertThat(object.getLastModified(), notNullValue());
        assertThat(object.getUri(), nullValue());
    }

    @Test
    void prefix() throws Exception {
        List task = list().prefix(Property.ofValue("reports/")).build();

        assertThat(keys(task.run(runContext(task))), containsInAnyOrder(
            "reports/a.csv", "reports/b.csv", "reports/c.json", "reports/2024/d.csv", "reports/folder/"
        ));
    }

    @Test
    void delimiter() throws Exception {
        List task = list().prefix(Property.ofValue("reports/")).delimiter(Property.ofValue("/")).build();

        assertThat(keys(task.run(runContext(task))), containsInAnyOrder("reports/a.csv", "reports/b.csv", "reports/c.json"));
    }

    @Test
    void regexp() throws Exception {
        List task = list().regexp(Property.ofValue(".*\\.csv")).build();

        assertThat(keys(task.run(runContext(task))), containsInAnyOrder("reports/a.csv", "reports/b.csv", "reports/2024/d.csv"));
    }

    @Test
    void filterFilesAndDirectories() throws Exception {
        List files = list().prefix(Property.ofValue("reports/")).filter(Property.ofValue(List.Filter.FILES)).build();
        List directories = list().prefix(Property.ofValue("reports/")).filter(Property.ofValue(List.Filter.DIRECTORY)).build();

        assertThat(keys(files.run(runContext(files))), not(hasItem("reports/folder/")));
        assertThat(keys(directories.run(runContext(directories))), contains("reports/folder/"));
    }

    @Test
    void startAfterAndMaxKeys() throws Exception {
        List task = list().startAfter(Property.ofValue("reports/a.csv")).maxKeys(Property.ofValue(2)).build();

        // keys are returned in lexicographical order
        assertThat(keys(task.run(runContext(task))), contains("reports/b.csv", "reports/c.json"));
    }

    private List.ListBuilder<?, ?> list() {
        return connection(List.builder())
            .id(IdUtils.create())
            .type(List.class.getName())
            .bucket(Property.ofValue(bucket));
    }

    private static java.util.List<String> keys(List.Output output) {
        return output.getObjects().stream().map(S3Object::getKey).toList();
    }
}
