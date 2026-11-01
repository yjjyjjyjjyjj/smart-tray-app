package kr.ac.gachon.smarttray.bluetooth

import org.junit.Assert.*
import org.junit.Test

class DoorAlertPolicyTest {
    private val settings = NotificationSettings(true, true, true,
        mapOf(1 to "출근 준비", 2 to "2번 트레이"), setOf(1, 2))
    private val trays = listOf(TrayReading(1, true, 128.5), TrayReading(2, true, 32.8))
    private fun closed(policy: DoorAlertPolicy) { policy.evaluate(DeviceReading("closed", trays), settings) }
    @Test fun groupsOccupiedTraysAndSuppressesDuplicateOpenSnapshots() {
        val policy = DoorAlertPolicy(); closed(policy)
        val alert = policy.evaluate(DeviceReading("open", trays), settings)!!
        assertEquals(listOf(1, 2), alert.trayIds)
        assertEquals("출근 준비 트레이, 2번 트레이에 물건이 남아있어요. 외출 전에 확인해주세요.", alert.message)
        assertNull(policy.evaluate(DeviceReading("open", trays), settings))
        closed(policy)
        assertNotNull(policy.evaluate(DeviceReading("open", trays), settings))
    }
    @Test fun disabledTrayAndFaultySensorAreExcluded() {
        val policy = DoorAlertPolicy(); closed(policy)
        val onlySecond = settings.copy(enabledTrays = setOf(2))
        val alert = policy.evaluate(DeviceReading("open", trays), onlySecond)!!
        assertEquals(listOf(2), alert.trayIds)
        assertEquals("2번 트레이에 물건이 남아있어요. 외출 전에 확인해주세요.", alert.message)
        closed(policy)
        assertEquals("door", policy.evaluate(DeviceReading("open", trays.map { it.copy(sensor = "error") }), settings)!!.kind)
    }
    @Test fun emptyTraysAndSeparateDoorPreference() {
        val policy = DoorAlertPolicy(); closed(policy)
        assertNull(policy.evaluate(DeviceReading("open", trays.map { it.copy(present = false) }), settings.copy(door = false)))
    }
    @Test fun enablingNotificationsWhileOpenDoesNotRetroactivelyNotify() {
        val policy = DoorAlertPolicy(); closed(policy)
        assertNull(policy.evaluate(DeviceReading("open", trays), settings.copy(enabled = false)))
        assertNull(policy.evaluate(DeviceReading("open", trays), settings))
    }
    @Test fun reconnectSnapshotDoesNotInventNewDoorEvent() {
        val policy = DoorAlertPolicy()
        assertNull(policy.evaluate(DeviceReading("open", trays), settings))
        closed(policy); assertNotNull(policy.evaluate(DeviceReading("open", trays), settings))
        policy.reset(); assertNull(policy.evaluate(DeviceReading("open", trays), settings))
    }
    @Test fun unknownDoorInvalidatesTransitionBaseline() {
        val policy = DoorAlertPolicy()
        closed(policy)
        assertNull(policy.evaluate(DeviceReading("unknown", trays), settings))
        assertNull(policy.evaluate(DeviceReading("open", trays), settings))
    }
    @Test fun incompleteFirmwareInputDoesNotInventSensorReadings() {
        val pending = ArduinoJsonProtocol()
        assertTrue(pending.ready)
        assertTrue(pending.feed(byteArrayOf(1, 2, 3)).isEmpty())
    }
}
