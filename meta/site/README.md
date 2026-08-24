# GT Wake site — gtwake.kirkouski.com

Marketing landing + privacy policy for the GT Wake apps.
**Vite + Vue 3 + TypeScript + Tailwind 4 + shadcn-vue (Reka UI)**, deployed to **Cloudflare Pages**.
Reuses the app's brand palette (cyan `#009EDA` → indigo `#6373F2` → magenta `#E058CE`).

- `/` — landing (app intro, store buttons, GT 6 Pro section + device-contribution ask)
- `/download` — direct APK download (third distribution channel, alongside Play + AppGallery)
- `/privacy` — privacy policy (the HTTPS URL to paste into Google Play + AppGallery)

## The APK download

`scripts/sync-apk.mjs` stages the signed APK and publishes its metadata. It reads the artifact the
repo-root `scripts/collect-release.sh` already produced — **it never invokes Gradle and never builds
anything**.

| Path | Tracked? | Notes |
|---|---|---|
| `public/apk/gtwake-<version>.apk` | gitignored | Copied from repo-root `dist/`. Wiped and re-staged on each run, so only the current version ships |
| `src/data/download.json` | **committed** | Generated. Version, size, SHA-256, cert fingerprint. A hash change showing up in `git diff` is the review checkpoint |
| `src/data/download.ts` | committed | Hand-written typed wrapper + display helpers |
| `public/_headers` | **committed** | Generated. Keyed on the *exact* APK filename, not `/apk/*` — see below |

It is wired into `npm run build`, which is wired into `npm run deploy`, so the page text can never
disagree with the binary sitting next to it. Run it standalone with `npm run apk:sync`.

**The script hard-fails unless the APK's signing cert is `95F6…`** — the same cert Google Play signs
with. That parity is what lets users move between the Play build and this one without uninstalling,
and what satisfies the watch's Wear Engine allow-list. `apksigner` is required (the APK is v2-signed
only, so `keytool -printcert -jarfile` reads nothing and cannot substitute).

Escape hatches, for development only — never for a real release:
`GTWAKE_SITE_ALLOW_NO_APK=1` (no `dist/` present: leaves the tree untouched),
`GTWAKE_SKIP_CERT_CHECK=1`, `GTWAKE_APK=<path>` / `--apk <path>`, `APKSIGNER=<path>`.

### Serving behaviour (all verified against production, 2026-08-24)

`public/_redirects` is a catch-all SPA rewrite. **Static assets take precedence over it** — the APK,
`/favicon.svg` and `/assets/*` all serve intact — so the catch-all only fires on a miss. Cloudflare's
docs claim redirects always win; they do not. Don't "fix" this.

A **missing** URL therefore returns `200 text/html` (the 1.4 kB SPA shell), *not* a 404. So:

- **Never verify a download by status code alone** — assert `content-length` or re-hash the bytes.
  `curl -I | grep 200` passes on a completely broken link.
- **`_redirects` cannot force a 404.** A `/apk/*  /index.html  404` rule was tried against production
  and Pages ignored the status, still returning 200.
- **`_headers` must key on the exact APK filename.** Pages *can* override Content-Type from
  `_headers` (confirmed). But a `/apk/*` glob also matches a miss, so a stale link — old APKs are
  pruned on release, and pages cache for 4 h — would download the HTML shell *labelled as an APK*:
  a corrupt file that fails to install with a confusing parser error. With the exact path, a miss is
  honest `text/html`, the SPA loads, and the router rule `/apk/:file(.*) → /download` lands the user
  on the current version.
- Beware `immutable` on a glob: it pins whatever was served at that path into the edge cache. One
  test URL (`/apk/gtwake-9.9.9.apk`) is stuck APK-typed for a year from this mistake. Harmless only
  because that version will never exist.

If a `latest` alias is ever added it must be a `302` placed **above** the catch-all — never a second
copy of the binary, and never with a long cache lifetime.

## Develop
```bash
npm install
npm run dev          # http://localhost:5173
```

## Build
```bash
npm run build        # type-safe transpile -> dist/   (npm run typecheck for full vue-tsc)
```

## Deploy (Cloudflare Pages, subdomain)
```bash
wrangler login                                  # one-time, browser auth
npm run deploy                                  # wrangler pages deploy dist --project-name=gtwake-site
```
Then add the custom domain **gtwake.kirkouski.com** to the `gtwake-site` Pages project
(Cloudflare dashboard → Pages → gtwake-site → Custom domains → Set up a custom domain).
`public/_redirects` handles SPA fallback so `/privacy` resolves on refresh.

## Before publishing — edit
- `src/pages/Home.vue` — `CONTACT`. The AppGallery URLs use the package-name form
  (`/app/detail?id=<pkg>`, verified to resolve) so they need no post-publish C-numbers; they go
  live when the apps pass review. The watch URL is an info page — the watch app installs via
  Huawei Health, not a web link.
- `src/pages/Privacy.vue` — `CONTACT`.
