<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { RouterLink } from 'vue-router'
import { download, formatMiB, formatBytes, colonize, androidRelease } from '@/data/download'

// Same constants as Home.vue. Duplicated rather than extracted: two usages is
// under the threshold where a shared module earns its keep.
const PLAY_URL = 'https://play.google.com/store/apps/details?id=com.kirkouski.gtwake.companion'
const APPGALLERY_PHONE = 'https://appgallery.huawei.com/app/detail?id=com.kirkouski.gtwake.companion'
const APPGALLERY_WATCH = 'https://appgallery.huawei.com/app/detail?id=com.kirkouski.gtwatch.watch'
const REPO = 'https://github.com/AndrewKirkovski/GT-Alarm'
</script>

<template>
  <article class="policy mx-auto max-w-3xl px-5 py-14">
    <h1 class="text-4xl font-extrabold tracking-tight">Download GT Wake for Android</h1>
    <p class="meta">
      <strong>Version:</strong> {{ download.versionName }} (build {{ download.versionCode }})
      &nbsp;·&nbsp;
      <strong>Built:</strong> {{ download.builtAt }}
      &nbsp;·&nbsp;
      <strong>Requires:</strong> Android {{ androidRelease(download.minSdk) }} or newer
    </p>

    <div class="rounded-2xl border border-border bg-card p-5">
      <strong>Use a store if you can</strong>
      <p>
        Google Play and AppGallery give you automatic updates and store-side integrity checks. This
        direct download is for phones where neither store is available.
      </p>
      <div class="mt-4 flex flex-wrap gap-3">
        <Button as="a" :href="PLAY_URL" variant="outline" size="sm">Google Play</Button>
        <Button as="a" :href="APPGALLERY_PHONE" variant="outline" size="sm">AppGallery</Button>
      </div>
    </div>

    <template v-if="download.available">
      <h2>Download</h2>
      <div class="mt-3 flex flex-wrap items-center gap-4">
        <Button as="a" :href="download.url" :download="download.fileName" variant="brand" size="lg">
          Download {{ download.fileName }}
        </Button>
        <span class="text-sm text-muted-foreground">
          {{ formatMiB(download.sizeBytes) }} · Android {{ androidRelease(download.minSdk) }}+ ·
          universal build (all CPU types)
        </span>
      </div>

      <h2>Verify what you downloaded</h2>
      <p>
        Two independent things are worth checking: that the file arrived intact, and that it was
        signed by us.
      </p>
      <p><strong>File checksum (SHA-256)</strong></p>
      <p><code class="break-all">{{ download.sha256 }}</code></p>
      <ul>
        <li>Linux — <code>sha256sum {{ download.fileName }}</code></li>
        <li>macOS — <code>shasum -a 256 {{ download.fileName }}</code></li>
        <li>Windows — <code>certutil -hashfile {{ download.fileName }} SHA256</code></li>
      </ul>
      <p><strong>Signing certificate (SHA-256)</strong></p>
      <p><code class="break-all">{{ colonize(download.certSha256) }}</code></p>
      <p>
        Check it with <code>apksigner verify --print-certs {{ download.fileName }}</code>, which
        should print:
      </p>
      <p><code class="break-all">Signer #1 certificate SHA-256 digest: {{ download.certSha256 }}</code></p>
      <p>
        This is the <strong>same certificate that signs the Google Play build</strong>. That is what
        lets you move between the Play version and this one in either direction without uninstalling
        and losing your alarms — and it is what the watch checks before it will pair.
      </p>

      <h2>Install it</h2>
      <ul>
        <li>Tap <strong>Download</strong> above. Your browser will warn that this kind of file can
          harm your device — that is the standard warning Android shows for any APK.</li>
        <li>Open the file from the download notification, or from <strong>Files → Downloads</strong>.</li>
        <li>Android will ask whether to let your browser install unknown apps. Tap
          <strong>Settings</strong>, turn on <strong>Allow from this source</strong>, then go back.</li>
        <li><strong>Play Protect</strong> may offer to scan the app, or show “unsafe app blocked”.
          That is Play Protect reacting to an app installed from outside a store, not a finding about
          this build. Once the checksum above matches, choose <strong>Install anyway</strong> (you may
          need <strong>More details</strong> first).</li>
        <li>Tap <strong>Install</strong>.</li>
      </ul>
      <p>
        <strong>Already have GT Wake from Google Play?</strong> This installs straight over it as an
        update — same app, same signing certificate — as long as its build number
        ({{ download.versionCode }}) is not lower than the one you have. Your alarms and settings are
        kept.
      </p>
      <p>
        <strong>The watch app is not on this page.</strong> It cannot be sideloaded from a file — it
        installs onto the watch from
        <a :href="APPGALLERY_WATCH" target="_blank" rel="noopener">AppGallery</a>, through the Huawei
        Health app on your phone.
      </p>

      <h2>Updates</h2>
      <p>
        <strong>This download does not update itself.</strong> The app will not notify you when a new
        version exists and does not check this site — it has no internet permission at all. When you
        want a newer version, come back to this page and install it over the old one.
      </p>
    </template>

    <template v-else>
      <h2>Download</h2>
      <p>
        The direct download is being refreshed. Please install from
        <a :href="PLAY_URL" target="_blank" rel="noopener">Google Play</a> or
        <a :href="APPGALLERY_PHONE" target="_blank" rel="noopener">AppGallery</a> for now.
      </p>
    </template>

    <h2>Privacy</h2>
    <p>
      No accounts, no analytics, no tracking, and no internet permission — nothing leaves your
      devices. See the
      <RouterLink to="/privacy" class="font-medium text-primary hover:underline">privacy policy</RouterLink>.
    </p>

    <h2>License</h2>
    <p><strong>Required Notice: Copyright (c) 2026 Andrei Kirkouski</strong></p>
    <p>
      GT Wake is distributed under the
      <a :href="`${REPO}/blob/main/LICENSE`" target="_blank" rel="noopener">PolyForm Noncommercial
      License 1.0.0</a>. Personal, research, educational and noncommercial-organisation use is
      permitted; commercial use is reserved to the copyright holder. See also the
      <a :href="`${REPO}/blob/main/NOTICE`" target="_blank" rel="noopener">NOTICE</a> file and the
      <a :href="REPO" target="_blank" rel="noopener">source repository</a>.
    </p>
  </article>
</template>

<style scoped>
.policy :deep(h2) {
  margin-top: 2rem;
  margin-bottom: 0.5rem;
  font-size: 1.25rem;
  font-weight: 700;
}
.policy p,
.policy ul {
  margin-top: 0.75rem;
  color: var(--muted-foreground);
  line-height: 1.7;
}
.policy ul {
  padding-left: 1.25rem;
  list-style: disc;
}
.policy li {
  margin-top: 0.3rem;
}
.policy .meta {
  margin-top: 0.5rem;
  font-size: 0.9rem;
}
/* Scoped to prose only. Privacy.vue can use a bare `.policy a` because it has
   no buttons; this page does, and <Button as="a"> renders an anchor — a bare
   rule repaints the brand button's label dark-on-gradient and turns the outline
   store buttons blue. */
.policy p a,
.policy li a {
  color: var(--primary);
  text-decoration: none;
}
.policy p a:hover,
.policy li a:hover {
  text-decoration: underline;
}
.policy code {
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 0.9em;
}
.policy strong {
  color: var(--foreground);
}
.policy .rounded-2xl {
  margin-top: 1.5rem;
}
</style>
