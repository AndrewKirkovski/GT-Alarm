package com.kirkouski.gtwake.companion

import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.kirkouski.gtwake.companion.data.sync.IncomingMessageHandler
import com.kirkouski.gtwake.companion.ring.AlarmNotifications
import com.kirkouski.gtwake.companion.scheduler.AlarmScheduler
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GtAlarmApp : Application() {

    @Inject lateinit var wearBridge: WearBridgeService
    @Inject lateinit var incomingHandler: IncomingMessageHandler
    @Inject lateinit var scheduler: AlarmScheduler

    override fun onCreate() {
        super.onCreate()
        logLaunchBanner()
        AlarmNotifications.ensureChannel(this)
        // Direct-boot: defer Wear bridge wiring until first unlock — Huawei
        // Health auth needs the credential keystore. Picked up by
        // BootReceiver.BOOT_COMPLETED + MainActivity.onStart.
        //
        // TODO(BFU on-device verification): preArmWatch pre-unlock already
        // exercises the same binder via sendAlarmFired. If on a real GT 6
        // the watch rings without unlock, wire the receiver here too; if
        // not, drop the preArmWatch pre-unlock branch as dead code.
        val um = getSystemService(android.os.UserManager::class.java)
        if (um?.isUserUnlocked == true) {
            wearBridge.setIncomingHandler(incomingHandler)
        } else {
            Log.i(TAG, "onCreate pre-unlock — deferring wearBridge.setIncomingHandler to first unlock")
        }
    }

    // Single boot-time fingerprint for every launch: build identity + system
    // permission snapshot. First log line filterable from adb at any time —
    // tells us which APK we're running and which OS gates are open. Without
    // this, a real-hardware test report can't tell a "broken alarm" from a
    // "denied USE_FULL_SCREEN_INTENT" without re-deriving permission state.
    private fun logLaunchBanner() {
        val pkg = packageName
        val (verName, verCode) = runCatching {
            val info = packageManager.getPackageInfo(pkg, 0)
            info.versionName.orEmpty() to info.longVersionCode
        }.getOrDefault("?" to -1L)

        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val fsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm?.canUseFullScreenIntent()
        } else {
            true
        }
        val notif = nm?.areNotificationsEnabled()
        val ignBatt = pm?.isIgnoringBatteryOptimizations(pkg)
        val exact = runCatching { scheduler.canScheduleExact() }.getOrNull()
        val signer = firstSignerSha256Short()

        Log.i(
            TAG,
            "launch pkg=$pkg ver=$verName($verCode) " +
                "perms[notif=$notif fsi=$fsi exactAlarm=$exact ignBattOpt=$ignBatt] " +
                "signer=$signer sdk=${Build.VERSION.SDK_INT}"
        )
    }

    private fun firstSignerSha256Short(): String =
        // PackageManager.getPackageInfo can throw NameNotFoundException plus
        // platform-version-specific runtime exceptions; launch-banner helper
        // must never abort boot.
        runCatching {
            // minSdk 31; signingInfo + GET_SIGNING_CERTIFICATES are API 28+,
            // so the legacy GET_SIGNATURES branch is unreachable and dropped.
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val first = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching "?"
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(first.toByteArray())
            // Locale-free hex: cert fingerprints are wire identifiers, not
            // user-facing text, so we deliberately avoid String.format/uppercase
            // (both default-locale-sensitive per detekt ImplicitDefaultLocale).
            val sb = StringBuilder(SIGNER_PREFIX_BYTES * 2)
            for (i in 0 until SIGNER_PREFIX_BYTES) {
                val v = digest[i].toInt() and 0xFF
                sb.append(HEX_CHARS[v shr 4])
                sb.append(HEX_CHARS[v and 0xF])
            }
            sb.toString()
        }.getOrElse { "err:${it.javaClass.simpleName}" }

    private companion object {
        const val TAG = "GtAlarmApp"
        const val SIGNER_PREFIX_BYTES = 4
        val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
        )
    }
}
