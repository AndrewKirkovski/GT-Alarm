// Copy for the /watch help page (the watch's empty-screen QR opens it).
// One layout, two locales: WatchHelpPage.vue draws the figures, these objects
// carry every visible word, so EN and ZH can't drift apart structurally.
// The page is picture-first: keep each string to a caption.

export type VibState = 'on' | 'off' | 'notNone'

export interface WatchHelpCopy {
  otherLang: { to: string; label: string }
  title: string
  heroLabel: string
  steps: [string, string, string, string]
  appTitle: string
  appText: string
  harmonyNote: string
  harmonyLink: string
  appGallery: string
  play: string
  apkLink: string
  listTitle: string
  listOff: string
  listOffText: string
  listOffLabel: string
  listOn: string
  listOnText: string
  listOnLabel: string
  listWhy: string
  listPathLabel: string
  listPath: [string, string, string]
  vibTitle: string
  // `where` is read by screen readers only; sighted readers get a device icon.
  vibRows: { label: string; where: string; device: 'phone' | 'watch'; state: VibState }[]
  vibNone: string
  stateText: Record<VibState, string>
  harmony: string
}

export const en: WatchHelpCopy = {
  otherLang: { to: '/watch/zh', label: '中文' },
  title: 'GT Wake on your watch',
  heroLabel:
    'Your phone rings, wakes the watch over Bluetooth, and the watch vibrates. Stopping or snoozing on either device stops both.',
  steps: [
    'Phone rings.',
    'Wakes the watch over Bluetooth.',
    'Watch vibrates.',
    'Stop or snooze on either; both follow.',
  ],
  appTitle: 'Get the phone app',
  appText: 'The watch needs GT Wake Companion on your Android phone.',
  harmonyNote: 'This phone runs HarmonyOS 5, which can’t install Android apps.',
  harmonyLink: 'Read why',
  appGallery: 'AppGallery',
  play: 'Google Play',
  apkLink: 'Or download the app file',
  listTitle: 'Alarm list on watch',
  listOff: 'Off (default)',
  listOffText: 'GT Wake stays closed.',
  listOffLabel: 'With the list off, editing an alarm on the phone sends nothing to the watch, and GT Wake stays closed.',
  listOn: 'On',
  listOnText: 'Every change opens it briefly.',
  listOnLabel: 'With the list on, each edit on the phone is sent to the watch, which opens GT Wake and shows your alarm list.',
  listWhy: 'Huawei only delivers to an open app.',
  listPathLabel: 'In the phone app',
  listPath: ['Settings', 'Watch', 'Show alarm list on watch'],
  vibTitle: 'Watch not vibrating?',
  vibRows: [
    { label: 'Watch vibration', where: 'In the alarm, in the phone app', device: 'phone', state: 'on' },
    { label: 'Do Not Disturb', where: 'On the watch', device: 'watch', state: 'off' },
    { label: 'Sleep mode', where: 'On the watch', device: 'watch', state: 'off' },
    { label: 'Vibration strength', where: 'On the watch', device: 'watch', state: 'notNone' },
  ],
  vibNone: 'None',
  stateText: { on: 'should be on', off: 'should be off', notNone: 'anything except None' },
  harmony: 'GT Wake and HarmonyOS',
}

export const zh: WatchHelpCopy = {
  otherLang: { to: '/watch', label: 'English' },
  title: '手表上的 GT Wake',
  heroLabel: '手机响铃后通过蓝牙唤醒手表，手表随之振动。在任一设备上关闭或稍后提醒，两边都会停止。',
  steps: ['手机响铃。', '通过蓝牙唤醒手表。', '手表振动。', '任一设备上关闭或稍后提醒，另一台同步。'],
  appTitle: '安装手机应用',
  appText: '手表需要 Android 手机上的 GT Wake Companion。',
  harmonyNote: '这部手机运行的是 HarmonyOS 5，无法安装 Android 应用。',
  harmonyLink: '了解原因（英文）',
  appGallery: '华为应用市场',
  play: 'Google Play',
  apkLink: '或直接下载安装文件（英文页面）',
  listTitle: '手表上的闹钟列表',
  listOff: '关闭（默认）',
  listOffText: 'GT Wake 保持关闭。',
  listOffLabel: '列表关闭时，在手机上编辑闹钟不会向手表发送任何内容，GT Wake 保持关闭。',
  listOn: '开启',
  listOnText: '每次更改都会短暂打开它。',
  listOnLabel: '列表开启时，手机上的每次编辑都会发送到手表，手表打开 GT Wake 并显示闹钟列表。',
  listWhy: '华为只能向已打开的应用发送更新。',
  listPathLabel: '在手机应用中',
  listPath: ['设置', '手表', '在手表上显示闹钟列表'],
  vibTitle: '手表不振动？',
  vibRows: [
    { label: '手表振动', where: '在手机应用的闹钟设置中', device: 'phone', state: 'on' },
    { label: '免打扰', where: '在手表上', device: 'watch', state: 'off' },
    { label: '睡眠模式', where: '在手表上', device: 'watch', state: 'off' },
    { label: '振动强度', where: '在手表上', device: 'watch', state: 'notNone' },
  ],
  vibNone: '无',
  stateText: { on: '应开启', off: '应关闭', notNone: '不能设为“无”' },
  harmony: 'GT Wake 与 HarmonyOS（英文）',
}
