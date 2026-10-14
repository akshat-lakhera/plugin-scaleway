package io.kestra.plugin.scaleway.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scaleway.storage.models.S3Object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.models.triggers.StatefulTriggerService.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Process every new file that lands under a prefix",
            full = true,
            code = """
                id: process_new_uploads
                namespace: company.team

                triggers:
                  - id: on_new_object
                    type: io.kestra.plugin.scaleway.storage.ObjectStorageTrigger
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                    prefix: incoming/
                    interval: PT2M

                tasks:
                  - id: each
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ trigger.objects | jq('.[].key') }}"
                    tasks:
                      - id: process
                        type: io.kestra.plugin.core.log.Log
                        message: "Processing {{ taskrun.value }}"
                """
        ),
        @Example(
            title = "Move matched CSV files to an archive prefix once they triggered the flow",
            full = true,
            code = """
                id: archive_new_csv
                namespace: company.team

                triggers:
                  - id: on_new_csv
                    type: io.kestra.plugin.scaleway.storage.ObjectStorageTrigger
                    accessKey: "{{ secret('SCW_ACCESS_KEY') }}"
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    region: fr-par
                    bucket: my-data-lake
                    prefix: incoming/
                    regexp: ".*\\\\.csv"
                    action: MOVE
                    moveTo:
                      bucket: my-archive
                      key: processed

                tasks:
                  - id: each
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ trigger.objects | jq('.[].uri') }}"
                    tasks:
                      - id: log
                        type: io.kestra.plugin.core.log.Log
                        message: "Downloaded file available at {{ taskrun.value }}"
                """
        )
    }
)
@Schema(
    title = "Trigger a flow when objects appear in a Scaleway Object Storage bucket",
    description = """
        Polls a bucket on a fixed interval and fires one execution with every new or updated object that matches the prefix and regexp filters.
        Matched objects are downloaded to Kestra internal storage. The trigger keeps the key and ETag of the objects it already fired for in its state, so an object fires only once per version even with action NONE.
        Use action DELETE or MOVE to also remove matched objects from the source location.
        Objects are downloaded first, then the action runs, then the state is saved. If the action fails part way, the remaining objects are retried on the next poll, so delivery is at-least-once."""
)
public class ObjectStorageTrigger extends AbstractTrigger
    implements PollingTriggerInterface, TriggerOutput<ObjectStorageTrigger.Output>, StatefulTriggerInterface, S3ConnectionInterface {

    @Schema(
        title = "Polling interval",
        description = "Time between two listings of the bucket."
    )
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    @ToString.Exclude
    private Property<String> accessKey;

    @ToString.Exclude
    private Property<String> secretKey;

    private Property<String> region;

    private Property<String> endpointOverride;

    @Builder.Default
    private Property<Boolean> forcePathStyle = Property.ofValue(false);

    @Schema(
        title = "Bucket name",
        description = "Name of the bucket to watch."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Key prefix",
        description = "Only watch keys that start with this prefix."
    )
    @PluginProperty(group = "source")
    private Property<String> prefix;

    @Schema(
        title = "Regexp filter",
        description = "Only watch keys that match this regular expression, for example .*\\.csv"
    )
    @PluginProperty(group = "processing")
    private Property<String> regexp;

    @Schema(
        title = "Object type filter",
        description = "Watch files, directory placeholders (keys ending with /) or both. Defaults to files only."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<List.Filter> filter = Property.ofValue(List.Filter.FILES);

    @Schema(
        title = "Maximum number of keys",
        description = "Upper bound on the keys read from the bucket on each poll. With action NONE, processed objects stay in the listing, so keep the watched prefix below this limit or new objects past it are not seen."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxKeys = Property.ofValue(1000);

    @Schema(
        title = "Maximum number of files per execution",
        description = "Objects above this limit are left for the next polls, so a burst of uploads is processed in batches."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxFiles = Property.ofValue(25);

    @Schema(
        title = "Action on matched objects",
        description = "NONE leaves the objects in place, DELETE deletes them and MOVE moves them to moveTo once they were downloaded. Downloads happen first, then the action, then the state is saved: if the action fails part way, the remaining objects are retried on the next poll (at-least-once)."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Action> action = Property.ofValue(Action.NONE);

    @Schema(
        title = "Destination for the MOVE action",
        description = "Objects keep their path relative to the watched prefix and are moved under this key prefix. The bucket defaults to the watched bucket."
    )
    @PluginProperty(group = "destination")
    private Copy.CopyObject moveTo;

    @Schema(
        title = "Trigger condition",
        description = "Fire on new objects only (CREATE), on changed objects only (UPDATE) or on both (CREATE_OR_UPDATE)."
    )
    @Builder.Default
    private Property<On> on = Property.ofValue(On.CREATE_OR_UPDATE);

    @Schema(
        title = "State key",
        description = "Key under which the trigger stores the objects it already fired for. Defaults to a key unique to this trigger."
    )
    private Property<String> stateKey;

    @Schema(
        title = "State TTL",
        description = "How long the trigger remembers an object it fired for. Unset means forever."
    )
    private Property<Duration> stateTtl;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        var rBucket = runContext.render(this.bucket).as(String.class).orElseThrow();
        var rOn = runContext.render(this.on).as(On.class).orElse(On.CREATE_OR_UPDATE);
        var rStateKey = runContext.render(this.stateKey).as(String.class)
            .orElse(StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), this.id));
        var rStateTtl = runContext.render(this.stateTtl).as(Duration.class);
        int rMaxFiles = runContext.render(this.maxFiles).as(Integer.class).orElse(25);
        if (rMaxFiles < 1) {
            throw new IllegalArgumentException("The 'maxFiles' property must be greater than 0, got " + rMaxFiles);
        }

        var rPrefix = runContext.render(this.prefix).as(String.class).orElse("");
        var rAction = runContext.render(this.action).as(Action.class).orElse(Action.NONE);
        if (rAction == Action.MOVE) {
            if (this.moveTo == null) {
                throw new IllegalArgumentException("The 'moveTo' property is required when 'action' is MOVE");
            }
            var rMoveToBucket = runContext.render(this.moveTo.getBucket()).as(String.class).orElse(rBucket);
            var rMoveToKey = runContext.render(this.moveTo.getKey()).as(String.class).orElse("");
            if (rMoveToBucket.equals(rBucket) && rMoveToKey.startsWith(rPrefix)) {
                throw new IllegalArgumentException(
                    "The 'moveTo' destination '%s' must not be inside the watched prefix '%s' of bucket '%s'".formatted(rMoveToKey, rPrefix, rBucket)
                );
            }
        }

        java.util.List<S3Object> listed;
        try (var client = S3Service.client(runContext, this)) {
            listed = S3Service.list(
                runContext,
                client,
                rBucket,
                this.prefix,
                null,
                null,
                this.maxKeys,
                this.regexp,
                this.filter
            );
        }

        var state = readState(runContext, rStateKey, rStateTtl);
        if (listed.isEmpty()) {
            // still persist the state so expired or removed entries are cleaned up
            writeState(runContext, rStateKey, state, rStateTtl);
            return Optional.empty();
        }

        java.util.List<TriggeredObject> triggered = new ArrayList<>();
        java.util.List<S3Object> matched = new ArrayList<>();

        for (S3Object object : listed) {
            if (triggered.size() >= rMaxFiles) {
                // left for the next poll: the state is only updated for objects that fired
                break;
            }

            var uri = "s3://%s/%s".formatted(rBucket, object.getKey());
            Instant modifiedAt = Optional.ofNullable(object.getLastModified()).orElse(Instant.now());
            String version = Optional.ofNullable(object.getEtag()).orElse(String.valueOf(modifiedAt.toEpochMilli()));

            var change = computeAndUpdateState(state, Entry.candidate(uri, version, modifiedAt), rOn);
            if (!change.fire()) {
                continue;
            }

            var download = Download.builder()
                .id(this.id)
                .type(Download.class.getName())
                .accessKey(this.accessKey)
                .secretKey(this.secretKey)
                .region(this.region)
                .endpointOverride(this.endpointOverride)
                .forcePathStyle(this.forcePathStyle)
                .bucket(Property.ofValue(rBucket))
                .key(Property.ofValue(object.getKey()))
                .build()
                .run(runContext);

            matched.add(object);
            triggered.add(
                TriggeredObject.builder()
                    .object(object.withUri(download.getUri()))
                    .changeType(change.isNew() ? ChangeType.CREATE : ChangeType.UPDATE)
                    .build()
            );
        }

        if (triggered.isEmpty()) {
            // still persist the state so expired or removed entries are cleaned up
            writeState(runContext, rStateKey, state, rStateTtl);
            return Optional.empty();
        }

        S3Service.performAction(runContext, this, matched, rBucket, rPrefix);
        writeState(runContext, rStateKey, state, rStateTtl);

        var output = Output.builder().objects(triggered).build();
        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    public enum Action {
        MOVE,
        DELETE,
        NONE
    }

    public enum ChangeType {
        CREATE,
        UPDATE
    }

    @Getter
    @Builder
    public static class TriggeredObject {
        @JsonUnwrapped
        private final S3Object object;

        @Schema(title = "Whether the object is new or was updated since the previous poll")
        private final ChangeType changeType;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Objects that fired the trigger",
            description = "Each object holds its key, ETag, size, last modification date, change type and the internal storage URI of its downloaded content."
        )
        private final java.util.List<TriggeredObject> objects;
    }
}
