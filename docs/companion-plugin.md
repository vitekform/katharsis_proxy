# The Paper companion

`paper/` holds a backend plugin that replaces the proxy's `/language` with a
clickable selector.

## What each side owns

| | Proxy (Velocity) | Companion (Paper) |
|---|---|---|
| Player languages | stores and caches them | never stores anything |
| Translations | every string, in `lang/` | only two, for when the proxy is unreachable |
| `/language` | text command, **dropped** once a companion announces | chest selector |

The split is deliberate: the companion holds no state and almost no text, so the
two plugins cannot drift out of agreement about a player's language, and adding
a language means editing the proxy's `lang/` directory only — every backend
picks it up at once without being updated or restarted.

## How the takeover works

1. A player joins a backend running the companion.
2. The companion sends `announce` on the `katharsis:language` channel.
3. The proxy records the server and **unregisters its own `/language`**.
4. From then on `/language` is the companion's selector.

Two consequences follow from plugin messaging needing a player to carry a
message:

- Until somebody joins a backend with the companion, the proxy's `/language`
  is still the one players get. The changeover happens on the first join, not at
  boot.
- The companion re-announces on join, at most once a minute, so a proxy that
  restarts learns about the server again without anyone restarting the backend.

The proxy never re-registers its command. **If only some of your backends run the
companion, players on the others lose `/language` entirely** once any backend has
announced. Either install the companion everywhere, or on none of them.

## The selector

`/language` (alias `/lang`) asks the proxy for `get_language_menu` and opens a
chest from the reply. Each language is one item, named in **its own language** —
`Čeština`, not "Czech" — because the proxy sends each entry's `language-name`
from that language's own file. The player's current language is a written book
with a glint; the rest are paper.

Clicking a language sends `set_language`. The proxy stores it and sends the
confirmation itself, in the newly chosen language, so the companion never
composes a translated sentence. Clicks are cancelled unconditionally and drags
are blocked, so nothing can be taken out of the menu or dropped into it.

If the proxy does not answer within `request-timeout-seconds`, the player is told
the proxy is unreachable rather than being left with a frozen command.

## Supported versions

The same sources are built twice, once per Paper line, because the two lines
need different JDKs:

| Variant | Jar | Built against | Bytecode | `api-version` | Runs on |
|---|---|---|---|---|---|
| default | `katharsis_paper-<v>.jar` | paper-api 1.21.1 | Java 21 | `1.21` | Paper 1.21.1 – 1.21.11 |
| `-P paper-26` | `katharsis_paper-<v>-mc26.jar` | paper-api 26.1.2 | Java 25 | `26.1.2` | Paper 26.1.2 – 26.2 |

Each variant is compiled against the **oldest** API in its line, which is what
lets one jar cover the whole line: a plugin built against an old API runs on
newer servers, never the reverse.

Install the jar matching your server's line. The 1.21.x jar is also *source and
bytecode* compatible with 26.x servers — Java 25 runs Java 21 bytecode, and
`compat-check.sh` proves the API surface exists there — so it can serve as a
single universal jar if you would rather ship one. The 26.x variant exists
because it declares a native `api-version` instead of relying on the 26.x line
still accepting the legacy `1.21` value, and because it leaves room for 26-only
API later.

### Why these are separate builds

Every 1.21.x Paper API jar is a Java 21 artifact; every 26.x one is Java 25.
A JDK cannot read class files newer than itself, so the 26.x variant simply
cannot be compiled on JDK 21 — `javac` reports every Bukkit type as
`cannot find symbol`. The build checks the JDK up front and says so:

> The 26.x variant is built against paper-api 26.1.2.build.74-stable, whose class
> files need JDK 25 or newer to read. Build it with that JDK, or drop
> `-P paper-26` to build the 1.21.x variant instead.

`PluginDescriptorTest` then asserts the variant that was built is internally
consistent: the emitted bytecode matches the Java release its line runs, the API
compiled against really is the oldest one claimed, and `api-version` names that
same minimum. Bumping a version property without meaning to fails the build with
an explanation.

### Checking the whole range

Compiling against the oldest API proves nothing about whether those methods
still exist later on. `paper/compat-check.sh` recompiles the sources against
every Paper API release across both lines and reports each one:

```bash
./paper/compat-check.sh
```

A version whose bytecode the running JDK cannot read is skipped with a notice
rather than failing, so this is useful on JDK 21 even though it cannot cover the
26.x half. On JDK 25 or newer, `--strict` turns any skipped version into a
failure, which is how CI proves the full range was really checked:

```bash
./paper/compat-check.sh --strict
```

## Building

The 1.21.x variant builds on **JDK 21**, the same as the proxy:

```bash
mvn -f paper/pom.xml verify
```

The 26.x variant needs **JDK 25**:

```bash
mvn -f paper/pom.xml -P paper-26 verify
```

Both write to `paper/target/`, and each build cleans it, so copy the first jar
aside if you want both at once.

## Installing

1. Put `katharsis_proxy-<version>.jar` in the proxy's `plugins/`.
2. Put the companion jar for your server's line in every backend's `plugins/`
   — `katharsis_paper-<version>.jar` for Paper 1.21.x, or
   `katharsis_paper-<version>-mc26.jar` for 26.x.
3. Start the proxy first, then the backends.

Order does not actually matter — the companion announces on the first player
join either way — but starting the proxy first means the changeover happens on
the very first join.

## Configuration

The companion's `config.yml` is deliberately almost empty. It holds the request
timeout and the two messages it needs when the proxy did not answer, because at
that moment there is no language to answer in. Everything else lives in the
proxy's `lang/` files.

## Adding a language

Add `lang/<code>.yml` to the **proxy's** data directory and restart the proxy.
The selector picks it up with no change to any backend. The file must define
`language-name`, which is what the selector shows.

Codes are at most five characters, because that is the width of the `language`
column; see [plugin-messaging.md](plugin-messaging.md) for the wire contract the
two plugins share.
