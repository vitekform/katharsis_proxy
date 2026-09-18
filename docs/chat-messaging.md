# Chat log over plugin messaging

Backend servers report every chat message to the proxy on the `katharsis:chat`
plugin message channel. The proxy writes each one to the database and, when a
channel is configured for the server, posts it to Discord. The companion plugin
sends these reports; any other backend plugin may too.

The channel is **one-way**: the proxy never replies, so a sender does not wait
for anything.

## Who may send

**Only backend servers.** A message that arrives from a *client* on this channel
is dropped, because a modified client could otherwise put words in anyone's
mouth. Messages on this channel are never forwarded to the other side of the
connection, so a player never receives a report as a plugin message.

## Encoding

Each plugin message is a single JSON object, encoded as UTF-8 bytes. The bytes
are the whole payload: no `writeUTF` length prefix, no other framing.

```json
{"action": "player_msg", "server": "lobby", "player": "1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8", "player_name": "Notch", "content": "hello"}
```

| Field         | Type   | Notes                                                    |
|---------------|--------|----------------------------------------------------------|
| `action`      | string | Always `player_msg`. Anything else is ignored.            |
| `server`      | string | Optional. The sender's server id, which picks its Discord channel. Absent or blank means the backend's name in `velocity.toml`. |
| `player`      | string | The author's uuid, canonical dashed form                  |
| `player_name` | string | The author's username, at most 64 characters              |
| `content`     | string | The message as plain text. May be empty.                  |

Field order does not matter and extra fields are ignored. A report that is not
valid JSON, has an unknown `action`, is missing a field, has a field of the
wrong type or has a malformed uuid is dropped with a warning in the proxy log.
Nothing is written or posted for it. A `server` that is present but not a
string counts as a field of the wrong type.

## What gets stored

Each report becomes one row in `katharsis_chat_messages`:

| Column        | Type                     | Source                                        |
|---------------|--------------------------|-----------------------------------------------|
| `id`          | `BIGINT` identity        | Assigned by the database                       |
| `player_uuid` | `UUID`                   | `player`                                       |
| `player_name` | `VARCHAR(64)`            | `player_name`, as it was when they wrote it    |
| `server`      | `VARCHAR(255)`           | The sending backend's name in `velocity.toml`  |
| `content`     | `TEXT`                   | `content`                                      |
| `sent_at`     | `TIMESTAMP WITH TIME ZONE` | When the proxy received the report, in UTC   |

The `server` column comes from the connection the report arrived on, not from
the payload's `server` field, so a backend cannot write another one's name into
the log. The table is indexed on
`(player_uuid, sent_at)` for looking up one player's history.

The log is append-only: identical messages are all kept.

If the database is unavailable, reports are dropped. The proxy logs that the
database is down once at startup, not once per message.

## Posting to Discord

When the proxy's Discord bot is on, each report is also posted to the channel
that `discord.chat-channels` in the proxy's `config.yml` maps its server id to:

```yaml
discord:
  enabled: true
  token: '...'
  chat-channels:
    lobby: 123456789012345678
```

The server id is the report's `server` field, or the backend's `velocity.toml`
name when it has none. Servers that are not listed are not posted.

A message appears as `**Notch:** hello`. Discord formatting in the name and the
message is escaped, so it shows exactly as typed in game, and line breaks become
spaces. Mentions such as `@everyone` are shown but notify nobody. Anything past
Discord's 2000 characters is cut off.

Posting does not depend on the database: chat still reaches Discord while the
database is down. Messages are posted in order, one at a time, on the bot's own
thread, so a busy chat can fall behind Discord's rate limits. Once a thousand
posts are waiting, new ones are dropped until the bot catches up.

## Messages from Discord

The other direction: what members write in a chat channel is shown in game to
every player on the servers whose id `discord.chat-channels` maps to that
channel. Several ids may share one channel, and all of their players see its
messages. The proxy sends the message to each player itself, so it needs no
plugin message and works on backends without the companion.

A message appears as `[Discord] Notch: hello`, from the `discord-chat-message`
entry in the player's language file. The name is the author's **linked Minecraft
name**: the chat channels should only be visible to linked members. For anyone
unlinked who gets in anyway, or while the database is down, their Discord display
name is used instead.

- Messages from bots, this one included, and from webhooks are ignored, so chat
  posted from the game never comes back into it.
- Only ordinary messages and replies are shown; joins, pins and boosts are not.
- User mentions show as `@name`, roles as `@role`, channels as `#channel`,
  custom emoji as `:name:`, and attachments and stickers as `[file name]`.
- The text is shown as written: MiniMessage tags in it are not applied. Line
  breaks become spaces, and anything past 256 characters is cut off.

This needs the bot's **Message Content** intent, a privileged intent: switch it
on in the Discord developer portal under Applications > Bot > Privileged Gateway
Intents. With `discord.relay-to-minecraft: true` (the default) and the intent off
in the portal, Discord refuses the login and the whole bot, `/link` included,
stays offline. Set `relay-to-minecraft: false` to post chat to Discord only.

### Which backends a server id means

A backend is known by its `velocity.toml` name until it reports another id. The
companion reports its `server-id` when it announces itself (on the first join
and at most once a minute after), and with every chat report, so a proxy that
restarts relearns it as soon as someone joins.

## The companion's reports

The companion listens to Paper's `AsyncChatEvent` at `MONITOR` priority, with
cancelled events skipped. So it reports what was actually sent to chat, after
other plugins have changed it, and nothing a mute or anti-spam plugin blocked.
Formatting is stripped to plain text.

The author's own connection carries the report, so every message can be
reported, even when only that one player is online.
