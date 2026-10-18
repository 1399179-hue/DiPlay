package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRadioCheckTest {
    @Test fun aRadioThatIsOnLetsTheAttemptGoOn() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = true, pausedByDiPlay = false))
    }

    // An Allwinner T8 head unit came up with Bluetooth off. A fork build that still offered the
    // local-only hotspot made 94 attempts in under six hours; each started the hotspot and only
    // then failed on the radio.
    @Test fun aRadioThatIsOffStopsTheAttemptBeforeTheHotspot() {
        assertEquals(
            BluetoothRadioCheck.RADIO_OFF,
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = false),
        )
    }

    // The pause is undone before the handshake; stopping here would never let that happen.
    @Test fun aRadioThatDiPlayPausedDoesNotStopTheAttempt() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = true))
    }

    @Test fun aHeadUnitWithoutAnAdapterStopsTheAttemptWhateverElseIsKnown() {
        for (paused in listOf(false, true)) {
            assertEquals(
                BluetoothRadioCheck.NO_ADAPTER,
                BluetoothRadioCheck.blocker(adapterPresent = false, enabled = false, pausedByDiPlay = paused),
            )
        }
    }

    // CarPlayHostActivity shows the request in the app's language by finding these words.
    @Test fun theRequestCarriesTheWordsTheHostScreenLooksFor() {
        assertTrue(BluetoothRadioCheck.RADIO_OFF.contains(BluetoothRadioCheck.RADIO_OFF_MARK))
    }

    // Android 8 head units report this for a second or two after startup; the attempt waits it out
    // before asking, so a radio that is on its way up is not reported as one the driver must switch on.
    @Test fun aRadioStillTurningOnIsNotReportedAsOff() {
        assertNull(
            BluetoothRadioCheck.blockerForState(
                adapterPresent = true,
                state = BluetoothAdapter.STATE_TURNING_ON,
                pausedByDiPlay = false,
            ),
        )
    }

    @Test fun aRadioThatIsOffOrGoingOffStopsTheAttempt() {
        for (state in listOf(BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF)) {
            assertEquals(
                BluetoothRadioCheck.RADIO_OFF,
                BluetoothRadioCheck.blockerForState(adapterPresent = true, state = state, pausedByDiPlay = false),
            )
        }
    }

    @Test fun aRadioThatIsOnLetsTheAttemptGoOnByState() {
        assertNull(
            BluetoothRadioCheck.blockerForState(
                adapterPresent = true,
                state = BluetoothAdapter.STATE_ON,
                pausedByDiPlay = false,
            ),
        )
    }

    // A state that could not be read is no better than an adapter that is not there.
    @Test fun anUnreadableStateIsTreatedAsNoAdapter() {
        assertEquals(
            BluetoothRadioCheck.NO_ADAPTER,
            BluetoothRadioCheck.blockerForState(adapterPresent = true, state = null, pausedByDiPlay = false),
        )
    }
}
