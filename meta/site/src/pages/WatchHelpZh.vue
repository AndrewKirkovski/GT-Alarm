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
    <p class="lang"><RouterLink to="/watch">English</RouterLink></p>
    <h1 class="text-4xl font-extrabold tracking-tight">手表上的 GT Wake</h1>

    <h2>手表应用的作用</h2>
    <!-- CJK sentences stay on one source line: Vue turns a line break into a visible space. -->
    <p>
      当您在 Android 手机上的 GT Wake 中设置的闹钟响起时，您的华为手表会振动并显示该闹钟。您可以在手表上关闭闹钟或稍后提醒。闹钟的设置和编辑都在手机上进行。
    </p>

    <h2>还没有手机应用？</h2>
    <p>
      手表应用需要配合手机应用 GT Wake Companion 使用。请将其安装在已通过华为运动健康与您的手表配对的
      Android 手机上。
    </p>
    <p v-if="platform === 'harmony-next'">
      这部手机运行的是 HarmonyOS 5，无法安装 Android 应用，因此无法运行 GT Wake Companion。<RouterLink to="/harmony">了解原因</RouterLink>（英文页面）。
    </p>
    <div class="mt-4 flex flex-wrap gap-3">
      <Button
        as="a"
        :href="appGalleryFirst ? APPGALLERY_PHONE_WEB : PLAY_URL"
        variant="brand"
      >{{ appGalleryFirst ? '华为应用市场' : 'Google Play' }}</Button>
      <Button
        as="a"
        :href="appGalleryFirst ? PLAY_URL : APPGALLERY_PHONE_WEB"
        variant="outline"
      >{{ appGalleryFirst ? 'Google Play' : '华为应用市场' }}</Button>
    </div>
    <p>
      如果两个应用商店都无法使用，您也可以<RouterLink to="/download">直接下载应用安装文件</RouterLink>（英文页面）。
    </p>

    <h2>在手表上查看闹钟列表</h2>
    <p>
      在手表上显示闹钟列表是可选功能。要开启它，请打开手机应用，进入<strong>设置 → 手表 → 在手表上显示闹钟列表</strong>。此选项默认关闭。
    </p>
    <p>
      华为的手表连接只能通过在手表上打开应用来传送更新。因此开启列表后，每次更改闹钟，您的手表都会被唤醒并短暂打开
      GT Wake。关闭列表时，每个闹钟响起时手表仍会显示，您也可以在手表上关闭或稍后提醒。
    </p>
    <p>
      更换或移除手表背景图片时，出于同样的原因，手表也会短暂打开 GT Wake。手机应用会先征求您的同意。
    </p>

    <h2>如果手表不振动</h2>
    <p>请在手机应用中打开该闹钟，确认<strong>手表振动</strong>已开启。</p>
    <p>然后在手表上检查以下设置：</p>
    <ul>
      <li><strong>免打扰</strong>已关闭。</li>
      <li><strong>睡眠模式</strong>已关闭。</li>
      <li>振动强度未设为<strong>无</strong>。</li>
    </ul>

    <p class="mt-8">
      <RouterLink to="/" class="font-medium text-primary hover:underline">返回 GT Wake 首页</RouterLink>。如有关于 HarmonyOS 手机的问题，请阅读 <RouterLink to="/harmony" class="font-medium text-primary hover:underline">GT Wake 与 HarmonyOS</RouterLink>（英文页面）。
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
