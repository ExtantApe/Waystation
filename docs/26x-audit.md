# Paper 26.x audit

Waystation 0.2.0 targets Paper 26.1 and later, on Java 25. 1.21.11 isn't supported any more. This note says what changed for
that, what was looked at and left alone, and why. It was checked against Paper's developer docs and the 26.1.2 API jar on
2026-11-07, not from memory.

## Why 26.1 is the floor

Every 26.x line has stable Paper builds: 26.1.2 build 74, 26.2 build 135, 26.3 build 244. The plugin uses nothing that's newer
than 26.1, so there's no reason to start later. It's built against `paper-api:26.1.2.build.+` with `api-version: '26.1'`, which
newer servers load and older ones refuse.

## Changed

- **Build:** Java 25 toolchain and `--release 25`, Paper's new `<version>.build.<n>` API coordinates, Multiverse-Core 5.8.2's
  API. Compiler warnings for deprecations and removals are on, and the build has none.
- **Game rules:** the station world used `DO_MOB_SPAWNING`, `DO_FIRE_TICK` and `DO_DAYLIGHT_CYCLE`, which are marked for removal.
  It now sets `SPAWN_MOBS`, `ADVANCE_TIME` and `FIRE_SPREAD_RADIUS_AROUND_PLAYER` (0, so no fire spreads). Protection still
  blocks fire on its own.
- **Teleports:** Multiverse's `teleport(Entity)` is marked for removal in Multiverse 6. Travel uses `teleportSingle`. Riders
  are refused before a trip, so it's always one entity.
- **Commands:** all four commands are registered with Paper's `registerCommand` and `BasicCommand`, and `plugin.yml` has no
  `commands:` section. This replaces the escape command's hand-made entry in the command map, which had to be unregistered and
  re-added on every reload. One behaviour changed: Paper registers commands at startup, so renaming the escape command or turning
  it on needs a restart. Turning it off still works at once with `/gate reload`, and the command then refuses. A reload that
  needs a restart says so in the log.
- **Items:** `new ItemStack(...)` became `ItemStack.of(...)`. The constructor isn't deprecated yet, but Paper's roadmap says it
  goes when `ItemStack` becomes an interface. Prices are checked when the config loads, so `ItemStack.of` can't throw at payment.
- **`paper-plugin.yml`:** replaces `plugin.yml`, since this project is Paper-only. Paper's docs still call the format
  experimental, and they don't cover everything, so the keys were checked in Paper 26.1.2's own loader (`PaperPluginMeta`):
  - `dependencies.server` lists Multiverse-Core as required. The optional plugins (Multiverse-Inventories, LuckPerms,
    GriefPrevention, Vault, BlueMap, ViaVersion) load first when they're there.
  - Only Multiverse-Core and BlueMap get `join-classpath`, because Waystation calls their APIs directly. The others are reached
    through their own classloaders, as before.
  - `has-open-classloader: true` lets other plugins see Waystation's classes, so they can still listen for
    `WaystationAccessEvent` and veto a gate. The test hook is an ordinary `plugin.yml` plugin and checks exactly that.
  - Permissions and their defaults are read the same way as in `plugin.yml`.
- **Fargate protocols:** the config comment and example now give 26.x numbers: 775 is 26.1, 776 is 26.2, 777 is 26.3.

## Looked at, left alone

- **Brigadier trees:** `/gate` has thirteen subcommands with their own permission nodes, and the console uses it. A full
  Brigadier tree would be a rewrite for the same behaviour. `BasicCommand` keeps the existing argument handling.
- **World names:** `World#getName` is `@Obsolete` in 26.1, in favour of world keys. Waystation stores worlds by name in
  `gates.yml`, `players.yml` and `config.yml`, and Multiverse does the same. Moving to keys would change every file people
  already have, for no change in behaviour. Names stay. They also came through the 26.x world-folder move unchanged.
- **The server's protocol number:** read with `Bukkit.getUnsafe().getProtocolVersion()`. It's been deprecated since 1.7.2 but
  isn't marked for removal, and the 26.1 API has nothing else that gives it. It's used only when ViaVersion isn't installed.
- **Data components:** the tribute chest checks for a custom name with item meta. Paper's docs say the data component API
  promises no backwards compatibility, so meta is the steadier choice for one check.
- **Registries:** marked experimental. Theme block names are read with `Material.matchMaterial`, which is fine.
- **Dialogs:** the tribute chest shows items to give, which a dialog can't do. The island post's two-click confirm could be a
  dialog, but players joining through ViaVersion on an older client wouldn't see one. Left as it is.
- **Display entities and visibility:** already per player with `showEntity` and `hideEntity`, and never saved to disk.
- **Java 25 language features:** the code already uses records, switch expressions and pattern matching. Nothing else would
  make it simpler.

## Tested

Bots on throwaway copies of a real server, on 127.0.0.1, one server at a time, 2026-11-07 and 08. Plugins were the 26.x
builds: Multiverse-Core 5.8.2, Multiverse-Inventories 5.3.6, LuckPerms 5.5.71, BlueMap 5.29, GriefPrevention, ViaVersion
and ViaBackwards 5.12.3 snapshots. EssentialsX 2.22.0 stood in for a real economy.

| Suite | 26.1.2 (build 74) | 26.2 (build 135) | 26.3 (build 242) |
| --- | --- | --- | --- |
| acceptance | 177/177 | 177/177 | stopped early, see below |
| station | 48/48 | 48/48 | 36/48 |
| fargate | 23/23 | 23/23 | 13/23 |
| defaults | 17/17 | 17/17 | 17/17 |
| hop | 12/12 | 12/12 | 0/1 |
| economy | 8/8 | 8/8 | 3/8 |

- **26.1.2:** the bots are 26.1 clients and join directly, with nothing translating.
- **26.2:** the same bots join through ViaVersion and ViaBackwards.
- **26.3:** the bots can only join through ViaVersion, and Paper 26.3 disconnects them for "invalid player movement" as
  soon as they move. Every failure there comes after such a disconnect. Every check that doesn't need a bot to walk
  passed: the station's layouts and borders, admin messages, `/gate enlarge`, the default config and the example
  worlds. Whether a real 26.3 client walks through gates is untested.
- **Not tested anywhere:** a real game client. Hops used bots that reconnect the way a client does after a transfer.

