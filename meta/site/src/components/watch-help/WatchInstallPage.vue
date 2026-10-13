<script setup lang="ts">
import { RouterLink } from 'vue-router'

defineProps<{ zh: boolean }>()
</script>

<template>
  <article class="policy mx-auto max-w-3xl px-5 py-14">
    <p class="text-sm">
      <RouterLink :to="zh ? '/watch/install' : '/watch/install/zh'" class="text-primary hover:underline">
        {{ zh ? 'English' : '中文' }}
      </RouterLink>
      <span class="mx-2 text-muted-foreground">·</span>
      <RouterLink :to="zh ? '/watch/zh' : '/watch'" class="text-primary hover:underline">
        {{ zh ? '返回手表帮助' : 'Watch help' }}
      </RouterLink>
    </p>

    <template v-if="zh">
      <h1 class="mt-3 text-4xl font-extrabold tracking-tight">安装 GT Wake 手表应用</h1>
      <p class="mt-3 text-muted-foreground">
        手表应用通过华为运动健康从华为应用市场安装。请在已配对手表的手机上打开华为运动健康，进入“设备” → 选择手表 → “应用市场”，然后搜索“GT Wake”。
      </p>
      <p class="mt-3 text-muted-foreground">
        手表应用只通过华为应用市场提供，没有可供下载的手表安装包。
      </p>

      <h2>如果应用市场中没有 GT Wake</h2>
      <p>
        华为应用市场的可用性可能因地区和设备而异。请通过手机应用中的“帮助”联系开发者：
        <a href="mailto:andrew.kirkovski@gmail.com">andrew.kirkovski@gmail.com</a>。
      </p>
      <p class="mt-3">
        设置与使用：<RouterLink to="/watch/zh" class="text-primary hover:underline">手表使用帮助</RouterLink>。
      </p>
    </template>

    <template v-else>
      <h1 class="mt-3 text-4xl font-extrabold tracking-tight">Install GT Wake on your watch</h1>
      <p class="mt-3 text-muted-foreground">
        Install the watch app from AppGallery through Huawei Health. On the phone paired with your watch, open Huawei Health,
        go to <strong>Devices → your watch → AppGallery</strong>, then search for <strong>GT Wake</strong>.
      </p>
      <p class="mt-3 text-muted-foreground">
        The watch app is only available from AppGallery. There is no watch package to download.
      </p>

      <h2>If GT Wake is not in AppGallery</h2>
      <p>
        Huawei AppGallery availability can vary by region and device. Contact the developer through Help in the phone app,
        or email <a href="mailto:andrew.kirkovski@gmail.com">andrew.kirkovski@gmail.com</a>.
      </p>
      <p class="mt-3">
        See the <RouterLink to="/watch" class="text-primary hover:underline">watch help page</RouterLink> for setup and use.
      </p>
    </template>

  </article>
</template>

<style scoped>
.policy :deep(h2) {
  margin-top: 2rem;
  margin-bottom: 0.5rem;
  font-size: 1.25rem;
  font-weight: 700;
}
.policy p {
  margin-top: 0.75rem;
  color: var(--muted-foreground);
  line-height: 1.7;
}
.policy a {
  color: var(--primary);
  text-decoration: none;
}
.policy a:hover {
  text-decoration: underline;
}
</style>
