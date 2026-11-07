// Runs one suite: node test/run.mjs <acceptance|station|fargate|defaults>
// The suites use generic world and group names. If your test server's names differ, point
// WAYSTATION_TEST_NAMES at a JSON file outside the repo, like {"meadow": "my_world"}, and they're
// swapped in a generated copy of the suites, in test/.run. Longest name first, whole text.
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const here = path.dirname(new URL(import.meta.url).pathname)
const suite = process.argv[2]
if (!suite || !existsSync(path.join(here, suite + '.mjs'))) throw new Error('Usage: node test/run.mjs <suite>')
const file = process.env.WAYSTATION_TEST_NAMES
const names = file ? Object.entries(JSON.parse(readFileSync(file, 'utf8'))).sort((a, b) => b[0].length - a[0].length) : []
const swap = text => names.reduce((t, [from, to]) => t.replaceAll(from, to), text)

const out = path.join(here, '.run')
rmSync(out, { recursive: true, force: true })
mkdirSync(out)
for (const f of readdirSync(here).filter(f => f.endsWith('.mjs') && f !== 'run.mjs')) {
  writeFileSync(path.join(out, f), swap(readFileSync(path.join(here, f), 'utf8')))
}
writeFileSync(path.join(out, 'luckperms-gates-only.txt'), swap(readFileSync(path.join(here, '../setup/luckperms-gates-only.txt'), 'utf8')))
const { status } = spawnSync('node', [path.join(out, suite + '.mjs')], { stdio: 'inherit' })
process.exit(status ?? 1)
