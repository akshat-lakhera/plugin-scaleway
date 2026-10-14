package io.kestra.plugin.scaleway.storage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

// Tasks run against a local S3-compatible gateway reached through endpointOverride.
@KestraTest
public abstract class AbstractObjectStorageTest {
    protected static final String ACCESS_KEY = "testuser";
    protected static final String SECRET_KEY = "testpassword";
    protected static final String REGION = "fr-par";

    private static final int S3_PORT = 9000;

    // one container for all test classes, Ryuk removes it when the JVM exits
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> S3_CONTAINER = new GenericContainer<>(DockerImageName.parse("versity/versitygw:v1.9.0"))
        .withExposedPorts(S3_PORT)
        .withTmpFs(Map.of("/data", "rw", "/versions", "rw"))
        .withCommand(
            "--access", ACCESS_KEY,
            "--secret", SECRET_KEY,
            "--port", ":" + S3_PORT,
            "--region", REGION,
            "posix",
            "--versioning-dir", "/versions",
            "/data"
        )
        .waitingFor(Wait.forListeningPort());

    static {
        S3_CONTAINER.start();
    }

    @Inject
    protected RunContextFactory runContextFactory;

    @Inject
    protected StorageInterface storageInterface;

    protected static String endpoint() {
        return "http://" + S3_CONTAINER.getHost() + ":" + S3_CONTAINER.getMappedPort(S3_PORT);
    }

    protected static String randomBucket() {
        return "kestra-" + IdUtils.create().toLowerCase();
    }

    protected RunContext runContext(Task task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    protected <T extends AbstractS3.AbstractS3Builder<?, ?>> T connection(T builder) {
        builder
            .accessKey(Property.ofValue(ACCESS_KEY))
            .secretKey(Property.ofValue(SECRET_KEY))
            .region(Property.ofValue(REGION))
            .endpointOverride(Property.ofValue(endpoint()))
            .forcePathStyle(Property.ofValue(true));
        return builder;
    }

    protected String createBucket() throws Exception {
        String bucket = randomBucket();
        CreateBucket task = connection(CreateBucket.builder())
            .id(IdUtils.create())
            .type(CreateBucket.class.getName())
            .bucket(Property.ofValue(bucket))
            .build();
        task.run(runContext(task));
        return bucket;
    }

    protected URI putInStorage(String content) throws Exception {
        return storageInterface.put(
            TenantService.MAIN_TENANT,
            null,
            URI.create("/" + IdUtils.create() + "/" + IdUtils.create() + ".txt"),
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))
        );
    }

    protected Upload.Output upload(String bucket, String key, String content) throws Exception {
        Upload task = connection(Upload.builder())
            .id(IdUtils.create())
            .type(Upload.class.getName())
            .bucket(Property.ofValue(bucket))
            .key(Property.ofValue(key))
            .from(Property.ofValue(putInStorage(content).toString()))
            .build();
        return task.run(runContext(task));
    }

    protected java.util.List<String> listKeys(String bucket) throws Exception {
        List task = connection(List.builder())
            .id(IdUtils.create())
            .type(List.class.getName())
            .bucket(Property.ofValue(bucket))
            .build();
        return task.run(runContext(task)).getObjects().stream()
            .map(io.kestra.plugin.scaleway.storage.models.S3Object::getKey)
            .toList();
    }

    protected String readStorage(URI uri) throws Exception {
        try (var inputStream = storageInterface.get(TenantService.MAIN_TENANT, null, uri)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
