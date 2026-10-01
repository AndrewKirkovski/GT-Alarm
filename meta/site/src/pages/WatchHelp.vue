<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { Button } from '@/components/ui/button'
import { PLAY_URL, APPGALLERY_PHONE_WEB, detectPhone, preferAppGallery } from '@/data/stores'

// Same store ordering as the Download page: the sniff only orders the buttons
// and adds the HarmonyOS 5 note. Both stores stay reachable whatever it returns.
const platform = detectPhone()
const appGalleryFirst = preferAppGallery(platform)
</script>

<template>
  <article class="policy mx-auto max-w-3xl px-5 py-14">
    <p class="lang"><RouterLink to="/watch/zh">中文</RouterLink></p>
    <h1 class="text-4xl font-extrabold tracking-tight">GT Wake on your watch</h1>

    <h2>What the watch app does</h2>
    <p>
      When an alarm you set in GT Wake on your Android phone goes off, your Huawei watch vibrates
      and shows the alarm. You can dismiss or snooze the alarm from the watch. You set and edit
      alarms on the phone.
    </p>

    <h2>Don't have the phone app yet?</h2>
    <p>
      The watch app needs the phone app, GT Wake Companion. Install it on the Android phone that is
      paired with your watch in Huawei Health.
    </p>
    <p v-if="platform === 'harmony-next'">
      This phone runs HarmonyOS 5, which can't install Android apps, so GT Wake Companion can't run
      on it. <RouterLink to="/harmony">Read why</RouterLink>.
    </p>
    <div class="mt-4 flex flex-wrap gap-3">
      <Button
        as="a"
        :href="appGalleryFirst ? APPGALLERY_PHONE_WEB : PLAY_URL"
        variant="brand"
      >{{ appGalleryFirst ? 'AppGallery' : 'Google Play' }}</Button>
      <Button
        as="a"
        :href="appGalleryFirst ? PLAY_URL : APPGALLERY_PHONE_WEB"
        variant="outline"
      >{{ appGalleryFirst ? 'Google Play' : 'AppGallery' }}</Button>
    </div>
    <p>
      If you can't use either store, you can
      <RouterLink to="/download">download the app file directly</RouterLink>.
    </p>

    <h2>See your alarm list on the watch</h2>
    <p>
      Showing your alarm list on the watch is optional. To turn it on, open the phone app and go to
      <strong>Settings → Watch → Show alarm list on watch</strong>. It is off by default.
    </p>
    <p>
      Huawei's watch connection can only deliver an update by opening the app on the watch. So when
      the list is on, your watch wakes and briefly opens GT Wake after every alarm change. When the
      list is off, the watch still shows every alarm when it goes off, and you can dismiss or snooze
      it there.
    </p>
    <p>
      Changing or removing the watch background picture also opens GT Wake on the watch briefly, for
      the same reason. The phone app asks before it does.
    </p>

    <h2>If the watch does not vibrate</h2>
    <p>In the phone app, open the alarm and check that <strong>Watch vibration</strong> is on.</p>
    <p>Then check these settings on the watch:</p>
    <ul>
      <li><strong>Do Not Disturb</strong> is off.</li>
      <li><strong>Sleep mode</strong> is off.</li>
      <li>The vibration strength is not set to <strong>None</strong> (on most models: Settings → Sounds &amp; vibration).</li>
    </ul>

    <p class="mt-8">
      <RouterLink to="/" class="font-medium text-primary hover:underline">Back to GT Wake</RouterLink>.
      For questions about HarmonyOS phones, read
      <RouterLink to="/harmony" class="font-medium text-primary hover:underline">GT Wake and HarmonyOS</RouterLink>.
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
.policy .lang {
  margin-bottom: 0.5rem;
  font-size: 0.9rem;
}
/* Prose links only. <Button as="a"> renders an anchor, and a bare `.policy a`
   rule would repaint the button labels. */
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
