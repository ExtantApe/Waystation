# Decisions

Choices the owner made for Waystation, newest first. Server-specific choices live with that server's own notes, not here.
Dates are when the decision was made. Those marked "by" were made while the first version was built, before that date.

| Date | Decision |
| --- | --- |
| 2026-11-09 | Sealed gates are a setting, `sealed-gates.enabled`, **off** (owner, 2026-11-09: "Off (Recommended)", for Sonoran Realms). Off keeps the promise Waystation already makes and tests: a gate you can't use isn't sent to you at all. On, it shows walled up with no light and no name. Off was recommended because a sealed gate still tells a player, a child included, that a line exists, and kids' privacy comes first. Platforms in `sealed-gates.hidden-groups` always stay hidden. |
| 2026-11-09 | Arrival flow (Helm, for the owner): the station welcome remembers when each player last walked into the station, as one timestamp on the server's own player file (`waystation:station_seen`). It's never shown to other players and never leaves the server. It's the only per-player data the arrival flow adds. Waystation's other per-player data is unchanged: `players.yml` keeps each player's last spot per world, so travel lands them where they were, and where to put them back after a fargate trip. Neither is shown to other players. |
| 2026-11-09 | Arrival flow, plan phase 3: a lobby gate into the station and a welcome inside it, built on the proposed lobby defaults while the owner confirms them. Latchkey stays optional and is read by name. |
| 2026-11-08 | OneBlock moves into the station and is handed to its own add-on, which owns getting in and out. Waystation's part: a `hand-over` gate that fires `WaystationHandOverEvent` and moves nobody (if no plugin takes the player, they stay and are told the world isn't open), and `LandingService`, the spot in front of that world's station gate for the add-on to send players back to. Players won't use OneBlock's own commands. Shipped in 0.4.0. |
| 2026-11-08 | The real-client walkthrough runs on a throwaway copy on Paper 26.2, with the owner's own account as LuckPerms admin on that copy only. The account name is given at setup and never written into this repo. |
| 2026-11-08 | The lobby welcome text stops teaching `/mvtp` and teaches walking through a gate and `/station`. Its exact wording is the server's, kept with the server's notes, not here. |
| 2026-11-08 | Review gate colours and label text for colour-blind players and contrast. Colour never carries meaning alone: a word or shape goes with it. Fixes ship in a 0.3.x with tests where they can be made. Findings in `docs/readability.md`. |
| 2026-11-08 | The return command is named `/return`, and `waystation.return` is granted to nobody by default, operators included. A server gives it out on purpose. |
| 2026-11-08 | Skip 0.2.0 and release one version, 0.3.0, with the move to 26.x, the fix for older configs showing message keys, and the return command. Walking on 26.3 is released untested with bots, marked so in the README and release notes, and waits for a real 26.3 client. |
| 2026-11-07 | ReturnGate is folded into Waystation: a configurable return command sends the player to a fargate destination after a countdown that damage cancels. Multiverse-Core becomes optional, so a single-world server can run Waystation for fargates and the return command alone. |
| 2026-11-07 | Release as soon as it's good, as 0.x versions on GitHub Releases only. Modrinth, Hangar and Spigot wait for the owner's own code review. |
| 2026-11-07 | Paper only: `paper-api` and `paper-plugin.yml`, not Spigot. |
| 2026-11-07 | Target Minecraft 26.1 and later, built and run on Java 25. Drop 1.21.11. Test on Paper 26.1, 26.2 and 26.3. Use newer Paper features where they make things simpler or sturdier, not for their own sake. |
| 2026-11-07 | Released as 0.1.0, public under ExtantApe, MIT. |
| by 2026-11-07 | The default config lists no worlds, so a new server works without editing. An `example-worlds` flag makes two worlds to try it on. Real server settings stay in a private file. |
| by 2026-11-07 | Gradle with Kotlin DSL, group `com.extantape`, built like BlueMapHUD. Repo and jar named `Waystation`. |
| by 2026-11-07 | One jar, readable source. No economy plugin of its own, no database, no client mod. Money goes through Vault when it's there. |
| by 2026-11-07 | No dependency on Multiverse-Portals. Inventories must never be lost or mixed. Admin rights come from permissions (LuckPerms), not from being an operator. |

## 2026-11-14

- **Other plugins' new names (owner, D194, D196, D197, D198):** Waystation reads WelcomeWagon's lobby and arrival event by their new names, and its docs and tests use OneVoid, Mayor, Sheriff and the other new names. Waystation itself keeps its name until the Waycave rebuild (K649), as the owner chose. Released as 0.7.2.

## 2026-11-22

- **Waycave answers (owner, Simon's dialogs, 2026-11-22, relayed by the chief of staff):** cave shape "organic tunnels and chambers that are unique to each instance"; trails "Moss and glow lichen (Recommended)", visible only to that player; seeing other players in the cave "Follow Mayor's groups (Recommended)"; arrival "End of their last trail", falling back to the start chamber when that spot is gone or unsafe (the lead's pick); the old station world "throw it away, was never used", moved to the Trash at the switch once a backup exists in the normal backup set, with no hold. Generated caves must always connect, with no dead chambers, and tests prove it. Details in `docs/waycave-design.md` (K649).

## 2026-11-24

- **Waycave build (K649).** Decisions the design notes didn't cover, made while building, for the owner to confirm:
  the world is a flat Multiverse world with the cave built high above its ground, because Multiverse loads worlds before a
  generator plugin is up on restart; the cave is planned once and `/gate reseed` leaves it alone; `/gate enlarge` is gone,
  since a chamber is sized for its group's gates plus spares; a world with no place left in its chamber is reported to
  admins and not squeezed in; the Trails board names a world as (trail closed) only to a player who once had a trail to
  it; Mayor must list `waycave` as a circle-only world for the cave to follow circles; the Wayport gate runs Wayport's
  command and Waycave's own fargate transfer code is untouched (to retire separately).
- **D284 (owner, 2026-11-24):** "Players never need to type a command in game where a walk-in portal, sign, book, menu or NPC can do the job. Commands stay for admins and as a hidden fallback." Applied: no text teaches a player command except `/buygate`, which still needs a menu or portal.
