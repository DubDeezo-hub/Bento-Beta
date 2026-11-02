package com.example.pssender

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineAlertTest {
    @Test
    fun comingOnlineIsAnnounced() {
        assertTrue(OnlineAlert.shouldNotify(was = false, up = true))
    }

    @Test
    fun stillOnlineIsNotAnnouncedAgain() {
        assertFalse(OnlineAlert.shouldNotify(was = true, up = true))
    }

    @Test
    fun theFirstLookOnlyNotes() {
        // Right after the switch is turned on nothing is known yet: no alert for a console that is already up
        assertFalse(OnlineAlert.shouldNotify(was = null, up = true))
    }

    @Test
    fun goingDownIsNotAnnounced() {
        assertFalse(OnlineAlert.shouldNotify(was = true, up = false))
        assertFalse(OnlineAlert.shouldNotify(was = false, up = false))
    }
}
