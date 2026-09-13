# Building and testing

## Requirements

- JDK 21 or newer
- Maven 3.9+
- Network access on the first build, to fetch dependencies and — for the
  integration test — a Velocity release

## Commands

```bash
mvn verify
```

That is the full gate, and it is exactly what CI runs:

| Phase               | What happens                                                        |
|---------------------|---------------------------------------------------------------------|
| `compile`           | Compiles against the Velocity API                                    |
| `test`              | Runs the unit tests (surefire) and writes the JaCoCo report          |
| `package`           | Builds the jar, then shades and relocates HikariCP, PostgreSQL and H2 |
| `integration-test`  | Boots the shaded jar on a real Velocity proxy (failsafe)              |

Other useful invocations:

```bash
mvn test
```

```bash
mvn verify -Dkatharsis.velocity.skip=true
```

The first runs only the unit tests. The second skips the Velocity integration
test, which is what you want when working offline.

## The test suites

Unit tests cover the pieces in isolation, against the **unrelocated** classes:

- `PlayerTest`, `CooldownManagerTest` — entity and cooldown rules
- `PlayerLanguageCacheTest` — the ten-minute retention window, with a short TTL
- `ConfigManagerTest` — default file, partial overrides, no rewriting
- `LanguageRegistryTest`, `MessagesTest` — translation loading, fallbacks,
  localised durations, and that placeholders cannot inject MiniMessage tags
- `DatabaseManagerTest`, `PlayerRepositoryTest` — pool lifecycle and SQL, against
  a real embedded H2 database in a temporary directory
- `LanguageChannelListenerTest` — the plugin message protocol, driving real
  `PluginMessageEvent`s through the listener

`VelocityServerIT` is the only test that runs the artifact a server owner will
actually install. It downloads the Velocity release named by
`velocity.test.version`, verifies its SHA-256, writes a `velocity.toml` on a free
port, drops the **shaded** jar into `plugins/`, boots the proxy, waits for
`Done (`, and asserts the plugin's own startup log — including that the
relocated `me.ganamaga.katharsis_proxy.libs.hikari` pool is what runs. It then
shuts the proxy down through its console and checks the data files were written.

This matters because the unit tests load unrelocated classes and **cannot** catch
a broken shade configuration, a missing resource, or a Guice wiring mistake.

The downloaded Velocity jar is cached in `target/velocity`. Point it elsewhere
with `-Dvelocity.cache.dir=...` to keep it across a `clean` — the pipeline does
this so the download can be cached between runs.

## Output

- `target/katharsis_proxy-<version>.jar` — the shaded plugin, the one to install
- `target/original-katharsis_proxy-<version>.jar` — pre-shade, not shipped
- `target/site/jacoco/` — coverage report
- `target/surefire-reports/`, `target/failsafe-reports/` — JUnit XML

## Pipeline

`azure-pipelines.yml` runs on pushes and pull requests to `master`:

1. **Build** — restores the Maven and Velocity caches, runs `mvn clean verify`,
   publishes unit and integration test results plus coverage, and uploads the
   shaded jar as the `plugin` pipeline artifact.
2. **Publish** — master only: retains that jar as the `katharsis-proxy` build
   artifact.

Test results are published with `condition: succeededOrFailed()`, so a failing
run still shows which tests failed rather than only that the build broke.
