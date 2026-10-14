package io.kestra.plugin.scaleway.storage;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerInterface;
import io.kestra.core.models.triggers.Trigger;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ObjectStorageTriggerTest extends AbstractObjectStorageTest {
    @Test
    void firesOnceForNewObjectsWithNoneAction() throws Exception {
        String bucket = createBucket();
        upload(bucket, "incoming/a.csv", "a");
        upload(bucket, "incoming/b.csv", "b");
        upload(bucket, "other/c.csv", "c");

        ObjectStorageTrigger trigger = trigger(bucket).prefix(Property.ofValue("incoming/")).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        java.util.List<Map<String, Object>> objects = objects(evaluate(trigger, context));

        assertThat(values(objects, "key"), containsInAnyOrder("incoming/a.csv", "incoming/b.csv"));
        assertThat(values(objects, "changeType"), everyItem(is("CREATE")));
        var a = objects.stream().filter(o -> o.get("key").equals("incoming/a.csv")).findFirst().orElseThrow();
        assertThat(readStorage(URI.create((String) a.get("uri"))), is("a"));

        // the objects are still there but were already processed
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
        assertThat(listKeys(bucket), hasSize(3));
    }

    @Test
    void firesAgainWhenAnObjectChanges() throws Exception {
        String bucket = createBucket();
        upload(bucket, "data.json", "v1");

        ObjectStorageTrigger trigger = trigger(bucket).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        assertThat(objects(evaluate(trigger, context)), hasSize(1));

        upload(bucket, "data.json", "v2");
        upload(bucket, "new.json", "new");

        java.util.List<Map<String, Object>> objects = objects(evaluate(trigger, context));
        assertThat(objects, hasSize(2));
        Map<Object, Object> changes = objects.stream().collect(java.util.stream.Collectors.toMap(o -> o.get("key"), o -> o.get("changeType")));
        assertThat(changes, is(Map.<Object, Object>of("data.json", "UPDATE", "new.json", "CREATE")));
    }

    @Test
    void onCreateIgnoresUpdates() throws Exception {
        String bucket = createBucket();
        upload(bucket, "data.json", "v1");

        ObjectStorageTrigger trigger = trigger(bucket).on(Property.ofValue(StatefulTriggerInterface.On.CREATE)).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        assertThat(objects(evaluate(trigger, context)), hasSize(1));

        upload(bucket, "data.json", "v2");

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
    }

    @Test
    void deleteAction() throws Exception {
        String bucket = createBucket();
        upload(bucket, "incoming/a.csv", "a");
        upload(bucket, "incoming/b.csv", "b");
        upload(bucket, "keep.txt", "keep");

        ObjectStorageTrigger trigger = trigger(bucket)
            .prefix(Property.ofValue("incoming/"))
            .action(Property.ofValue(ObjectStorageTrigger.Action.DELETE))
            .build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(objects(evaluate(trigger, context)), hasSize(2));
        assertThat(listKeys(bucket), contains("keep.txt"));
    }

    @Test
    void moveAction() throws Exception {
        String bucket = createBucket();
        String archive = createBucket();
        upload(bucket, "incoming/a.csv", "a");
        upload(bucket, "incoming/b.json", "b");

        ObjectStorageTrigger trigger = trigger(bucket)
            .prefix(Property.ofValue("incoming/"))
            .regexp(Property.ofValue(".*\\.csv"))
            .action(Property.ofValue(ObjectStorageTrigger.Action.MOVE))
            .moveTo(Copy.CopyObject.builder().bucket(Property.ofValue(archive)).key(Property.ofValue("processed/")).build())
            .build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(objects(evaluate(trigger, context)), hasSize(1));
        assertThat(listKeys(bucket), contains("incoming/b.json"));
        assertThat(listKeys(archive), contains("processed/a.csv"));
    }

    @Test
    void maxFilesSplitsABurstAcrossPolls() throws Exception {
        String bucket = createBucket();
        upload(bucket, "1.txt", "1");
        upload(bucket, "2.txt", "2");
        upload(bucket, "3.txt", "3");

        ObjectStorageTrigger trigger = trigger(bucket).maxFiles(Property.ofValue(2)).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(values(objects(evaluate(trigger, context)), "key"), contains("1.txt", "2.txt"));
        assertThat(values(objects(evaluate(trigger, context)), "key"), contains("3.txt"));
        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
    }

    @Test
    void emptyBucketDoesNotFire() throws Exception {
        String bucket = createBucket();

        ObjectStorageTrigger trigger = trigger(bucket).build();
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(trigger.evaluate(context.getKey(), context.getValue()).isPresent(), is(false));
    }

    private ObjectStorageTrigger.ObjectStorageTriggerBuilder<?, ?> trigger(String bucket) {
        return ObjectStorageTrigger.builder()
            .id("trigger_" + IdUtils.create())
            .type(ObjectStorageTrigger.class.getName())
            .accessKey(Property.ofValue(ACCESS_KEY))
            .secretKey(Property.ofValue(SECRET_KEY))
            .region(Property.ofValue(REGION))
            .endpointOverride(Property.ofValue(endpoint()))
            .forcePathStyle(Property.ofValue(true))
            .bucket(Property.ofValue(bucket));
    }

    private static Execution evaluate(ObjectStorageTrigger trigger, Map.Entry<ConditionContext, Trigger> context) throws Exception {
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(execution.isPresent(), is(true));
        return execution.get();
    }

    private static java.util.List<String> values(java.util.List<Map<String, Object>> objects, String field) {
        return objects.stream().map(o -> (String) o.get(field)).toList();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> objects(Execution execution) {
        return (java.util.List<Map<String, Object>>) execution.getTrigger().getVariables().get("objects");
    }
}
