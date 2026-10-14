package io.kestra.plugin.scaleway.storage;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.FileUtils;
import io.kestra.plugin.scaleway.storage.models.S3Object;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

final class S3Service {
    static final String DEFAULT_ENDPOINT = "https://s3.%s.scw.cloud";

    // S3 caps a single ListObjectsV2 page at 1000 keys.
    private static final int MAX_PAGE_SIZE = 1000;

    private S3Service() {
    }

    static S3Client client(RunContext runContext, S3ConnectionInterface connection) throws IllegalVariableEvaluationException {
        var rRegion = runContext.render(connection.getRegion()).as(String.class)
            .filter(StringUtils::isNotBlank)
            .orElseThrow(() -> new IllegalArgumentException("The 'region' property is required"));
        var rAccessKey = runContext.render(connection.getAccessKey()).as(String.class).orElseThrow();
        var rSecretKey = runContext.render(connection.getSecretKey()).as(String.class).orElseThrow();
        var rEndpoint = runContext.render(connection.getEndpointOverride()).as(String.class)
            .filter(StringUtils::isNotBlank)
            .orElse(DEFAULT_ENDPOINT.formatted(rRegion));
        var rForcePathStyle = runContext.render(connection.getForcePathStyle()).as(Boolean.class).orElse(false);

        return S3Client.builder()
            .httpClientBuilder(ApacheHttpClient.builder())
            .region(Region.of(rRegion))
            .endpointOverride(URI.create(rEndpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(rAccessKey, rSecretKey)))
            .forcePathStyle(rForcePathStyle)
            // Only send or validate checksums when an operation requires it, like other S3-compatible providers.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
    }

    static Download.Output download(RunContext runContext, S3Client client, GetObjectRequest request) throws Exception {
        File tempFile = runContext.workingDir().createTempFile(FileUtils.getExtension(request.key())).toFile();
        // the SDK refuses to overwrite an existing file
        //noinspection ResultOfMethodCallIgnored
        tempFile.delete();

        GetObjectResponse response = client.getObject(request, ResponseTransformer.toFile(tempFile.toPath()));
        URI uri = runContext.storage().putFile(tempFile);

        return Download.Output.builder()
            .uri(uri)
            .contentLength(response.contentLength())
            .contentType(response.contentType())
            .eTag(response.eTag())
            .versionId(response.versionId())
            .metadata(response.metadata())
            .build();
    }

    static java.util.List<S3Object> list(
        RunContext runContext,
        S3Client client,
        String bucket,
        Property<String> prefix,
        Property<String> delimiter,
        Property<String> startAfter,
        Property<Integer> maxKeys,
        Property<String> regexp,
        Property<List.Filter> filter
    ) throws IllegalVariableEvaluationException {
        int rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(MAX_PAGE_SIZE);
        if (rMaxKeys < 1) {
            throw new IllegalArgumentException("The 'maxKeys' property must be greater than 0, got " + rMaxKeys);
        }

        var builder = ListObjectsV2Request.builder()
            .bucket(bucket)
            .maxKeys(Math.min(rMaxKeys, MAX_PAGE_SIZE));
        runContext.render(prefix).as(String.class).ifPresent(builder::prefix);
        runContext.render(delimiter).as(String.class).ifPresent(builder::delimiter);
        runContext.render(startAfter).as(String.class).ifPresent(builder::startAfter);

        String rRegexp = runContext.render(regexp).as(String.class).orElse(null);
        var rFilter = runContext.render(filter).as(List.Filter.class).orElse(List.Filter.BOTH);

        java.util.List<software.amazon.awssdk.services.s3.model.S3Object> contents = new ArrayList<>();
        ListObjectsV2Request request = builder.build();
        ListObjectsV2Response response;
        do {
            response = client.listObjectsV2(request);
            contents.addAll(response.contents());

            if (Boolean.TRUE.equals(response.isTruncated()) && response.nextContinuationToken() == null) {
                // never loop forever on a provider that reports truncation without a token
                runContext.logger().warn("Listing of bucket '{}' stopped: the response is truncated but has no continuation token", bucket);
                break;
            }
            request = request.toBuilder().continuationToken(response.nextContinuationToken()).build();
        } while (Boolean.TRUE.equals(response.isTruncated()) && contents.size() < rMaxKeys);

        return contents.stream()
            .limit(rMaxKeys)
            .filter(object -> matches(object.key(), rRegexp, rFilter))
            .map(S3Object::of)
            .toList();
    }

    static void performAction(
        RunContext runContext,
        ObjectStorageTrigger trigger,
        java.util.List<S3Object> objects,
        String bucket
    ) throws Exception {
        var rAction = runContext.render(trigger.getAction()).as(ObjectStorageTrigger.Action.class).orElse(ObjectStorageTrigger.Action.NONE);

        if (rAction == ObjectStorageTrigger.Action.DELETE) {
            for (S3Object object : objects) {
                Delete.builder()
                    .id(trigger.getId())
                    .type(Delete.class.getName())
                    .accessKey(trigger.getAccessKey())
                    .secretKey(trigger.getSecretKey())
                    .region(trigger.getRegion())
                    .endpointOverride(trigger.getEndpointOverride())
                    .forcePathStyle(trigger.getForcePathStyle())
                    .bucket(Property.ofValue(bucket))
                    .key(Property.ofValue(object.getKey()))
                    .build()
                    .run(runContext);
            }
        } else if (rAction == ObjectStorageTrigger.Action.MOVE) {
            if (trigger.getMoveTo() == null) {
                throw new IllegalArgumentException("The 'moveTo' property is required when 'action' is MOVE");
            }
            var rMoveToBucket = runContext.render(trigger.getMoveTo().getBucket()).as(String.class).orElse(bucket);
            var rMoveToKey = runContext.render(trigger.getMoveTo().getKey()).as(String.class).orElse("");

            for (S3Object object : objects) {
                var destinationKey = StringUtils.isEmpty(rMoveToKey)
                    ? FilenameUtils.getName(object.getKey())
                    : StringUtils.stripEnd(rMoveToKey, "/") + "/" + FilenameUtils.getName(object.getKey());

                Copy.builder()
                    .id(trigger.getId())
                    .type(Copy.class.getName())
                    .accessKey(trigger.getAccessKey())
                    .secretKey(trigger.getSecretKey())
                    .region(trigger.getRegion())
                    .endpointOverride(trigger.getEndpointOverride())
                    .forcePathStyle(trigger.getForcePathStyle())
                    .from(
                        Copy.CopyObjectFrom.builder()
                            .bucket(Property.ofValue(bucket))
                            .key(Property.ofValue(object.getKey()))
                            .build()
                    )
                    .to(
                        Copy.CopyObject.builder()
                            .bucket(Property.ofValue(rMoveToBucket))
                            .key(Property.ofValue(destinationKey))
                            .build()
                    )
                    .delete(Property.ofValue(true))
                    .build()
                    .run(runContext);
            }
        }
    }

    private static boolean matches(String key, String regexp, List.Filter filter) {
        boolean isDirectory = key.endsWith("/");
        boolean typeMatches = switch (filter) {
            case FILES -> !isDirectory;
            case DIRECTORY -> isDirectory;
            case BOTH -> true;
        };
        return typeMatches && (regexp == null || key.matches(regexp));
    }
}
