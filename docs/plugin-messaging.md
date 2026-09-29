# Language API over plugin messaging

Backend servers can read and change a player's language through the
`katharsis:language` plugin message channel, and the companion plugin reads
and changes the rest of a player's settings through it too. These operations are
exposed:

| Operation           | Effect                                                            |
|---------------------|-------------------------------------------------------------------|
| `get_language`      | Reads the player's language.                                       |
| `set_language`      | Stores a new language for the player.                              |
| `clear_language`    | Resets the player's language to the configured `default-language`. |
| `get_language_menu` | Everything needed to draw a language selector, already translated. |
| `get_settings_menu` | Everything needed to draw `/settings` and the Discord chat question. |
| `set_discord_chat`  | Shows or hides the Discord chat channels in game for the player.   |
| `announce`          | Declares that this backend runs the companion plugin.              |

Every request gets exactly one response on the same channel.

## Who may call it

**Only backend servers.** A plugin message that arrives from a *client* on this
channel is dropped without a reply, because a modified client could otherwise
rewrite any player's preferences.

Messages on this channel are never forwarded to the other side of the
connection, so they will not reach the player.

## Encoding

Standard Minecraft plugin messages. Every field is a `DataOutput#writeUTF`
string except `success`, which is a single `boolean` byte. Read the fields back
in the order given — there is no framing beyond the field order.

### Request

| # | Field       | Type     | Notes                                                     |
|---|-------------|----------|-----------------------------------------------------------|
| 1 | `operation` | UTF      | One of the operations above                                |
| 2 | `requestId` | UTF      | Free-form, echoed back so you can correlate. May be empty. |
| 3 | `uuid`      | UTF      | Target player, canonical dashed form                       |
| 4 | `payload`   | UTF      | Present for **`set_language`** (the language code), **`set_discord_chat`** (`true` or `false`) and **`announce`** (the sender's version). Omit it for every other operation. |

### Response

| # | Field       | Type     | Notes                                                        |
|---|-------------|----------|--------------------------------------------------------------|
| 1 | `operation` | UTF      | Echoed from the request                                       |
| 2 | `requestId` | UTF      | Echoed from the request                                       |
| 3 | `uuid`      | UTF      | Echoed from the request                                       |
| 4 | `success`   | boolean  | `true` when the operation was applied                         |
| 5 | `language`  | UTF      | Resulting language on success, empty string on failure        |
| 6 | `error`     | UTF      | Error code on failure, empty string on success                |

A successful `get_language_menu` reply appends these fields after `error`:

| # | Field         | Type    | Notes                                                  |
|---|---------------|---------|--------------------------------------------------------|
| 7 | `title`       | UTF     | Menu title, a MiniMessage template                      |
| 8 | `selectHint`  | UTF     | Lore for a language the player may pick                 |
| 9 | `currentHint` | UTF     | Lore for the language the player already uses           |
| 10| `count`       | int     | How many languages follow                               |
| 11| *entries*     | UTF × 2 | `count` pairs of language code and its own display name |

A successful `get_settings_menu` reply appends these fields after `error`. Every
UTF field is a MiniMessage template:

| # | Field                | Type    | Notes                                                    |
|---|----------------------|---------|----------------------------------------------------------|
| 7 | `title`              | UTF     | Title of `/settings`                                      |
| 8 | `languageLabel`      | UTF     | Name of the item that opens the language selector         |
| 9 | `languageHint`       | UTF     | Its lore, with a `<language>` placeholder                  |
| 10| `languageName`       | UTF     | The current language's own name, for that placeholder      |
| 11| `discordChat`        | boolean | Whether Discord chat is shown to the player               |
| 12| `discordChatLabel`   | UTF     | Name of the item that shows or hides Discord chat         |
| 13| `discordChatOnHint`  | UTF     | Its lore while Discord chat is shown                      |
| 14| `discordChatOffHint` | UTF     | Its lore while Discord chat is hidden                     |
| 15| `promptTitle`        | UTF     | Title of the first-join Discord chat question             |
| 16| `promptYes`          | UTF     | The answer that shows Discord chat                        |
| 17| `promptNo`           | UTF     | The answer that keeps it hidden                           |

No other operation appends anything, so a reader that only handles the six base
fields keeps working.

When a request cannot be decoded at all, fields 1–3 come back as empty strings
and `error` is `malformed_request`.

## Operation semantics

### `get_language`

Answers from the in-memory cache whenever the entry is still warm — an online
player, or one who disconnected within the retention window — and otherwise
reads the row. A player who has never joined — so has no row —
returns `success = false` with `unknown_player` rather than the default
language, so you can tell "unknown" apart from "using the default".

### `set_language`

The code is normalised first, so `EN-us`, `en-US` and `en_US` are the same
request. The language must have a file in the proxy's `lang/` directory;
anything else is rejected with `unknown_language` and nothing is written. If the
player has no row yet, one is created.

The change takes effect immediately — the player's next message from the proxy
uses the new language, without a reconnect.

If the player is online, **the proxy sends them the confirmation message itself**,
in their new language. A caller does not need to say anything, and should not:
the proxy owns the translated strings. Nothing is sent for an offline player.

### `get_language_menu`

Returns the player's current language in the `language` field, plus the menu's
title and lore **already translated into that language**, and every available
language paired with its own name — `cs_CZ` comes back as `Čeština`, not as
"Czech". A caller can therefore draw a complete selector without shipping a
single translated string of its own.

A player who has never chosen a language is not an error here: the reply
succeeds and reports the configured default, so the menu still opens.

### `get_settings_menu`

Returns the player's current language in the `language` field, whether Discord
chat is shown to them, and every string the companion's `/settings` menu and its
first-join Discord chat question need, **already translated into that
language**. Like `get_language_menu`, a player who has never joined is not an
error: the reply succeeds with the default language and Discord chat hidden.

### `set_discord_chat`

Shows (`true`) or hides (`false`) the messages from the Discord chat channels in
game for the player; anything else is `malformed_request` and nothing is
written. It is stored in the player's row, which is created if they have none,
and takes effect for the very next Discord message. The `language` field of the
reply carries the player's language.

If the player is online, the proxy confirms the change to them itself, in their
language, just as for `set_language`.

### `announce`

Tells the proxy that this backend runs the companion plugin. The proxy records
the server and logs it; nothing else changes, because the proxy registers no
`/language` of its own in the first place.

The `uuid` field is ignored — no player is involved — but it must still be
present; send the nil UUID. The fourth field carries the sender's version and
appears in the proxy log.

An optional fifth UTF field carries the backend's server id, the key the proxy
looks up in `discord.chat-channels`. The proxy then delivers messages from that
Discord channel to this backend. Blank means the backend's `velocity.toml` name;
leaving the field out changes nothing, so older companions keep working.

A successful reply appends one field after `error`:

| # | Field        | Type | Notes                                          |
|---|--------------|------|------------------------------------------------|
| 7 | `serverName` | UTF  | The name the proxy knows this backend by        |

A backend cannot read its own entry out of `velocity.toml`, so this is how it
learns which server it is running as.

Announcing is idempotent: repeat it as often as you like. A backend can only
announce once a player is connected to it, since a plugin message needs a player
to travel on.

### `open_language_menu` (proxy to backend)

The one message the proxy sends without being asked. When a player joins the
network for the first time, and is given the default language, the proxy sends
it to the first server they connect to, on that player's connection. It is laid
out as a successful response with an empty `requestId`, the player's `uuid`,
empty `language` and `error`, and no extra fields, so it decodes with the same
reader as every reply.

The companion answers by opening the selector for that player, a second after
it arrives so the client's loading screen does not close it, and asks about
Discord chat once they have picked a language or closed the selector. A backend
without the companion ignores it, and the player keeps the default language and
Discord chat hidden.

### `clear_language`

Sets the player's language back to the configured `default-language` and
refreshes the cache. The response carries that default in the `language` field,
and an online player is told, exactly as for `set_language`.

A player with no row is already "cleared", so **no row is created for them** and
the call still reports success.

## Error codes

| Code                    | Meaning                                                      |
|-------------------------|--------------------------------------------------------------|
| `malformed_request`     | The payload was truncated or not in the format above.         |
| `unknown_operation`     | `operation` was not one of the names above.                   |
| `invalid_uuid`          | `uuid` was not a canonical UUID.                              |
| `unknown_language`      | No file for that language in `lang/`.                         |
| `unknown_player`        | No row for that player; they have never joined.               |
| `database_unavailable`  | The database is not reachable right now.                      |
| `database_error`        | The query failed. The proxy log has the stack trace.          |

## Caching

A language or Discord chat setting fetched or written through this channel is
cached. For an **online**
player the entry is pinned until they disconnect; for an **offline** player it is
given the normal ten-minute retention window instead, so querying players who
never join cannot grow the cache without bound.

## Example: Paper / Spigot

Register the channel in both directions, then send through any online player —
the message is a proxy request, so the carrier does not have to be the player
you are asking about.

```java
public final class LanguageClient extends JavaPlugin implements PluginMessageListener {

    private static final String CHANNEL = "katharsis:language";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
    }

    /** Asks the proxy for a player's language. The reply arrives in onPluginMessageReceived. */
    public void requestLanguage(Player carrier, UUID target, String requestId) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("get_language");
        out.writeUTF(requestId);
        out.writeUTF(target.toString());
        carrier.sendPluginMessage(this, CHANNEL, out.toByteArray());
    }

    public void setLanguage(Player carrier, UUID target, String language, String requestId) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("set_language");
        out.writeUTF(requestId);
        out.writeUTF(target.toString());
        out.writeUTF(language);
        carrier.sendPluginMessage(this, CHANNEL, out.toByteArray());
    }

    public void clearLanguage(Player carrier, UUID target, String requestId) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("clear_language");
        out.writeUTF(requestId);
        out.writeUTF(target.toString());
        carrier.sendPluginMessage(this, CHANNEL, out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String operation = in.readUTF();
        String requestId = in.readUTF();
        String uuid = in.readUTF();
        boolean success = in.readBoolean();
        String language = in.readUTF();
        String error = in.readUTF();

        if (!success) {
            getLogger().warning(operation + " (" + requestId + ") failed: " + error);
            return;
        }
        getLogger().info(uuid + " uses " + language);
    }
}
```

`ByteArrayDataOutput` and `ByteStreams` come from Guava, which Paper ships.

## Notes

- **Responses are asynchronous.** The proxy answers after it has talked to the
  database, and the reply arrives on a network thread. Match it to your request
  with `requestId`, and hop back onto the main thread before touching the world.
- **`requestId` is opaque to the proxy.** It is echoed back unchanged; use a
  counter, a UUID, or leave it empty if you do not need correlation.
- **A carrier player must be online** on the backend server to send a plugin
  message at all. That is a Minecraft protocol constraint, not a choice of this
  API.
- The channel is served even when the database is down, so you get an explicit
  `database_unavailable` instead of a silent timeout.

# Mutes over `katharsis:punish`

The proxy cannot stop a player's chat itself: cancelling a signed chat message
on its way through disconnects the client. So mutes are enforced by the
companion plugin, and the proxy tells it who is muted on its server over a
second channel, `katharsis:punish`. See [punishments.md](punishments.md) for the
mutes themselves.

| Operation       | Direction        | Meaning                                                    |
|-----------------|------------------|------------------------------------------------------------|
| `mute_state`    | proxy to backend | Whether the player is muted on this server, and what that blocks. |
| `mute_query`    | backend to proxy | Send me this player's `mute_state`.                         |
| `muted_attempt` | backend to proxy | A muted player tried to chat or use a blocked command.     |

Like `katharsis:language`, it is only served to backend servers, messages from
clients are dropped, and nothing on it is forwarded to the other side. Every
message travels on the connection of the player it is about; one naming any
other player is ignored.

## Encoding

Every message starts with two `DataOutput#writeUTF` strings: the `operation`
and the player's `uuid` in canonical dashed form. `mute_query` and
`muted_attempt` carry nothing more. `mute_state` goes on:

| # | Field          | Type                 | Notes                                                    |
|---|----------------|----------------------|----------------------------------------------------------|
| 3 | `muted`        | boolean              | The rest is only present when `true`.                    |
| 4 | `expiresAt`    | long                 | Epoch milliseconds; `-1` for a mute that never ends.      |
| 5 | `punishmentId` | long                 | The mute's id, as `/history` shows it.                   |
| 6 | `commandCount` | int                  | How many commands follow.                                |
| 7 | `commands`     | UTF × `commandCount` | Lower-case labels without the slash, e.g. `msg`. `ns:msg` is blocked by `msg` too. |

There is no `requestId`: a `mute_state` is the same whether it was asked for or
pushed.

## When `mute_state` is sent

- whenever a player reaches a server;
- when they are muted or unmuted, or their mute runs out, while online;
- in answer to `mute_query`, which the companion sends for each player as they
  join and for everyone online when it starts, so a reload loses nothing.

The proxy works out which mute applies from the `velocity.toml` name of the
server the connection is to, so a backend only ever learns "muted here or not".
The companion checks `expiresAt` itself, so a mute ends on time even if the
proxy's `mute_state` saying so arrives late. A backend that has not been told
anything treats the player as not muted.

## `muted_attempt`

The companion cancels the message or command and sends this; the proxy then
tells the player, in their own language, why and for how long, at most once
every three seconds. That keeps every translated string on the proxy.

# Logins over `katharsis:auth`

The Katharsis NeoForge mod holds every player who joins its server in place
until the proxy says they have logged in; see
[authentication.md](authentication.md#modded-servers). This channel carries
that conversation.

| Operation | Direction        | Meaning                                                              |
|-----------|------------------|----------------------------------------------------------------------|
| `locked`  | backend to proxy | The player has joined and can do nothing until unlocked.             |
| `unlock`  | proxy to backend | The player has logged in; let them play.                             |

Like the other channels, messages from clients are dropped and nothing on it is
forwarded to the other side. That is what stops a client from unlocking itself.
Every message travels on the connection of the player it is about; one naming
any other player is ignored on both sides.

## Encoding

Two `DataOutput#writeUTF` strings: the `operation` and the player's `uuid` in
canonical dashed form. `locked` goes on with one more:

| # | Field      | Type | Notes                                                                 |
|---|------------|------|-----------------------------------------------------------------------|
| 3 | `protocol` | UTF  | The mod's protocol, currently `1`. The proxy logs a mismatch.          |

## When they are sent

- The mod sends `locked` once per connection, as the player enters the world.
- The proxy answers at once with `unlock` if the player has already logged in:
  a premium player, or anyone moving here from another server.
- Otherwise the proxy answers once they log in on this server. It never
  answers for a player who has not.

The mod registers the channel as optional, so clients do not need the mod. It
takes `unlock` off the connection before the server or any other mod sees it.
