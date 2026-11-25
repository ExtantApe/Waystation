// The arrival flow: the lobby gate into the station, and the welcome inside it. Two small made-up servers on
// 127.0.0.1:25653, one after the other: fresh Paper with Multiverse-Core and LuckPerms, first without WelcomeWagon (the lobby
// comes from Waycave's own config) and then with WelcomeWagon 0.3.0 (the lobby comes from WelcomeWagon, read by name).
// No copy of a real server.
//
// WAYCAVE_ARRIVAL_VERSION is 26.1.2 (default), 26.2 or 26.3. WELCOMEWAGON_JAR is a WelcomeWagon 0.3.0 jar.
// ./gradlew clean shadowJar, then:
// WAYCAVE_TEST_JARS=<folder> WAYCAVE_TEST_EULA=accept WAYCAVE_TEST_JAVA=<java 25> WELCOMEWAGON_JAR=<jar> node test/arrival.mjs
import { JAR, freshServer, writeConfig, pluginJar, Server, bot, sleep, check, summary, BASE } from './return-harness.mjs'
import { existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'

const V = process.env.WAYCAVE_ARRIVAL_VERSION ?? '26.1.2'
if (!['26.1.2', '26.2', '26.3'].includes(V)) throw new Error('WAYCAVE_ARRIVAL_VERSION must be 26.1.2, 26.2 or 26.3')
const WELCOMEWAGON = process.env.WELCOMEWAGON_JAR
if (!WELCOMEWAGON || !existsSync(WELCOMEWAGON)) throw new Error('Set WELCOMEWAGON_JAR to a WelcomeWagon jar')
const PORT = 25653, LABEL = '<gold><bold>The Waycave</bold>'

/** One gate from gates.yml, read as plain text, so the test doesn't trust the plugin's own reader. */
function gate(folder, id) {
  const f = path.join(folder, 'plugins/Waycave/gates.yml')
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
  for (const p of ['multiverse.access.world', 'multiverse.access.waycave']) await s.ask(`lp group default permission set ${p} true`, 400)
}

console.log(`Arrival suite on Paper ${V}, port ${PORT}; servers in ${BASE}`)

// --- without WelcomeWagon: the lobby is Waycave's own setting ---
{
  const folder = freshServer('arrival', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core')] })
  writeConfig(folder, [['lobby-world: ""', 'lobby-world: world'], ['# lobby-spot: {x: 0, y: 65, z: 0}', 'lobby-spot: {x: 60.5, y: -60, z: 60.5}']])
  const s = new Server(folder, V, PORT)
  try {
    await s.start()
    await setup(s)
    const g = await until(() => { const x = gate(folder, 'return-world'); return x && x.label === LABEL ? x : null })
    check('without WelcomeWagon, the configured lobby world gets a lobby gate with the lobby label', !!g, JSON.stringify(g))
    check('it stands by the lobby spot, not the world spawn', g && dist(g, 60.5, 60.5) <= 24 && dist(g, 0.5, 0.5) > 24, g && `${g.x},${g.z}`)
    check('it leads to the station', g?.destination === 'waycave')

    const b = await bot('Newcomer', { port: PORT, physics: V !== '26.3' })
    await s.ask('mvtp Newcomer waycave', 2500)
    const welcome = b.chat_log.filter(m => /Waycave|reach|glowing trail/.test(m))
    check('walking into the station for the first time, they get the welcome', welcome.some(m => m.includes('Welcome to the Waycave.')), welcome.join(' | '))
    check('it counts what this player can reach', welcome.some(m => /From here you can reach (no worlds yet|\d+ worlds?) on (no chambers yet|\d+ chambers?)\./.test(m)), welcome.join(' | '))
    const before = b.chat_log.length
    await s.ask('mvtp Newcomer world', 2000)
    await s.ask('mvtp Newcomer waycave', 2500)
    check('coming back soon after, they aren\'t welcomed again', !b.chat_log.slice(before).some(m => m.includes('Welcome to the Waycave.')))
    b.quit(); await sleep(1500)
    const b2 = await bot('Newcomer', { port: PORT, physics: V !== '26.3' })
    await sleep(1500)
    check('nor when they log back in inside the station', !b2.chat_log.some(m => m.includes('Welcome to the Waycave.')))
    b2.quit(); await sleep(1000)

    // Who sees what on the Trails board, and the welcome, is covered by the cave suite. The old Departures Board and wall map are gone.
    await s.ask('mv create meadow normal --world-type flat --no-structures', 10000)
    await s.ask('lp user Newcomer permission set multiverse.access.meadow true', 800)
    await until(() => gate(folder, 'station-meadow'), 60000)
    const nb = await bot('Newcomer', { port: PORT, physics: false })
    const ob = await bot('Other', { port: PORT, physics: false })
    for (const n of ['Newcomer', 'Other']) await s.ask(`mvtp ${n} waycave`, 2000)
    await sleep(3000)
    // Sealed gates: off by default, so a gate you can't use isn't sent to you at all. The station's layout is random,
    // so the platform's own sign may stand beside this gate; the checks look at the gate's own parts and its name.
    const sm = gate(folder, 'station-meadow')
    const atGate = bb => Object.values(bb.entities).filter(e => /display/.test(e.name) && Math.hypot(e.position.x - (sm.x + 0.5), e.position.z - (sm.z + 0.5)) < 3)
    const named = list => list.some(e => /Meadow/.test(JSON.stringify(e.metadata)))
    const goTo = async (n, dz) => s.ask(`execute in waycave run tp ${n} ${sm.x + 0.5} ${sm.y} ${sm.z + 0.5 + dz}`, 600)
    await goTo('Newcomer', 4); await goTo('Other', -4); await sleep(3000)
    const blocks = bb => atGate(bb).filter(e => e.name === 'block_display').length
    const baseBlocks = blocks(ob)
    check('sealing off: the gate and its name aren\'t sent to someone who can\'t use it', !named(atGate(ob)) && blocks(nb) > baseBlocks && named(atGate(nb)), `${baseBlocks} vs ${blocks(nb)} block parts`)
    const cfgS = path.join(folder, 'plugins/Waycave/config.yml')
    writeFileSync(cfgS, readFileSync(cfgS, 'utf8').replace('sealed-gates:\n  enabled: false', 'sealed-gates:\n  enabled: true'))
    await s.ask('gate reload', 1500); await sleep(3000)
    check('sealing on: they see the gate, walled up', blocks(ob) > baseBlocks, `${baseBlocks} -> ${blocks(ob)} block parts`)
    check('with no name on it', !named(atGate(ob)))
    check('while someone who can use it still sees it open, with its name', named(atGate(nb)))
    writeFileSync(cfgS, readFileSync(cfgS, 'utf8').replace('  hidden-groups: []', `  hidden-groups: ["${sm.group}"]`))
    await s.ask('gate reload', 1500); await sleep(3000)
    check('a chamber in hidden-groups stays hidden even with sealing on', !named(atGate(ob)) && blocks(ob) === baseBlocks, `${blocks(ob)} block parts vs ${baseBlocks} before, group ${sm.group}`)
    writeFileSync(cfgS, readFileSync(cfgS, 'utf8').replace('sealed-gates:\n  enabled: true', 'sealed-gates:\n  enabled: false').replace(`  hidden-groups: ["${sm.group}"]`, '  hidden-groups: []'))
    await s.ask('gate reload', 1500)
    nb.quit(); ob.quit(); await sleep(1500)

    const cfg = path.join(folder, 'plugins/Waycave/config.yml')
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
    check('gate list answers in pages, with each gate\'s state', all?.ok && Array.isArray(all.items) && all.items.some(g => g.id === 'return-world' && g.state === 'open' && g.destination === 'waycave'), JSON.stringify(all)?.slice(0, 200))
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
    check('a player without waycave.admin gets no-permission, and no list', pb.chat_log.some(m => m.includes('ea-result w11') && m.includes('"code":"no-permission"')) && !pb.chat_log.some(m => m.includes('"items"')), pb.chat_log.join(' | ').slice(0, 200))
    pb.quit(); await sleep(1000)
    const human = (await s.ask('gate list', 1500)).join('\n')
    check('without --json, gate list reads as before', /Gate seed -?\d+, cave of/.test(human) && /return-world: world /.test(human) && !/ea-result/.test(human), human.slice(0, 200))
    check('no Waycave errors', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
  } catch (e) { check('the run without WelcomeWagon finished', false, e.message) } finally { await s.stop(); rmSync(folder, { recursive: true, force: true }) }
}

// --- with WelcomeWagon: its lobby wins, and its arrival event moves the gate when the lobby moves ---
{
  const folder = freshServer('arrival-lk', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core'), WELCOMEWAGON] })
  writeConfig(folder, [['lobby-world: ""', 'lobby-world: world']]) // WelcomeWagon's lobby must win over this
  mkdirSync(path.join(folder, 'plugins/WelcomeWagon'), { recursive: true })
  const lk = path.join(folder, 'plugins/WelcomeWagon/config.yml')
  const lkConfig = spot => {
    const shipped = execFileSync('unzip', ['-p', WELCOMEWAGON, 'config.yml'], { encoding: 'utf8' }) // the jar's own default config, wherever the jar is kept
    writeFileSync(lk, shipped.slice(0, shipped.indexOf('\nlobby:')) + `\nlobby:\n  world: hub\n  spawn: {x: ${spot[0]}, y: -60, z: ${spot[1]}}\n  send: always\n`)
  }
  lkConfig([30.5, 30.5])
  const s = new Server(folder, V, PORT)
  try {
    await s.start()
    await setup(s)
    check('Waycave found WelcomeWagon\'s lobby by name', s.log.some(l => /Using WelcomeWagon's lobby for the lobby gate/.test(l)))
    await s.ask('mv create hub normal --world-type flat --no-structures', 10000)
    await s.ask('lp group default permission set multiverse.access.hub true', 400)
    await s.ask('welcomewagon reload', 1500)
    // A join fires WelcomeWagon's PlayerArrivedEvent, which Waycave reads by name and acts on.
    const b = await bot('Visitor', { port: PORT, physics: V !== '26.3' })
    const g = await until(() => { const x = gate(folder, 'return-hub'); return x && x.label === LABEL ? x : null }, 60000)
    check('with WelcomeWagon, its lobby world gets the lobby gate', !!g, JSON.stringify(gate(folder, 'return-hub')))
    check('by WelcomeWagon\'s lobby spot', g && dist(g, 30.5, 30.5) <= 24, g && `${g.x},${g.z}`)
    const w = gate(folder, 'return-world')
    check('and Waycave\'s own lobby-world setting gives way to it', !w || w.label !== LABEL, JSON.stringify(w))
    b.quit(); await sleep(1500)

    lkConfig([-120.5, 90.5])
    await s.ask('welcomewagon reload', 1500)
    const b2 = await bot('Visitor', { port: PORT, physics: V !== '26.3' })
    const moved = await until(() => { const x = gate(folder, 'return-hub'); return x && dist(x, -120.5, 90.5) <= 24 ? x : null }, 60000)
    check('when WelcomeWagon\'s lobby moves, the next arrival puts the gate by the new spot', !!moved, JSON.stringify(gate(folder, 'return-hub')))
    b2.quit(); await sleep(1000)
    check('no Waycave errors', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
  } catch (e) { check('the run with WelcomeWagon finished', false, e.message) } finally { await s.stop(); rmSync(folder, { recursive: true, force: true }) }
}

summary()
