package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class BluetoothDiagnosticSnapshotTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        context.getSharedPreferences("diplay_bluetooth_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun capturesAdapterPresenceAndStateWhenEnabled() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter).setEnabled(true)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals(27, snap.apiLevel)
        assertTrue(snap.hasDefaultAdapterFallback)
        assertEquals("STATE_ON", snap.adapterStateName)
        assertTrue(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.READY, snap.classification)
    }

    @Test
    fun capturesDisabledStateWhenOff() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        shadowOf(adapter).setEnabled(false)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals("STATE_OFF", snap.adapterStateName)
        assertFalse(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.DISABLED, snap.classification)
    }

    @Test
    fun capturesTransitionalStateWhenTurningOn() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_TURNING_ON)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals("STATE_TURNING_ON", snap.adapterStateName)
        assertFalse(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.TURNING_ON, snap.classification)
    }

    @Test
    fun capturesAbsentStateWhenBluetoothUnsupported() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals("ABSENT", snap.adapterStateName)
        assertFalse(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.ABSENT, snap.classification)
    }

    @Test
    fun recordsAndRedactsFailureInfo() {
        BluetoothDiagnosticSnapshot.recordFailure(
            context,
            BluetoothDiagnosticSnapshot.FailureCategory.SOCKET_MANAGER_MISSING,
            "Could not connect RFCOMM to AA:BB:CC:DD:EE:FF: socket manager missing",
        )

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals(BluetoothDiagnosticSnapshot.FailureCategory.SOCKET_MANAGER_MISSING.name, snap.lastFailureCategory)
        assertTrue(snap.lastFailureSummary!!.contains("[REDACTED_MAC]"))
        assertFalse(snap.lastFailureSummary!!.contains("AA:BB:CC:DD:EE:FF"))

        val report = BluetoothDiagnosticSnapshot.report(context)
        assertTrue(report.contains("--- Bluetooth Diagnostic Snapshot ---"))
        assertTrue(report.contains("SOCKET_MANAGER_MISSING"))
        assertFalse(report.contains("AA:BB:CC:DD:EE:FF"))
    }
}
