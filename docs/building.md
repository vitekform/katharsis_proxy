# Building and testing

## Requirements

- **JDK 21** for both plugins
- Maven 3.9+
- Network access on the first build, to fetch dependencies and — for the
  integration test — a Velocity release

**JDK 25** is needed for the companion's 26.x variant, because every 26.x Paper
API jar is a Java 25 artifact that an older `javac` cannot read. The 1.21.x
variant, the proxy and all the tests build on JDK 21.

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

And for the companion, once per Paper line:

```bash
mvn -f paper/pom.xml verify
```

```bash
mvn -f paper/pom.xml -P paper-26 verify
```

The first needs JDK 21 and produces the 1.21.x jar; the second needs JDK 25 and
produces `katharsis_paper-<version>-mc26.jar`. See
[companion-plugin.md](companion-plugin.md#supported-versions) for why they are
separate builds.

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
- `paper/target/katharsis_paper-<version>.jar` — the companion for Paper 1.21.x
- `paper/target/katharsis_paper-<version>-mc26.jar` — the companion for Paper 26.x
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
   - *Paper companion (1.21.x)*: on the preinstalled JDK 21, runs
     `mvn -f paper/pom.xml clean verify`, then `compat-check.sh`. That check
     covers the 1.21.x half of the range and reports the 26.x versions as
     skipped, since JDK 21 cannot read them. Uploads `companion-plugin`.
   - *Paper companion (26.x)*: fetches a JDK 25, builds with `-P paper-26`, and
     runs `compat-check.sh --strict` — on that JDK nothing can be skipped, so
     this is where the **full** range is proven. Uploads `companion-plugin-26`.
2. **Publish** — master only: retains all three jars as build artifacts.

The proxy and 1.21.x companion jobs use the agent's preinstalled JDK 21. Only
the 26.x job fetches a JDK, so a problem there cannot stop the other artifacts
being produced.

Test results are published with `condition: succeededOrFailed()`, so a failing
run still shows which tests failed rather than only that the build broke.
