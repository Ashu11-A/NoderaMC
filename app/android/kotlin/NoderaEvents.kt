package dev.nodera.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Where the node's pushes land.
 *
 * Rust calls [onEvent] from a tokio worker thread; Compose collects the flow. It is a push and not
 * a poll on purpose: the dashboard is emitted at the moment a snapshot is accepted, so an event
 * means "this just changed" rather than "a second has passed" — and a polling front end would have
 * re-introduced exactly the cadence this codebase spent a release removing.
 *
 * `extraBufferCapacity` rather than a rendezvous: `tryEmit` must never block a tokio thread, and a
 * dropped frame under load is better than a stalled link. `replay = 1` is what lets a screen opened
 * ten seconds in render immediately rather than waiting for the next push.
 */
object NoderaEvents {
    private val stream = MutableSharedFlow<Pair<String, String>>(
        replay = 1,
        extraBufferCapacity = 32,
    )

    val events: SharedFlow<Pair<String, String>> = stream

    /** Called from Rust. Keep the name and signature: `bridge.rs` looks them up by string. */
    @JvmStatic
    fun onEvent(topic: String, json: String) {
        stream.tryEmit(topic to json)
    }
}
