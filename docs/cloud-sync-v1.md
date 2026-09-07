# OneDrive Cloud Sync Protocol v1

Desktop and Android use the same SQLite database snapshot. The synchronized file is
`Wuwa Gacha Tool/gacha-data.db`; it is not a per-UID JSON file.

## File layout

On first sync, each client creates a visible `Wuwa Gacha Tool` directory at the OneDrive root. One database file is stored inside it:

```text
Wuwa Gacha Tool/gacha-data.db
```

## OneDrive transport

Both clients use the Microsoft consumer device-code flow as public clients. Builds receive the same `WUWA_ONEDRIVE_CLIENT_ID` at compile time and request only `offline_access` and `Files.ReadWrite`. No client secret is embedded in either application.

The desktop client stores the refresh token in the operating-system credential vault. Android encrypts it with an Android Keystore AES-GCM key before writing the ciphertext to private preferences. Access tokens remain in memory only. Tokens, device codes, download URLs, and complete Graph responses must never be logged.

Before uploading, a client creates a consistent SQLite backup without uploading WAL or SHM files, then downloads the current snapshot and ETag. New files use `If-None-Match: *`; existing files use `If-Match`. A concurrent cloud change (`409` or `412`) stops the operation and requires a fresh sync. The local database is replaced transactionally only after the downloaded file passes size, SQLite integrity, schema, and required-table checks.

## Validation

Both clients reject the downloaded file before touching local data when it exceeds the byte-size limit, fails SQLite `integrity_check`, has an unsupported schema version, or is missing required tables/columns. Unknown future schema versions fail closed and require an application update.

## Snapshot semantics

The SQLite snapshot contains shared record data, import state, and pool history boundaries. Device-only state such as game paths, resource caches, sync baselines, and credentials is not uploaded. Replacing the snapshot therefore synchronizes additions, edits, deletions, clears, and mock-record changes as one database state; it does not merge two offline database versions. If both sides changed since the common ETag/hash baseline, synchronization stops instead of applying last-writer-wins.

The desktop and Android clients must keep the shared database schema and migrations compatible. A schema change must be implemented and tested in both clients before release.
