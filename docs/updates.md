# Updates

The proxy plugin, the Paper companion and the NeoForge mod each check the
release system ([rsys.ganamaga.me](https://rsys.ganamaga.me/katharsis/public/latest.json),
project `katharsis`) for a newer build of themselves, download it, and have it
take over at the next restart. Nothing is ever swapped under a running server.

| Piece     | Settings                                  | Downloaded to                 | Installed                                  | By hand                                   |
|-----------|-------------------------------------------|-------------------------------|--------------------------------------------|-------------------------------------------|
| Proxy     | `updates:` in the proxy's `config.yml`    | `plugins/katharsis/updates/`  | moved into `plugins/` as the proxy stops   | `/katharsis check`, `/katharsis update`   |
| Companion | `updates:` in the companion's `config.yml`| `plugins/update/<this jar's name>` | by Paper, from its update folder, at the next start | `/kcompanion check`, `/kcompanion update` |
| Mod       | `config/katharsis-updates.properties`     | `katharsis/updates/`          | moved into `mods/` once the server stops   | —                                         |

All three share the same keys:

| Key                    | Default | Meaning                                                                    |
|------------------------|---------|----------------------------------------------------------------------------|
| `enabled`              | `true`  | Check at all.                                                              |
| `channel`              | `auto`  | `release` for tagged releases, `dev` for every master build too, `auto` for whatever the running jar is. |
| `auto-download`        | `true`  | Download a newer jar as soon as one is found. Off, it is only announced.   |
| `check-interval-hours` | `6`     | Hours between checks, the first one 10 s after startup. `0` checks once.   |
| `notify-staff`         | `true`  | Proxy and companion: tell players with `katharsis.update.notify` on join.  |
| `url`                  | empty   | The project's public root, for another release system.                     |

## Permissions

| Node                      | Proxy | Companion (default op) |
|---------------------------|-------|------------------------|
| `katharsis.update`        | `/katharsis check`, `/katharsis update` | `/kcompanion check`, `/kcompanion update` |
| `katharsis.update.notify` | told about an update on joining | the same |

`/katharsis` and `/kcompanion` with no arguments show the running version to
anyone holding `katharsis.update`. The update commands download even when
`auto-download` is off, and even into a local build.

## Which jar is "newer"

Every jar CI builds is stamped with the release it becomes: `0.2-dev.41` for
the 41st build of master, `0.2` for the tag `v0.2`. Versions compare like
semantic versions, so `0.1-dev.13 < 0.1 < 0.2-dev.14 < 0.2`, and a dev build
counts only against other builds of the same channel unless `channel` says
otherwise.

A jar also knows its variant: the companion for Paper 26.x only ever takes
`katharsis_paper-<version>-mc26.jar`, the mod for 1.21.11 only
`katharsis_neoforge-<version>-mc1.21.11.jar`, and so on. A release without a
jar for the running variant is reported, not installed.

A jar built without the stamp, on a pull request or on someone's machine, runs
as its project version (`0.2`), says "a local build" in its startup line, and
is never replaced on its own. It is still told about updates, and the update
commands still work.

## Safety

- Every download is checked against the size and sha256 the release system
  lists for it, and written beside its destination first, so a partial or
  tampered jar never lands under a name a loader would pick up.
- The new jar is renamed over the old one in a single step. If the old jar
  cannot be removed, as on Windows while it is loaded, nothing changes and the
  console says where the download is, so the server never starts with two
  copies of the plugin.
- A failed check is logged once per outage, and an update once per version,
  not at every check.

## How it is built

The logic lives in `updater/src/main/java` (package
`me.ganamaga.katharsis_updater`), plain Java plus Gson, which all three
platforms ship. Each build compiles it in as sources: `build-helper-maven-plugin`
in the two poms, a second `srcDir` in `neoforge/build.gradle`, and
`paper/compat-check.sh` includes it when compiling against every Paper API.
Its tests, `updater/src/test/java`, run in the proxy's build against a fake
release system on a local port.

Each jar carries `katharsis-build.properties`, filled in by the build:

```properties
version=0.2
release=0.2-dev.41
artifact=katharsis_paper
classifier=mc26
```

`release` comes from `-Dkatharsis.release=...` (Maven) or `-Prelease=...`
(Gradle), which `build.yml` passes from its Plan job.
