# Waystation

Walkable gates between Multiverse world groups on a Paper server. Every world gets a gate on a platform
at a train station in its own flat world, and a return gate near its own spawn. No client mods.

**Status:** passes its bot test suites on throwaway servers running Paper 26.1.2 and 26.2. It hasn't been seen in a
real game client yet. On 26.1.2 the bots join as 26.1 clients directly. On 26.2 they go through ViaVersion and ViaBackwards.
On 26.3 it loads, builds the station and passes every check that doesn't make a bot walk. Walking can't be tested there
yet, because Paper 26.3 disconnects clients that come through ViaVersion when they move ("invalid player movement").
Server hops were tested between two throwaway servers, with bots that reconnect the way a client does after a transfer.
Money prices were tested with EssentialsX.

Needs a real client, still untested:

- Walking through gates on 26.3, and anything that follows from it: station travel, fargates, buying a gate.
- How gates, signs and the return countdown look in game.
- What a client shows while it's transferred to another server and back.

Paper 26.1 or later, Java 25. 1.21.11 isn't supported since 0.3.0; use 0.1.0 there.
Multiverse-Core 5 runs the station and the automatic waygates. Without it, Waystation still does fargates, gates you
place yourself and the return command, for a server with one world. Optional: Multiverse-Inventories, LuckPerms,
GriefPrevention, Vault with an economy plugin, BlueMap.

## How it behaves

- **Gates are display entities**, not blocks. Nothing can break them and they place no blocks. They're
  never saved to disk, so a shutdown or a crash can't leave any behind, and a once-a-second pass redraws
  anything missing or moved (`/kill`, for example).
- **You see a gate only if you can use it.** Otherwise there's nothing there: plain ground, no frame, no floor, no sign,
  and walking through it does nothing. Access is `multiverse.access.<world>`, read through Bukkit
  so LuckPerms contexts apply. It's checked again when you walk in, and Multiverse checks it a third time.
- **Travel goes through Multiverse's teleporter**, so Multiverse-Inventories swaps inventories normally.
  In survival worlds you arrive at the world's gate, with 5 seconds of protection. Elsewhere you land where you last
  were, or at spawn on a first visit, a death, or if that spot is no longer safe.
- **Platforms come from your Multiverse-Inventories groups**, each with a sign, a floor of randomly mixed
  themed materials, and its own gate shape. Frames use blocks sampled from the world beyond.
- **New world:** it gets a pair by itself, within 30 seconds, and the station re-lays itself out. If a gate has nowhere
  safe to stand, admins are asked: `/gate enlarge` keeps the station with more room, `/gate reseed` rolls a new one.
- **Every station is different.** A seed picks the floor plan, platform order, gate designs and themes.
- **Deleted world:** the gate goes dark and stays until `/gate remove <id>`. If the world is only
  unloaded, the gate lights up again when it's back.
- **Nobody can edit** the station's world or the ground at a world's gate: no permission and no operator
  gets past this. To change it, set `protection.enabled: false` and `/gate reload`.
  The console can still run `/fill`: Minecraft reports no block events for it, so gates survive but the
  blocks do change.

## OneBlock

OneBlock gives each player their own island, 500 blocks apart, so one fixed gate can't serve them. The station's
OneBlock gate runs `/ob join`, which puts you on your own island. Any island with someone on it gets a glowing post
beside its block, and clicking it twice takes you back to the station. The post is display entities, so it places no
blocks and is never left behind when OneBlock reuses an island.

## Moving a waygate

Beside the waygate in each world listed in `move.allowed-worlds` is a tribute chest. It asks for hearts (6, or 3 in `move.gentle-worlds`). If you can't
spare them, it offers the pieces you're wearing that aren't named or enchanted, and you give up one. Pay, and the gate
moves near your bed in that world, with no promises on the exact spot, and then rests for a day.

Nothing goes into the chest: the menu only shows what you can give, and it's taken after the move is saved, so a crash
can't lose anything. Each tribute is written to the server log.

## Commands

| Command | Who | What |
|---|---|---|
| `/waygate` | everyone | Coordinates of the closest waygate you can use |
| `/station`, `/hub` | everyone | Straight to the station. Name and on/off in config |
| `/buygate [world]` | everyone | Buy access to a priced world |
| `/return` | with `waystation.return` | To the other server after a countdown, if it's turned on. Name in config |
| `/gate list, create, fargate, move, link, remove, test, addworld, relayout, reseed, enlarge, reload` | admins | Admin |

## Permissions

Gates never grant access: these only narrow it. A node blocks when it's explicitly set to **false**, for a
player or a group, with contexts if you like. Nothing changes until someone sets one.

| Node | Effect when false |
|---|---|
| `waystation.use.<world>` | Closes every gate to that world for them |
| `waystation.platform.<group>` | Closes a whole platform |
| `waystation.gate.<gate id>` | Closes one gate |
| `waystation.move.<world>` | No moving waygates in that world |

| Node | Default | Effect when true |
|---|---|---|
| `waystation.admin` | false | All of `/gate` |
| `waystation.admin.<subcommand>` | false | One `/gate` subcommand, for a narrower role |
| `waystation.admin.seedead` | false | See dead gates |
| `waystation.find`, `.move`, `.escape`, `.buy` | true | `/waygate`, `/move`, `/station`, `/buygate` |
| `waystation.return` | false | The return command. Name in config |

Other plugins can veto a gate by cancelling `com.extantape.waystation.WaystationAccessEvent`.

## Build

```bash
./gradlew build
```

The jar is `build/libs/Waystation-0.3.0.jar`. Java 25.

## Install

With the server stopped:

1. Copy the jar to `plugins/`. Start the server, and it makes the `waystation` world and a gate for every world.
   The default config lists no worlds, and doesn't need to. To name them, theme their platforms or turn on
   tribute chests, edit `plugins/Waystation/config.yml`.
2. To try it on an empty server, create `plugins/Waystation/config.yml` before the first start, holding only
   `example-worlds: true`. That makes `example_survival` and `example_creative`. It does nothing later on.
3. If you use Multiverse-Inventories, add the station's world to a group, under `worlds:` in
   `plugins/Multiverse-Inventories/groups.yml`, then run `mvinv reload`. The server warns at startup until this is done.
4. To make gates the only way to travel, edit `setup/luckperms-gates-only.txt` for your worlds and groups, then
   paste it into the console. It takes `/mvtp` away from everyone but admins. The console keeps it.

### BlueMap (optional)

If BlueMap stops at startup, `plugins/BlueMap/core.conf` has `accept-download: false`. Setting it to true is
accepting Mojang's EULA, which is yours to do. Then copy `setup/bluemap-waystation.conf` to `plugins/BlueMap/maps/waystation.conf`.
If your `level-name` in `server.properties` isn't `world`, change `world:` in that file to match: on Paper 26.x every world
lives inside the main one, and the station is its dimension `minecraft:waystation`. Then run `/bluemap reload`. Maps made
before 26.x need the same change, or BlueMap shows nothing for them.

Gates show as markers. BlueMap draws blocks only, so the station's map shows flat ground with markers,
not the platforms. The map also shows every gate to anyone with the link, whoever can use them.

### Prices (optional)

Add `price: {xp-levels: 30, money: 500, items: {diamond: 5}}` to a world under `worlds:`. Its gate shows
as locked to people without access. `/buygate` charges, runs `buy.grant-commands`, and refunds if the
access doesn't show up. Money prices need Vault and an economy plugin. A purchase grants that one world, so add commands if it should bring the
world's nether and End along.

### GriefPrevention (optional)

Gates only respect claims if GriefPrevention is installed: a gate won't land in a claim unless its owner trusts you. Install it with the settings in
`setup/griefprevention-config.yml`.
By default it kicks anyone past three players on one IP address. For a family sharing a home connection,
set `GriefPrevention.MaxPlayersPerIpAddress` higher in its config before relying on it. It also refuses logins within
about a minute of leaving, which can bounce a player coming back through a fargate, so lower
`GriefPrevention.Spam.LoginCooldownSeconds` too. Its default rules
for creative worlds and explosions also apply on top of everything here.

### Fargates (optional)

Fargates lead to another server, look different (obsidian and red glass, labelled "Leaving this server"), and
sit on no platform. Define the server under `fargate-destinations` in `config.yml`, then
`/gate fargate <id> <destination>` where you want it. Grant `waystation.fargate.<name>` in LuckPerms.
A player without it, or on a client version not in `protocols`, can't see the gate, and walking into it does
nothing. Both servers need `accepts-transfers=true`, and the other server needs its own way back. Coming
back, the player lands where they walked in from. `protocols` is set by hand, so update it when either
server upgrades.

### Return command (optional)

A fargate you don't have to walk to. `/return` sends the player to one of your `fargate-destinations` after a
5-second countdown. Moving, taking damage, a harmful effect or a teleport cancels it, and the player is told why and
how to try again. The seconds left show above the hotbar, with a soft tick each second, so it can be followed by ear
too. This server checks the destination answers before sending anyone, so nobody waits out a countdown for a server
that's down, and a failed try changes nothing about the player. Coming back, they land where they stood.

Turn it on under `return:` in `config.yml`, name the destination, and restart. Then grant `waystation.return` in
LuckPerms to whoever should use it: nobody has it by default, operators included. The command's name and the
countdown are in the same section, and every line players see is under `messages:`.

### Without Multiverse-Core

Leave Multiverse-Core out and Waystation runs with Paper alone. There's no station and no automatic waygates, since the
station needs a world made for it, and so no `/station`. Fargates, gates placed with `/gate create` or `/gate fargate`,
and the return command all work. The server log says which way it's running at startup.

## Tests

Bots on throwaway servers, 127.0.0.1 only. Run `./gradlew jar hookJar` and `npm install` in `test/` first, then
`node test/run.mjs <suite>`.

| Suite | What it covers | Needs |
| --- | --- | --- |
| `acceptance` | gates, permissions, inventories, protection, tribute, buying, crashes | |
| `station` | the station generator, and what it costs the server | |
| `fargate` | who sees a fargate, and coming back | |
| `defaults` | the default config and `example-worlds` | |
| `hop` | two servers, there and back through fargates | |
| `economy` | buying with a real economy plugin | `WAYSTATION_TEST_ECONOMY_JAR` |
| `return` | the return command, and running without Multiverse-Core, on a fresh empty server | see below |

All but `return` need `WAYSTATION_TEST_BASE`, a folder holding a copy made by `test/prepare-copy.sh` from a 26.x server that
isn't running. `WAYSTATION_TEST_JAVA` is the Java 25 to run it with, if `java` isn't. The bots are 26.1 clients;
`WAYSTATION_TEST_CLIENT` changes that. A server newer than 26.1 needs ViaVersion and ViaBackwards to let them in.
`WAYSTATION_TEST_PORT` changes the port from 25599, and must match the copy. The suites use generic world names.
If your copy's differ, `WAYSTATION_TEST_NAMES` points at a file mapping them, kept out of the repo. See
`test/run.mjs`. `test/hook/` holds a test-only plugin with an event listener and a stand-in economy.

`return` needs no copy of a server. It makes fresh ones, so it needs `WAYSTATION_TEST_JARS`: a folder with
`paper-<version>.jar` (and `mojang_<version>.jar`, so Paper needn't download it) and a `plugins/` folder holding
ViaVersion, ViaBackwards and LuckPerms jars. Starting a server accepts the Minecraft EULA, so it also needs
`WAYSTATION_TEST_EULA=accept`. `WAYSTATION_RETURN_SOURCE` picks 26.1.2, 26.2 or 26.3, and `WAYSTATION_TWO_SERVERS=1`
adds a real second server to hop to, on another version. Without that, a stand-in answers the destination's ping.

## License

MIT. See `LICENSE`.
