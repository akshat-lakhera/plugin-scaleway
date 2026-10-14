package io.kestra.plugin.scaleway.storage;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.scaleway.storage.models.Bucket;

import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BucketTest extends AbstractObjectStorageTest {
    @Test
    void createListAndDelete() throws Exception {
        String bucket = randomBucket();

        CreateBucket create = connection(CreateBucket.builder())
            .id(IdUtils.create())
            .type(CreateBucket.class.getName())
            .bucket(Property.ofValue(bucket))
            .build();
        CreateBucket.Output created = create.run(runContext(create));
        assertThat(created.getBucket(), is(bucket));

        assertThat(bucketNames(), hasItem(bucket));

        DeleteBucket.Output deleted = deleteBucket(bucket, false);
        assertThat(deleted.getBucket(), is(bucket));
        assertThat(deleted.getDeletedObjects(), is(0L));

        assertThat(bucketNames(), not(hasItem(bucket)));
    }

    @Test
    void deleteNonEmptyBucketWithoutForceFails() throws Exception {
        String bucket = createBucket();
        upload(bucket, "file.txt", "content");

        S3Exception exception = assertThrows(S3Exception.class, () -> deleteBucket(bucket, false));
        assertThat(exception.statusCode(), is(409));
        assertThat(bucketNames(), hasItem(bucket));
    }

    @Test
    void forceDeleteRemovesObjects() throws Exception {
        String bucket = createBucket();
        upload(bucket, "a.txt", "a");
        upload(bucket, "nested/b.txt", "b");

        DeleteBucket.Output deleted = deleteBucket(bucket, true);

        assertThat(deleted.getDeletedObjects(), is(2L));
        assertThat(bucketNames(), not(hasItem(bucket)));
    }

    @Test
    void forceDeleteRemovesEveryVersionAndDeleteMarker() throws Exception {
        String bucket = createBucket();

        DeleteBucket probe = connection(DeleteBucket.builder())
            .id(IdUtils.create())
            .type(DeleteBucket.class.getName())
            .bucket(Property.ofValue(bucket))
            .build();
        try (var client = probe.client(runContext(probe))) {
            client.putBucketVersioning(
                PutBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build())
                    .build()
            );
        }

        upload(bucket, "versioned.txt", "v1");
        upload(bucket, "versioned.txt", "v2");
        Delete delete = connection(Delete.builder())
            .id(IdUtils.create())
            .type(Delete.class.getName())
            .bucket(Property.ofValue(bucket))
            .key(Property.ofValue("versioned.txt"))
            .build();
        assertThat(delete.run(runContext(delete)).getDeleteMarker(), is(true));

        DeleteBucket.Output deleted = deleteBucket(bucket, true);

        // two object versions and one delete marker
        assertThat(deleted.getDeletedObjects(), is(3L));
        assertThat(bucketNames(), not(hasItem(bucket)));
    }

    private DeleteBucket.Output deleteBucket(String bucket, boolean force) throws Exception {
        DeleteBucket task = connection(DeleteBucket.builder())
            .id(IdUtils.create())
            .type(DeleteBucket.class.getName())
            .bucket(Property.ofValue(bucket))
            .force(Property.ofValue(force))
            .build();
        return task.run(runContext(task));
    }

    private java.util.List<String> bucketNames() throws Exception {
        ListBuckets task = connection(ListBuckets.builder())
            .id(IdUtils.create())
            .type(ListBuckets.class.getName())
            .build();
        return task.run(runContext(task)).getBuckets().stream().map(Bucket::getName).toList();
    }
}
