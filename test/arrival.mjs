// The arrival flow: the lobby gate into the station, and the welcome inside it. Two small made-up servers on
// 127.0.0.1:25653, one after the other: fresh Paper with Multiverse-Core and LuckPerms, first without Latchkey (the lobby
// comes from Waystation's own config) and then with Latchkey 0.3.0 (the lobby comes from Latchkey, read by name).
// No copy of a real server.
//
// WAYSTATION_ARRIVAL_VERSION is 26.1.2 (default), 26.2 or 26.3. LATCHKEY_JAR is a Latchkey 0.3.0 jar.
// ./gradlew clean shadowJar, then:
// WAYSTATION_TEST_JARS=<folder> WAYSTATION_TEST_EULA=accept WAYSTATION_TEST_JAVA=<java 25> LATCHKEY_JAR=<jar> node test/arrival.mjs
import { JAR, freshServer, writeConfig, pluginJar, Server, bot, sleep, check, summary, BASE } from './return-harness.mjs'
import { existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'

const V = process.env.WAYSTATION_ARRIVAL_VERSION ?? '26.1.2'
if (!['26.1.2', '26.2', '26.3'].includes(V)) throw new Error('WAYSTATION_ARRIVAL_VERSION must be 26.1.2, 26.2 or 26.3')
const LATCHKEY = process.env.LATCHKEY_JAR
if (!LATCHKEY || !existsSync(LATCHKEY)) throw new Error('Set LATCHKEY_JAR to a Latchkey 0.3.0 jar')
const PORT = 25653, LABEL = '<gold><bold>Central Station</bold>'

/** One gate from gates.yml, read as plain text, so the test doesn't trust the plugin's own reader. */
function gate(folder, id) {
  const f = path.join(folder, 'plugins/Waystation/gates.yml')
  if (!existsSync(f)) return null
  const lines = readFileSync(f, 'utf8').split('\n')
  const i = lines.findIndex(l => l === `  ${id}:`)
  if (i < 0) return null
  const g = { id }
  for (let j = i + 1; j < lines.length && lines[j].startsWith('    '); j++) {
    const m = /^    ([a-z-]+): (.*)$/.exec(lines[j])
    if (m) g[m[1]] = m[2].replace(/^'(.*)'$/, '$1')
  }
  for (const k of ['x', 'y', 'z']) g[k] = Number(g[k])
  return g
}
const dist = (g, x, z) => Math.hypot(g.x + 0.5 - x, g.z + 0.5 - z)
async function until(fn, ms = 45000) { const end = Date.now() + ms; while (Date.now() < end) { const v = fn(); if (v) return v; await sleep(500) } return fn() }
async function setup(s) {
  for (const p of ['multiverse.access.world', 'multiverse.access.waystation']) await s.ask(`lp group default permission set ${p} true`, 400)
}

console.log(`Arrival suite on Paper ${V}, port ${PORT}; servers in ${BASE}`)

// --- without Latchkey: the lobby is Waystation's own setting ---
{
  const folder = freshServer('arrival', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core')] })
  writeConfig(folder, [['lobby-world: ""', 'lobby-world: world'], ['# lobby-spot: {x: 0, y: 65, z: 0}', 'lobby-spot: {x: 60.5, y: -60, z: 60.5}']])
  const s = new Server(folder, V, PORT)
  try {
    await s.start()
    await setup(s)
    const g = await until(() => { const x = gate(folder, 'return-world'); return x && x.label === LABEL ? x : null })
    check('without Latchkey, the configured lobby world gets a lobby gate with the lobby label', !!g, JSON.stringify(g))
    check('it stands by the lobby spot, not the world spawn', g && dist(g, 60.5, 60.5) <= 24 && dist(g, 0.5, 0.5) > 24, g && `${g.x},${g.z}`)
    check('it leads to the station', g?.destination === 'waystation')

    const b = await bot('Newcomer', { port: PORT, physics: V !== '26.3' })
    await s.ask('mvtp Newcomer waystation', 2500)
    const welcome = b.chat_log.filter(m => /Central Station|reach|waygate/.test(m))
    check('walking into the station for the first time, they get the welcome', welcome.some(m => m.includes('Welcome to Central Station.')), welcome.join(' | '))
    check('it counts what this player can reach', welcome.some(m => /From here you can reach (no worlds yet|\d+ worlds?) on (no platforms yet|\d+ platforms?)\./.test(m)), welcome.join(' | '))
    const before = b.chat_log.length
    await s.ask('mvtp Newcomer world', 2000)
    await s.ask('mvtp Newcomer waystation', 2500)
    check('coming back soon after, they aren\'t welcomed again', !b.chat_log.slice(before).some(m => m.includes('Welcome to Central Station.')))
    b.quit(); await sleep(1500)
    const b2 = await bot('Newcomer', { port: PORT, physics: V !== '26.3' })
    await sleep(1500)
    check('nor when they log back in inside the station', !b2.chat_log.some(m => m.includes('Welcome to Central Station.')))
    b2.quit(); await sleep(1000)

    // The Departures Board: each player sees their own, listing only what they can use, and it follows access.
    await s.ask('mv create meadow normal --world-type flat --no-structures', 10000)
    await s.ask('lp user Newcomer permission set multiverse.access.meadow true', 800)
    await until(() => gate(folder, 'station-meadow'), 60000)
    const boardOf = bb => Object.values(bb.entities).filter(e => e.name === 'text_display' && Math.hypot(e.position.x - 0.5, e.position.z - 5.5) < 1.5)
    const textOf = bb => JSON.stringify(boardOf(bb).map(e => e.metadata))
    const nb = await bot('Newcomer', { port: PORT, physics: false })
    const ob = await bot('Other', { port: PORT, physics: false })
    // The wall map: every map packet each bot receives, by map id.
    for (const bb of [nb, ob]) { bb.maps = {}; bb._client.on('map', d => { const m = (bb.maps[d.itemDamage ?? d.mapId] ??= { icons: [], data: null }); m.icons = d.icons ?? []; if (d.data) m.data = Buffer.from(d.data).toString('hex') }) }
    for (const n of ['Newcomer', 'Other']) await s.ask(`mvtp ${n} waystation`, 2000)
    await s.ask('execute in waystation run tp Newcomer 0.5 -60 9.5', 800); await s.ask('execute in waystation run tp Other 1.5 -60 9.5', 800)
    await sleep(3000)
    check('each player sees exactly one board', boardOf(nb).length === 1 && boardOf(ob).length === 1, `${boardOf(nb).length} and ${boardOf(ob).length}`)
    check('it lists the worlds they can use, under their platform', /Departures/.test(textOf(nb)) && /Meadow/.test(textOf(nb)) && /Platform \d/.test(textOf(nb)), textOf(nb).slice(0, 300))
    check('and not worlds they can\'t', /Departures/.test(textOf(ob)) && !/Meadow/.test(textOf(ob)), textOf(ob).slice(0, 300))
    // Stand them in front of the wall map, apart, and give the maps a moment to be sent.
    await s.ask('execute in waystation run tp Newcomer -3.5 -60 -3.5 0 0', 600); await s.ask('execute in waystation run tp Other 4.5 -60 -2.5 0 0', 600)
    await sleep(4000)
    const ids = path.join(folder, 'plugins/Waystation/wallmap.yml')
    check('the wall map\'s ids are saved for reuse', existsSync(ids) && (readFileSync(ids, 'utf8').match(/^- \d+$/mg) ?? []).length === 4, existsSync(ids) ? readFileSync(ids, 'utf8') : 'missing')
    const icons = bb => Object.values(bb.maps).flatMap(m => m.icons)
    check('each player is sent the wall map, with exactly one marker: their own', icons(nb).length === 1 && icons(ob).length === 1, `${icons(nb).length} and ${icons(ob).length} markers on ${Object.keys(nb.maps).length} maps`)
    const where = bb => { const i = icons(bb)[0]; return i ? `${i.x},${i.z ?? i.y}` : '' }
    check('the markers are where each player stands, so they differ', where(nb) && where(ob) && where(nb) !== where(ob), `${where(nb)} vs ${where(ob)}`)
    const pixels = bb => Object.keys(bb.maps).sort().map(k => bb.maps[k].data).join('|')
    check('each player\'s picture shows only their own gates, so it differs with access', pixels(nb) && pixels(ob) && pixels(nb) !== pixels(ob))
    // Sealed gates: off by default, so a gate you can't use isn't sent to you at all. The station's layout is random,
    // so the platform's own sign may stand beside this gate; the checks look at the gate's own parts and its name.
    const sm = gate(folder, 'station-meadow')
    const atGate = bb => Object.values(bb.entities).filter(e => /display/.test(e.name) && Math.hypot(e.position.x - (sm.x + 0.5), e.position.z - (sm.z + 0.5)) < 3)
    const named = list => list.some(e => /Meadow/.test(JSON.stringify(e.metadata)))
    const goTo = async (n, dz) => s.ask(`execute in waystation run tp ${n} ${sm.x + 0.5} ${sm.y} ${sm.z + 0.5 + dz}`, 600)
    await goTo('Newcomer', 4); await goTo('Other', -4); await sleep(3000)
    const blocks = bb => atGate(bb).filter(e => e.name === 'block_display').length
    const baseBlocks = blocks(ob)
    check('sealing off: the gate and its name aren\'t sent to someone who can\'t use it', !named(atGate(ob)) && blocks(nb) > baseBlocks && named(atGate(nb)), `${baseBlocks} vs ${blocks(nb)} block parts`)
    const cfgS = path.join(folder, 'plugins/Waystation/config.yml')
    writeFileSync(cfgS, readFileSync(cfgS, 'utf8').replace('sealed-gates:\n  enabled: false', 'sealed-gates:\n  enabled: true'))
    await s.ask('gate reload', 1500); await sleep(3000)
    check('sealing on: they see the gate, walled up', blocks(ob) > baseBlocks, `${baseBlocks} -> ${blocks(ob)} block parts`)
    check('with no name on it', !named(atGate(ob)))
    check('while someone who can use it still sees it open, with its name', named(atGate(nb)))
    writeFileSync(cfgS, readFileSync(cfgS, 'utf8').replace('  hidden-groups: []', `  hidden-groups: ["${sm.group}"]`))
    await s.ask('gate reload', 1500); await sleep(3000)
    check('a platform in hidden-groups stays hidden even with sealing on', !named(atGate(ob)) && blocks(ob) === baseBlocks, `${blocks(ob)} block parts vs ${baseBlocks} before, group ${sm.group}`)
    writeFileSync(cfgS, readFileSync(cfgS, 'utf8').replace('sealed-gates:\n  enabled: true', 'sealed-gates:\n  enabled: false').replace(`  hidden-groups: ["${sm.group}"]`, '  hidden-groups: []'))
    await s.ask('gate reload', 1500)
    await s.ask('lp user Other permission set multiverse.access.meadow true', 3000)
    check('a new grant shows on their board within seconds', /Meadow/.test(textOf(ob)), textOf(ob).slice(0, 200))
    const boards = async () => {
      const out = (await s.ask('execute in waystation if entity @e[type=minecraft:text_display,x=-1,y=-60,z=4,dx=3,dy=4,dz=3]', 800)).join(' ')
      if (process.env.ARRIVAL_DEBUG) console.log('  console: ' + out.slice(0, 300))
      return Number(/count: (\d+)/i.exec(out)?.[1] ?? 0)
    }
    const boardsBefore = await boards()
    await s.ask('mvtp Other world', 2500); await sleep(1500)
    const boardsAfter = await boards()
    check('one board per player in the station, and leaving takes yours away', boardsBefore === 2 && boardsAfter === 1, `${boardsBefore} -> ${boardsAfter}`)
    nb.quit(); ob.quit(); await sleep(1500)

    const cfg = path.join(folder, 'plugins/Waystation/config.yml')
    writeFileSync(cfg, readFileSync(cfg, 'utf8').replace('lobby-spot: {x: 60.5, y: -60, z: 60.5}', 'lobby-spot: {x: -150.5, y: -60, z: 40.5}'))
    await s.ask('gate reload', 1500)
    const moved = await until(() => { const x = gate(folder, 'return-world'); return x && dist(x, -150.5, 40.5) <= 24 ? x : null })
    check('moving the lobby spot and reloading puts the gate by the new spot', !!moved, JSON.stringify(gate(folder, 'return-world')))

    // /gate --json (Signalbox format 1), for scripts and the Leaf Juice app.
    const j = async (c, id, ms = 1500) => {
      const l = (await s.ask(`gate ${c} --json ${id}`, ms)).find(x => x.includes('ea-result ' + id + ' '))
      return l ? JSON.parse(l.slice(l.indexOf('{', l.indexOf('ea-result')))) : null
    }
    const d = await j('describe', 'w1')
    check('gate describe lists its commands in format 1', d?.ok && d.format === 1 && ['list', 'create', 'fargate', 'test', 'relayout'].every(n => d.commands.some(c => c.name === n)), JSON.stringify(d)?.slice(0, 200))
    const all = await j('list', 'w2')
    check('gate list answers in pages, with each gate\'s state', all?.ok && Array.isArray(all.items) && all.items.some(g => g.id === 'return-world' && g.state === 'open' && g.destination === 'waystation'), JSON.stringify(all)?.slice(0, 200))
    const p1 = await j('list 1', 'w3'), p2 = p1?.next ? await j('list 1 ' + p1.next, 'w4') : null
    check('a page of one has a cursor to the next, which gives a different gate', p1?.items?.length === 1 && p1.next === 'g1' && p2?.items?.length === 1 && p2.items[0].id !== p1.items[0].id, JSON.stringify([p1?.next, p2?.items?.[0]?.id]))
    const made = await j('create jsontest world world 30 -60 30 north', 'w5')
    check('gate create answers with the new gate', made?.ok && made.gate?.id === 'jsontest' && made.gate.facing === 'north', JSON.stringify(made))
    const again = await j('create jsontest world world 30 -60 30 north', 'w6')
    check('creating it twice is refused with already-exists', again?.ok === false && again.error.code === 'already-exists', JSON.stringify(again))
    const tested = await j('test jsontest', 'w7')
    check('gate test answers with the gate and each player', tested?.ok && tested.gate?.id === 'jsontest' && Array.isArray(tested.players), JSON.stringify(tested)?.slice(0, 200))
    const gone = await j('remove jsontest', 'w8'), missing = await j('remove jsontest', 'w9')
    check('remove works once, then says not-found', gone?.ok && gone.removed === 'jsontest' && missing?.ok === false && missing.error.code === 'not-found', JSON.stringify([gone, missing]))
    const nope = await j('frobnicate', 'w10')
    check('an unknown subcommand still gets one answer', nope?.ok === false && nope.error.code === 'unknown-command', JSON.stringify(nope))
    const pb = await bot('Plain', { port: PORT, physics: V !== '26.3' })
    pb.chat('/gate list --json w11'); await sleep(1500)
    check('a player without waystation.admin gets no-permission, and no list', pb.chat_log.some(m => m.includes('ea-result w11') && m.includes('"code":"no-permission"')) && !pb.chat_log.some(m => m.includes('"items"')), pb.chat_log.join(' | ').slice(0, 200))
    pb.quit(); await sleep(1000)
    const human = (await s.ask('gate list', 1500)).join('\n')
    check('without --json, gate list reads as before', /Station seed -?\d+, plan/.test(human) && /return-world: world /.test(human) && !/ea-result/.test(human), human.slice(0, 200))
    check('no Waystation errors', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
  } catch (e) { check('the run without Latchkey finished', false, e.message) } finally { await s.stop(); rmSync(folder, { recursive: true, force: true }) }
}

// --- with Latchkey: its lobby wins, and its arrival event moves the gate when the lobby moves ---
{
  const folder = freshServer('arrival-lk', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core'), LATCHKEY] })
  writeConfig(folder, [['lobby-world: ""', 'lobby-world: world']]) // Latchkey's lobby must win over this
  mkdirSync(path.join(folder, 'plugins/Latchkey'), { recursive: true })
  const lk = path.join(folder, 'plugins/Latchkey/config.yml')
  const lkConfig = spot => {
    const shipped = readFileSync(path.join(path.dirname(LATCHKEY), '../../src/main/resources/config.yml'), 'utf8')
    writeFileSync(lk, shipped.slice(0, shipped.indexOf('\nlobby:')) + `\nlobby:\n  world: hub\n  spawn: {x: ${spot[0]}, y: -60, z: ${spot[1]}}\n  send: always\n`)
  }
  lkConfig([30.5, 30.5])
  const s = new Server(folder, V, PORT)
  try {
    await s.start()
    await setup(s)
    check('Waystation found Latchkey\'s lobby by name', s.log.some(l => /Using Latchkey's lobby for the lobby gate/.test(l)))
    await s.ask('mv create hub normal --world-type flat --no-structures', 10000)
    await s.ask('lp group default permission set multiverse.access.hub true', 400)
    await s.ask('latchkey reload', 1500)
    // A join fires Latchkey's PlayerArrivedEvent, which Waystation reads by name and acts on.
    const b = await bot('Visitor', { port: PORT, physics: V !== '26.3' })
    const g = await until(() => { const x = gate(folder, 'return-hub'); return x && x.label === LABEL ? x : null }, 60000)
    check('with Latchkey, its lobby world gets the lobby gate', !!g, JSON.stringify(gate(folder, 'return-hub')))
    check('by Latchkey\'s lobby spot', g && dist(g, 30.5, 30.5) <= 24, g && `${g.x},${g.z}`)
    const w = gate(folder, 'return-world')
    check('and Waystation\'s own lobby-world setting gives way to it', !w || w.label !== LABEL, JSON.stringify(w))
    b.quit(); await sleep(1500)

    lkConfig([-120.5, 90.5])
    await s.ask('latchkey reload', 1500)
    const b2 = await bot('Visitor', { port: PORT, physics: V !== '26.3' })
    const moved = await until(() => { const x = gate(folder, 'return-hub'); return x && dist(x, -120.5, 90.5) <= 24 ? x : null }, 60000)
    check('when Latchkey\'s lobby moves, the next arrival puts the gate by the new spot', !!moved, JSON.stringify(gate(folder, 'return-hub')))
    b2.quit(); await sleep(1000)
    check('no Waystation errors', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
  } catch (e) { check('the run with Latchkey finished', false, e.message) } finally { await s.stop(); rmSync(folder, { recursive: true, force: true }) }
}

summary()
