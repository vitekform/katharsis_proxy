# Language API over plugin messaging

Backend servers can read and change a player's language through the
`katharsis:language` plugin message channel. Three operations are exposed:

| Operation         | Effect                                                           |
|-------------------|------------------------------------------------------------------|
| `get_language`    | Reads the player's language.                                      |
| `set_language`    | Stores a new language for the player.                             |
| `clear_language`  | Resets the player's language to the configured `default-language`.|

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
| 1 | `operation` | UTF      | `get_language`, `set_language` or `clear_language`         |
| 2 | `requestId` | UTF      | Free-form, echoed back so you can correlate. May be empty. |
| 3 | `uuid`      | UTF      | Target player, canonical dashed form                       |
| 4 | `language`  | UTF      | **`set_language` only.** Omit it for the other operations. |

### Response

| # | Field       | Type     | Notes                                                        |
|---|-------------|----------|--------------------------------------------------------------|
| 1 | `operation` | UTF      | Echoed from the request                                       |
| 2 | `requestId` | UTF      | Echoed from the request                                       |
| 3 | `uuid`      | UTF      | Echoed from the request                                       |
| 4 | `success`   | boolean  | `true` when the operation was applied                         |
| 5 | `language`  | UTF      | Resulting language on success, empty string on failure        |
| 6 | `error`     | UTF      | Error code on failure, empty string on success                |

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

### `clear_language`

Sets the player's language back to the configured `default-language` and
refreshes the cache. The response carries that default in the `language` field.

A player with no row is already "cleared", so **no row is created for them** and
the call still reports success.

## Error codes

| Code                    | Meaning                                                      |
|-------------------------|--------------------------------------------------------------|
| `malformed_request`     | The payload was truncated or not in the format above.         |
| `unknown_operation`     | `operation` was not one of the three names.                   |
| `invalid_uuid`          | `uuid` was not a canonical UUID.                              |
| `unknown_language`      | No file for that language in `lang/`.                         |
| `unknown_player`        | No row for that player; they have never joined.               |
| `database_unavailable`  | The database is not reachable right now.                      |
| `database_error`        | The query failed. The proxy log has the stack trace.          |

## Caching

A language fetched or written through this channel is cached. For an **online**
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
