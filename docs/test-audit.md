# Test audit

What Waystation promises, and the tests that prove it. Newest first.

## 2026-11-09: the arrival flow (0.5.0, branch arrival-flow)

Covers what the arrival flow adds, and the first run of the older suites since there was no room for a real copy.

| Promise | Proved by |
| --- | --- |
| The lobby world's return gate stands by the lobby, not by the world's spawn, with the lobby label, leading to the station | `arrival`, without Latchkey (config lobby) and with Latchkey (its lobby). `ArrivalTest` |
| Latchkey's lobby wins over Waystation's own setting, and Latchkey stays optional, read by name | `arrival`: "found Latchkey's lobby by name", "gives way to it"; the run without Latchkey |
| When the lobby moves (config and reload, or Latchkey's lobby and the next arrival), the gate is placed again by the new spot | `arrival`, both runs. `ArrivalTest.whenTheLobbyGateIsPlacedAgain` |
| A gate placed as an ordinary return gate before the lobby was known is replaced | `ArrivalTest`. This was a real bug: a removed gate was never placed again until its world's "done" mark was cleared |
| The station welcome comes on a first visit, counts what that player can reach, and doesn't repeat on a quick return or a re-login | `arrival`. The 30-day gap: `ArrivalTest.aWelcomeIsDueOnTheFirstVisitAndAfterTheGap` |
| The Departures Board: one per player in the station, listing only the worlds that player can use, under their platform | `arrival`: two players with different access |
| A new grant shows on the board within seconds, and leaving the station takes your board away | `arrival`: grant, then a console count of board entities, 2 then 1 |
| Board text: headings in order, an empty board says so in words, platform names can't inject formatting | `DeparturesBoardTest` |
| What per-player data is kept, and that none is shown to other players | `docs/decisions.md`, 2026-11-09 |

`arrival` passes 20/20 on Paper 26.1.2, 26.2 and 26.3, with and without Latchkey 0.3.0. Unit tests: 26.

### The older suites, on a small made-up copy

`test/prepare-small.mjs` builds a 290 MB stand-in instead of copying the real server (62 GB). It has the same world,
inventory-group and LuckPerms group names, all flat and empty. Results on Paper 26.1.2:

- `station`: 51/51.
- `acceptance`: 147/161, with the OneBlock section skipped.

What still needs the real copy, or something the stand-in can't have:

- **Blocked on Vault (owner step N58):** "Buying a gate", 5 checks (`can't afford`, `buying works`, `charged exactly`,
  `the gate now lets them through`, `grant fails: full refund`). Also the test hook plugin, which needs Vault, so
  "plugin event hook vetoes the gate". The `economy` and `handover` suites need Vault too.
- **Needs a OneBlock plugin:** the whole OneBlock section, 7 checks. It's skipped on the stand-in with a line saying so.
- **Needs the real server's set-up:**
  - "creative: vanilla /tp still works" and "nether portals still work" depend on the real server's creative permissions and portal plugins;
  - "admins keep /mvtp" and "can still send someone else" depend on its admin groups;
  - "world gate is a BlueMap marker" needs BlueMap's first render, which downloads Minecraft's client files.
- **Not settled yet:**
  - "chest opened for the crash test" and "after kill -9: the worn piece is still worn" failed on the stand-in. They come
    right after the skipped OneBlock section, so they may depend on its state. Rerun them on the real copy before reading
    anything into them.
  - "op can't break ground at a world gate" passed in one stand-in run and failed in the next, so it's flaky on flat ground.

Each of these runs on the real copy once there's room and the owner says so. The stand-in is for the everyday runs.

## 2026-11-09: the hand-over (0.4.0)

This pass covers only what 0.4.0 added. A full audit of the rest of the repo is still due.

| Promise | Proved by |
| --- | --- |
| A `hand-over` world gets a station gate and no return gate | `handover`: gates. Control: an ordinary world still gets its return gate |
| Walking through fires `WaystationHandOverEvent`, once, with the right gate and world, unhandled at first, with the inventory screen closed | `handover`: walking through |
| Unhandled: the player stays, is told the world isn't open, the console says why, and nothing in their inventory changes | `handover`: walking through. Control: the same walk with a listener that handles it |
| Handled: the listener moves the player, with no "isn't open" message | `handover`: walking through |
| `landing()` is two blocks in front of the station gate, facing away from it | `handover`: landing() |
| `landing()` is empty for a world with no gate, and for the station itself | `handover`: landing() |
| Standing on the landing spot doesn't trigger the gate | `handover`: landing() |
| A death in the hand-over world can respawn at `landing()`, with a spawn point set there, and the two inventory groups stay apart both ways | `handover`: a death in the hand-over world |
| `hand-over` with `enter-command` is refused with a reason, and the running settings stay | `handover`: config |

### Gaps

- **Walking through on 26.3.** Bots behind ViaVersion are disconnected when they walk, so on 26.3 the event, the
  unhandled message and the handled trip aren't tested. A real 26.3 client has to check them. On 26.3 the bot gets
  into the hand-over world with `/mvtp`, and the death is done by the test plugin (health set to 0, respawned from the
  server side), since `/kill` and the bot's respawn request don't get through. Paper fires the same events either way.
- **A death without keepInventory.** The suite keeps inventory on death so it can prove the two groups stay apart. Items
  dropping in the hand-over world is that world's plugin's business, and that plugin tests it.
- **The older suites** (`acceptance`, `station` and the rest) weren't rerun for 0.4.0. They need a copy of a real
  server, and there wasn't room for one. The paths they cover changed only by a rename (`enter-command` worlds still
  skip the return gate through the same check). The unit tests pass.

### Skipped as overkill

- The event's getters on their own: the suite reads every field from a real event.
- `LandingService` being unregistered on disable: Bukkit does that for every plugin, and the README says to treat a
  missing service as "Waystation isn't running".
