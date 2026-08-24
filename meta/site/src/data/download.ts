// Typed access to the generated download metadata.
//
// download.json is written by scripts/sync-apk.mjs from the signed APK itself.
// This wrapper is hand-maintained: keeping the display helpers out of the JSON
// means the script only ever rewrites pure data, so a regenerated file diffs
// cleanly and a changed hash is visible in review.
import raw from './download.json'

export interface DownloadMeta {
  /** False when no APK was staged; the page falls back to store links. */
  available: boolean
  versionName: string
  versionCode: number
  fileName: string
  /** Site-absolute path to the binary. */
  url: string
  sizeBytes: number
  /** SHA-256 of the APK file, for the user to verify their download. */
  sha256: string
  /** SHA-256 of the signing certificate — the same one Google Play signs with. */
  certSha256: string
  minSdk: number
  /** YYYY-MM-DD, from the APK's own mtime. */
  builtAt: string
}

export const download = raw as DownloadMeta

/** 5812620 -> "5.54 MiB" */
export function formatMiB(bytes: number): string {
  return `${(bytes / 1048576).toFixed(2)} MiB`
}

/** 5812620 -> "5,812,620" */
export function formatBytes(bytes: number): string {
  return bytes.toLocaleString('en-US')
}

/** "95f6001e..." -> "95:F6:00:1E:..." to match what apksigner and Play Console print. */
export function colonize(hex: string): string {
  return (hex.toUpperCase().match(/.{2}/g) ?? []).join(':')
}

/** minSdk -> the marketing Android version users recognise. */
export function androidRelease(apiLevel: number): string {
  const names: Record<number, string> = {
    31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16',
  }
  return names[apiLevel] ?? `API ${apiLevel}`
}
