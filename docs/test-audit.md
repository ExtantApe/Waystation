# Test audit

What Waystation promises, and the tests that prove it. Newest first.

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
