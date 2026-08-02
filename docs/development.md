# Development and foundation status

## Build

The project targets Java 21 and uses a multi-module Gradle Kotlin DSL build.

```text
gradle clean check
gradle :plugin:shadowJar
```

The deployable jar is produced by `:plugin:shadowJar`.

## Module direction

```text
plugin -> api, adapters-paper, adapters-sqlite
adapters-paper -> api, application, domain
adapters-sqlite -> application, domain
api -> application
application -> domain
domain -> JDK only
```

`architecture-tests` imports the compiled modules only to verify dependency rules and cycles.

## Foundation database

The intended production path is:

```text
plugins/EnthusiaLoreItems/loreitems.db
```

Migration `V1__foundation.sql` establishes the durable records required by the planning documents. SQLite connections enable foreign keys, WAL mode, `synchronous=NORMAL`, and a bounded busy timeout. Callers must open connections, run migrations, and execute repository work away from the server thread.

## Current limitations

This first scaffold deliberately exposes the versioned Bukkit service as unavailable. It does not create, adopt, protect, locate, edit, deliver, or delete physical items. The database lifecycle, repositories, bounded worker runtime, immutable configuration, Paper identity codec, and operational metrics still need to be implemented before the foundation PR is complete.

No live-server behavior has been tested.
