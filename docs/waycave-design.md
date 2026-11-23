# Waycave design notes

Draft by the Plugins Lead, 2026-11-22, for K649. Nothing here is built yet. The decisions it rests on are settled. The choices marked **Open** are the lead's recommendations and wait for the owner.

## What's decided

- **D194 (2026-11-14):** Waystation becomes Waycave. "One per server, an extra lush glowing cave built from that server's world groups, with loose organic trails from the center to the Waygates; each player sees only their own trails and gates; the board is headed Trails, closed ones read (trail closed)." The Waycave's gate to another server is a Wayport. It starts "with a test cave the owner walks in a real game".
- **D196:** no old names anywhere: no alias, old command, old permission or folder copy. Installs rename the data folder and permission nodes before first start.
- **K649 note (owner, 2026-11-14):** the rename comes with a new `waycave` world.
- **K24** folds in: the older suites' last checks (Vault buying, the OneBlock section, the crash-test pair, a flaky op-break check, and "a move changes no block" on real terrain) run against Waycave on a stopped real copy.

## What carries over unchanged

These already work and are tested, so Waycave keeps them as they are:

- Gates are display entities. They place no blocks, aren't saved, and are redrawn every second.
- You see a gate only if you can use it (`multiverse.access.<world>`, read through Bukkit so LuckPerms contexts apply), checked again on walking in.
- Travel goes through Multiverse's teleporter, so Multiverse-Inventories swaps inventories normally.
- World groups come from Multiverse-Inventories' own `groups.yml`. Gate frames are sampled from the world beyond.
- Return gates near each world's spawn, fargates, buying a gate, the arrival guard, Rewild keeps, BlueMap markers, WelcomeWagon's lobby gate.

## The cave

**Shape.** A domed central hall where you arrive, with one chamber per world group opening off it. Each chamber holds that group's Waygates in its walls, framed with blocks from each world as today. A group that's added later gets a new chamber in the next free direction; existing chambers never move, so the way to a world stays where players learned it.

**Generated.** The cave is generated from the server's seed and its group list, so the same server always gets the same cave, and a rebuild after a crash or an edit gives the same result. It's built in its own void world (`waycave`), inside a closed stone shell, so there's no sky, nothing outside to wander into, and no natural spawns.

**Lush and glowing.** Moss floors with azalea, flowering azalea, small and big dripleaf, moss carpet and the odd pool of still water; glow berries hanging from the dome, glow lichen on the walls, spore blossoms, a little amethyst. Light comes only from those (no torches or lanterns), set so every walkable block is at light 9 or more, which keeps the cave readable and blocks mob spawns even if a gamerule changes. Mob spawning, fire spread and time stay off, as in today's station.

**Protection.** Players can't break or place anything in the cave (today's `Protection`). No signs or books a player can write. Claims don't apply there.

## Trails

Each player sees a loose, winding trail from the center of the hall to each Waygate they can use, and no one else's.

- **How they're drawn:** fake blocks sent only to that player (`Player.sendBlockChanges`), laid over the real moss floor: a path of moss carpet edged with glow lichen, so it reads as a worn track. Nothing is placed in the world, so another player standing on the same spot sees plain floor. They're resent when the player's chunks load, and redrawn when their access changes.
- **The shape:** a smooth curve from the center to the gate with a gentle wobble from noise seeded by the player and the gate, so trails look grown, differ a little between players, and stay the same for one player every visit. Trails to gates in the same chamber share their first stretch, the way real paths merge.
- **Closed trails:** when a player loses access to a world, its trail and gate disappear for them, and the Trails board lists it as **(trail closed)**, as D194 says.
- **Kids' safety:** a trail shows only where that player can already go. It never shows another player's trails, names or where anyone is.

## The Trails board

Today's Departures Board, renamed and reworded: headed **Trails**, one line per world the player can reach, grouped by chamber, and a line reading (trail closed) for each world they've lost access to. Readable from 6 blocks at the default GUI scale, with contrast checked as in `docs/readability.md`.

## Wayports

The cave's gate to another server is a Wayport (D194). Wayport is its own plugin now. Waycave asks Wayport, when it's installed, to place its gate in the hall, and doesn't keep its own copy of the transfer code. Without Wayport, the hall has no server gate. Today's `TransferPass` moves to Wayport, or is removed if Wayport already covers it (to check when Wayport's code is read for this).

## The rename

All in one release, with no old names kept (D196):

| Today | Waycave |
| --- | --- |
| repo `waystation` | repo `Waycave` (one capital, like every Minecraft plugin) |
| plugin, jar, `rootProject.name` `Waystation` | `Waycave` |
| package `com.extantape.waystation` | `com.extantape.waycave` |
| permissions `waystation.*` | `waycave.*` (same subnodes) |
| `WaystationAccessEvent` | `WaycaveAccessEvent` |
| data folder `plugins/Waystation` | `plugins/Waycave`, renamed by the install before first start |
| station world | new `waycave` world |

The install steps rename the folder and the permission nodes (LuckPerms: one `lp` command per node, listed in the release notes) while the server is stopped. Waycave reads only its own names, so a missed step shows up as a clear console warning on first start and nothing half-works. The old station world is unloaded and left on disk. **Open:** how long it stays before it's removed.

`waystation-private` (the leak terms) is renamed to match, and its contents don't change.

## Order of work

1. The rename on its own, with today's station layout, so the suites prove nothing else changed.
2. The cave generator and the Trails board, with a `/waycave preview` that builds the cave in a throwaway world.
3. Trails.
4. The test cave the owner walks in a real game (D194). Nothing goes live before that.
5. Wayport in the hall.
6. K24's checks on a stopped real copy, then the live install on the owner's go.

## Testing

- **Unit:** cave layout from a seed and a group list (same input, same cave; a new group adds a chamber and moves none), trail curves (stay on walkable floor, never cross a wall, reach the gate), light levels (no walkable block below 9), the board's lines including closed trails.
- **Bots on a throwaway server:** each bot sees only its own trails and gates (two bots with different access, looking at the same blocks); a lost access closes the trail and the board says so; protection holds; arrival lands in the hall.
- **Real client (owner):** how the cave and trails look and read, as D194 asks.
- **Clean-up (D226):** every test removes only the folders and servers it made, pass or fail, with a leftover check.

## Open questions for the owner

Each goes to the owner as its own question, through the chief of staff:

1. **Cave shape:** a domed hall with a chamber per world group (recommended: easy to learn, and chambers never move), or winding tunnels.
2. **Trail look:** fake moss and glow lichen blocks only that player sees (recommended: steady and readable in daylight-free caves), or particle trails.
3. **Seeing other players in the cave:** follow Mayor's player groups, so players outside each other's groups don't see each other there (recommended, for kids' safety), or everyone sees everyone as in today's station.
4. **Where you arrive:** the center of the hall (recommended: every trail starts there), or the end of the trail you last used.
5. **The old station world:** keep it unloaded for 30 days after the switch, then remove it with the owner's yes (recommended), or keep it indefinitely.
