# Linking Minecraft and Discord accounts

Players link their Minecraft account to their Discord account in two steps:

1. On Discord, they run `/link`. The bot replies privately with a one-time code,
   such as `K7QM2XPD`, valid for **6 hours**.
2. In game, they run `/link K7QM2XPD`. The accounts are linked and the bot gives
   them the linked role on Discord.

Links are permanent: each Minecraft account and each Discord account can be
linked exactly once, and there is no unlinking.

## Setup

In the proxy's `config.yml`:

```yaml
discord:
  enabled: true
  token: '...'
  guild-id: 111111111111111111
  linked-role-id: 222222222222222222
```

- `guild-id` is the Discord server `/link` is registered in. Without it the
  command does not exist on Discord, so no codes can be issued.
- `linked-role-id` is the role granted on linking; `0` grants none. The bot needs
  the **Manage Roles** permission, and its highest role must sit above this one.

The bot registers `/link` in that server each time the proxy starts. Registering
under the same name updates the existing command rather than adding another.

## Codes

- Eight characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, which leaves out
  look-alikes such as `0`/`O` and `1`/`I`. Case, spaces and dashes are ignored
  when typed.
- One outstanding code per Discord user: asking again replaces the old one.
- A code is used up only when it actually links the accounts. A player who is
  already linked gets an error, and the code keeps working for someone else.
- Codes are stored in the database, so a proxy restart does not invalidate them.
- In game, `/link` can be tried once every 3 seconds per player, which makes
  guessing codes hopeless.

If a Discord user who is already linked runs `/link` again, they are told which
player they are linked to and the role is granted again, which repairs a role
that was removed or could not be granted at the time.

## Storage

`katharsis_account_links` holds the links:

| Column             | Type                       | Notes                                   |
|--------------------|----------------------------|-----------------------------------------|
| `player_uuid`      | `UUID` primary key         | The Minecraft account                   |
| `player_name`      | `VARCHAR(64)`              | Username when they linked                |
| `discord_user_id`  | `BIGINT` unique            | The Discord account                      |
| `discord_username` | `VARCHAR(64)`              | Discord username when the code was made  |
| `linked_at`        | `TIMESTAMP WITH TIME ZONE` | When the link was made, in UTC           |

`katharsis_link_codes` holds outstanding codes, one row per Discord user.
Expired codes are deleted whenever a new code is issued.

Redeeming a code happens in a single transaction, so two players racing for the
same code cannot both link.

## Cache

Each player's link, or the fact that they have none, is loaded when they join
and kept in memory while they are online and for **10 minutes** after they
leave. Linking in game updates the cache straight away.

## Permissions

`katharsis.command.link` controls the in-game command. Everyone may use it unless
the node is explicitly denied.

## When things are down

- **Database down:** `/link` in game and on Discord replies that linking is
  unavailable. Nothing is linked.
- **Bot off or not logged in:** codes cannot be issued. A code issued earlier
  still links in game, but no role is granted; the player can run `/link` on
  Discord later to receive it.
- **Role grant fails** (missing permission, role above the bot's, member left
  the server): the link is still made, and the failure is logged.
