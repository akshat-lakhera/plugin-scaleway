package io.kestra.plugin.scaleway.storage;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import lombok.*;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.s3.S3Client;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractS3 extends Task implements S3ConnectionInterface {
    @ToString.Exclude
    protected Property<String> accessKey;

    @ToString.Exclude
    protected Property<String> secretKey;

    protected Property<String> region;

    protected Property<String> endpointOverride;

    @Builder.Default
    protected Property<Boolean> forcePathStyle = Property.ofValue(false);

    protected S3Client client(RunContext runContext) throws IllegalVariableEvaluationException {
        return S3Service.client(runContext, this);
    }
}
