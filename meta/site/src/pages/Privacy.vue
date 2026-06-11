<script setup lang="ts">
const EFFECTIVE_DATE = '2026-06-08'
const CONTACT = 'andrew.kirkovski@gmail.com'
</script>

<template>
  <article class="policy mx-auto max-w-3xl px-5 py-14">
    <p class="lang"><RouterLink to="/privacy/zh">中文</RouterLink></p>
    <h1 class="text-4xl font-extrabold tracking-tight">Privacy Policy</h1>
    <p class="meta">
      <strong>Effective date:</strong> {{ EFFECTIVE_DATE }} &nbsp;·&nbsp;
      <strong>Developer:</strong> Andrei Kirkouski &nbsp;·&nbsp;
      <strong>Contact:</strong> <a :href="`mailto:${CONTACT}`">{{ CONTACT }}</a>
    </p>
    <p>
      This policy covers both apps: <strong>GT Wake Companion</strong> (Android,
      <code>com.kirkouski.gtwake.companion</code>) and <strong>GT Wake</strong> (Huawei watch,
      <code>com.kirkouski.gtwatch.watch</code>), built for the Huawei Watch GT 6 Pro.
    </p>

    <div class="rounded-2xl border border-border bg-card p-5">
      <strong>Summary</strong>
      <ul>
        <li>We do <strong>not</strong> collect, store, or transmit your personal data to us or any server.</li>
        <li>No accounts, no analytics, no advertising, no tracking.</li>
        <li>Your alarms, settings, and any images stay on your own devices.</li>
        <li>Alarm syncing happens directly between your phone and your paired watch over Bluetooth
          (Huawei Wear Engine, via Huawei Health) — not through our servers.</li>
        <li>The apps request <strong>no internet permission</strong> and cannot send your data over the internet.</li>
      </ul>
    </div>

    <h2>1. Data we collect</h2>
    <p>
      <strong>None that is sent to us.</strong> The apps have no backend, no developer-operated
      server, and no account system. The following is created and kept <strong>locally on your
      device(s)</strong>: your alarms and their settings, app preferences (clock format, first day
      of week, language), and any background images you choose. A mirror of your alarms is kept on
      your paired watch so it can display and ring them. None of it is uploaded anywhere.
    </p>

    <h2>2. Network and data transmission</h2>
    <p>
      The phone app declares <strong>no <code>INTERNET</code> permission</strong>, so it cannot open
      internet connections to send your data anywhere. An <code>ACCESS_NETWORK_STATE</code>
      permission is present only because the Huawei Wear Engine library includes it; it reads
      whether a connection exists and does not transmit your data. All communication created by the
      apps is device-to-device between your phone and your own paired watch.
    </p>

    <h2>3. Permissions, and why each is used</h2>
    <ul>
      <li><strong>Exact alarms</strong> — to ring your alarms at the precise time you set.</li>
      <li><strong>Notifications</strong> — to show the ringing alarm and alarm-related notifications.</li>
      <li><strong>Full-screen intent</strong> — to show the alarm over the lock screen when it fires.</li>
      <li><strong>Foreground service</strong> — to keep the alarm reliably ringing while active.</li>
      <li><strong>Vibrate / Wake lock</strong> — for vibration and to wake the screen so the alarm can ring.</li>
      <li><strong>Run on boot</strong> — to re-arm your alarms after the phone restarts.</li>
      <li><strong>Ignore battery optimizations (optional)</strong> — you may grant this so alarms fire on time in deep sleep.</li>
      <li><strong>Watch connectivity (Wear Engine)</strong> — to send/receive alarm and ring/snooze/dismiss signals to your paired watch.</li>
    </ul>
    <p>The apps do <strong>not</strong> request location, contacts, microphone, camera, call logs, SMS, or device identifiers.</p>

    <h2>4. Phone ↔ watch syncing (Huawei Wear Engine)</h2>
    <p>
      To make alarms ring on your wrist in sync with your phone, the apps exchange messages
      <strong>directly between your phone and your own paired Huawei Watch GT 6 Pro</strong> using
      <strong>Huawei Wear Engine</strong>, over Bluetooth through the <strong>Huawei Health</strong>
      app. These messages contain your alarm data, ring/snooze/dismiss signals, and — if you choose
      a watch background — that image. The data goes only between your two paired devices; we do not
      receive it. Because this transport is provided by Huawei, Huawei's handling of the connection
      is governed by
      <a href="https://consumer.huawei.com/en/privacy/privacy-policy/" target="_blank" rel="noopener">Huawei's privacy policy</a>
      and the Huawei Health app.
    </p>

    <h2>5. Voice alarms</h2>
    <p>
      When you create an alarm by voice, your device's assistant (e.g. Google Assistant) handles the
      voice using Android's standard “set alarm” feature, and the apps simply receive the resulting
      alarm time. Any voice processing is performed by that assistant under <em>its</em> provider's
      privacy policy. The apps do not access your microphone.
    </p>

    <h2>6. Background images</h2>
    <p>
      If you pick an image as an alarm background, the app reads it from your device to display it
      (on the lock-screen alarm and/or the watch). For a watch background, the image is sent to your
      paired watch via Wear Engine (§4). Images are not uploaded to us or any third party.
    </p>

    <h2>7. Advertising, analytics, and tracking</h2>
    <p>
      The apps contain no advertising, no analytics SDKs, and no tracking. We do not build profiles
      and do not share or sell any data (we have none).
    </p>

    <h2>8. Data retention and deletion</h2>
    <p>
      All data is stored locally, so you control it entirely. Deleting an alarm removes it from the
      phone and, on the next sync, the watch. Uninstalling an app removes its data from that device.
      We hold no copies, so there is nothing for us to delete on your behalf.
    </p>

    <h2>9. Children's privacy</h2>
    <p>The apps are general-purpose alarm clocks, not directed at children, and collect no personal data from anyone.</p>

    <h2>10. Security</h2>
    <p>
      Your data stays in each app's private on-device storage and is exchanged only between your
      paired devices over Huawei's Wear Engine/Bluetooth transport. We operate no servers and hold
      no central store of your information.
    </p>

    <h2>11. Changes to this policy</h2>
    <p>If this policy changes, we will update the effective date above and post the new version at this URL.</p>

    <h2>12. Contact</h2>
    <p>Questions about this policy: <a :href="`mailto:${CONTACT}`">{{ CONTACT }}</a></p>
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
.policy .lang {
  margin-bottom: 0.5rem;
  font-size: 0.9rem;
}
.policy a {
  color: var(--primary);
  text-decoration: none;
}
.policy a:hover {
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
