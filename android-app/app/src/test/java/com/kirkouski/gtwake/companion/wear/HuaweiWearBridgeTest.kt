package com.kirkouski.gtwake.companion.wear

import android.content.Context
import com.huawei.hmf.tasks.OnFailureListener
import com.huawei.hmf.tasks.OnSuccessListener
import com.huawei.hmf.tasks.Task
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.auth.AuthClient
import com.huawei.wearengine.auth.Permission
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.device.DeviceClient
import com.huawei.wearengine.p2p.Message
import com.huawei.wearengine.p2p.P2pClient
import com.huawei.wearengine.p2p.PingCallback
import com.huawei.wearengine.p2p.Receiver
import com.huawei.wearengine.p2p.SendCallback
import com.kirkouski.gtwake.companion.data.SettingsStore
import com.kirkouski.gtwake.companion.data.sync.IncomingMessageHandler
import com.kirkouski.gtwake.companion.domain.Alarm
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HuaweiWearBridgeTest {
    private val context = mockk<Context>(relaxed = true)
    private val authClient = mockk<AuthClient>()
    private val deviceClient = mockk<DeviceClient>()
    private val p2pClient = mockk<P2pClient>()

    // Everything the bridge sent, parsed. File messages are recorded by name.
    private val sent = mutableListOf<JSONObject>()
    private var receiver: Receiver? = null

    // Per-test send behavior. Default: transport success (207).
    private var onSend: (Message, SendCallback) -> Unit = { _, cb -> cb.onSendResult(207) }

    @Before
    fun mockHiWearClients() {
        mockkStatic(HiWear::class)
        every { HiWear.getAuthClient(context) } returns authClient
        every { HiWear.getDeviceClient(context) } returns deviceClient
        every { HiWear.getP2pClient(context) } returns p2pClient
        every { p2pClient.setPeerPkgName(any()) } returns p2pClient
        every { p2pClient.setPeerFingerPrint(any()) } returns p2pClient
        every { authClient.checkPermission(Permission.DEVICE_MANAGER) } returns successfulTask(true)
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } answers {
            receiver = secondArg()
            successfulTask<Void>(null)
        }
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns successfulTask<Void>(null)
        every { p2pClient.ping(any<Device>(), any<PingCallback>()) } answers {
            secondArg<PingCallback>().onPingResult(202)
            successfulTask<Void>(null)
        }
        every { p2pClient.send(any<Device>(), any<Message>(), any<SendCallback>()) } answers {
            val message = secondArg<Message>()
            message.data?.let { sent += JSONObject(String(it, Charsets.UTF_8)) }
            onSend(message, thirdArg())
            successfulTask<Void>(null)
        }
    }

    @After
    fun unmockHiWearClients() {
        unmockkStatic(HiWear::class)
    }

    @Test
    fun `successful null receiver registration is retained for repeated handler install`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } returns successfulTask<Void>(null)

        val bridge = bridge(StandardTestDispatcher(testScheduler))
        val handler = mockk<IncomingMessageHandler>(relaxed = true)
        bridge.setIncomingHandler(handler)
        runCurrent()
        bridge.setIncomingHandler(handler)
        runCurrent()

        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }
    }

    @Test
    fun `same handler retries receiver registration after failed task`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        val registrations = mutableListOf<Task<Void>>()
        registrations += failedTask<Void>()
        registrations += successfulTask<Void>(null)
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } answers { registrations.removeAt(0) }

        val bridge = bridge(StandardTestDispatcher(testScheduler))
        val handler = mockk<IncomingMessageHandler>(relaxed = true)
        bridge.setIncomingHandler(handler)
        runCurrent()
        bridge.setIncomingHandler(handler)
        runCurrent()

        verify(exactly = 2) { p2pClient.registerReceiver(device, any<Receiver>()) }
        // A register that the SDK FAILED is a definite "not registered", so the
        // retry needs no unregister (only a timeout or a cancelled call does).
        verify(exactly = 0) { p2pClient.unregisterReceiver(any<Receiver>()) }
        assertTrue(bridge.isReceiveReady)
    }

    @Test
    fun `fresh connected device replaces stale disconnected selection and receiver`() = runTest {
        var oldConnected = true
        val oldDevice = connectedDevice("watch-old") { oldConnected }
        val newDevice = connectedDevice("watch-new")
        every { deviceClient.bondedDevices } answers {
            val devices = if (oldConnected) listOf(oldDevice) else listOf(oldDevice, newDevice)
            successfulTask(devices)
        }
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } returns successfulTask<Void>(null)
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns successfulTask<Void>(null)

        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        oldConnected = false
        bridge.refreshWatchConnection()
        runCurrent()

        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 1) { p2pClient.registerReceiver(oldDevice, any<Receiver>()) }
        verify(exactly = 1) { p2pClient.registerReceiver(newDevice, any<Receiver>()) }
    }

    // ---- B1 / C3: legacy reply matching (fixture-driven) -----------------------

    // Fails on the old code: the pending request carries firedAt, the released
    // reply does not, so the old "legacy only if BOTH lack firedAt" rule
    // dropped it and sendAlarmFiredAwaiting burned its full 10 s and returned false.
    @Test
    fun `released alarm_ringing without firedAt satisfies a pre-arm that carries firedAt`() = runTest {
        val bridge = startedBridge()
        val result = async { bridge.sendAlarmFiredAwaiting(3L, 10_000L, hints(firedAt = FIRED_AT)) }
        runCurrent()

        deliver(fixture("watchToPhone", "alarm_ringing"))

        assertTrue(result.await())
        assertTrue("completed by the reply, not by the timeout", testScheduler.currentTime < 10_000L)
    }

    // Fails on the old code for the same reason: the dismiss wait ran out its
    // resend rounds (about 12 s with forced re-pings) instead of completing.
    @Test
    fun `released alarm_dismissed_ack without firedAt satisfies a dismiss wait that carries firedAt`() = runTest {
        val bridge = startedBridge()
        val result = async { bridge.sendAlarmDismissedAwaiting(3L, 12_000L, FIRED_AT) }
        runCurrent()

        deliver(fixture("watchToPhone", "alarm_dismissed_ack"))

        assertTrue(result.await())
        assertTrue(testScheduler.currentTime < 4_000L)
    }

    @Test
    fun `released alarm_snoozed_ack without firedAt satisfies a snooze wait that carries firedAt`() = runTest {
        val bridge = startedBridge()
        val result = async { bridge.sendAlarmSnoozedAwaiting(3L, 1_760_000_300_000L, 12_000L, FIRED_AT) }
        runCurrent()

        deliver(fixture("watchToPhone", "alarm_snoozed_ack"))

        assertTrue(result.await())
        assertTrue(testScheduler.currentTime < 4_000L)
    }

    @Test
    fun `reply with firedAt and the matching stamp completes the pre-arm`() = runTest {
        val bridge = startedBridge()
        val result = async { bridge.sendAlarmFiredAwaiting(3L, 10_000L, hints(firedAt = FIRED_AT)) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_fired" }.getLong("updatedAtEpoch")

        deliver(fixture("watchToPhone", "alarm_ringing").put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp))

        assertTrue(result.await())
    }

    @Test
    fun `reply with a wrong firedAt or a wrong stamp does not complete the pre-arm`() = runTest {
        val bridge = startedBridge()
        val result = async { bridge.sendAlarmFiredAwaiting(3L, 10_000L, hints(firedAt = FIRED_AT)) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_fired" }.getLong("updatedAtEpoch")

        deliver(fixture("watchToPhone", "alarm_ringing").put("firedAt", FIRED_AT + 1).put("updatedAtEpoch", stamp))
        deliver(fixture("watchToPhone", "alarm_ringing").put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp + 1))
        advanceUntilIdle()

        assertFalse(result.await())
    }

    @Test
    fun `ok false never completes a dismiss wait, with or without firedAt`() = runTest {
        val bridge = startedBridge()
        val result = async { bridge.sendAlarmDismissedAwaiting(3L, 12_000L, FIRED_AT) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_dismissed" }.getLong("updatedAtEpoch")

        deliver(fixture("watchToPhone", "alarm_dismissed_ack").put("ok", false))
        deliver(
            fixture("watchToPhone", "alarm_dismissed_ack")
                .put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp).put("ok", false),
        )
        advanceUntilIdle()

        assertFalse(result.await())
    }

    // Passes on the old code too (a legacy reply was already refused there for
    // another reason); it pins the tightening so it cannot regress.
    @Test
    fun `after a firedAt reply matched, a legacy reply no longer matches that ring`() = runTest {
        val bridge = startedBridge()
        val ringing = async { bridge.sendAlarmFiredAwaiting(3L, 10_000L, hints(firedAt = FIRED_AT)) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_fired" }.getLong("updatedAtEpoch")
        deliver(fixture("watchToPhone", "alarm_ringing").put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp))
        assertTrue(ringing.await())

        val dismiss = async { bridge.sendAlarmDismissedAwaiting(3L, 12_000L, FIRED_AT) }
        runCurrent()
        deliver(fixture("watchToPhone", "alarm_dismissed_ack"))
        advanceUntilIdle()

        assertFalse(dismiss.await())
    }

    // An updated watch echoes firedAt, the phone's own stamp and ok:true. Once
    // the pre-arm matched strictly, only this form can end a phone Stop/Snooze
    // wait, so a slip in it would leave every phone Stop on "Waiting for watch…".
    private suspend fun TestScope.strictPreArm(bridge: HuaweiWearBridge) {
        val ringing = async { bridge.sendAlarmFiredAwaiting(3L, 10_000L, hints(firedAt = FIRED_AT)) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_fired" }.getLong("updatedAtEpoch")
        deliver(fixture("watchToPhone", "alarm_ringing").put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp))
        assertTrue(ringing.await())
    }

    @Test
    fun `updated watch reply with firedAt, stamp and ok true completes a dismiss wait`() = runTest {
        val bridge = startedBridge()
        strictPreArm(bridge)
        val start = testScheduler.currentTime

        val dismiss = async { bridge.sendAlarmDismissedAwaiting(3L, 12_000L, FIRED_AT) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_dismissed" }.getLong("updatedAtEpoch")
        deliver(
            fixture("watchToPhone", "alarm_dismissed_ack")
                .put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp).put("ok", true),
        )

        assertTrue(dismiss.await())
        assertTrue("completed by the reply, not by the timeout", testScheduler.currentTime - start < 4_000L)
    }

    @Test
    fun `updated watch reply with firedAt, stamp and ok true completes a snooze wait`() = runTest {
        val bridge = startedBridge()
        strictPreArm(bridge)
        val start = testScheduler.currentTime

        val snooze = async { bridge.sendAlarmSnoozedAwaiting(3L, 1_760_000_300_000L, 12_000L, FIRED_AT) }
        runCurrent()
        val stamp = sent.last { it.getString("type") == "alarm_snoozed" }.getLong("updatedAtEpoch")
        deliver(
            fixture("watchToPhone", "alarm_snoozed_ack")
                .put("firedAt", FIRED_AT).put("updatedAtEpoch", stamp).put("ok", true),
        )

        assertTrue(snooze.await())
        assertTrue("completed by the reply, not by the timeout", testScheduler.currentTime - start < 4_000L)
    }

    // ---- B3 / B4: replies are sent without a wake --------------------------------

    // Fails on the old code: sendPeerActionAck went through performSend, which
    // pings when the running cache is cold.
    @Test
    fun `sendPeerActionAck sends the reply without pinging the watch`() = runTest {
        val bridge = startedBridge()

        bridge.sendPeerActionAck(3L, "alarm_dismissed", 555L, FIRED_AT, true)
        runCurrent()

        verify(exactly = 0) { p2pClient.ping(any<Device>(), any<PingCallback>()) }
        val reply = sent.single()
        assertEquals("alarm_dismissed_ack", reply.getString("type"))
        assertEquals(3L, reply.getLong("alarmId"))
        assertEquals(555L, reply.getLong("updatedAtEpoch"))
        assertEquals(FIRED_AT, reply.getLong("firedAt"))
        assertEquals(true, reply.getBoolean("ok"))
    }

    // Fails on the old code: a legacy request (no firedAt) still got a reply.
    @Test
    fun `sendPeerActionAck with null firedAt sends nothing`() = runTest {
        val bridge = startedBridge()

        bridge.sendPeerActionAck(3L, "alarm_snoozed", 555L, null, true)
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        verify(exactly = 0) { p2pClient.ping(any<Device>(), any<PingCallback>()) }
    }

    // Fails on the old code: sendPeerEnded did not exist (interface default no-op).
    @Test
    fun `sendPeerEnded sends the existing peer-ended envelope without pinging`() = runTest {
        val bridge = startedBridge()

        bridge.sendPeerEnded(3L, "alarm_snoozed", FIRED_AT)
        bridge.sendPeerEnded(4L, "alarm_dismissed", FIRED_AT + 5)
        runCurrent()

        verify(exactly = 0) { p2pClient.ping(any<Device>(), any<PingCallback>()) }
        assertEquals(listOf("alarm_snoozed", "alarm_dismissed"), sent.map { it.getString("type") })
        val snoozed = sent[0]
        assertEquals(3L, snoozed.getLong("alarmId"))
        assertEquals(FIRED_AT, snoozed.getLong("firedAt"))
        assertTrue(snoozed.getLong("updatedAtEpoch") > 1_700_000_000_000L)
        assertFalse(snoozed.has("rescheduleEpoch"))
    }

    // Fails on the old code: a failed reply send marked the connection ERROR
    // (invalidateAndMarkError) and pinged before each retry.
    @Test
    fun `a failed reply is retried without ping and does not mark the connection as error`() = runTest {
        val bridge = startedBridge()
        val results = ArrayDeque(listOf(206, 206, 207))
        onSend = { _, cb -> cb.onSendResult(results.removeFirst()) }

        bridge.sendPeerActionAck(3L, "alarm_dismissed", 555L, FIRED_AT, false)
        advanceUntilIdle()

        assertEquals(3, sent.size)
        verify(exactly = 0) { p2pClient.ping(any<Device>(), any<PingCallback>()) }
        assertNotEquals(WatchSyncStatus.ERROR, bridge.statusFlow.value)
    }

    @Test
    fun `a reply that never completes is abandoned within the bound`() = runTest {
        val bridge = startedBridge()
        onSend = { _, _ -> }

        bridge.sendPeerActionAck(3L, "alarm_dismissed", 555L, FIRED_AT, true)
        advanceTimeBy(4_001L)
        runCurrent()

        assertTrue("no retry storm past the 4 s bound", sent.size <= 4)
        val sizeAtBound = sent.size
        advanceUntilIdle()
        assertEquals(sizeAtBound, sent.size)
        assertNotEquals(WatchSyncStatus.ERROR, bridge.statusFlow.value)
    }

    // ---- B6: alarm_fired capability flag -----------------------------------------

    // Fails on the old code: supportsActionAck was written unconditionally.
    @Test
    fun `alarm_fired carries supportsActionAck only when the hint is true`() = runTest {
        val bridge = startedBridge()

        bridge.sendAlarmFired(3L, hints(firedAt = FIRED_AT, supportsActionAck = false))
        bridge.sendAlarmFired(3L, null)
        bridge.sendAlarmFired(3L, hints(firedAt = FIRED_AT, supportsActionAck = true))
        runCurrent()

        val fired = sent.filter { it.getString("type") == "alarm_fired" }
        assertEquals(3, fired.size)
        assertFalse(fired[0].has("supportsActionAck"))
        assertFalse(fired[1].has("supportsActionAck"))
        assertEquals(true, fired[2].getBoolean("supportsActionAck"))
        assertEquals(FIRED_AT, fired[2].getLong("firedAt"))
    }

    // ---- B5 / B7 / B8: receiver ownership ----------------------------------------

    @Test
    fun `isReceiveReady follows the registration`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        assertFalse(bridge.isReceiveReady)

        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        assertTrue(bridge.isReceiveReady)

        bridge.setIncomingHandler(null)
        runCurrent()
        assertFalse(bridge.isReceiveReady)
    }

    // Fails on the old code: a 206 cleared receiverRegistered without
    // unregistering, so the retry's re-resolve registered the receiver a second time.
    @Test
    fun `a failed send does not cause a second receiver registration`() = runTest {
        val bridge = startedBridge()
        onSend = { _, cb -> cb.onSendResult(206) }

        val result = async { bridge.sendAlarmDismissed(3L, null) }
        advanceUntilIdle()

        assertFalse(result.await())
        verify(exactly = 1) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
        verify(exactly = 0) { p2pClient.unregisterReceiver(any<Receiver>()) }
        assertTrue(bridge.isReceiveReady)
    }

    // Fails on the old code: re-installing the same handler while the receiver
    // was not registered cancelled attachJob and replaced it.
    @Test
    fun `re-installing the same handler keeps attachJob`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        val registrations = mutableListOf<Task<Void>>(failedTask(), successfulTask(null))
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } answers { registrations.removeAt(0) }
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        val handler = mockk<IncomingMessageHandler>(relaxed = true)
        bridge.setIncomingHandler(handler)
        runCurrent()
        val before = attachJob(bridge)
        assertNotNull(before)

        bridge.setIncomingHandler(handler)
        runCurrent()

        assertSame(before, attachJob(bridge))
        assertTrue(before!!.isActive)
        assertTrue(bridge.isReceiveReady)
    }

    // ---- B9: lookup failure is not "no watch" ---------------------------------------

    // Fails on the old code: a null Task result was treated as an empty list,
    // which cleared the device and unregistered the receiver.
    @Test
    fun `a failed bonded-device lookup keeps the device and the registration`() = runTest {
        val bridge = startedBridge()
        assertNotNull(bridge.pairedDeviceInfo.value)

        every { deviceClient.bondedDevices } returns failedTask()
        bridge.refreshWatchConnection()
        runCurrent()

        assertNotNull(bridge.pairedDeviceInfo.value)
        assertTrue(bridge.isReceiveReady)
        verify(exactly = 0) { p2pClient.unregisterReceiver(any<Receiver>()) }
    }

    @Test
    fun `a failed permission check keeps the device and the registration`() = runTest {
        val bridge = startedBridge()

        every { authClient.checkPermission(Permission.DEVICE_MANAGER) } returns failedTask()
        bridge.refreshWatchConnection()
        runCurrent()

        assertNotNull(bridge.pairedDeviceInfo.value)
        assertTrue(bridge.isReceiveReady)
        verify(exactly = 0) { p2pClient.unregisterReceiver(any<Receiver>()) }
    }

    @Test
    fun `a successful empty bonded-device list clears the device and the registration`() = runTest {
        val bridge = startedBridge()

        every { deviceClient.bondedDevices } returns successfulTask(emptyList<Device>())
        bridge.refreshWatchConnection()
        runCurrent()

        assertNull(bridge.pairedDeviceInfo.value)
        assertFalse(bridge.isReceiveReady)
        assertEquals(WatchSyncStatus.NOT_CONNECTED, bridge.statusFlow.value)
        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }
    }

    // ---- B10: bounded SDK waits --------------------------------------------------------

    // Fails on the old code: a Task that never completes held connectionMutex
    // forever, so the second install never registered.
    @Test
    fun `a Task that never completes times out and releases the connection mutex`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns hangingTask()
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        verify(exactly = 0) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }

        advanceTimeBy(5_001L)
        runCurrent()
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()

        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }
        assertTrue(bridge.isReceiveReady)
    }

    // Fails on the old code: sendOnce waited on the callback with no bound, so
    // the dismiss never returned.
    @Test
    fun `a send callback that never fires does not hang the caller`() = runTest {
        val bridge = startedBridge()
        onSend = { _, _ -> }

        val result = async { bridge.sendAlarmDismissed(3L, null) }
        advanceUntilIdle()

        assertTrue(result.isCompleted)
        assertFalse(result.await())
    }

    // ---- B11: legacy alarms_received -----------------------------------------------------

    // Fails on the old code: the second stamp clause rejected every
    // alarms_received whose stamp was not the phone's, so the legacy file push
    // waited out SYNC_COMMIT_ACK_TIMEOUT_MS (4 s) before verifying by hash.
    @Test
    fun `legacy alarms_received with the watch's own stamp completes the file replace`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("gtwake-sync").toFile()
        try {
            every { context.cacheDir } returns tmp
            val bridge = startedBridge()
            var watchHash = "00000000"
            onSend = { message, cb ->
                cb.onSendResult(207)
                val file = message.file
                val data = message.data
                if (file != null) {
                    watchHash = file.name.removePrefix("alarms_").removeSuffix(".json")
                    deliverNow(fixture("watchToPhone", "alarms_received").put("hash", watchHash))
                } else if (data != null && JSONObject(String(data, Charsets.UTF_8)).optString("type") == "sync_check") {
                    deliverNow(JSONObject().put("type", "sync_hash").put("hash", watchHash))
                }
            }
            val alarms = (1L..8L).map { Alarm(id = it, label = "a$it", hour = 7, minute = it.toInt(), updatedAtEpoch = it) }

            val result = async { bridge.forceSync { alarms } }
            advanceUntilIdle()

            assertEquals(ForceSyncResult.Ok(alarms.size), result.await())
            assertTrue(
                "the ack completed the wait instead of timing out (${testScheduler.currentTime} ms)",
                testScheduler.currentTime < 4_000L,
            )
        } finally {
            tmp.deleteRecursively()
        }
    }


    // ---- round 2: awaitReceiveReady, uncertainty, selection --------------------------

    // Fails on the old code: awaitReceiveReady was only the interface default
    // (returns isReceiveReady), so it never registered and returned false.
    @Test
    fun `awaitReceiveReady registers without pinging and returns true`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns failedTask()
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        assertFalse(bridge.isReceiveReady)
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))

        val ready = bridge.awaitReceiveReady(1_500L)

        assertTrue(ready)
        assertTrue(bridge.isReceiveReady)
        assertNotNull(bridge.pairedDeviceInfo.value)
        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }
        verify(exactly = 0) { p2pClient.ping(any<Device>(), any<PingCallback>()) }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `awaitReceiveReady without an installed handler does not register`() = runTest {
        every { deviceClient.bondedDevices } returns successfulTask(listOf(connectedDevice("watch-1")))
        val bridge = bridge(StandardTestDispatcher(testScheduler))

        assertFalse(bridge.awaitReceiveReady(1_500L))

        verify(exactly = 0) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
    }

    // Fails on the old code: no implementation, so it could not become ready
    // after the hang cleared.
    @Test
    fun `awaitReceiveReady returns false within the timeout and releases the mutex`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns failedTask()
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        every { deviceClient.bondedDevices } returns hangingTask()

        val started = testScheduler.currentTime
        val first = async { bridge.awaitReceiveReady(1_500L) }
        advanceTimeBy(1_501L)
        runCurrent()

        assertTrue(first.isCompleted)
        assertFalse(first.await())
        assertTrue(testScheduler.currentTime - started < 5_000L)

        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        assertTrue(bridge.awaitReceiveReady(1_500L))
        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }
    }

    // Fails on the old code: the flag was only set AFTER a non-successful
    // result, so a registration cancelled mid-flight left it false and the next
    // register was not preceded by an unregister.
    @Test
    fun `cancelling a register in flight leaves the next register preceded by an unregister`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } returns hangingTask()
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }

        // A different handler replaces attachJob and cancels the register above.
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } returns successfulTask<Void>(null)
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()

        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 2) { p2pClient.registerReceiver(device, any<Receiver>()) }
        assertTrue(bridge.isReceiveReady)
    }

    // Fails on the old code: a timed-out unregister cleared the flags as if it
    // had succeeded, so the next register skipped the unregister.
    @Test
    fun `an unregister timeout keeps the uncertain flag until a later unregister`() = runTest {
        val bridge = startedBridge()
        val u1 = Completion()
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns controllableTask<Void>(u1)
        bridge.setIncomingHandler(null)
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()
        assertFalse(bridge.isReceiveReady)
        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }

        // The timed-out unregister finishes late; only then may a fresh pass proceed.
        u1.succeed()
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns successfulTask<Void>(null)
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()

        verify(exactly = 2) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 2) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
        assertTrue(bridge.isReceiveReady)
    }

    // Fails on the old code: firstOrNull { isConnected } followed the list order,
    // so a reordered list flipped the receiver to the other device.
    @Test
    fun `selection keeps the registered device when two bonded devices are connected`() = runTest {
        val deviceA = connectedDevice("watch-a")
        val deviceB = connectedDevice("watch-b")
        var order = listOf(deviceA, deviceB)
        every { deviceClient.bondedDevices } answers { successfulTask(order) }
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()

        order = listOf(deviceB, deviceA)
        bridge.refreshWatchConnection()
        runCurrent()

        verify(exactly = 1) { p2pClient.registerReceiver(deviceA, any<Receiver>()) }
        verify(exactly = 0) { p2pClient.registerReceiver(deviceB, any<Receiver>()) }
        verify(exactly = 0) { p2pClient.unregisterReceiver(any<Receiver>()) }
    }

    // Fails on the old code: every re-resolve set CONNECTING, so the status
    // flickered while a known watch was being re-checked.
    @Test
    fun `re-resolving a known device does not flip the status to connecting`() = runTest {
        var connected = true
        val device = connectedDevice("watch-1") { connected }
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        assertEquals(WatchSyncStatus.CONNECTED, bridge.statusFlow.value)

        connected = false
        every { deviceClient.bondedDevices } returns hangingTask()
        val send = async { bridge.sendAlarmDismissed(3L, null) }
        runCurrent()

        assertEquals(WatchSyncStatus.CONNECTED, bridge.statusFlow.value)
        advanceUntilIdle()
        send.await()
    }

    // Fails on the old code: after a timed-out pre-register unregister it
    // registered at once, so a late unregister could remove the new
    // registration while the flags still said ready.
    @Test
    fun `a timed-out pre-register unregister blocks the register until a later pass`() = runTest {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } returns hangingTask()
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }

        // Replacing the handler cancels the register above (uncertain), and the
        // unregister that must precede the next register never answers.
        every { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) } returns successfulTask<Void>(null)
        val u1 = Completion()
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns controllableTask<Void>(u1)
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()

        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 1) { p2pClient.registerReceiver(device, any<Receiver>()) }
        assertFalse(bridge.isReceiveReady)

        // U1 finishes late; the next pass then does one fresh unregister + register.
        u1.succeed()
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns successfulTask<Void>(null)
        assertTrue(bridge.awaitReceiveReady(1_500L))

        verify(exactly = 2) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 2) { p2pClient.registerReceiver(device, any<Receiver>()) }
    }

    // Fails on the old code: after U1 timed out, a later pass sent U2 and then
    // registered; U1 could still complete late and remove that registration.
    @Test
    fun `an outstanding timed-out unregister blocks every register until it completes`() = runTest {
        val bridge = startedBridge()
        val u1 = Completion()
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns controllableTask<Void>(u1)
        bridge.setIncomingHandler(null)
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()
        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }

        // A later pass whose own unregister would succeed still must not register
        // while U1 is pending (it is not even queued behind it).
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns successfulTask<Void>(null)
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        assertFalse(bridge.awaitReceiveReady(1_500L))

        verify(exactly = 1) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }
        assertFalse(bridge.isReceiveReady)

        // U1 finishes late (here with a failure; success clears it the same way).
        u1.fail()
        assertTrue(bridge.awaitReceiveReady(1_500L))

        verify(exactly = 2) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 2) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
    }

    // Fails on the old code: an unregister that never answered blocked every
    // later register until the process restarted.
    @Test
    fun `a hung unregister is abandoned after 60 seconds so registration can resume`() = runTest {
        val bridge = startedBridge()
        var now = 1_000L
        bridge.elapsedClock = { now }
        val u1 = Completion()
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns controllableTask<Void>(u1)
        bridge.setIncomingHandler(null)
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals(1, bridge.outstandingUnregisterCount)

        // Before 60 s: still blocked, nothing new is sent.
        every { p2pClient.unregisterReceiver(any<Receiver>()) } returns successfulTask<Void>(null)
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        now = 1_000L + 59_000L
        assertFalse(bridge.awaitReceiveReady(1_500L))
        verify(exactly = 1) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 1) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
        assertEquals(1, bridge.outstandingUnregisterCount)

        // After 60 s: U1 is abandoned; one fresh unregister + register.
        now = 1_000L + 61_000L
        assertTrue(bridge.awaitReceiveReady(1_500L))
        verify(exactly = 2) { p2pClient.unregisterReceiver(any<Receiver>()) }
        verify(exactly = 2) { p2pClient.registerReceiver(any<Device>(), any<Receiver>()) }
        assertEquals(0, bridge.outstandingUnregisterCount)

        // U1 finally completes: nothing changes, the count cannot go below zero.
        u1.succeed()
        assertEquals(0, bridge.outstandingUnregisterCount)
        assertTrue(bridge.isReceiveReady)
        verify(exactly = 2) { p2pClient.unregisterReceiver(any<Receiver>()) }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun bridge(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        HuaweiWearBridge(context, dispatcher, mockk<SettingsStore>(relaxed = true))

    private fun TestScope.startedBridge(): HuaweiWearBridge {
        val device = connectedDevice("watch-1")
        every { deviceClient.bondedDevices } returns successfulTask(listOf(device))
        val bridge = bridge(StandardTestDispatcher(testScheduler))
        bridge.setIncomingHandler(mockk(relaxed = true))
        runCurrent()
        sent.clear()
        return bridge
    }

    private fun TestScope.deliver(json: JSONObject) {
        deliverNow(json)
        runCurrent()
    }

    private fun deliverNow(json: JSONObject) {
        receiver!!.onReceiveMessage(Message.Builder().setPayload(json.toString().toByteArray(Charsets.UTF_8)).build())
    }

    private fun hints(firedAt: Long, supportsActionAck: Boolean = false) = AlarmRingHints(
        vibrationPatternName = "PULSE",
        snoozeAllowed = true,
        watchVibrationEnabled = true,
        firedAtEpoch = firedAt,
        supportsActionAck = supportsActionAck,
    )

    private fun attachJob(bridge: HuaweiWearBridge): Job? =
        HuaweiWearBridge::class.java.getDeclaredField("attachJob").let {
            it.isAccessible = true
            it.get(bridge) as Job?
        }

    // Released 1.0.11 envelopes (copy, so a test can add fields).
    private fun fixture(group: String, name: String): JSONObject {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH)) { "missing $FIXTURE_PATH" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(JSONObject(text).getJSONObject(group).getJSONObject(name).toString())
    }

    private fun connectedDevice(uuid: String, connected: () -> Boolean = { true }): Device {
        val device = mockk<Device>()
        every { device.uuid } returns uuid
        every { device.name } returns "Watch"
        every { device.model } returns "GT Test"
        every { device.isConnected } answers { connected() }
        return device
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> successfulTask(value: T?): Task<T> {
        val task = mockk<Task<T>>()
        every { task.addOnSuccessListener(any()) } answers {
            firstArg<OnSuccessListener<T>>().onSuccess(value as T)
            task
        }
        every { task.addOnFailureListener(any()) } returns task
        return task
    }

    private fun <T> failedTask(): Task<T> {
        val task = mockk<Task<T>>()
        every { task.addOnSuccessListener(any()) } returns task
        every { task.addOnFailureListener(any()) } answers {
            firstArg<OnFailureListener>().onFailure(IllegalStateException("task failed"))
            task
        }
        return task
    }

    // A Task whose listeners are never called.
    private fun <T> hangingTask(): Task<T> {
        val task = mockk<Task<T>>()
        every { task.addOnSuccessListener(any()) } returns task
        every { task.addOnFailureListener(any()) } returns task
        return task
    }

    // A Task that stays pending until the test completes it. The listeners it
    // received are kept, like the SDK keeps them, so a late completion still
    // reaches the bridge after the bridge gave up waiting.
    private class Completion {
        var succeed: () -> Unit = {}
        var fail: () -> Unit = {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> controllableTask(completion: Completion): Task<T> {
        val task = mockk<Task<T>>()
        var success: OnSuccessListener<T>? = null
        var failure: OnFailureListener? = null
        every { task.addOnSuccessListener(any()) } answers {
            success = firstArg<OnSuccessListener<T>>()
            task
        }
        every { task.addOnFailureListener(any()) } answers {
            failure = firstArg<OnFailureListener>()
            task
        }
        completion.succeed = { success?.onSuccess(null as T) }
        completion.fail = { failure?.onFailure(IllegalStateException("late failure")) }
        return task
    }

    private companion object {
        const val FIXTURE_PATH = "fixtures/released-1.0.11-envelopes.json"
        const val FIRED_AT = 1_760_000_000_000L
    }
}
