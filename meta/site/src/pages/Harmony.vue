<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { Button } from '@/components/ui/button'

const CONTACT = 'mailto:andrew.kirkovski@gmail.com?subject=GT%20Wake%20on%20HarmonyOS'
</script>

<template>
  <article class="policy mx-auto max-w-3xl px-5 py-14">
    <h1 class="text-4xl font-extrabold tracking-tight">GT Wake and HarmonyOS</h1>
    <p class="meta">
      Short version: GT Wake works with HarmonyOS 4 and earlier. It cannot work on HarmonyOS 5 — and on
      HarmonyOS 5 you almost certainly do not need it, because your phone already does this natively.
    </p>

    <h2>Who actually needs GT Wake</h2>
    <p>
      <strong>Owners of a non-Huawei Android phone</strong> — Samsung, Pixel, Xiaomi and the rest — paired with a
      Huawei watch. On those phones nothing native covers this: the watch stays silent when a phone alarm goes off,
      and Huawei Health can only add a separate alarm that lives on the watch alone. Huawei&apos;s own support pages
      state that phone-alarm sync is not supported on third-party phones.
    </p>

    <h2>Which phones work</h2>
    <ul>
      <li><strong>Any Android phone</strong> — including Huawei phones on EMUI or HarmonyOS 2, 3 and 4. These run
        Android underneath, so GT Wake Companion installs and syncs to your watch normally.</li>
      <li><strong>HarmonyOS 5 (also called HarmonyOS NEXT)</strong> — not supported. See below.</li>
    </ul>

    <h2>Why HarmonyOS 5 cannot work</h2>
    <p>
      This is not something we have chosen to skip, and it is not a bug we can fix. Two things stand in the way,
      and they are both Huawei platform decisions:
    </p>
    <ul>
      <li>
        <strong>HarmonyOS 5 removed Android.</strong> Versions 1 to 4 were built on the Android open-source core.
        Version 5 dropped it entirely, so an Android app has no native runtime there.
      </li>
      <li>
        <strong>Watch sync runs through Huawei Health.</strong> Huawei's Wear Engine — the only way an app can talk
        to a Huawei watch — does not connect to the watch directly. It connects to the <em>Android</em> Huawei Health
        app on your phone, which then talks to the watch. HarmonyOS 5 has a rebuilt, native Huawei Health with no
        Android interface for us to connect to. Huawei's own documentation lists Wear Engine support as
        Android 6 to 14, or Huawei phones on EMUI 4.1 and HarmonyOS 2.0 or later. HarmonyOS 5 is not on that list.
      </li>
    </ul>
    <p>
      Huawei does offer a replacement toolkit for HarmonyOS 5 apps. Its own documentation restricts it to developers
      publishing in the Chinese mainland, and GT Wake is published from the EU — so that route is closed to us too.
    </p>

    <h2>If you have a HarmonyOS 5 phone, you already have this</h2>
    <p>
      This is the good news, and it is the reason the gap matters less than it sounds. On Huawei phones the feature
      GT Wake provides is <strong>built into the system</strong>: a phone alarm rings on your paired watch, and you
      can dismiss or snooze it from either device. Huawei has shipped this since EMUI 8.1 / HarmonyOS 2.
    </p>
    <p>
      On HarmonyOS 5 it is behind a switch. Open <strong>Huawei Health → your watch → Alarm</strong> and turn on
      phone alarm sync. Note that the watch reminder only fires while you are wearing the watch.
    </p>
    <p>
      What you do not get natively: your phone's alarm list shown on the watch, and GT Wake's per-alarm extras such
      as background images and vibration patterns. If those matter to you, see the donation note below — that is
      what a HarmonyOS build would need.
    </p>

    <h2>Want GT Wake on HarmonyOS?</h2>
    <p>
      So do we. The blocker is not effort or interest — it is that we have no HarmonyOS 5 device to build against
      and no way to test a single line of it. Every feature in GT Wake was verified on real hardware, and shipping a
      HarmonyOS version we had never run would be guesswork.
    </p>
    <p>
      <strong>If you would like to help:</strong> donating a HarmonyOS 5 phone, or lending one for testing, is the
      single thing that would move this. Getting in touch to say you want it is useful too — it tells us how many
      people are waiting.
    </p>
    <div class="mt-6 flex flex-wrap gap-3">
      <Button as="a" :href="CONTACT" variant="brand">Get in touch about HarmonyOS</Button>
      <Button as="a" href="/download" variant="outline">Download for Android</Button>
    </div>

    <h2>The watch app</h2>
    <p>
      GT Wake on the watch is a companion to the phone app — it shows your alarms and handles the ring, but the phone
      schedules everything. Without a supported phone there is nothing for it to sync with. It installs on round
      Huawei Watch GT-series watches through
      <strong>Huawei Health → Devices → your watch → AppGallery</strong>.
    </p>

    <p class="mt-8">
      <RouterLink to="/" class="font-medium text-primary hover:underline">Back to GT Wake</RouterLink>
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
  font-size: 0.95rem;
}
.policy p a,
.policy li a {
  color: var(--primary);
  text-decoration: none;
}
.policy p a:hover,
.policy li a:hover {
  text-decoration: underline;
}
.policy strong {
  color: var(--foreground);
}
</style>
