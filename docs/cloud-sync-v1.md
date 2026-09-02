# Cloud Sync Protocol v1

Cloud sync transports validated record snapshots. It never uploads or replaces a SQLite database.

## File layout

On first sync, each client creates a visible `Wuwa Gacha Tool` directory at the OneDrive root. One file is stored per UID inside it:

```text
Wuwa Gacha Tool/<uid>.wuwa.json
```

## OneDrive transport

Both clients use the Microsoft consumer device-code flow as public clients. Builds receive the same `WUWA_ONEDRIVE_CLIENT_ID` at compile time and request only `offline_access` and `Files.ReadWrite`. No client secret is embedded in either application.

The desktop client stores the refresh token in the operating-system credential vault. Android encrypts it with an Android Keystore AES-GCM key before writing the ciphertext to private preferences. Access tokens remain in memory only. Tokens, device codes, download URLs, and complete Graph responses must never be logged.

Before uploading, a client downloads the current snapshot and ETag. New files use `If-None-Match: *`; existing files use `If-Match`. HTTP `409` or `412` causes a fresh download and merge, with at most three retries. A successful cloud upload is applied to the local database transactionally; if local persistence fails, the uploaded additive snapshot remains recoverable on the next sync.

The JSON envelope contains `schema_version`, `uid`, `updated_at`, and `records`. Every record carries the complete merge identity: official pool ID, time, resource ID, quality, resource type, count, occurrence number, mock flag, and mock batch ID. `order_in_timestamp` preserves draw order when multiple records share one second.

## Validation

Both clients reject the complete payload before touching local data when:

- the schema version is unsupported;
- the selected UID and payload UID differ;
- a UID, pool, resource type, timestamp, count, or resource field is invalid;
- occurrence numbers or same-second order numbers conflict or are not contiguous from zero;
- an official record carries a mock batch ID;
- the payload exceeds the record-count or byte-size limit.

Unknown future schema versions fail closed and require an application update.

## Merge semantics

The cloud snapshot is the ordering authority for records already present at a shared pool and timestamp. Local-only records are appended in their existing order. The merged snapshot:

- keeps the maximum observed multiplicity of an identical same-second record;
- isolates records by UID and official pool ID;
- keeps official and mock identities separate;
- refreshes derived display fields without duplicating records;
- normalizes occurrence and same-second order numbers;
- applies additions and authoritative order in one local database transaction;
- is idempotent when applied repeatedly.

The merged snapshot is uploaded with the ETag returned by the download. An HTTP `412 Precondition Failed` requires downloading the new cloud snapshot, merging again, and retrying with the new ETag. A client must never resolve this conflict with last-writer-wins replacement.

## Deletion boundary

Version 1 is additive and does not synchronize deletions. Deletion requires a future version with durable tombstones; omitting a record from a v1 snapshot never deletes it from another device.
