# Waycave

A lush, glowing cave of its own on a Paper server, with a gate to every Multiverse world. The cave is built once from
your world groups and is different on every server. Each player sees only their own trails and gates: a worn track of
moss and glow lichen from the start chamber to each world they can use. Waycave is the rebuilt and renamed Waystation
(0.8.0). No client mods.

**Status:** the cave, its trails, who-sees-whom and arrival passed a new bot suite on throwaway servers (see
[Tests](#tests) for versions and counts). **No real game client has seen it yet**, so how the cave and the trails look
and read is unchecked, and so is how the Trails board reads. It is not on any live server. Walking through gates on
Paper 26.3 can't be tested with bots (Paper 26.3 disconnects older clients that move behind ViaVersion), so the 26.3 run
covers what a bot can do without walking. The older suites were not rerun for 0.8.0 (see `docs/test-audit.md`).

Needs a real client, still untested:

- How the cave looks: its shape, light, moss, plants and vines, and the glow of the fake lichen along a trail.
- How the moss carpet trail feels to walk on, since the server sees plain floor under it.
- How gates, chamber signs, the Trails board and the return countdown look in game, and the arrival pieces below.
- What a client shows while it's transferred to another server and back.

Paper 26.1 or later, Java 25. Multiverse-Core 5 runs the cave and the automatic waygates. Without it, Waycave still does
fargates, gates you place yourself and the return command, for a server with one world. Optional: Multiverse-Inventories,
LuckPerms, GriefPrevention, Vault with an economy plugin, BlueMap and [Posse](https://github.com/ExtantApe/Posse) (who sees
whom in the cave).

## How it behaves

- **One cave per server, in a world of its own, `waycave`.** Organic tunnels and chambers, planned once from a seed and
  saved in `cave.yml`. There's a start chamber where every trail begins, a chamber for each world group, and a few side
  chambers with pools and groves, joined by winding tunnels three to five blocks wide. The world is a flat Multiverse
  world, with the cave built in stone far above its ground, so there's no sky and nothing outside to walk into.
- **It's always connected.** Before anything is built, the plan is checked: every chamber and gate place can be walked
  to from the start (floor, two blocks of headroom, no step over one block). After building, the real blocks are walked
  the same way. A cave that fails either check isn't used, and no gate goes in it.
- **It stays put as the server grows.** A new group grows a new chamber on a new branch. Nothing already built moves, so
  the way to a world stays where players learned it. A gate keeps its place when other worlds come and go.
- **It's lit and mob-free.** The only light is glowing berry vines, placed until every walkable block is at light 9 or
  more. Mob spawning, fire spread and time stay off.
- **Trails are fake blocks, sent to one player.** Moss carpet edged with glow lichen from the start chamber to each gate
  that player can use. Another player on the same spot sees plain floor. Nothing is placed in the world. A gate you lose
  access to loses its trail within a second.
- **Seeing other players follows Posse's groups.** In the cave, anyone Posse won't connect to you is hidden from you.
  Without Posse, or if Posse can't answer, nobody sees anyone. Add `waycave` to Posse's `circle-only-worlds`: otherwise
  everyone in the same world group hears, and so sees, each other.
- **You see a gate only if you can use it.** Otherwise there's nothing there, and walking through it does nothing.
  Servers can turn on `sealed-gates` instead, off by default, since even a sealed gate tells a player a line exists.
  Access is `multiverse.access.<world>`, read through Bukkit so LuckPerms contexts apply, and checked again on entry.
- **Gates are display entities**, not blocks. They place no blocks and aren't saved. A once-a-second pass redraws
  anything missing or moved.
- **Travel goes through Multiverse's teleporter**, so Multiverse-Inventories swaps inventories normally. In survival
  worlds you arrive at the world's gate, with 5 seconds of protection. Elsewhere you land where you last were.
- **You arrive at the end of your last trail.** Coming back to the cave puts you two blocks in front of the gate you last
  walked through, facing into the cave. If that gate is gone, you can't use it now, or the spot isn't safe, you arrive in
  the start chamber. Nobody arrives at a gate they can't use.
- **Players walk and click; they don't type.** Everything a player does in the cave is walking to a gate, reading the
  Trails board (which is also where a player buys a trail, below). The few player commands left are a hidden fallback that no text in the
  game teaches.
- **Deleted world:** the gate goes dark and stays until `/gate remove <id>`: a cracked grey frame with nothing inside,
  labelled "(trail closed)". If the world is only unloaded, the gate lights up again when it's back.
- **Labels read over anything.** Floating labels have a text shadow and a dark plate behind them. A gate's state is always
  in words as well as colour: "Locked", "(trail closed)", "Leaving this server".
- **Nobody can edit** the cave or the ground at a world's gate: no permission and no operator gets past this. To change
  it, set `protection.enabled: false` and `/gate reload`.

## OneBlock

OneBlock gives each player their own island, 500 blocks apart, so one fixed gate can't serve them. Waycave can
hand the player at the cave's OneBlock gate to a plugin that knows where they belong (`hand-over: true`, see
"For other plugins"), or run `/ob join` as the player (`enter-command`). Any island with someone on it gets a glowing post
beside its block, and clicking it twice takes you back to the cave. The post is display entities, so it places no
blocks and is never left behind when OneBlock reuses an island.

## Arriving players

- **Lobby gate.** The lobby world's gate to the cave stands by the lobby, not by the world's spawn, in its own design: a
  wide arch on two lantern posts. With [WelcomeWagon](https://github.com/ExtantApe/WelcomeWagon) its lobby is used,
  read by name, so WelcomeWagon stays optional. Otherwise set `arrival.lobby-world`. Move the lobby and the gate follows.
- **Welcome.** Walking into the cave the first time, or after 30 days away, you get short lines saying how many worlds and
  chambers you can reach. The time of your last visit is kept on your player file and shown to nobody else.
- **Trails board.** In front of the start chamber's monument. Each player sees their own: a heading for each chamber and the
  worlds they can use under it. A world they could reach before and can't now stays on it, reading **(trail closed)**. A
  world they have never been able to use is never named. The worlds a player has had a trail to are kept in `players.yml`,
  shown to nobody else. It changes within a second when their access does.
- **Buying menu.** A world with a price reads **(for sale: click the board)** on the board. Right-clicking the board (or
  left-clicking it) opens a menu of the gates that player could buy, each with its price and what they have. Clicking one
  asks "Buy it?" on a screen with a **Yes** and a **No** square, and only a second click on Yes buys. The same offers are
  also written in chat, with the price and balance spelled out and a name to click, so a screen reader or narrator can
  follow it. Locked gates say where to go. Nothing is offered for a world the player can't see a gate to (a child sees only
  their own group's), for a price in money without Vault and an economy plugin, or without `waycave.buy`; each click
  checks all of it again, and a player who can't afford it is told so and charged nothing. The board's click spot belongs
  to one player: nobody else is sent it, and a click on someone else's is ignored. The words are in `messages:` under `buy-menu-*`.
- **No Wayport gate yet (D301).** The start chamber's gate to another server is off in 0.8.0: nobody sees it and nobody can walk into it,
  even with Wayport installed. Wayport 0.4.0 made its command admin-only, and Waycave never runs it with borrowed rights. The gate returns when
  Wayport has an API for it.
- **No wall map.** The old wall of maps needs a flat wall. It's off in a cave (`wall-map.enabled: false`).

## Moving a waygate

Beside the waygate in each world listed in `move.allowed-worlds` is a tribute chest. It asks for hearts (6, or 3 in `move.gentle-worlds`). If you can't
spare them, it offers the pieces you're wearing that aren't named or enchanted, and you give up one. Pay, and the gate
moves near your bed in that world, with no promises on the exact spot, and then rests for a day.

Nothing goes into the chest: the menu only shows what you can give, and it's taken after the move is saved, so a crash
can't lose anything. Each tribute is written to the server log.

## Commands

| Command | Who | What |
|---|---|---|
| `/waygate` | everyone | Hidden fallback: coordinates of the closest waygate you can use |
| `/cave`, `/hub` | everyone | Hidden fallback: straight to the start chamber. Name and on/off in config |
| `/buygate [world]` | everyone | Hidden fallback: buy access to a priced world. Players buy from the Trails board menu, and no text teaches this command |
| `/return` | with `waycave.return` | To the other server after a countdown, if it's turned on. Name in config |
| `/gate list, station, create, fargate, move, link, remove, test, addworld, relayout, reseed, reload, describe` | admins | Admin |

Every `/gate` command also takes `--json <id>` at the end, for scripts and the Leaf Juice app, and then answers with one
`ea-result <id> {...}` line in the shared [Signalbox](https://github.com/ExtantApe/Signalbox) format. `/gate describe`
lists the commands and their arguments. `/gate list` answers in pages of 25 (up to 100 with a limit) with a cursor
for the next page, and `/gate station` gives the gate seed and the cave. The slow ones (`addworld`, `relayout`,
`reseed`) answer once the cave is built, with any problems in a list.

## Permissions

Gates never grant access: these only narrow it. A node blocks when it's explicitly set to **false**, for a
player or a group, with contexts if you like. Nothing changes until someone sets one.

| Node | Effect when false |
|---|---|
| `waycave.use.<world>` | Closes every gate to that world for them |
| `waycave.platform.<group>` | Closes a whole chamber |
| `waycave.gate.<gate id>` | Closes one gate |
| `waycave.move.<world>` | No moving waygates in that world |

| Node | Default | Effect when true |
|---|---|---|
| `waycave.admin` | false | All of `/gate` |
| `waycave.admin.<subcommand>` | false | One `/gate` subcommand, for a narrower role |
| `waycave.admin.seedead` | false | See dead gates |
| `waycave.find`, `.move`, `.escape`, `.buy` | true | `/waygate`, `/move`, `/cave`, `/buygate` |
| `waycave.return` | false | The return command. Name in config |

Other plugins can veto a gate by cancelling `com.extantape.waycave.WaycaveAccessEvent`.

## Build

```bash
./gradlew build
```

The jar is `build/libs/Waycave-0.8.0.jar`. Java 25.

## Install

With the server stopped:

1. Copy the jar to `plugins/`. Start the server, and it makes the `waycave` world, builds the cave (a few seconds) and
   puts a gate in it for every world. The default config lists no worlds, and doesn't need to. To name them, theme
   their chambers or turn on tribute chests, edit `plugins/Waycave/config.yml`. Set `cave.seed` before the first start to
   get the same cave twice; the plan is then saved in `cave.yml` and never changes.
2. To try it on an empty server, create `plugins/Waycave/config.yml` before the first start, holding only
   `example-worlds: true`. That makes `example_survival` and `example_creative`. It does nothing later on.
3. If you use Multiverse-Inventories, add the `waycave` world to a group, under `worlds:` in
   `plugins/Multiverse-Inventories/groups.yml`, then run `mvinv reload`. The server warns at startup until this is done.
4. To make gates the only way to travel, edit `setup/luckperms-gates-only.txt` for your worlds and groups, then
   paste it into the console. It takes `/mvtp` away from everyone but admins. The console keeps it.
5. If you use Posse, add `waycave` to `circle-only-worlds` in its `config.yml` and run `/posse reload`. That is what makes
   players in the cave see only the people their circles allow.

### Upgrading from Waystation

Waycave keeps no old names, commands, permissions or folders (D196), so a server being upgraded renames these with the
server **stopped**, before the first start. Each is a one-time step. A step missed shows as a clear warning in the console
and nothing half-works.

1. Remove `Waystation-*.jar` from `plugins/` and add `Waycave-0.8.0.jar`.
2. Rename the data folder: `plugins/Waystation` becomes `plugins/Waycave`. `config.yml`, `gates.yml` and `players.yml`
   come along. Then in `plugins/Waycave/config.yml`:
   - Rename the `station:` section to `cave:`. Set `cave.world: waycave`. Delete `cave.spacing`, `cave.aisle`,
     `cave.slots`, and `cave.tile-depth` if you want no floor tiles (the new default is 0).
   - Rename `departures-board:` to `trails-board:`.
   - Rename every `waystation.` in the file (the `move`, `escape` and `return` permission nodes) to `waycave.`.
   - Under `escape:`, `command: station` becomes `command: cave`.
   - Set `cave.y: 64`.
3. Permissions (LuckPerms), one per node you've granted or denied, for example
   `lp group <group> permission set waycave.<node> <true or false>` and the matching `permission unset waystation.<node>`.
   The nodes are `waystation.admin`, `waystation.admin.<subcommand>`, `waystation.buy`, `waystation.find`,
   `waystation.move`, `waystation.return`, `waystation.escape`, `waystation.use.<world>`, `waystation.platform.<group>`
   and `waystation.gate.<id>`. `waystation.admin.enlarge` is gone. Access to the new world is
   `multiverse.access.waystation` becoming `multiverse.access.waycave`.
4. Multiverse-Inventories `groups.yml`: replace the `waystation` world with `waycave` in the lobby's group.
5. BlueMap: rename `maps/waystation.conf` to `maps/waycave.conf` and use the new `setup/bluemap-waycave.conf`.
6. `plugins/Waystation/transfer.key`, if the arrival guard is on, moves with the folder.
7. Add `waycave` to Posse's `circle-only-worlds`.
8. Other plugins that read Waystation by name (OneVoid, WelcomeWagon) release in step with this one: OneVoid compiles
   against `WaycaveHandOverEvent` and `LandingService`.
9. Add the old `waystation` world to `exclude-worlds` in `plugins/Waycave/config.yml`. It stays on the server until you trash it, and without
   this it gets its own chamber and gate. Other plugins' configs that name the old world or `Waystation` (Rewild, MobSquad, Packmule,
   Posse and the like) change to `waycave`.
10. The old `waystation` world is never used again. After a backup exists in your normal backup set, move it to the Trash.
   Waycave re-points its return gates to the new world by itself and places the cave's gates again.

Players see the welcome once more, since the "last visit" stamp is stored under the plugin's own name.
### BlueMap (optional)

If BlueMap stops at startup, `plugins/BlueMap/core.conf` has `accept-download: false`. Setting it to true is
accepting Mojang's EULA, which is yours to do. Then copy `setup/bluemap-waycave.conf` to `plugins/BlueMap/maps/waycave.conf`.
If your `level-name` in `server.properties` isn't `world`, change `world:` in that file to match: on Paper 26.x every world
lives inside the main one, and the cave is its dimension `minecraft:waycave`. Then run `/bluemap reload`. Maps made
before 26.x need the same change, or BlueMap shows nothing for them.

Gates show as markers. BlueMap draws blocks only, so the cave's map shows flat ground with markers,
not the chambers. The map also shows every gate to anyone with the link, whoever can use them.

### Prices (optional)

Add `price: {xp-levels: 30, money: 500, items: {diamond: 5}}` to a world under `worlds:`. Its gate shows
as locked to people without access, and it appears in the buying menu on the Trails board. A purchase charges, runs
`buy.grant-commands`, and refunds if the access doesn't show up. `/buygate <world>` does the same as the hidden fallback. Money prices need Vault and an economy plugin. A purchase grants that one world, so add commands if it should bring the
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
`/gate fargate <id> <destination>` where you want it. Grant `waycave.fargate.<name>` in LuckPerms.
A player without it, or on a client version not in `protocols`, can't see the gate, and walking into it does
nothing. Both servers need `accepts-transfers=true`, and the other server needs its own way back. Coming
back, the player lands where they walked in from. `protocols` is set by hand, so update it when either
server upgrades.

Before sending anyone, a fargate checks the other server answers (`check-host` and `check-port` if its status
address differs). If it doesn't, the player stays put and is told, and nothing changes.

### Arrival guard (optional)

For a server players should only reach through another server's fargate. With `arrival-guard` on, a joining
player is checked while their connection is still being set up, before they reach any world, and is let in only
with a valid pass from the fargate: signed, for them, for this server, about a minute old at most, and never used
before. A direct join, a missing or reused pass, or someone else's pass is refused with `arrival-guard.message`.
There's no bypass, for admins either. The whitelist still applies.

1. On both servers, put the same key in `plugins/Waycave/transfer.key`: at least 32 random bytes, readable only
   by the server's user, for example `head -c 32 /dev/urandom > transfer.key && chmod 600 transfer.key`. Never
   put it in `config.yml`, a repo or chat. To change it, replace the file on both servers and restart both.
2. On the sending server, add `pass: true` to that server's entry under `fargate-destinations`.
3. On the guarded server, set `arrival-guard.enabled: true` and `arrival-guard.name` to the same name as that
   entry.

While the key is missing, too short or readable by others, the guarded server lets nobody in, and the sending
server sends nobody there, each with a console line saying what to fix.

A player put at a gate, or within a block of it, by a teleport or a respawn, by any plugin, has to step clear of it once before
it takes them anywhere. Otherwise a plugin that restores a player's last spot could drop them on the step into a
gate.

### Return command (optional)

A fargate you don't have to walk to. `/return` sends the player to one of your `fargate-destinations` after a
5-second countdown. Moving, taking damage, a harmful effect or a teleport cancels it, and the player is told why and
how to try again. The seconds left show above the hotbar, with a soft tick each second, so it can be followed by ear
too. This server checks the destination answers before sending anyone, so nobody waits out a countdown for a server
that's down, and a failed try changes nothing about the player. Coming back, they land where they stood.

Turn it on under `return:` in `config.yml`, name the destination, and restart. Then grant `waycave.return` in
LuckPerms to whoever should use it: nobody has it by default, operators included. The command's name and the
countdown are in the same section, and every line players see is under `messages:`.

### Without Multiverse-Core

Leave Multiverse-Core out and Waycave runs with Paper alone. There's no cave and no automatic waygates, since the
cave needs a world made for it, and so no `/cave`. Fargates, gates placed with `/gate create` or `/gate fargate`,
and the return command all work. The server log says which way it's running at startup.

## Tests

Bots on throwaway servers, 127.0.0.1 only. Run `./gradlew shadowJar hookJar` and `npm install` in `test/` first, then
`node test/run.mjs <suite>`.

| Suite | What it covers | Needs |
| --- | --- | --- |
| `acceptance` | gates, permissions, inventories, protection, tribute, buying, crashes | |
| `cave` | the cave, its trails, who sees whom, arrival, growth, and that the Wayport gate stays off | `WAYCAVE_TEST_POSSE_JAR`, `WAYCAVE_TEST_WAYPORT_JAR` |
| `fargate` | who sees a fargate, and coming back | |
| `defaults` | the default config and `example-worlds` | |
| `hop` | two servers, there and back through fargates | |
| `economy` | buying with a real economy plugin | `WAYCAVE_TEST_ECONOMY_JAR` |
| `buymenu` | the buying menu: choose, confirm, who is offered what, no Vault, nobody else's board | a Vault jar in `WAYCAVE_TEST_JARS/plugins` when making the small copy |
| `return` | the return command, and running without Multiverse-Core, on a fresh empty server | see below |
| `handover` | `hand-over`, `LandingService`, and a death that respawns at the landing spot, on a small made-up server | see below |

All but `return` and `handover` need `WAYCAVE_TEST_BASE`, a folder holding a copy made by `test/prepare-copy.sh` from a 26.x server that
isn't running. `WAYCAVE_TEST_JAVA` is the Java 25 to run it with, if `java` isn't. The bots are 26.1 clients;
`WAYCAVE_TEST_CLIENT` changes that. A server newer than 26.1 needs ViaVersion and ViaBackwards to let them in.
`WAYCAVE_TEST_PORT` changes the port from 25599, and must match the copy. The suites use generic world names.
If your copy's differ, `WAYCAVE_TEST_NAMES` points at a file mapping them, kept out of the repo. See
`test/run.mjs`. `test/hook/` holds a test-only plugin with an event listener and a stand-in economy.

`cave` runs on the small made-up server `test/prepare-small.mjs` makes (no copy of a real one), on 127.0.0.1 only. It
also needs `WAYCAVE_TEST_POSSE_JAR` and `WAYCAVE_TEST_WAYPORT_JAR`. On 26.2 and 26.3 set `WAYCAVE_TEST_NOWALK=1`: bots
that move are disconnected there, so those runs keep them still and skip the two checks that need a walk.

**A test cave to walk in a real client.** `test/test-cave.mjs` starts a throwaway server from that same small copy, builds the
cave, and keeps running until you press Ctrl-C, then deletes everything it made. Set `WAYCAVE_TEST_PLAYER` to your
Minecraft name (it is never written into the repo), `WAYCAVE_TEST_GROUP` to `family` or `kids` (run it twice to compare
what each sees), and the usual `WAYCAVE_TEST_BASE`, `WAYCAVE_TEST_PORT` and `WAYCAVE_TEST_JAVA`. Join `127.0.0.1:<port>`
with a client of the server's version; you arrive in the start chamber and the trails to your gates are already laid.
The short way: `zsh test/walk-cave.sh YOURNAME [family|kids] [26.3|26.2|26.1.2]` builds the jar, makes the small copy in a temp
folder (accepting the EULA for it), runs the test cave on 127.0.0.1:25725 and deletes the temp folder at Ctrl-C.

`return` and `handover` need no copy of a server. They make fresh ones, so it needs `WAYCAVE_TEST_JARS`: a folder with
`paper-<version>.jar` (and `mojang_<version>.jar`, so Paper needn't download it) and a `plugins/` folder holding
ViaVersion, ViaBackwards and LuckPerms jars. Starting a server accepts the Minecraft EULA, so it also needs
`WAYCAVE_TEST_EULA=accept`. `WAYCAVE_RETURN_SOURCE` picks 26.1.2, 26.2 or 26.3, and `WAYCAVE_TWO_SERVERS=1`
adds a real second server to hop to, on another version. Without that, a stand-in answers the destination's ping.
`handover` also needs Multiverse-Core, Multiverse-Inventories and Vault jars in `plugins/`, and
`WAYCAVE_HANDOVER_VERSION` picks 26.1.2, 26.2 or 26.3. It runs on 127.0.0.1:25651.

## For other plugins

A plugin that runs a world of its own, such as a OneBlock add-on, can take players in at the cave's gate and put
them back in front of it. Waycave never moves them for it.

**Taking players in.** Set `worlds.<name>.hand-over: true`. That world gets a cave gate and no return gate. When
someone walks through, after every access check has passed and with their inventory screen closed, Waycave fires
`com.extantape.waycave.WaycaveHandOverEvent` on the main thread: `getPlayer()`, `getGateId()`,
`getDestination()` (the world's name), `isHandled()` and `setHandled(boolean)`. Move the player and call
`setHandled(true)`. If nobody does, the player stays where they are, is told the world isn't open, and the console
says no plugin handled it.

**Putting players back.** `com.extantape.waycave.LandingService`, from
`Bukkit.getServicesManager().load(LandingService.class)`, has `Optional<Location> landing(String world)`: two blocks in
front of the cave gate to that world, facing away from it, safe to stand on and clear of the gate. It's empty if
the cave isn't built, has no gate to that world, or the spot isn't safe. Ask each time: the cave grows
again when worlds come and go. The service is null while Waycave isn't running.

In your `paper-plugin.yml`, so you load after Waycave and can see its classes:

```yaml
dependencies:
  server:
    Waycave: {load: BEFORE, required: true, join-classpath: true}
```

Build against the Waycave jar from its GitHub release as `compileOnly`. `WaycaveAccessEvent` lets a plugin say no
to a gate, and is described in its source.

If the world has its own Multiverse-Inventories group, put the `waycave` world in your lobby's group, so a trip or a
respawn between them swaps inventories cleanly.

## License

MIT. See `LICENSE`.
