# Changelog

## 0.9.0

- **The gate to another server is back, through Wayport's API (D301).** With Wayport 0.5.0 or newer installed and
  running, the start chamber has its gate. Waycave asks Wayport's API about each player, and Wayport answers from that
  player's own `wayport.use` and its own config. A player Wayport allows sees the gate, walks in, is told to stand still
  (`messages.wayport-enter`), and once they have stopped Wayport counts down and sends them. Waycave runs no command and
  lends no rights.
- **Fails closed.** No Wayport, a Wayport that is off, one older than 0.5.0, an API older than version 1, a Wayport that
  isn't set up, or any answer other than a plain yes: the gate is shown to nobody and sends nobody. A player Wayport
  doesn't allow is sent no gate and gets no message at the place it stands.
- A visit starts only by walking in, and Wayport is asked once per visit, so a canceled trip doesn't restart until the
  player steps out and back in, and a player who logs in inside the gate isn't sent anywhere.
- Removed `wayport.command` and `wayport.permission` from `config.yml`: who may use the gate is Wayport's to say. Only
  `wayport.label` is left. `/gate test wayport <player>` says when Wayport is missing, too old or says no.

Tested: 105 unit tests pass (`./gradlew build`), 13 of them new in `WayportLinkTest`. The `cave` bot suite, on throwaway
servers on 127.0.0.1 with made-up worlds, Posse 0.11.0, Wayport 0.4.0 (no API) and Wayport 0.5.0: 62/62 on Paper 26.2,
62/62 on 26.3, 67/68 on 26.1.2. Every Wayport gate check passes on all three: no gate with no Wayport or with
0.4.0, none shown while Wayport isn't set up, a player holding only `wayport.use` is sent the gate and transferred by
Wayport, a player without it at the same spot is sent nothing, told nothing and goes nowhere, and no command is run. The
bot walks in on 26.1.2; on 26.2 and 26.3 a test-only hook reports the step, since bots can't walk there. The miss on
26.1.2 is the older check "and so is its gate" (a lost world's gate: the server says hidden, the bot still lists the
entities). It failed in two of three runs and passed in one, so it is a flaky check to fix separately.
Not tested: a real game client at the gate; every other bot suite (`acceptance`, `buymenu`, `economy`, `defaults`,
`return`, `arrival`, `arrival-guard`, `fargate`, `hop`, `handover`) was not rerun for 0.9.0; a full trip to a second
server from this gate (the bots record the transfer packet; Wayport's own suites cover the arrival).

## 0.8.0, the buying menu

- **Buying menu (D284).** Players buy a gate by clicking the Trails board, not by typing `/buygate`. A priced world reads
  "(for sale: click the board)". Right or left click opens a menu of the gates they could buy, with price and balance;
  clicking one asks "Buy it?"; a second click on Yes buys it. The same offers are said in chat, each with a name to click,
  for screen readers. Locked gates now say "Buy it at the Trails board". `/buygate` stays as the hidden fallback.
- **Fails closed.** Nothing is offered or bought for a world the player has no visible gate to (so a child never buys
  another group's gate, by the menu or by `/buygate`, which now refuses it too), for a price in money with no Vault and
  economy plugin, or without `waycave.buy`. A second purchase can't start while one is still being granted.
- New messages `buy-menu-*` in `config.yml`; the `gate-locked` message and the gate label changed. Nothing bumped.

Tested: 92 unit tests pass (10 new, in `BuyMenuTest`). The new `buymenu` bot suite is in the test run notes of the PR. Not
tested: a real game client (how the menu looks and reads, the chat names being clicked, which uses a callback); the
`acceptance` and `economy` suites after this change; a board with more than 54 things for sale.

## 0.8.0

Waystation is now Waycave, rebuilt as a cave (K649, D194, D196). Not on any live server.

- **Renamed.** Plugin, jar, `rootProject.name`, package (`com.extantape.waycave`), permissions (`waycave.*`),
  `WaycaveAccessEvent`, `WaycaveHandOverEvent`, the data folder and the world (`waycave`). No old names, commands,
  permissions or folder copies are kept. The upgrade steps are in the README ("Upgrading from Waystation").
- **The cave.** A generated cave replaces the station: a start chamber, a chamber for each world group and side chambers
  with pools and groves, joined by winding tunnels. Planned once from a seed and saved in `cave.yml`; a new group grows a
  new chamber and nothing already built moves. Checked as a plan and again over the real blocks: every chamber and gate
  place can be walked to, or nothing is built into use. Lit by glowing berry vines to at least light 9.
- **Trails.** Moss carpet edged with glow lichen from the start chamber to each gate a player can use, sent to that
  player only.
- **Who sees whom.** In the cave, players see each other only where Posse allows, and nobody does without Posse.
- **Arrival** at the end of the last trail, or in the start chamber when that gate is gone, unusable or unsafe.
- **Trails board** replaces the Departures Board. A world a player lost access to reads (trail closed).
- **Wayport gate**: built, then switched off for 0.8.0 (see below).
- **Players don't type (D284).** The welcome, the escape messages and `/waygate` no longer teach commands. `/cave`
  (was `/station`), `/waygate` and `/return` stay as hidden fallbacks. `/buygate` is still typed.
- Removed: `/gate enlarge` and `waycave.admin.enlarge`, the floor plans, `cave.spacing`, `cave.aisle`, `cave.slots`, the
  wall map (off by default), and `station.mjs`.
- Changed defaults: `cave.y` 64, `cave.tile-depth` 0, `wall-map.enabled` false, `escape.command` `cave`.

Posse, not Mayor (D295, 2026-11-25): the cave crowd asks the plugin named `Posse`, the dependency and the circle nodes
(`posse.circle.*`) follow, and no old name is read. With no Posse, nobody in the cave sees anyone (unit test, and the
`cave` suite's run without it).

Tested, on throwaway servers on 127.0.0.1 with made-up worlds, Paper 26.1.2, 26.2 and 26.3, with Posse 0.11.0, Wayport
0.4.0, WelcomeWagon 0.7.0 and EssentialsX 2.22.0: unit tests pass (`./gradlew build`).

| Suite | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| `cave` | 47/47 | 42/42 | 42/42 |
| `buymenu` | 33/33 | 33/33 | 33/33 |
| `economy` | 10/10 | 10/10 | 10/10 |
| `defaults` | 17/17 | 17/17 | 17/17 |
| `return` | 53/53 | 53/53 | 50/50 |
| `arrival` | 30/30 | 30/30 | 30/30 |
| `arrival-guard` | 18/18 | 18/18 | 18/18 |
| `handover` | 28/28 | 28/28 | 19/19 |
| `acceptance` | 166/169 | not run | not run |
| `fargate` | 22/22 | not run | not run |
| `hop` | 12/12 | not run | not run |

- **Wayport gate off (D301).** Wayport 0.4.0 made `/wayport` admin-only, so the cave's gate to another server did nothing
  for players. It is hidden from everyone in 0.8.0 and Waycave never runs `/wayport` with borrowed rights. The `cave` suite
  checks that nobody sees it, even a player holding `wayport.use` and `wayport.admin`. It returns when Wayport has an API.
- `acceptance` 166/169: the three that remain depend on what the small made-up copy lacks: vanilla `/tp` in creative,
  nether portals and BlueMap's first render (the same three as before the cave).
- On 26.2 and 26.3 the bots stand still, so those runs skip the checks that walk (arrival, the walks in
  `handover`). `acceptance`, `fargate` and `hop` walk through gates and stay on 26.1.2.
- The older suites were brought up to the cave: chambers instead of platforms, `/cave`, operator checks on the cave floor
  read from the server, the Departures Board and wall map checks dropped (gone), `fargate` checks that its destination
  answers, `economy` runs with still bots on 26.3.
- Not tested: a real game client; an upgrade of a real install (the live install step is rehearsed on a made-up server, 24 checks pass, outside this repo); trails after a chunk reload. The 26.2 and 26.3 `cave` runs are on the final jar; the other suites there ran on the jar before the Wayport gate was switched off, which touches nothing they check.
