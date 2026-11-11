# Test audit

What Waystation promises, and the tests that prove it. Newest first.

## 2026-11-11: the whole repo (K23)

Every promise in the README, `docs/decisions.md` and the shipped config's comments was checked against the real check names in `test/*.mjs` and the unit tests. The mappings in the sections below held up. What follows is only what they didn't cover.

Gaps that must be covered (security, safety or money), each with the test that closes it:

| Promise | Test to add | Status |
| --- | --- | --- |
| Arriving at a gate gives 5 seconds of protection | acceptance: "arriving at a gate: damage within 5 seconds is stopped" (20 → 20) and its control after 5 s (20 → 16.8) | passes, 2026-11-11 |
| `waystation.move.<world>` false stops a tribute move | acceptance: "waystation.move.meadow false: no chest opens, and they're told", "and nothing is taken or moved", and the control after unsetting | passes, 2026-11-11 |
| A dead gate is drawn only for those with access to its world, admins and `waystation.admin.seedead` | acceptance: "a dead gate isn't sent to a player with no access and no seedead" | passes, 2026-11-11, with its control: once given `seedead`, the player is sent the dead gate (33 entities). The control first failed because the check read a bot that had been disconnected by the suite's earlier restart. That was a test bug, not a plugin bug (K478, closed). |
| The arrival guard has no bypass, operators included, and the whitelist still applies | `arrival-guard.mjs`: "an operator's direct join is refused too", "with a valid pass but off B's whitelist, still refused" (with a control that the pass was given) | passes, 18/18, 2026-11-11 |
| `waystation.return` is granted to nobody, operators included | `return.mjs`: "an operator without an explicit grant is refused too" | passes, 53/53, 2026-11-11 |
| `waystation.buy` false stops buying | acceptance: "waystation.buy false: nothing bought, nothing charged" | passes, 2026-11-11 |
| With no economy plugin, a money price charges nothing and opens nothing | `economy.mjs`, restarted without EssentialsX: "no economy plugin: a money price is refused", "and no access is granted" | passes, 10/10, 2026-11-11 |
| Each player's data (`players.yml`, seen marks) is shown to nobody else | by reading: the board, the wall map and the welcome only ever read the viewing player's own record. A unit test would test the shape of the code, not a path anyone can reach, so it's skipped | skipped, with this reason |

Smaller gaps, not added now:

- The default for `waystation.fargate.<name>` (nobody has it): covered indirectly. "No permission: sent no entities" is the same code path.
- Tribute written to the server log: a convenience for admins, not a promise to players.
- GriefPrevention's own claim settings (only the bed-trust case is tested): waits for a run with GriefPrevention configured like the real server.
- The three layers of the access check, one at a time: walking into a refused gate is covered as a whole.

Correction to the audit agent's report: the 5-second protection exists (`Travel.arrivalGuard`); only its test was missing.

Acceptance on the stand-in: 161/169, the same 8 as before.

Known limits, unchanged: walking on 26.3 (bots can't), the arrival guard on the 26.2 → 26.3 path (the Server lead rehearses it on copies), BlueMap and OneBlock checks plus the 8 stand-in failures (they wait for the real copy, K24), and the flaky "op can't break ground at a world gate".

Overkill: `ReturnCommandTest.everyReturnMessageTheCodeUsesIsInTheShippedConfig` overlaps the live "a config.yml from before the return command still shows the shipped messages". It stays, because it's cheap and fails before a server starts.

## 2026-11-10: the arrival guard, fargate checks, and stepping clear (0.7.0)

| Promise | Covered by |
| --- | --- |
| On a guarded server, only a transfer with a valid pass gets in: a direct join, no pass, a reused pass and someone else's pass are refused before any world | bots `arrival-guard` (two servers); unit `ArrivalGuardTest` (9 tests: also a forged or altered pass, a pass for another server, an expired pass, a client that never answers) |
| No usable key means nobody gets in and nobody is sent | unit `withoutAUsableKeyNobodyGetsIn`, `theKeyFileMustBeLongEnoughAndPrivate` (and a key problem never prints the key) |
| A pass is spent on the client once used | bots "B overwrites the pass on the client" |
| A fargate sends nobody to a server that doesn't answer, and says so | bots "with B down, nobody is transferred", "and they are told, kindly" |
| A teleport or respawn that lands a player at a gate can't let the gate take them on their first step | bots "walking straight in from where a teleport put them does nothing", with the control once they've stepped clear; Voidwell's suite plays Multiverse-Inventories' last-location teleport |
| Paper-only servers don't try to build a station every 30 seconds | by reading: `Station.sync` returns before `generate` without Multiverse-Core (no bot check: the return suite runs Paper-only and showed no such warning) |

Acceptance on the stand-in: 153/161, the same 8 as 0.6.0. One check ("op in creative can't break the station floor") read the bot's own view after a refused dig and failed once; it now asks the server, and passes.

**Not covered:** the guard on the 26.2 server → 26.3 client → 26.3 server path the guarded server uses; the bots can't speak 26.3 without ViaVersion, which the guarded server doesn't have. The Server lead rehearses it on copies of both real servers.

## 2026-11-10: the --json console, and the Vault-blocked checks (0.6.0)

| Promise | Proved by |
| --- | --- |
| Every `/gate` command answers exactly one `ea-result` line with `--json`, ok or an error with a stable code | unit `AdminCommandTest` (6 tests): once only, even if the code tries twice; a failure is the only answer; people get no JSON. Live `arrival`: unknown subcommand, `already-exists`, `not-found` |
| `describe` lists every subcommand, each needing its own `waystation.admin.<sub>` node, optional arguments last | unit `describeListsEverySubcommandButHelp`, `everyCommandNeedsItsOwnNode`, `optionalArgumentsOnlyComeLast`; live `arrival` |
| `list` answers in pages with a cursor to the next one; `create`, `test` and `remove` answer with the gate | live `arrival` |
| A player without `waystation.admin` gets `no-permission` and no data | live `arrival` |
| Without `--json`, the commands read as before | live `arrival` ("gate list reads as before") and every suite that parses `/gate list` text |
| Negative coordinates are values, not flags | Signalbox 0.3.1 unit test. Found by `acceptance` here: Signalbox 0.3.0 refused `-60`, so `/gate create`/`move` with a negative y failed |
| Buying a gate: price shown, can't afford refused with nothing charged, charged exactly, the gate opens, a failed grant refunds in full | `acceptance` "Buying a gate", 7/7, and `economy` 8/8 with EssentialsX, both on 26.1.2. Unblocked by VaultUnlocked 2.20.3 (Modrinth, SHA-512 checked) in place of Vault |
| A plugin's veto event stops a gate | `acceptance` "plugin event hook vetoes the gate" |
| The hand-over to a world's own plugin | `handover` 28/28 on 26.1.2 |

`test/prepare-small.mjs` now adds Vault or VaultUnlocked to the stand-in when the jar folder has one. Results on Paper 26.1.2: `acceptance` 153/161, with the OneBlock section skipped. The 8 failures are exactly the stand-in list below (the real server's set-up, BlueMap's first render, the flaky op check, and the two crash checks after the OneBlock section). `arrival` passes 39/39 on 26.1.2 with Latchkey 0.3.0, the 10 `--json` checks included.

**Still open:** walking through gates and fargates on 26.3 (bots are disconnected when they move there; the newest ViaVersion builds were checked on 2026-11-10), and everything on the real-client list.

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
| The wall map: each player is sent their own marker only, where they stand, and a picture with only the gates they can see | `arrival`: two players apart with different access, read from the map packets each receives |
| The wall map's ids are saved, so restarts reuse its maps instead of making new map files | `arrival`: `wallmap.yml` holds 4 ids |
| The picture: every gate fits, platforms carry their number as well as a colour, unseen gates leave no trace, colours stay distinct | `StationPictureTest` |
| Sealed gates, off by default: a gate you can't use, and its name, aren't sent to you at all | `arrival`: a player without access, near the gate |
| Sealed gates on: the gate is sent walled up, with no name, while players with access still see it open and named | `arrival` |
| A platform in `sealed-gates.hidden-groups` stays hidden even with sealing on | `arrival` |
| A reload redraws the board in the new settings (the label backing, for one) | `station`: "label-backing 0 keeps Minecraft's own background". It caught the board keeping its old plate until fixed |
| What per-player data is kept, and that none is shown to other players | `docs/decisions.md`, 2026-11-09 |

`arrival` passes 29/29 on Paper 26.1.2, 26.2 and 26.3, with and without Latchkey 0.3.0. Unit tests: 31. How the board, the wall map and a sealed gate look, and whether the marker turns with you, need a real client (tests.md T12).

### The older suites, on a small made-up copy

`test/prepare-small.mjs` builds a 290 MB stand-in instead of copying the real server (62 GB). It has the same world,
inventory-group and LuckPerms group names, all flat and empty. Results on Paper 26.1.2:

- `station`: 51/51.
- `acceptance`: 147/161, with the OneBlock section skipped.

What still needs the real copy, or something the stand-in can't have:

- **Blocked on Vault (owner step N58), unblocked 2026-11-10 (see above):** "Buying a gate", 5 checks (`can't afford`, `buying works`, `charged exactly`,
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
