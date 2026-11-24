# Changelog

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
