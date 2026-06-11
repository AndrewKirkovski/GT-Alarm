# GT Wake site — gtwake.kirkouski.com

Marketing landing + privacy policy for the GT Wake apps.
**Vite + Vue 3 + TypeScript + Tailwind 4 + shadcn-vue (Reka UI)**, deployed to **Cloudflare Pages**.
Reuses the app's brand palette (cyan `#009EDA` → indigo `#6373F2` → magenta `#E058CE`).

- `/` — landing (app intro, store buttons, GT 6 Pro section + device-contribution ask)
- `/privacy` — privacy policy (the HTTPS URL to paste into Google Play + AppGallery)

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
