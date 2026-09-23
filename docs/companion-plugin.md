# The Paper companion

`paper/` holds a backend plugin that replaces the proxy's `/language` with a
clickable selector, and adds a `/settings` menu.

## What each side owns

| | Proxy (Velocity) | Companion (Paper) |
|---|---|---|
| Player languages and settings | stores and caches them | never stores anything |
| Translations | every string, in `lang/` | only two, for when the proxy is unreachable |
| `/language`, `/settings` | **never registered** | chest menus |

The split is deliberate: the companion holds no state and almost no text, so the
two plugins cannot drift out of agreement about a player's language, and adding
a language means editing the proxy's `lang/` directory only — every backend
picks it up at once without being updated or restarted.

## How the proxy learns about a server

The proxy registers no `/language` of its own at all — the companion owns the
command outright, so there is no window where two implementations of the same
name shadow each other.

It still wants to know which backends are equipped, because a server *without*
the companion has no selector at all. The companion tells it:

1. The companion sends `announce` on the `katharsis:language` channel.
2. The proxy records the server and logs it:
   `'lifesteal' runs the companion plugin 0.1 and serves the language selector.`
3. The proxy replies with the name it knows that server by, and the companion
   logs `Announced to the proxy as 'lifesteal'`. A backend cannot read its own
   entry out of `velocity.toml`, so this is the only way its log can name the
   server it is actually running as.

### When the announcement is sent

The companion announces **at startup** — but a plugin message can only travel on
a player's connection, so "at startup" means as soon as there is a player to
carry it:

- Reloading the plugin on a server that already has players online announces
  immediately.
- On a cold start there is nobody to carry the message yet. The companion says
  so in its log and the first join sends it.

That is a Minecraft protocol constraint, not a design choice: there is no
backend-to-proxy transport that works with zero players connected.

The announcement then repeats on join, at most once a minute, so a proxy that
restarts learns about the server again without anyone touching the backend.

**If a backend does not run the companion, players on it have no way to change
language** — there is no proxy-side fallback any more. Install the companion on
every backend.

## Chat log

The companion reports every chat message to the proxy on the `katharsis:chat`
channel. The proxy stores it in the `katharsis_chat_messages` table and can post
it to Discord.

Set `server-id` in the companion's `config.yml` to choose which Discord channel
this server's chat goes to: it is the key looked up in the proxy's
`discord.chat-channels`. Left empty, the proxy uses the server's name from
`velocity.toml`. Messages written in that channel are shown to the players on
this server; the proxy sends them itself, and the companion only tells it the
server id when it announces. See [chat-messaging.md](chat-messaging.md) for the format and
exactly which messages are reported.

## The selector

`/language` (alias `/lang`) asks the proxy for `get_language_menu` and opens a
chest from the reply. Each language is one item, named in **its own language** —
`Čeština`, not "Czech" — because the proxy sends each entry's `language-name`
from that language's own file. Each item is paper showing that language's flag
through the resource pack, as
`/give @s paper[custom_model_data={strings:["flags_cz"]}]` would make it. The
model is `flags_` plus the code's region in lower case, so `cs_CZ` is `flags_cz`
and `en_US` is `flags_us`; a new language needs a matching model in the pack. The
player's current language has a glint.

String custom model data exists from Minecraft 1.21.4. On 1.21.1–1.21.3 servers,
and for a code with no region, the items are plain paper.

Clicking a language sends `set_language`. The proxy stores it and sends the
confirmation itself, in the newly chosen language, so the companion never
composes a translated sentence. Clicks are cancelled unconditionally and drags
are blocked, so nothing can be taken out of the menu or dropped into it.

A player joining the network for the first time gets the default language and
has the selector opened for them on the first server they reach, so they can pick
a language straight away. It is only shown once: switching servers or rejoining
does not open it again. If that first server does not run the companion, it is
not shown at all.

If the proxy does not answer within `request-timeout-seconds`, the player is told
the proxy is unreachable rather than being left with a frozen command.

## Settings

`/settings` asks the proxy for `get_settings_menu` and opens a three-row chest
with two items on the middle row:

- **Language** — the current language's flag, with its name in the lore.
  Clicking it opens the language selector above.
- **Discord chat** — lime dye with a glint while messages from the Discord chat
  channels are shown in game, grey dye while they are hidden. Clicking it sends
  `set_discord_chat` with the opposite value; once the proxy has stored it, the
  item is redrawn in place and the proxy confirms the change in chat. Clicks
  made while a change is still on its way are ignored.

Discord chat is **hidden** for every player to begin with. A new player is asked
about it on their first join: right after they pick a language in the selector
that opens by itself — or close it without picking — a second chest asks
*Show Discord chat in game?* with a green "yes" and a red "no". The question is
fetched after the new language is stored, so it is asked in that language.
Closing it without answering leaves Discord chat hidden. It is asked only once;
after that, `/settings` is the place to change it.

Every string in both menus comes from the proxy's `lang/` files, under the
`settings-*` and `discord-chat-prompt-*` keys.

## Mutes

Mutes are issued on the proxy, but only the backend can stop a player's chat:
the proxy cannot cancel a signed chat message without disconnecting the client.
The proxy tells the companion who is muted on its server over
`katharsis:punish`, and the companion cancels their chat and the commands in the
proxy's `punishments.muted-commands` before any other plugin sees them, so
muted messages never reach the chat log either. The player is told why by the
proxy, in their language.

The companion asks for everyone's state when it starts, so a reload with
players online loses no mutes, and checks each mute's end itself. A server
without the companion does not enforce mutes. See
[punishments.md](punishments.md) and
[plugin-messaging.md](plugin-messaging.md#mutes-over-katharsispunish).

## Supported versions

The same sources are built twice, once per Paper line, because the two lines
need different JDKs:

| Variant | Jar | Built against | Bytecode | `api-version` | Runs on |
|---|---|---|---|---|---|
| default | `katharsis_paper-<v>.jar` | paper-api 1.21.1 | Java 21 | `1.21` | Paper 1.21.1 – 1.21.11 |
| `-P paper-26` | `katharsis_paper-<v>-mc26.jar` | paper-api 26.1.2 | Java 25 | `26.1.2` | Paper 26.1.2 – 26.3 |

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

Order does not matter: the companion announces as soon as a player is connected,
whichever started first.

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
