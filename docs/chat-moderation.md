# Chat moderation

Every message a backend reports on the `katharsis:chat` channel passes a filter
before the proxy does anything else with it. What comes back clean is relayed to
Discord and written to the chat log exactly as before. What is held back reaches
neither: it is logged as flagged, reported to a Discord staff channel, and its
author is told.

It is off by default. Turn it on under `moderation` in `config.yml`, which
documents every key.

## What it can and cannot do

**The proxy is told about a message only after the backend has already shown it
in game.** The companion reports chat at `EventPriority.MONITOR`, once the
message has been sent. Holding one back here therefore does not un-say it to the
players who were standing there.

What it does do is keep the message out of Discord, out of the ordinary chat
log, and out of anything downstream of those; record it as flagged for
moderators to act on; and tell its author it did not go through. Blocking a
message *in game* would mean cancelling the chat event on the backend, which is
a different feature and is not this one.

## The three layers

A message goes through them in order of what they cost, and stops at the first
one that objects.

### 1. Normalization

`TextNormalizer` folds the message into two forms. Neither is ever shown to
anyone or stored; the message that reaches Discord and the database is always
the original.

Both forms drop invisible formatting characters, decompose with NFKD so
ligatures, full-width letters and accents come apart, drop the combining marks
that leaves behind, lower case, and map the Cyrillic and Greek letters drawn
like Latin ones onto the letter they imitate.

They then part company:

| Form         | Digits and punctuation                   | Separators | Used for                        |
|--------------|------------------------------------------|------------|---------------------------------|
| `normalized` | kept as themselves                        | one space  | `patterns`                      |
| `squeezed`   | leetspeak read as the letter it stands for | removed    | `words`                         |

So `Hello, World!! I have 3 apples` normalizes to `hello world i have 3 apples`
and squeezes to `helloworldiiihaveeapples`. The squeezed form is the suspicious
reading and is allowed to be nonsense — nobody sees it, and no rule is written
against `worldiii`.

### 2. Rules

`moderation.words` are literal terms matched against the squeezed form. Because
the separators are gone there, one entry catches every spelling:

```
words:
  - racist
```

catches `racist`, `RACIST`, `r a c i s t`, `r.a.c.i.s.t`, `r@c!st`, `r4c1st`,
`ráčìsť`, `ｒａｃｉｓｔ`, `ⓡⓐⓒⓘⓢⓣ`, `ra<U+200B>ci<U+200D>st` and `raaaciiisttt`.

Stretching is handled by collapsing the term's own repeated letters and letting
every letter repeat, so `nigger` compiles to `n+i+g+e+r+` and catches both
`niger` and `niiiggerrr`.

**The Scunthorpe problem.** The squeezed form has no word boundaries left, so a
short term is a substring of innocent words. `moderation.allow` is the answer: a
match that falls inside an allowed term is not a match.

```
words:
  - cunt
  - ass
allow:
  - scunthorpe
  - assassin
```

`moderation.patterns` are regular expressions used exactly as written, matched
against the normalized form — which still has single spaces, so `\b` works, and
still has digits, so an address matches — and against the message as it was
typed. The allow list does not apply to them; an expression says for itself what
it matches. One that does not compile is logged and skipped, so a bad line does
not cost the server every other rule.

### 3. OpenAI

What the rules let through is sent to OpenAI's moderation model, as the original
text: the model reads obfuscation better than a word list does, and folding
`1337` down to letters would only take context away from it.

`moderation.openai.categories` narrows what is acted on, and `score-threshold`
flags on a score even where the model did not flag the message itself.

The quota is counted **before** anything is sent, against the `requests-per-minute`,
`tokens-per-minute` and `requests-per-day` on your key, so the day's requests are
spent on messages rather than on 429s.

## When the model cannot answer

Two failures look alike and are treated as opposites on purpose.

| What happened                                        | The message | Why                                                            |
|------------------------------------------------------|-------------|----------------------------------------------------------------|
| Out of quota, counted here or a 429 from OpenAI       | **through** | The limit is reached in ordinary use; chat must not stop working every evening. |
| Timeout, connection refused, refused key, 5xx, unreadable answer | **held back** | A fault to fix rather than a state to live in, and an unchecked message is what this exists to prevent. |

`moderation.openai.timeout-ms` is 30000 by default. A real call takes well over a
second, and a timeout costs a held-back message and a player asked to try again,
so waiting is nearly always the better trade. What waits is the Discord relay and
the database write — players in game have already seen the message by then. The
connection itself still has 5 seconds, so an unreachable network fails fast
rather than holding a message for the full timeout.

Held-back messages of the second kind are recorded with the source
`unavailable`. That says no more than that nothing could judge them: they are
**not** a finding against the player, they are not reported to the staff
channel — an outage would post one for every message on the network — and their
author is told to try again rather than that they broke the rules.

A failing call writes a full warning at most once a minute, so an outage does
not bury the log under the very thing an admin needs to read.

## The debug log

The console says what happened to a message. It does not say what was sent, what
came back or how long it took, which is what tells a refused key from a slow one
from a message the model simply did not object to. That goes to
`plugins/katharsis/debug.log`, one line per OpenAI call, while `debug-log` in
`config.yml` is on — it is on by default:

```
2026-09-22 18:04:05.678 --- Katharsis started ---
2026-09-22 18:07:12.004 openai call=1 request model=omni-moderation-latest url=https://api.openai.com/v1/moderations chars=13 tokens=13 timeout=30000ms text="you are r4c1st"
2026-09-22 18:07:12.269 openai call=1 answer status=200 in=265ms outcome=FLAGGED categories=[harassment, hate] detail= body={"id":"modr-…","results":[{"flagged":true,…}]}
2026-09-22 18:09:44.117 openai call=2 skipped model=omni-moderation-latest chars=31 tokens=19 remaining-today=0 reason=out-of-quota
2026-09-22 18:11:02.882 openai call=3 answer status=401 in=88ms outcome=ERROR body={"error":{"message":"Incorrect API key provided"…
```

`call=N` pairs a request with its answer, since several messages are checked at
once and the lines interleave. The `body` and the `text` are cut off at 1000
characters, so one bad answer cannot fill the file.

**The API key is never written. The chat text is**, which makes the file as
private as the messages in it. It is rotated to `debug.log.1` past 5 MiB, and
either file can be deleted at any time, including while the proxy is running.
Set `debug-log: false` to write nothing at all.

## Where a held-back message ends up

**The proxy log**, at `WARN`, with the message itself, since an admin is the one
person who needs to see exactly what was stopped:

```
Held back a chat message from Notch (1e1f2b3c-...) on 'lifesteal' [rules: word: racist]: you are r4c1st
```

**The database**, in `katharsis_chat_messages` alongside everything else, with
`flagged`, `flag_source` (`rules`, `openai` or `unavailable`) and `flag_reason`
filled in. Flagged messages are not kept in a table of their own: a moderator
reading one player's history wants what was said and what was stopped in one
place and in order. `findByPlayer` returns both; `findFlaggedByPlayer` returns
only the held-back ones. A log written before moderation existed gains the three
columns on the next start.

**The Discord staff channel** named by `moderation.staff-channel-id`, as an
embed carrying the player, the server, what caught it and the message. The text
is escaped and notifies nobody: a message being reported to moderators is no
reason for it to be able to ping them.

**The author**, in their own language, through `chat-blocked` or
`chat-unchecked` in the language files. The warning never repeats what was
written and never names the rule that caught it — that would only tell them
which spelling to try next. Turn it off with `moderation.warn-player: false`.

## Ordering

Moderation blocks on an HTTP call, so it and everything after it run on the
event task rather than the event thread. Relaying therefore happens a little
later than it used to, and two messages sent at almost the same moment may reach
Discord in the other order. What players saw in game is unaffected, since the
backend has already shown it to them by the time any of this runs.

## Scope

Only chat reported from the backends is filtered. Messages coming the other way,
from Discord into the game, are not: see `DiscordInboundChat`.
