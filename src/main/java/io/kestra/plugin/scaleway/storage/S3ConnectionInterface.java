package io.kestra.plugin.scaleway.storage;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public interface S3ConnectionInterface {
    @Schema(
        title = "Scaleway access key",
        description = "Access key of a Scaleway API key, used as the AWS Signature V4 access key ID."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    Property<String> getAccessKey();

    @Schema(
        title = "Scaleway secret key",
        description = "Secret key of a Scaleway API key, used as the AWS Signature V4 secret access key."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    Property<String> getSecretKey();

    @Schema(
        title = "Scaleway region",
        description = "Region hosting the buckets, for example fr-par, nl-ams or pl-waw. The endpoint is derived from it as https://s3.{region}.scw.cloud."
    )
    @NotNull
    @PluginProperty(group = "connection")
    Property<String> getRegion();

    @Schema(
        title = "Endpoint override",
        description = "Custom S3 endpoint to use instead of https://s3.{region}.scw.cloud, for example a private or test endpoint."
    )
    @PluginProperty(group = "advanced")
    Property<String> getEndpointOverride();

    @Schema(
        title = "Force path-style access",
        description = "Address buckets as {endpoint}/{bucket} instead of {bucket}.{endpoint}. Scaleway supports both; path-style is mostly useful with a custom endpoint."
    )
    @PluginProperty(group = "advanced")
    Property<Boolean> getForcePathStyle();
}
