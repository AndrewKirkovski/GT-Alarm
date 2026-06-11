<script setup lang="ts">
const EFFECTIVE_DATE = '2026-06-08'
const CONTACT = 'andrew.kirkovski@gmail.com'
</script>

<template>
  <article class="policy mx-auto max-w-3xl px-5 py-14">
    <p class="lang"><RouterLink to="/privacy">English</RouterLink></p>
    <h1 class="text-4xl font-extrabold tracking-tight">隐私政策</h1>
    <p class="meta">
      <strong>生效日期：</strong>{{ EFFECTIVE_DATE }} &nbsp;·&nbsp;
      <strong>开发者：</strong>Andrei Kirkouski &nbsp;·&nbsp;
      <strong>联系方式：</strong><a :href="`mailto:${CONTACT}`">{{ CONTACT }}</a>
    </p>
    <p>
      本政策适用于两款应用：<strong>GT Wake Companion</strong>（Android，
      <code>com.kirkouski.gtwake.companion</code>）和 <strong>GT Wake</strong>（华为手表，
      <code>com.kirkouski.gtwatch.watch</code>），专为华为 Watch GT 6 Pro 打造。
    </p>

    <div class="rounded-2xl border border-border bg-card p-5">
      <strong>摘要</strong>
      <ul>
        <li>我们<strong>不会</strong>收集、存储或将您的个人数据传输到我们或任何服务器。</li>
        <li>没有账号、没有统计分析、没有广告、没有跟踪。</li>
        <li>您的闹钟、设置以及任何图片都仅保存在您自己的设备上。</li>
        <li>闹钟同步通过蓝牙（华为 Wear Engine，经由华为运动健康）直接在您的手机与已配对手表之间进行，不经过我们的服务器。</li>
        <li>应用<strong>不申请任何网络权限</strong>，无法通过互联网发送您的数据。</li>
      </ul>
    </div>

    <h2>1. 我们收集的数据</h2>
    <p>
      <strong>没有任何数据会发送给我们。</strong>应用没有后端、没有由开发者运营的服务器，也没有账号系统。
      以下数据仅在您的设备<strong>本地</strong>创建和保存：您的闹钟及其设置、应用偏好（时间格式、
      每周起始日、语言），以及您选择的任何背景图片。您的闹钟会在已配对手表上保存一份镜像副本，
      以便显示和响铃。这些数据都不会上传到任何地方。
    </p>

    <h2>2. 网络与数据传输</h2>
    <p>
      手机应用<strong>未声明 <code>INTERNET</code> 权限</strong>，因此无法打开互联网连接发送您的数据。
      应用包含 <code>ACCESS_NETWORK_STATE</code> 权限，仅因为华为 Wear Engine 库包含它；它只用于读取
      是否存在连接，不会传输您的数据。应用产生的所有通信都是您的手机与您自己已配对手表之间的设备到设备通信。
    </p>

    <h2>3. 权限及其用途</h2>
    <ul>
      <li><strong>精确闹钟</strong> — 在您设定的精确时间响铃。</li>
      <li><strong>通知</strong> — 显示正在响的闹钟及相关通知。</li>
      <li><strong>全屏 Intent</strong> — 闹钟触发时在锁屏上方显示闹钟界面。</li>
      <li><strong>前台服务</strong> — 在闹钟响铃期间保持其可靠运行。</li>
      <li><strong>震动 / 唤醒锁</strong> — 用于震动，并唤醒屏幕以便闹钟响铃。</li>
      <li><strong>开机启动</strong> — 在手机重启后重新设置您的闹钟。</li>
      <li><strong>忽略电池优化（可选）</strong> — 您可以授予此权限，以便在深度睡眠时闹钟也能准时响起。</li>
      <li><strong>手表连接（Wear Engine）</strong> — 向已配对手表发送/接收闹钟及响铃/稍后提醒/关闭信号。</li>
    </ul>
    <p>应用<strong>不会</strong>申请位置、通讯录、麦克风、相机、通话记录、短信或设备标识符。</p>

    <h2>4. 手机 ↔ 手表同步（华为 Wear Engine）</h2>
    <p>
      为了让闹钟在您的手腕上与手机同步响铃，应用使用 <strong>华为 Wear Engine</strong>，通过
      <strong>华为运动健康</strong>应用经蓝牙，<strong>直接在您的手机与您自己已配对的华为 Watch GT 6 Pro
      之间</strong>交换消息。这些消息包含您的闹钟数据、响铃/稍后提醒/关闭信号，以及（如果您选择了手表背景）
      该图片。这些数据仅在您的两台已配对设备之间传输；我们不会收到。由于该传输由华为提供，华为对连接的处理受
      <a href="https://consumer.huawei.com/zh/privacy/privacy-policy/" target="_blank" rel="noopener">华为隐私政策</a>
      及华为运动健康应用约束。
    </p>

    <h2>5. 语音闹钟</h2>
    <p>
      当您通过语音创建闹钟时，您设备的助手（例如 Google 助理）使用 Android 标准的「设置闹钟」功能处理语音，
      应用只是接收最终的闹钟时间。任何语音处理都由该助手在<em>其</em>提供商的隐私政策下执行。应用不会访问您的麦克风。
    </p>

    <h2>6. 背景图片</h2>
    <p>
      如果您选择一张图片作为闹钟背景，应用会从您的设备读取该图片以显示（在锁屏闹钟和/或手表上）。对于手表背景，
      该图片会通过 Wear Engine 发送到您已配对的手表（见第 4 节）。图片不会上传到我们或任何第三方。
    </p>

    <h2>7. 广告、分析与跟踪</h2>
    <p>
      应用不含广告、不含分析 SDK、不含跟踪。我们不会建立用户画像，也不会分享或出售任何数据（我们没有任何数据可分享）。
    </p>

    <h2>8. 数据保留与删除</h2>
    <p>
      所有数据都保存在本地，因此完全由您掌控。删除闹钟会将其从手机中移除，并在下次同步时从手表中移除。
      卸载某个应用会从该设备移除其数据。我们不保留任何副本，因此无需我们代为删除。
    </p>

    <h2>9. 儿童隐私</h2>
    <p>本应用是通用闹钟应用，并非面向儿童，且不会收集任何人（包括儿童）的个人数据。</p>

    <h2>10. 安全</h2>
    <p>
      您的数据保存在每个应用的私有设备存储中，且仅通过华为 Wear Engine/蓝牙传输在您已配对的设备之间交换。
      我们不运营任何服务器，也不保存您信息的任何集中存储。
    </p>

    <h2>11. 本政策的变更</h2>
    <p>如果本政策发生变更，我们将更新上方的生效日期，并在此网址发布新版本。</p>

    <h2>12. 联系方式</h2>
    <p>有关本政策的问题：<a :href="`mailto:${CONTACT}`">{{ CONTACT }}</a></p>
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
