# Building and testing

## Requirements

- **JDK 21** for both plugins
- Maven 3.9+
- Network access on the first build, to fetch dependencies and — for the
  integration test — a Velocity release

JDK 25 or newer is needed for one optional task: checking the companion against
the newest Paper APIs, which are Java 25 artifacts. See
[companion-plugin.md](companion-plugin.md#checking-the-whole-range).

This repository holds two plugins, built separately:

- the root project — the Velocity proxy plugin
- `paper/` — the Paper companion, see [companion-plugin.md](companion-plugin.md)

They share a wire protocol, not code, so neither depends on the other at build
time.

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

And for the companion:

```bash
mvn -f paper/pom.xml verify
```

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

The companion's own suite is smaller, because its logic is deliberately thin:

- `ProtocolCodecTest` — the wire format, including a **golden-bytes** case that
  pins the exact encoding the proxy must accept
- `LanguageMenuModelTest` — selector layout: centring, row growth, the double
  chest cap, and which entry is marked current
- `PluginDescriptorTest` — what decides whether the plugin loads and on which
  servers: that the jar is Java 21 bytecode, that it was compiled against the
  oldest supported API, that `api-version` names the minimum server, that the
  main class exists, and that Maven filtered the version placeholder

The golden-bytes case is asserted on both sides. The companion asserts it
*writes* those bytes; the proxy's `LanguageChannelListenerTest` feeds the same
literal bytes through its listener and asserts it *understands* them. The two
plugins repeat the protocol constants rather than sharing a module, so this pair
of tests is what stops them drifting apart.

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

- `target/katharsis_proxy-<version>.jar` — the shaded proxy plugin, the one to install
- `target/original-katharsis_proxy-<version>.jar` — pre-shade, not shipped
- `paper/target/katharsis_paper-<version>.jar` — the companion, for each backend
- `target/site/jacoco/` — coverage report
- `target/surefire-reports/`, `target/failsafe-reports/` — JUnit XML

There is **no equivalent real-server test for the companion**. Booting Paper
needs Mojang's CDN, which was unreachable from the machine this was developed on,
so such a test could not be run and was not written rather than shipped unproven.
`PluginDescriptorTest` covers the part that would otherwise only fail at load
time — it already caught an `api-version` that did not match the compiled API.

## Pipeline

`azure-pipelines.yml` runs on pushes and pull requests to `master`:

1. **Build** — two independent jobs:
   - *Velocity plugin*: restores the Maven and Velocity caches, runs
     `mvn clean verify`, publishes unit and integration results plus coverage,
     and uploads the shaded jar as `proxy-plugin`.
   - *Paper companion*: runs `mvn -f paper/pom.xml clean verify`, then
     `compat-check.sh` to recompile the sources against every supported Paper
     API, publishes its results and coverage, and uploads its jar as
     `companion-plugin`. On the agent's JDK 21 the check covers the 1.21.x half
     of the range and reports the 26.x versions as skipped.
2. **Publish** — master only: retains both jars as build artifacts.

Both jobs use the agent's preinstalled JDK 21.

Test results are published with `condition: succeededOrFailed()`, so a failing
run still shows which tests failed rather than only that the build broke.
