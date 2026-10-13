package dev.atoll.app.app

/** Ignore callbacks for an unchanged default network; report recovery and handover. */
internal class DefaultNetworkChanges<N>(initial: N?) {
    private var current = initial
    @Synchronized fun available(network: N): Boolean {
        if (current == network) return false
        current = network
        return true
    }
    @Synchronized fun lost(network: N) {
        if (current == network) current = null
    }
}
