# Changelog

## Unreleased

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

## 0.8.0 (unreleased)

Waystation is now Waycave, rebuilt as a cave (K649, D194, D196). Not released and not on any live server.

- **Renamed.** Plugin, jar, `rootProject.name`, package (`com.extantape.waycave`), permissions (`waycave.*`),
  `WaycaveAccessEvent`, `WaycaveHandOverEvent`, the data folder and the world (`waycave`). No old names, commands,
  permissions or folder copies are kept. The upgrade steps are in the README ("Upgrading from Waystation").
- **The cave.** A generated cave replaces the station: a start chamber, a chamber for each world group and side chambers
  with pools and groves, joined by winding tunnels. Planned once from a seed and saved in `cave.yml`; a new group grows a
  new chamber and nothing already built moves. Checked as a plan and again over the real blocks: every chamber and gate
  place can be walked to, or nothing is built into use. Lit by glowing berry vines to at least light 9.
- **Trails.** Moss carpet edged with glow lichen from the start chamber to each gate a player can use, sent to that
  player only.
- **Who sees whom.** In the cave, players see each other only where Mayor allows, and nobody does without Mayor.
- **Arrival** at the end of the last trail, or in the start chamber when that gate is gone, unusable or unsafe.
- **Trails board** replaces the Departures Board. A world a player lost access to reads (trail closed).
- **Wayport gate** in the start chamber when Wayport is installed.
- **Players don't type (D284).** The welcome, the escape messages and `/waygate` no longer teach commands. `/cave`
  (was `/station`), `/waygate` and `/return` stay as hidden fallbacks. `/buygate` is still typed.
- Removed: `/gate enlarge` and `waycave.admin.enlarge`, the floor plans, `cave.spacing`, `cave.aisle`, `cave.slots`, the
  wall map (off by default), and `station.mjs`.
- Changed defaults: `cave.y` 64, `cave.tile-depth` 0, `wall-map.enabled` false, `escape.command` `cave`.

Tested, on throwaway servers on 127.0.0.1 with made-up worlds: 82 unit tests pass. The new `cave` bot suite passed on
Paper 26.1.2 (47/47, bots walking, Mayor 0.8.0 and Wayport 0.3.0 installed), 26.2 (40/40) and 26.3 (40/40). On 26.2 and
26.3 the bots stand still, so arrival and walking into the Wayport gate weren't run there. The 26.1.2 run was made before
the last small change to the suite's "its gate is gone" check, which now also asks the server. Not tested: a real game
client; the older bot suites (`acceptance`, `fargate`, `defaults`, `hop`, `economy`, `return`, `handover`), which weren't
updated or rerun; an upgrade of a real install; trails after a chunk reload.
