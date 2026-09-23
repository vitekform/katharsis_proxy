# Logging in

On an offline-mode proxy anyone can join under any name. The proxy therefore
makes every player without a Mojang account log in with a password before they
reach a server. Players with a Mojang account are let in through Mojang's own
login and never see a password prompt.

## Requirements

- **[LimboAPI](https://github.com/Elytrium/LimboAPI)** on the proxy. It hosts
  the empty world players log in from. Current LimboAPI builds need
  **Velocity 4.2.0 or newer**; on 3.5.x LimboAPI refuses to start.
- A working database (`database` in `config.yml`). Accounts are stored in the
  `katharsis_auth_accounts` table.

Without either one, logging in **fails closed**: players without a Mojang
account are refused at the door rather than let in unchecked, and the proxy log
says why.

## What a player sees

A player without a Mojang account joins into an empty End-sky void with no
blocks. A gold **Login** title stays on screen, with a subtitle saying which
command to use:

| | Command |
|---|---|
| First visit | `/register PASSWORD PASSWORD` |
| Returning | `/login PASSWORD` |

`/l` and `/reg` are aliases. Until the player logs in:

- Any other command, and all chat, only repeats how to get in.
- They are not connected to any server. The void lives inside the proxy, and
  the first server is picked only after login.
- They are kicked after `max-attempts` wrong passwords (default 3), or when
  `login-timeout-seconds` runs out (default 60).

Once logged in, `/login` and `/register` are answered by the proxy with "You are
already logged in". A password typed out of habit therefore never reaches a
backend, whose console would log it. Every new connection logs in again.

## Premium players

With `premium-skip` on (the default), a name the proxy has not seen before is
looked up at Mojang when the player joins:

- **Mojang knows the name:** the connection goes through Mojang's login, the
  same as an online-mode server. The player is in with no password, and the
  name is recorded as a premium account, so later joins skip the lookup.
- **Mojang does not know the name, or cannot be reached:** the player is asked
  to register.

A cracked player cannot use a name that belongs to a Mojang account. Mojang's
login fails and they are told they are not logged in to their Minecraft
account. This is deliberate: it is what stops anyone from playing as a premium
player.

A name that was **registered with a password first** stays a password account,
even if Mojang later knows it.

## Names and case

Names are unique regardless of case, but must be joined with the exact spelling
they were registered with. An offline-mode uuid is derived from the exact
spelling, so `Steve` and `steve` would otherwise be one account with two sets
of player data. Joining as `steve` when `Steve` is registered is refused with a
message naming the right spelling.

## Passwords

Passwords are hashed with **Argon2id** (Password4j) and stored in PHC format,
e.g. `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`. They are never logged.

- The default cost is OWASP's recommended minimum: 19 MiB, 2 iterations,
  1 lane. It can be raised under `auth.argon2`. Existing hashes keep working and
  are redone with the new settings the next time their owner logs in.
- At most two passwords are checked at once, with up to 64 waiting. This caps
  the memory a flood of logins can take. Beyond that, players are asked to try
  again.
- Passwords must be `min-password-length` to `max-password-length` characters
  long (8 to 64 by default), and cannot be the player's own name.

There is no password reset yet. To reset a player's password, delete their row
from `katharsis_auth_accounts` and they will be asked to register again.
