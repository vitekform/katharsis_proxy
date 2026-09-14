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

One jar covers **Paper 1.21.1 through 26.2**.

That works because the companion is compiled against the *oldest* API in the
range, not the newest. A plugin built against an old API runs on newer servers;
the reverse never works. Two things follow, and both are enforced by tests:

- **`paper.version` in `paper/pom.xml` is the oldest supported API**, currently
  `1.21.1-R0.1-SNAPSHOT`. Bumping it to the newest Paper silently breaks every
  older server.
- **The jar is Java 21 bytecode.** Paper's 1.21.x API is a Java 21 artifact;
  the 26.x line is Java 25. Compiling against 26.x would emit class files a
  1.21.x server cannot load at all.

`PluginDescriptorTest` asserts both — it reads the compiled class file's own
version — so an accidental upgrade fails the build with an explanation rather
than shipping a jar that only works on new servers.

`api-version` in `plugin.yml` is `1.21`: the *minimum* server, which is what the
field is for.

### Checking the whole range

Compiling against the oldest API proves nothing about whether those methods
still exist later on. `paper/compat-check.sh` recompiles the sources against
every Paper API release in the range and reports each one:

```bash
./paper/compat-check.sh
```

A version whose bytecode the running JDK cannot read is skipped with a notice
rather than failing, so this is useful on JDK 21 even though it cannot cover the
26.x half. Before a release, run it on JDK 25 or newer with `--strict`, which
turns any skipped version into a failure:

```bash
./paper/compat-check.sh --strict
```

## Building

The companion builds on **JDK 21**, the same as the proxy:

```bash
mvn -f paper/pom.xml verify
```

## Installing

1. Put `katharsis_proxy-<version>.jar` in the proxy's `plugins/`.
2. Put `katharsis_paper-<version>.jar` in every backend's `plugins/`.
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
