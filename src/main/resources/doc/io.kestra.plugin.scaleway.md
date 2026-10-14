# How to use the Scaleway plugin

Orchestrate Scaleway resources from Kestra flows.

## Authentication

Create an API key in the Scaleway console (IAM, API keys) and store its access key and secret key as [secrets](https://kestra.io/docs/concepts/secret). Pass them to every task and trigger with the `accessKey` and `secretKey` properties, and set `region` to the region of your resources, for example `fr-par`, `nl-ams` or `pl-waw`.

## Object Storage

The `storage` package works with Scaleway Object Storage through its S3-compatible API. The endpoint is derived from the region as `https://s3.{region}.scw.cloud`; set `endpointOverride` (and `forcePathStyle` if needed) to reach another S3-compatible endpoint.

- `CreateBucket`, `ListBuckets` and `DeleteBucket` manage buckets; `DeleteBucket` can empty the bucket first with `force: true`.
- `Upload` sends a file from internal storage to a bucket and `Download` stores an object in internal storage.
- `List`, `Copy` and `Delete` list, copy or move, and delete objects.
- `ObjectStorageTrigger` polls a bucket and starts an execution with the new or updated objects. It keeps track of the objects it already fired for, and can delete or move them afterwards with `action`.
