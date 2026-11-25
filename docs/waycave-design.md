# Waycave design notes

Draft by the Plugins Lead, 2026-11-22, for K649. Nothing here is built yet, except the trail shape on the `waycave` branch. The owner answered the five open questions the same night; his answers are below, and the sections follow them.

## What's decided

- **D194 (2026-11-14):** Waystation becomes Waycave. "One per server, an extra lush glowing cave built from that server's world groups, with loose organic trails from the center to the Waygates; each player sees only their own trails and gates; the board is headed Trails, closed ones read (trail closed)." The Waycave's gate to another server is a Wayport. It starts "with a test cave the owner walks in a real game".
- **D196:** no old names anywhere: no alias, old command, old permission or folder copy. Installs rename the data folder and permission nodes before first start.
- **K649 note (owner, 2026-11-14):** the rename comes with a new `waycave` world.
- **K24** folds in: the older suites' last checks (Vault buying, the OneBlock section, the crash-test pair, a flaky op-break check, and "a move changes no block" on real terrain) run against Waycave on a stopped real copy.
- **The owner's answers (Simon's dialogs, 2026-11-22):**
  - Cave shape: "organic tunnels and chambers that are unique to each instance". Each server gets its own generated layout.
  - Trails: "Moss and glow lichen (Recommended)", visible only to that player.
  - Seeing other players in the cave: "Follow Posse's groups (Recommended)".
  - Arrival: "End of their last trail", with a safe fallback (see Arrival).
  - The old station world: "throw it away, was never used". At the switch it moves to the Trash once a backup exists in the normal backup set. No hold period.

## What carries over unchanged

These already work and are tested, so Waycave keeps them as they are:

- Gates are display entities. They place no blocks, aren't saved, and are redrawn every second.
- You see a gate only if you can use it (`multiverse.access.<world>`, read through Bukkit so LuckPerms contexts apply), checked again on walking in.
- Travel goes through Multiverse's teleporter, so Multiverse-Inventories swaps inventories normally.
- World groups come from Multiverse-Inventories' own `groups.yml`. Gate frames are sampled from the world beyond.
- Return gates near each world's spawn, fargates, buying a gate, the arrival guard, Rewild keeps, BlueMap markers, WelcomeWagon's lobby gate.

## The cave

**Shape.** Organic tunnels and chambers, unique to each server (owner). The generator grows the layout from the server's seed: a start chamber where every trail begins, one chamber per world group, and a few side chambers, joined by winding tunnels. Chambers are blobs (overlapping spheres with noise on their walls). Tunnels follow curves with the same kind of wobble as trails and are 3 to 5 blocks wide, so two players can pass. Each group's Waygates sit in its chamber's walls, framed with blocks from each world as today.

**Always connected, no dead chambers.** The generator plans the layout as a graph first, with chambers as nodes and tunnels as edges: a spanning tree from the start chamber, plus a few extra loops so it doesn't feel like a maze of dead ends. Before carving, it checks the plan: every chamber is reachable from the start, every tunnel is walkable (floor, two blocks of headroom, no step higher than one block), and every chamber has a use, holding gates or a feature such as a pool or a big dripleaf grove. After carving, a flood fill over the real blocks checks the same again, and the build is refused if any gate or chamber can't be walked to. Both checks are unit tested over many seeds.

**Stable over time.** A group added later grows a new chamber on a new branch off the existing layout. Nothing already built moves, so the way to a world stays where players learned it. The layout and its seed are saved with the cave, so a rebuild after a crash gives the same cave.

**Its own world.** The cave is built in its own void world (`waycave`), inside a closed stone shell, so there's no sky, nothing outside to wander into, and no natural spawns.

**Lush and glowing.** Moss floors with azalea, flowering azalea, small and big dripleaf, moss carpet and the odd pool of still water; glow berries hanging from the ceilings, glow lichen on the walls, spore blossoms, a little amethyst. Light comes only from those (no torches or lanterns), set so every walkable block is at light 9 or more, which keeps the cave readable and blocks mob spawns even if a gamerule changes. Mob spawning, fire spread and time stay off, as in today's station.

**Protection.** Players can't break or place anything in the cave (today's `Protection`). No signs or books a player can write. Claims don't apply there.

## Trails

Each player sees a loose, winding trail from the start chamber to each Waygate they can use, and no one else's.

- **Look (owner):** moss and glow lichen. Fake blocks sent only to that player (`Player.sendBlockChanges`), laid over the real floor: a path of moss carpet edged with glow lichen, so it reads as a worn track. Nothing is placed in the world, so another player standing on the same spot sees plain floor. They're resent when the player's chunks load, and redrawn when their access changes.
- **The shape:** the trail follows the tunnels from the start chamber to the gate, with a gentle wobble from noise seeded by the player and the gate, so trails look grown, differ a little between players, and stay the same for one player every visit. Trails to gates in the same chamber share their first stretch, the way real paths merge. The shape is built and unit tested (`TrailPath`, on the `waycave` branch).
- **Closed trails:** when a player loses access to a world, its trail and gate disappear for them, and the Trails board lists it as **(trail closed)**, as D194 says.
- **Kids' safety:** a trail shows only where that player can already go. It never shows another player's trails, names or where anyone is.

## Arrival

You arrive at the end of your last trail (owner): one step in front of the gate you last came through, facing into the cave. If that spot is gone or unsafe, you arrive in the start chamber, where every trail begins. That covers a removed gate, a world you've lost access to, a rebuild that left the spot solid, and a first visit. "Safe" is today's `SafeSpots` check: solid floor, two blocks of air, no liquid, inside the cave. Nobody ever arrives at a gate they can't see.

## Seeing other players

Players in the cave see each other only where Posse's player groups allow (owner). Anyone outside a player's groups is hidden from them while both are in the cave (`hideEntity`), and shown again when either leaves or the groups change. Without Posse, players in the cave are hidden from each other (fails closed).

## The Trails board

Today's Departures Board, renamed and reworded: headed **Trails**, one line per world the player can reach, grouped by chamber, and a line reading (trail closed) for each world they've lost access to. Readable from 6 blocks at the default GUI scale, with contrast checked as in `docs/readability.md`.

## Wayports

The cave's gate to another server is a Wayport (D194). Wayport is its own plugin now. Waycave asks Wayport, when it's installed, to place its gate in the start chamber, and doesn't keep its own copy of the transfer code. Without Wayport, the cave has no server gate. Today's `TransferPass` moves to Wayport, or is removed if Wayport already covers it (to check when Wayport's code is read for this).

## The rename

All in one release, at the cave rebuild, with no old names kept (D196). OneVoid compiles against Waystation's classes (`LandingService`, `WaystationHandOverEvent`) and its player text says "the Waystation", so OneVoid releases in lockstep; WelcomeWagon's comments change too.

| Today | Waycave |
| --- | --- |
| repo `waystation` | repo `Waycave` (one capital, like every Minecraft plugin) |
| plugin, jar, `rootProject.name` `Waystation` | `Waycave` |
| package `com.extantape.waystation` | `com.extantape.waycave` |
| permissions `waystation.*` | `waycave.*` (same subnodes) |
| `WaystationAccessEvent` | `WaycaveAccessEvent` |
| data folder `plugins/Waystation` | `plugins/Waycave`, renamed by the install before first start |
| station world | new `waycave` world |

The install steps rename the folder and the permission nodes (LuckPerms: one `lp` command per node, listed in the release notes) while the server is stopped. Waycave reads only its own names, so a missed step shows up as a clear console warning on first start and nothing half-works. The old station world is thrown away at the switch (owner: "was never used"): once a backup exists in the normal backup set, it's moved to the Trash, never hard-deleted.

`waystation-private` (the leak terms) is renamed to match, and its contents don't change.

## Order of work

1. The cave generator, with its connection checks, and a `/waycave preview` that builds a cave in a throwaway world.
2. Trails on the generated tunnels, the Trails board, arrival and hiding players by Posse's groups.
3. The rename, in the same release (it waits for the cave).
4. The test cave the owner walks in a real game (D194). Nothing goes live before that.
5. Wayport in the start chamber.
6. K24's checks on a stopped real copy, then the live install on the owner's go.

## Testing

- **Unit:** the layout from a seed and a group list (same input, same cave; different servers, different caves; a new group adds a chamber and moves none). Over many seeds: every chamber and gate reachable from the start, no dead chambers, every tunnel walkable, and the flood fill agrees with the plan. Trail curves (stay on walkable floor, never cross a wall, reach the gate). Light levels (no walkable block below 9). Arrival (last trail's end, and the start chamber whenever that spot is gone or unsafe). The board's lines, including closed trails.
- **Bots on a throwaway server:** each bot sees only its own trails and gates (two bots with different access, looking at the same blocks); bots outside each other's Posse groups don't see each other in the cave; a lost access closes the trail and the board says so; protection holds; arrival lands at the last trail's end, or the start chamber after that gate is removed.
- **Real client (owner):** how the cave and trails look and read, as D194 asks.
- **Clean-up (D226):** every test removes only the folders and servers it made, pass or fail, with a leftover check.
