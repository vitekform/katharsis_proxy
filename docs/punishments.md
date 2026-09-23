# Punishments

Bans, mutes, kicks and their history, issued on the proxy and enforced across
the network. Everything is stored in the database (`katharsis_punishments`), so
nothing works while it is down; see `fail-closed` below for what happens to
logins then.

## Commands

Every command needs `katharsis.punish.<command>`, e.g. `katharsis.punish.ban`.
Unlike the plugin's player commands, **nobody has these until they are
granted**; without the node the command is not even suggested.

| Command | Syntax | |
|---|---|---|
| `/ban` | `<player> [flags] [reason]` | Permanent. |
| `/tempban` | `<player> <duration> [flags] [reason]` | |
| `/banip` (`/ipban`) | `<player\|ip> [duration] [flags] [reason]` | The address only. |
| `/unban` (`/pardon`) | `<player\|ip\|#id> [-g\|-s scope] [reason]` | Every active ban on the target, or only those on one scope, or one by id. |
| `/mute` | `<player> [flags] [reason]` | Permanent. |
| `/tempmute` | `<player> <duration> [flags] [reason]` | |
| `/muteip` (`/ipmute`) | `<player\|ip> [duration] [flags] [reason]` | The address only. |
| `/unmute` | as `/unban` | |
| `/warn` | `<player> [flags] [#template] [reason]` | Lasts `warns.expire-after`. |
| `/unwarn` (`/delwarn`) | `<player\|#id> [reason]` | The newest active warning, or one by id. |
| `/warns` | `<player>` | Active warnings, and what the next one adds up to. |
| `/punish` | `<player> <#template> [flags] [details]` | Whatever the template gives. |
| `/kick` | `<player> [-q] [reason]` | Online players only; kept in the history. |
| `/history` | `<player\|ip> [page]` | Everything ever issued, newest first. |
| `/check` (`/lookup`) | `<player\|ip>` | What is in force now, and when and from where the player was last seen. |
| `/alts` | `<player>` | Other accounts that played from the player's addresses recently, and whether they are banned or muted. |

Durations combine `y`, `mo` (30 days), `w`, `d`, `h`, `m` and `s`, e.g. `30m`,
`7d`, `1mo2w`. `perm` means for good.

### Flags

Flags may stand anywhere after the command.

| Flag | |
|---|---|
| `-g` | On the whole network. Needs `katharsis.punish.global`. |
| `-s <name>` | On one server (its `velocity.toml` name) or server group. |
| `-a` | The account only, not its address. |
| `-i` | The address only, not the account. |
| `-q` | Quietly: staff are not told. |
| `-f` | Replace an active punishment of the same type on the same scope instead of refusing; also accept a name nobody has joined under, on an offline-mode proxy. |

### Other permissions

| Node | |
|---|---|
| `katharsis.punish.global` | Punish on the whole network. The console always may. |
| `katharsis.punish.notify` | Be told about every punishment issued and lifted. |
| `katharsis.punish.exempt` | Cannot be punished, and is not caught by bans on an address they share. |
| `katharsis.punish.seeip` | See addresses in `/check`, `/alts` and staff messages. |
| `katharsis.punish.override` | Punish staff at or above one's own rank. |

### Ranks

With [LuckPerms](https://luckperms.net), staff can be ranked with the meta value
`katharsis-punish-weight`:

```
/lp group mod meta set katharsis-punish-weight 10
/lp group admin meta set katharsis-punish-weight 50
```

Staff cannot punish anyone whose weight is at or above their own, unless they
hold `katharsis.punish.override`. A player without the meta weighs nothing, so
until weights are set up anyone may punish anyone not exempt. The console always
may.

LuckPerms is optional. With it, the permissions of offline players are known:
an offline admin is still exempt, and Discord staff need not be online. Without
it, only players online can be asked about: offline players are never exempt and
have no rank.

## Scope

Each punishment applies somewhere:

- **the whole network** (`-g`): a banned player cannot join the proxy at all;
- **one server** (`-s lobby`);
- **a group of servers** (`-s survival`), from `punishments.server-groups`.
  Groups are looked up whenever a punishment is checked, so adding a server to
  a group extends the bans already issued on it.

Without a flag, a staff member in game punishes on the server they are on, or
on its group with `default-scope-uses-group`; the console and Discord punish on
`console-default-scope`.

## Bans

A `/ban` holds the account **and the address it last joined from**, so a new
account on the same connection is kept out too (`ban-ip-by-default`). `-a`
bans only the account; `/banip` or `-i` only the address. Addresses listed in
`trusted-proxy-ips` are never banned.

A network-wide ban is checked twice:

1. **Before login**, by account for offline-mode names. This runs before the
   login system, so a banned player costs no Mojang lookup and never reaches the
   login void. Premium names are left to the next check, since Mojang lets names
   change hands; so are bans on addresses, since exempt staff are only known then.
2. **At login**, by account and address. The punishments in force against the
   player are loaded then for the rest of their session, so switching servers
   never waits on the database.

The player sees the reason, how long is left, who banned them, the ban id, and
`appeal-url` if set, in their own language.

A ban on a server or group keeps the player off those servers:

- joining the network, they are sent to the first server in Velocity's `try`
  list they may join and told why; with none left, they are disconnected;
- switching to such a server is refused, and they stay where they are;
- a kick never redirects them to one;
- a new server ban moves anyone on those servers elsewhere at once.

## Mutes

A muted player cannot chat, or use the commands in `muted-commands`, on the
servers the mute covers. The proxy cannot block chat itself, so this is done by
the **companion plugin** on each backend (see
[plugin-messaging.md](plugin-messaging.md#mutes-over-katharsispunish)). On a
server without the companion, a muted player can still chat; staff muting a
player on such a server are warned. The proxy's own commands in the list are
blocked on the proxy.

Mutes hold the account only, unless `mute-ip-by-default` is on; `/muteip` or
`-i` mutes only the address.

A muted player who tries to speak is told why and for how long. They are told
when they are muted, and when the mute is lifted or runs out.

## Templates

A template is a punishment given by name, configured under
`punishments.templates`:

```yaml
templates:
  hacking:
    type: ban
    reason: "Cheating"
    scope: global
    ladder: [7d, 30d, perm]
```

`/ban Steve #hacking` bans Steve for a week the first time, a month the
second, and for good after that; `/punish Steve #hacking` does the same without
naming the type. Only earlier punishments from the same template count, and
lifted ones do not, so an appeal won resets the ladder. A template's `scope` is
used unless the command names one; anything typed after the template is added
to the reason. `/ban` only takes ban templates, `/mute` mute templates and
`/warn` warning templates.

## Warnings and escalation

A warning is a recorded telling-off that counts for `warns.expire-after`. When
a player's active warnings reach a count in `warns.escalation`, they are given
that step's punishment automatically, issued by "Escalation" and linked to the
warning that triggered it:

```yaml
warns:
  expire-after: 30d
  escalation:
    - { count: 3, type: mute, duration: 1h }
    - { count: 5, template: hacking }
```

A step fires when the count lands on it, so the warnings after it do not give
the same punishment again. Escalations apply on the warning's own scope unless
the step names one, and hold the account only, never the address.

### Automatic warnings

With `automod.enabled`, chat the moderation filters held back (see
[chat-moderation.md](chat-moderation.md)) earns its author a warning, or
`automod.template` if set, on the server it was written on. It counts towards
escalation like any other. A player gets at most one per `automod.cooldown`,
and a message that could not be checked at all is never held against them.

## Alts and ban evasion

Every session's address is recorded once the player reaches a server (so
never for someone who failed a password account's login). Two accounts
**share an address** when both used it within `alts.lookback`; addresses in
`trusted-proxy-ips` never count. `/alts` lists them, with the addresses only for
staff with `katharsis.punish.seeip`.

When a player joins from an address shared with an account under a
network-wide ban, `alts.evasion` decides:

| Value | |
|---|---|
| `ignore` | Nothing. |
| `alert` | Staff with `katharsis.punish.notify` are told. The default. |
| `ban` | Staff are told, and the new account is banned on the whole network for as long as the original ban has left, issued by "Evasion" and linked to it. |

A shared address is a hint, not proof: households, schools and mobile networks
put strangers on one address. Players exempt from punishment are not looked at.

With `alts.join-alerts`, staff are also told when a player joins with at least
`join-alert-min-warns` active warnings, or with banned accounts on their
addresses.

## Discord

With the Discord bot running (see the `discord` section of `config.yml`):

- **The punishment log.** Every punishment issued and lifted is posted to
  `punishments.discord.log-channel-id`, or to `moderation.staff-channel-id` if
  that is not set: who, where, how long (counting down in each reader's time
  zone), why, by whom, and the template or punishment it came from. Addresses
  are hidden unless `log-ips` is on.
- **`/punish`**, with `commands-enabled`: `ban`, `mute`, `warn`, `kick`,
  `unban`, `unmute`, `unwarn`, `history` and `check`, working as in game.
  Using it takes all of:
  1. one of the roles in `staff-role-ids`;
  2. a Minecraft account linked with `/link`;
  3. that account holding the same permission the command needs in game, e.g.
     `katharsis.punish.ban`, `katharsis.punish.tempban` with a duration, and
     `katharsis.punish.global` to punish on the whole network. Without
     LuckPerms this is only known while they are online in game.

  What they issue is recorded as issued by their Minecraft account, and ranks
  apply as in game. Without a `scope`, punishments apply on
  `console-default-scope`. Replies are only shown to whoever asked. The command
  is hidden from members without Moderate Members by default; Discord's server
  settings can change who sees it, but not who may use it.

## When the database is down

Nothing can be issued or checked. With `fail-closed: false` (the default),
players are let in unchecked and it is logged; with `true` they are turned away
until it is back. `/kick` still disconnects the player, but the kick is not
recorded.

## Limits

- **One proxy.** Punishments are cached per proxy; on a second proxy, a new
  punishment only takes effect at the player's next login there.
- **Addresses.** Address bans need the players' real addresses. Behind a reverse
  proxy or TCPShield, set up proxy-protocol forwarding, or every player appears
  to join from the same address; list that address in `trusted-proxy-ips`
  meanwhile. Players sharing a network (a school, a household, mobile carriers)
  share an address too.
