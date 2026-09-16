# Chat log over plugin messaging

Backend servers report every chat message to the proxy on the `katharsis:chat`
plugin message channel, and the proxy writes each one to the database. The
companion plugin sends these reports; any other backend plugin may too.

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
{"action": "player_msg", "player": "1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8", "player_name": "Notch", "content": "hello"}
```

| Field         | Type   | Notes                                                    |
|---------------|--------|----------------------------------------------------------|
| `action`      | string | Always `player_msg`. Anything else is ignored.            |
| `player`      | string | The author's uuid, canonical dashed form                  |
| `player_name` | string | The author's username, at most 64 characters              |
| `content`     | string | The message as plain text. May be empty.                  |

Field order does not matter and extra fields are ignored. A report that is not
valid JSON, has an unknown `action`, is missing a field, has a field of the
wrong type or has a malformed uuid is dropped with a warning in the proxy log.
Nothing is written for it.

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

`server` comes from the connection the report arrived on, not from the payload,
so a backend cannot claim to be another one. The table is indexed on
`(player_uuid, sent_at)` for looking up one player's history.

The log is append-only: identical messages are all kept.

If the database is unavailable, reports are dropped. The proxy logs that the
database is down once at startup, not once per message.

## The companion's reports

The companion listens to Paper's `AsyncChatEvent` at `MONITOR` priority, with
cancelled events skipped. So it reports what was actually sent to chat, after
other plugins have changed it, and nothing a mute or anti-spam plugin blocked.
Formatting is stripped to plain text.

The author's own connection carries the report, so every message can be
reported, even when only that one player is online.
