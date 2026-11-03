# Onyx Telnet Control Reference (discovered on hardware)

Discovered live against an Onyx console on port 23, 2026-09-22. Onyx exposes a
**self-documenting** line protocol — `help` returns the full command list, so
this file records what that returned plus the response formats, which are *not*
in the `help` output.

> **Why this matters:** OSC cannot jump to a specific cue and is fire-and-forget.
> Telnet can (`GTQ`), addresses cuelists by **number** (bank-independent), and
> **confirms every command**. For a show controller whose job is knowing whether
> things actually worked, the confirmation alone justifies the switch.

## Connection
Plain TCP, port 23, `\r\n`-terminated lines. Banner on connect:
```
200 Welcome
```
No authentication was required.

## Commands (verbatim from `help`)

| Command | Syntax | Meaning |
|---|---|---|
| `GQL` | `GQL #` | **Go** cuelist (# = cuelist number) |
| `GTQ` | `GTQ #,#(,#)` | **Go to cue** — cuelist number, cue number |
| `PQL` | `PQL #` | **Pause** cuelist |
| `RQL` | `RQL #` | **Release** cuelist |
| `SQL` | `SQL #,#` | **Set cuelist level** — cuelist, level 0–255 |
| `SetQLLevel` | `SetQLLevel #,#` | Same as `SQL` |
| `RAQL` | `RAQL` | Release **all** cuelists |
| `RAQLDF` | `RAQLDF` | Release all cuelists, dimmer first |
| `RAO` | `RAO` | Release all overrides |
| `RAQLO` | `RAQLO` | Release all cuelists **and** overrides |
| `RAQLODF` | `RAQLODF` | …dimmer first |
| `CLRCLR` | `CLRCLR` | Clear + Clear (clear the programmer) |
| `Help` | `help` | List commands |

## Response formats — the useful part

Success:
```
200 Ok
GOTOCUE COMMAND OK
.
```
`PQL` / `RQL` answer `222 Ok` rather than `200 Ok`; both indicate success.
Responses are terminated by a line containing a single `.`.

Target does not exist (**command was still accepted**):
```
200 Ok
GOTOCUE Cuelist Not Found
.
```

Unknown command:
```
400-Unknown Command
400 BOGUSCMD
```

**Parse the second line, not the numeric code.** `200 Ok` is returned even when
the cuelist does not exist — the code reports "I understood you", not "I did it".
Success is the literal `COMMAND OK`; failure is `... Not Found`.

## Practical notes

- **Cuelist existence probe (non-mutating):** `GTQ <n>,99999` returns
  `GOTOCUE COMMAND OK` if cuelist `<n>` exists and `Cuelist Not Found` if not.
  An out-of-range **cue** number is accepted silently, so this discovers which
  cuelists exist without a level change. ⚠️ Not fully verified as side-effect
  free — confirm on a spare cuelist before relying on it.
- **Liveness probe:** `GTQ 9999,1` is harmless (no such cuelist) and returns a
  definitive response. This is a far better heartbeat than the OSC PF-group
  scroll probe, which had to be chosen carefully to avoid corrupting the command
  line, and which only yields an *unsolicited* feedback stream rather than a
  request/response confirmation.
- **Addressing is by cuelist number**, so it is immune to the bank-dependency
  problem that makes the OSC `/Mx/button/42xx` form unsafe (those mean
  "playback N on whatever bank is currently selected").

## Division of labour with OSC

| Purpose | Use | Why |
|---|---|---|
| Firing / jumping / releasing cues | **Telnet** | direct cue addressing + per-command confirmation |
| Setting cuelist levels | **Telnet** (`SQL`) | bank-independent, confirmed |
| Console liveness | Telnet round-trip (preferred) or the OSC feedback stream | telnet confirms; OSC only infers |

*Source: `help` output from the live console. Re-run `help` after an Onyx
upgrade — this interface is undocumented publicly, so treat it as version-specific.*
