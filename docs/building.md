# Building and testing

## Requirements

- **JDK 21** for both plugins
- Maven 3.9+
- Network access on the first build, to fetch dependencies and — for the
  integration test — a Velocity release

**JDK 25** is needed for the companion's 26.x variant, because every 26.x Paper
API jar is a Java 25 artifact that an older `javac` cannot read. The 1.21.x
variant, the proxy and all the tests build on JDK 21.

This repository holds three projects, built separately:

- the root project — the Velocity proxy plugin
- `paper/` — the Paper companion, see [companion-plugin.md](companion-plugin.md)
- `neoforge/` — the NeoForge mod that lets players log in on modded servers,
  see [authentication.md](authentication.md#modded-servers). It builds with
  Gradle (the wrapper is checked in), since NeoForge's tooling is Gradle-only.

They share a wire protocol, not code, so none depends on another at build time.

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

The NeoForge mod has three lines, one per break in the API it uses:

```bash
cd neoforge && ./gradlew build
```

```bash
cd neoforge && ./gradlew build -Pline=1.21.11
```

```bash
cd neoforge && ./gradlew build -Pline=26
```

| Line      | Minecraft          | Built against | JDK | Jar                                     |
|-----------|--------------------|---------------|-----|-----------------------------------------|
| (default) | 1.21.1 – 1.21.10   | NeoForge 21.1 | 21  | `katharsis_neoforge-<version>.jar`         |
| `1.21.11` | 1.21.11            | NeoForge 21.11| 21  | `katharsis_neoforge-<version>-mc1.21.11.jar` |
| `26`      | 26.1.2 – 26.x      | NeoForge 26.1 | 25  | `katharsis_neoforge-<version>-mc26.jar`    |

1.21.11 renamed `ResourceLocation` to `Identifier`, the one type the mod names
that changed, and the 26 line is Java 25 bytecode. Each line is built against
the oldest NeoForge it covers. `neoforge/compat-check.sh` compiles the mod
against the newest NeoForge of every Minecraft version in between. The first
time, each version has Minecraft decompiled for it, which takes a few minutes.
Gradle finds JDK 21 and 25 wherever they are installed.

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
with `-Dvelocity.cache.dir=...` to keep it across a `clean` — CI does
this so the download can be cached between runs.

## Output

- `target/katharsis_proxy-<version>.jar` — the shaded proxy plugin, the one to install
- `target/original-katharsis_proxy-<version>.jar` — pre-shade, not shipped
- `paper/target/katharsis_paper-<version>.jar` — the companion for Paper 1.21.x
- `paper/target/katharsis_paper-<version>-mc26.jar` — the companion for Paper 26.x
- `neoforge/build/libs/katharsis_neoforge-<version>*.jar` — the NeoForge mod, one
  jar per line
- `target/site/jacoco/` — coverage report
- `target/surefire-reports/`, `target/failsafe-reports/` — JUnit XML

There is **no equivalent real-server test for the companion**. Booting Paper
needs Mojang's CDN, which was unreachable from the machine this was developed on,
so such a test could not be run and was not written rather than shipped unproven.
`PluginDescriptorTest` covers the part that would otherwise only fail at load
time — it already caught an `api-version` that did not match the compiled API.

## CI

`.github/workflows/build.yml` runs on pushes and pull requests to `master`, and
can be started by hand from the Actions tab:

1. **Cleanup** — pushes only: `ci/cleanup-artifacts.sh` deletes the oldest runs'
   artifacts until the repository's unexpired artifacts fit a 1 GiB budget,
   always keeping the newest run's. It is non-fatal, so a failure there never
   blocks a build. Its selection logic is tested by `ci/cleanup-artifacts-test.sh`
   against a mock of the GitHub API.
2. **Build** — three independent jobs:
   - *Velocity plugin*: restores the Maven and Velocity caches, runs
     `mvn clean verify`, reports unit and integration results plus coverage,
     and uploads the shaded jar as `katharsis-proxy`.
   - *Paper companion (1.21.x)*: on JDK 21, runs
     `mvn -f paper/pom.xml clean verify`, then `compat-check.sh`. That check
     covers the 1.21.x half of the range and reports the 26.x versions as
     skipped, since JDK 21 cannot read them. Uploads `katharsis-companion`.
   - *Paper companion (26.x)*: on JDK 25, builds with `-P paper-26`, and runs
     `compat-check.sh --strict` — on that JDK nothing can be skipped, so this is
     where the **full** range is proven. Uploads `katharsis-companion-26`.
   - *NeoForge mod*: with JDK 21 and 25 installed, builds and tests all three
     lines and uploads their jars as `katharsis-neoforge`.
   - *NeoForge mod (every supported version)*: runs `neoforge/compat-check.sh`.
     It is the slowest job by far, since it sets up thirteen NeoForge versions.

Jars are only uploaded from `master`; pull request runs build and test but keep
nothing. JDKs come from `actions/setup-java` (Temurin), so a problem fetching
JDK 25 cannot stop the other artifacts being produced.

Test results are reported with `!cancelled()`, so a failing run still shows
which tests failed rather than only that the build broke. Coverage is written to
the run's summary page by `ci/coverage-summary.sh`.
