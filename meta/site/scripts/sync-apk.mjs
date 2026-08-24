// Stage the signed release APK into the site build and publish its metadata.
//
// Reads the already-built, already-signed APK out of the repo-root dist/ (put
// there by scripts/collect-release.sh) — it NEVER invokes Gradle and never
// builds anything.
//
// The cert assertion below is the load-bearing part. Google Play's app-signing
// key is our own gtwake.phone key, so a Play install and this file share a
// signing certificate. That is what lets users move between the two channels
// without uninstalling, and what satisfies the watch's Wear Engine allow-list
// (watch-app/entry/src/main/config.json -> supportLists). Publishing an APK
// signed with anything else breaks both, silently. So: hard fail.
//
// Usage: node scripts/sync-apk.mjs [--apk <path>]
// Env:   GTWAKE_APK                  override the source APK path
//        GTWAKE_SITE_ALLOW_NO_APK=1  no APK present is OK (leave tree untouched)
//        GTWAKE_SKIP_CERT_CHECK=1    skip the signature assertion (escape hatch)
//        APKSIGNER                   explicit apksigner path
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs'
import path from 'node:path'

const EXPECTED_CERT_SHA256 =
  '95f6001e2b44692cce6fe6663a98aa2bddb6ae557db77dbba346d03dbc33be5c'
const WARN_BYTES = 20 * 1024 * 1024
const FAIL_BYTES = 25 * 1024 * 1024 // Cloudflare Pages per-file ceiling

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SITE_ROOT = path.resolve(HERE, '..')
const REPO_ROOT = path.resolve(SITE_ROOT, '..', '..')
const GRADLE = path.join(REPO_ROOT, 'android-app', 'app', 'build.gradle.kts')
const LIBS = path.join(REPO_ROOT, 'android-app', 'gradle', 'libs.versions.toml')
const APK_DIR = path.join(SITE_ROOT, 'public', 'apk')
const META = path.join(SITE_ROOT, 'src', 'data', 'download.json')

const die = (msg) => { console.error('ERROR: ' + msg); process.exit(1) }
const log = (msg) => console.log('==> ' + msg)

// --- versions -------------------------------------------------------------
// The same fields collect-release.sh reads, so the filename we look for is
// exactly the one it writes.
const gradle = fs.readFileSync(GRADLE, 'utf8')
const versionName = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1]
const versionCode = Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1])
if (!versionName || !Number.isInteger(versionCode)) {
  die('could not parse versionName/versionCode from ' + GRADLE)
}
const minSdk = Number(fs.readFileSync(LIBS, 'utf8').match(/^minSdk\s*=\s*"(\d+)"/m)?.[1])
if (!Number.isInteger(minSdk)) die('could not parse minSdk from ' + LIBS)

// --- locate the APK -------------------------------------------------------
// Pinned to the CURRENT source version rather than "newest in dist/": if source
// has moved to 1.0.8 and only 1.0.7 is built, that is an error, not a silent
// publish of the stale build.
const argIdx = process.argv.indexOf('--apk')
const srcApk = argIdx !== -1
  ? process.argv[argIdx + 1]
  : process.env.GTWAKE_APK
    || path.join(REPO_ROOT, 'dist', 'gtwake-phone-' + versionName + '-' + versionCode + '.apk')

if (!fs.existsSync(srcApk)) {
  if (process.env.GTWAKE_SITE_ALLOW_NO_APK === '1') {
    console.warn('WARNING: no APK at ' + srcApk)
    console.warn('         GTWAKE_SITE_ALLOW_NO_APK=1 — leaving download.json and public/apk/ untouched.')
    console.warn('         The deployed page will advertise whatever the committed metadata says.')
    process.exit(0)
  }
  die('no APK at ' + srcApk + '\n       Build and collect it first:  ./scripts/collect-release.sh')
}

log('v' + versionName + ' (build ' + versionCode + ') — ' + path.relative(REPO_ROOT, srcApk))

// --- signature assertion --------------------------------------------------
function findApksigner () {
  if (process.env.APKSIGNER) return process.env.APKSIGNER
  const roots = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
    process.env.HOME && path.join(process.env.HOME, 'Android', 'Sdk'),
  ].filter(Boolean)
  const byVersionDesc = (a, b) => {
    const pa = a.split('.').map(Number)
    const pb = b.split('.').map(Number)
    for (let i = 0; i < 3; i++) {
      const d = (pb[i] || 0) - (pa[i] || 0)
      if (d !== 0) return d
    }
    return 0
  }
  for (const root of roots) {
    const bt = path.join(root, 'build-tools')
    if (!fs.existsSync(bt)) continue
    for (const ver of fs.readdirSync(bt).sort(byVersionDesc)) {
      for (const exe of ['apksigner.bat', 'apksigner']) {
        const p = path.join(bt, ver, exe)
        if (fs.existsSync(p)) return p
      }
    }
  }
  return null
}

function readCertSha256 (exe, apk) {
  // A .bat cannot be spawned directly on modern Node; route it through cmd.
  // The command line needs an OUTER quote pair on top of the per-argument
  // quotes: `cmd /s /c` strips the first and last character of the remainder
  // when both are quotes, so a single pair leaves a mangled, unbalanced line
  // ("The filename, directory name, or volume label syntax is incorrect").
  const isBatch = /\.(bat|cmd)$/i.test(exe)
  const res = isBatch
    ? spawnSync(
      process.env.ComSpec || 'cmd.exe',
      ['/d', '/s', '/c', '""' + exe + '" verify --print-certs "' + apk + '""'],
      { encoding: 'utf8', windowsVerbatimArguments: true },
    )
    : spawnSync(exe, ['verify', '--print-certs', apk], { encoding: 'utf8' })

  if (res.error) die('apksigner failed to run: ' + res.error.message)
  if (res.status !== 0) die('apksigner exited ' + res.status + '\n' + (res.stderr || res.stdout))
  const m = res.stdout.match(/^Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})$/m)
  if (!m) die('could not parse the signer digest from apksigner output:\n' + res.stdout)
  return m[1]
}

let certSha = EXPECTED_CERT_SHA256
if (process.env.GTWAKE_SKIP_CERT_CHECK === '1') {
  console.warn('WARNING: GTWAKE_SKIP_CERT_CHECK=1 — signature assertion skipped.')
} else {
  const exe = findApksigner()
  if (!exe) {
    die('apksigner not found (looked at $APKSIGNER, $ANDROID_HOME, $ANDROID_SDK_ROOT,\n'
      + '       %LOCALAPPDATA%/Android/Sdk). It is required: this APK is v2-signed only,\n'
      + '       so "keytool -printcert -jarfile" reads nothing and cannot substitute.\n'
      + '       Set APKSIGNER=<path>, or GTWAKE_SKIP_CERT_CHECK=1 to publish unverified.')
  }
  certSha = readCertSha256(exe, srcApk)
  if (certSha !== EXPECTED_CERT_SHA256) {
    die('signing certificate mismatch\n'
      + '       expected ' + EXPECTED_CERT_SHA256 + '\n'
      + '       got      ' + certSha + '\n'
      + '       Publishing this would break in-place updates between Play and the site,\n'
      + '       and break watch pairing (Wear Engine supportLists is keyed on the cert).')
  }
  log('signing cert OK — ' + certSha)
}

// --- stat the SOURCE before copying ---------------------------------------
// copyFile stamps the destination with the current time, which would make
// builtAt change on every run and dirty git.
const st = fs.statSync(srcApk)
const sizeBytes = st.size
const builtAt = st.mtime.toISOString().slice(0, 10)

if (sizeBytes > FAIL_BYTES) {
  die('APK is ' + (sizeBytes / 1048576).toFixed(2) + ' MiB — over the 25 MiB Cloudflare Pages\n'
    + '       per-file limit. Move the binary to an R2 bucket and point "url" at it.')
}
if (sizeBytes > WARN_BYTES) {
  console.warn('WARNING: APK is ' + (sizeBytes / 1048576).toFixed(2)
    + ' MiB — approaching the 25 MiB Pages limit.')
}

const sha256 = createHash('sha256').update(fs.readFileSync(srcApk)).digest('hex')

// --- stage ----------------------------------------------------------------
// Wipe first: otherwise every release leaves another ~5.5 MB of dead build in
// the deploy forever.
const fileName = 'gtwake-' + versionName + '.apk'
fs.rmSync(APK_DIR, { recursive: true, force: true })
fs.mkdirSync(APK_DIR, { recursive: true })
fs.copyFileSync(srcApk, path.join(APK_DIR, fileName))
log('staged public/apk/' + fileName + '  (' + sizeBytes.toLocaleString('en-US') + ' bytes)')

// --- metadata -------------------------------------------------------------
// No generatedAt field: every value derives from the APK, so an unchanged APK
// must produce a byte-identical file or "npm run build" dirties git every run.
const meta = {
  $comment: 'GENERATED by scripts/sync-apk.mjs. Do not edit by hand.',
  available: true,
  versionName,
  versionCode,
  fileName,
  url: '/apk/' + fileName,
  sizeBytes,
  sha256,
  certSha256: certSha,
  minSdk,
  builtAt,
}
const next = JSON.stringify(meta, null, 2) + '\n'
const prev = fs.existsSync(META) ? fs.readFileSync(META, 'utf8') : null
if (prev === next) {
  log('metadata unchanged')
} else {
  fs.mkdirSync(path.dirname(META), { recursive: true })
  fs.writeFileSync(META, next)
  log('wrote src/data/download.json  (sha256 ' + sha256 + ')')
}

// --- _headers -------------------------------------------------------------
// Generated, and deliberately keyed on the EXACT filename rather than /apk/*.
//
// Pages sets nosniff on every asset, so the declared Content-Type is
// load-bearing. But a glob would also match a MISS: old APKs are pruned on
// release, so a stale link (a page cached up to 4h) hits a path with no asset,
// falls through the SPA catch-all, and would be served as the 1.4 kB HTML
// shell *labelled as an APK* — a corrupt download that fails to install with a
// confusing parser error. (`_redirects` cannot fix this: Pages ignores a 404
// status there — verified against production.) Pinning the exact path means a
// miss is honestly text/html, the SPA loads, and the router sends /apk/* to
// /download so the user lands on the current version.
const HEADERS = path.join(SITE_ROOT, 'public', '_headers')
const headersBody = [
  '# GENERATED by scripts/sync-apk.mjs. Do not edit by hand.',
  '# Exact path, not a glob — see the rationale in that script.',
  '/apk/' + fileName,
  '  Content-Type: application/vnd.android.package-archive',
  '  Content-Disposition: attachment',
  '  Cache-Control: public, max-age=31536000, immutable',
  '',
].join('\n')
const prevHeaders = fs.existsSync(HEADERS) ? fs.readFileSync(HEADERS, 'utf8') : null
if (prevHeaders !== headersBody) {
  fs.writeFileSync(HEADERS, headersBody)
  log('wrote public/_headers')
}
