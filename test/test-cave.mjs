// A test cave to walk in a real game client. Starts a throwaway server on 127.0.0.1 with Waycave on a small made-up
// world set (the one test/prepare-small.mjs makes), waits for the cave, and keeps running until you press Ctrl-C. Then it
// stops the server and deletes everything it made.
//
//   WAYCAVE_TEST_PLAYER   your Minecraft name (never written into the repo). The server is offline-mode, 127.0.0.1 only.
//   WAYCAVE_TEST_GROUP    family (default, sees most gates) or kids (sees two) - run it twice to compare
//   WAYCAVE_TEST_POSSE_JAR  optional, a Posse jar: with it, players you add see each other only within circles
// Plus the usual WAYCAVE_TEST_BASE, WAYCAVE_TEST_PORT and WAYCAVE_TEST_JAVA (see test/README in the main README).
// Join with a client of the server's version at 127.0.0.1:<port>. You arrive in the cave's start chamber.
import { copyFileSync, existsSync, rmSync } from 'node:fs'
import path from 'node:path'
import { Server, freshCopy, installJar, sleep, DIR, RUN, PORT } from './harness.mjs'

const player = process.env.WAYCAVE_TEST_PLAYER
if (!player || !/^\w{3,16}$/.test(player)) throw new Error('Set WAYCAVE_TEST_PLAYER to your Minecraft name (3 to 16 letters, digits or _)')
const group = process.env.WAYCAVE_TEST_GROUP === 'kids' ? 'kids' : 'family'
const s = new Server()
let stopping = false
async function finish() {
  if (stopping) return
  stopping = true
  console.log('\nStopping the server and deleting the test copy...')
  try { await s.stop() } catch { await s.kill9().catch(() => {}) }
  rmSync(RUN, { recursive: true, force: true })
  console.log(existsSync(RUN) ? 'Could not delete ' + RUN : 'Done. Nothing left behind.')
  process.exit(0)
}
process.on('SIGINT', finish)
process.on('SIGTERM', finish)

freshCopy()
if (process.env.WAYCAVE_TEST_POSSE_JAR) copyFileSync(process.env.WAYCAVE_TEST_POSSE_JAR, path.join(DIR, 'plugins', 'Posse.jar'))
installJar()
await s.start()
await s.waitFor(/The cave is built|failed its walk-through|plan has a flaw/, 240000)
await sleep(8000)
await s.ask(`op ${player}`, 300)
console.log(`\nThe test cave is ready. Join 127.0.0.1:${PORT} as ${player} (group: ${group}). Press Ctrl-C here when you're done.`)
let seen = 0
while (!stopping) {
  const joined = s.log.slice(seen).some(l => l.includes(`${player} joined the game`))
  seen = s.log.length
  if (joined) {
    await sleep(1500)
    await s.ask(`lp user ${player} parent add ${group}`, 400)
    await s.ask(`mvtp ${player} w:waycave`, 1500)
  }
  await sleep(1000)
}
