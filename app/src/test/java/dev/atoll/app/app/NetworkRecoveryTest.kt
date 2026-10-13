package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class NetworkRecoveryTest {
    @Test fun recoveryAndHandoverReconnectOnceWithoutRestartingForInitialOrRepeatedCallbacks() {
        val networks = DefaultNetworkChanges("wifi")
        assertFalse(networks.available("wifi"))
        networks.lost("wifi")
        assertTrue(networks.available("wifi"))
        assertFalse(networks.available("wifi"))
        assertTrue(networks.available("mobile"))
        networks.lost("wifi") // A late loss of the replaced network must not reset the new one.
        assertFalse(networks.available("mobile"))
        networks.lost("mobile")
        assertTrue(networks.available("wifi"))
    }
    @Test fun startingOfflineReconnectsWhenTheFirstNetworkAppears() {
        val networks = DefaultNetworkChanges<String>(null)
        assertTrue(networks.available("wifi"))
        assertFalse(networks.available("wifi"))
    }
}
