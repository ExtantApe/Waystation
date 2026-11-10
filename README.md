# Waystation

Walkable gates between Multiverse world groups on a Paper server. Every world gets a gate on a platform
at a train station in its own flat world, and a return gate near its own spawn. No client mods.

**Status:** passes its bot test suites on throwaway servers running Paper 26.1.2 and 26.2. It hasn't been seen in a
real game client yet. On 26.1.2 the bots join as 26.1 clients directly. On 26.2 they go through ViaVersion and ViaBackwards.
On 26.3 it loads, builds the station and passes every check that doesn't make a bot walk. Walking can't be tested there
yet, because Paper 26.3 disconnects clients that come through ViaVersion when they move ("invalid player movement").
Buying a gate was tested on 26.1.2 with EssentialsX behind VaultUnlocked (which keeps Vault's API); 0.6.0's `--json`
console with bots on 26.1.2.
Server hops were tested between two throwaway servers, with bots that reconnect the way a client does after a transfer.
Money prices were tested with EssentialsX.

Needs a real client, still untested:

- Walking through gates on 26.3, and anything that follows from it: station travel, fargates, buying a gate.
- How gates, signs and the return countdown look in game, and the arrival pieces below: the lobby gate, the welcome
  lines, the Departures Board, the wall map (and whether its marker turns with you) and a sealed gate.
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
  and walking through it does nothing. Servers can turn on `sealed-gates` instead, so the gate shows walled up, with no light and
  no name; it's off by default, since even a sealed gate tells a player that a line exists. Access is `multiverse.access.<world>`, read through Bukkit
  so LuckPerms contexts apply. It's checked again when you walk in, and Multiverse checks it a third time.
- **Travel goes through Multiverse's teleporter**, so Multiverse-Inventories swaps inventories normally.
  In survival worlds you arrive at the world's gate, with 5 seconds of protection. Elsewhere you land where you last
  were, or at spawn on a first visit, a death, or if that spot is no longer safe.
- **Platforms come from your Multiverse-Inventories groups**, each with a sign, a floor of randomly mixed
  themed materials, and its own gate shape. Frames use blocks sampled from the world beyond.
- **New world:** it gets a pair by itself, within 30 seconds, and the station re-lays itself out. If a gate has nowhere
  safe to stand, admins are asked: `/gate enlarge` keeps the station with more room, `/gate reseed` rolls a new one.
- **Every station is different.** A seed picks the floor plan, platform order, gate designs and themes.
- **Deleted world:** the gate goes dark and stays until `/gate remove <id>`: a cracked grey frame with nothing inside,
  labelled "(line closed)". If the world is only unloaded, the gate lights up again when it's back.
- **Labels read over anything.** Floating labels have a text shadow and a dark plate behind them, so they show over
  snow, sky or a dark cave. `label-backing` sets how dark, 0 for Minecraft's own faint one. A gate's state is always
  in words as well as colour: "Locked", "(line closed)", "Leaving this server".
- **Nobody can edit** the station's world or the ground at a world's gate: no permission and no operator
  gets past this. To change it, set `protection.enabled: false` and `/gate reload`.
  The console can still run `/fill`: Minecraft reports no block events for it, so gates survive but the
  blocks do change.

## OneBlock

OneBlock gives each player their own island, 500 blocks apart, so one fixed gate can't serve them. Waystation can
hand the player at the station's OneBlock gate to a plugin that knows where they belong (`hand-over: true`, see
"For other plugins"), or run `/ob join` as the player (`enter-command`). Any island with someone on it gets a glowing post
beside its block, and clicking it twice takes you back to the station. The post is display entities, so it places no
blocks and is never left behind when OneBlock reuses an island.

## Arriving players

- **Lobby gate.** The lobby world's gate to the station stands by the lobby, not by the world's spawn, in its own design: a
  wide arch on two lantern posts. With [Latchkey](https://github.com/ExtantApe/Latchkey) 0.3.0 its lobby is used,
  read by name, so Latchkey stays optional. Otherwise set `arrival.lobby-world`. Move the lobby and the gate follows.
- **Welcome.** Walking into the station the first time, or after 30 days away, you get three short lines saying how many
  worlds and platforms you can reach. The time of your last visit is kept on your player file and shown to nobody else.
- **Departures Board.** In front of the centrepiece. Each player sees their own: only the worlds they can use, grouped
  by platform like the gates. It changes within a second when their access does.
- **Wall map.** A 2x2 wall of maps behind the centrepiece shows the station from above: the platforms in their colours
  with their numbers, and the gates on them. Each player sees their own marker, and only the gates they can use. The
  map ids are kept in `wallmap.yml`, so restarts don't make new map files.

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
| `/gate list, station, create, fargate, move, link, remove, test, addworld, relayout, reseed, enlarge, reload, describe` | admins | Admin |

Every `/gate` command also takes `--json <id>` at the end, for scripts and the Leaf Juice app, and then answers with one
`ea-result <id> {...}` line in the shared [Signalbox](https://github.com/ExtantApe/Signalbox) format. `/gate describe`
lists the commands and their arguments. `/gate list` answers in pages of 25 (up to 100 with a limit) with a cursor
for the next page, and `/gate station` gives the seed, plan and platforms. The slow ones (`addworld`, `relayout`,
`reseed`, `enlarge`) answer once the station is built, with any problems in a list.

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

The jar is `build/libs/Waystation-0.4.0.jar`. Java 25.

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

Fargates lead to another server, look different (a yellow and black striped frame around red glass, labelled "Leaving this server"), and
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

Bots on throwaway servers, 127.0.0.1 only. Run `./gradlew shadowJar hookJar` and `npm install` in `test/` first, then
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
| `handover` | `hand-over`, `LandingService`, and a death that respawns at the landing spot, on a small made-up server | see below |

All but `return` and `handover` need `WAYSTATION_TEST_BASE`, a folder holding a copy made by `test/prepare-copy.sh` from a 26.x server that
isn't running. `WAYSTATION_TEST_JAVA` is the Java 25 to run it with, if `java` isn't. The bots are 26.1 clients;
`WAYSTATION_TEST_CLIENT` changes that. A server newer than 26.1 needs ViaVersion and ViaBackwards to let them in.
`WAYSTATION_TEST_PORT` changes the port from 25599, and must match the copy. The suites use generic world names.
If your copy's differ, `WAYSTATION_TEST_NAMES` points at a file mapping them, kept out of the repo. See
`test/run.mjs`. `test/hook/` holds a test-only plugin with an event listener and a stand-in economy.

`return` and `handover` need no copy of a server. They make fresh ones, so it needs `WAYSTATION_TEST_JARS`: a folder with
`paper-<version>.jar` (and `mojang_<version>.jar`, so Paper needn't download it) and a `plugins/` folder holding
ViaVersion, ViaBackwards and LuckPerms jars. Starting a server accepts the Minecraft EULA, so it also needs
`WAYSTATION_TEST_EULA=accept`. `WAYSTATION_RETURN_SOURCE` picks 26.1.2, 26.2 or 26.3, and `WAYSTATION_TWO_SERVERS=1`
adds a real second server to hop to, on another version. Without that, a stand-in answers the destination's ping.
`handover` also needs Multiverse-Core, Multiverse-Inventories and Vault jars in `plugins/`, and
`WAYSTATION_HANDOVER_VERSION` picks 26.1.2, 26.2 or 26.3. It runs on 127.0.0.1:25651.

## For other plugins

A plugin that runs a world of its own, such as a OneBlock add-on, can take players in at the station's gate and put
them back in front of it. Waystation never moves them for it.

**Taking players in.** Set `worlds.<name>.hand-over: true`. That world gets a station gate and no return gate. When
someone walks through, after every access check has passed and with their inventory screen closed, Waystation fires
`com.extantape.waystation.WaystationHandOverEvent` on the main thread: `getPlayer()`, `getGateId()`,
`getDestination()` (the world's name), `isHandled()` and `setHandled(boolean)`. Move the player and call
`setHandled(true)`. If nobody does, the player stays where they are, is told the world isn't open, and the console
says no plugin handled it.

**Putting players back.** `com.extantape.waystation.LandingService`, from
`Bukkit.getServicesManager().load(LandingService.class)`, has `Optional<Location> landing(String world)`: two blocks in
front of the station gate to that world, facing away from it, safe to stand on and clear of the gate. It's empty if
the station isn't built, has no gate to that world, or the spot isn't safe. Ask each time: the station lays itself out
again when worlds come and go. The service is null while Waystation isn't running.

In your `paper-plugin.yml`, so you load after Waystation and can see its classes:

```yaml
dependencies:
  server:
    Waystation: {load: BEFORE, required: true, join-classpath: true}
```

Build against the Waystation jar from its GitHub release as `compileOnly`. `WaystationAccessEvent` lets a plugin say no
to a gate, and is described in its source.

If the world has its own Multiverse-Inventories group, put the station's world in your lobby's group, so a trip or a
respawn between them swaps inventories cleanly.

## License

MIT. See `LICENSE`.
