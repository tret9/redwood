@file:OptIn(app.cash.redwood.RedwoodCodegenApi::class)

package app.cash.redwood.treehouse

import app.cash.redwood.protocol.EventTag
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.guest.DirectEventDispatcher
import app.cash.redwood.protocol.guest.ProtocolMismatchHandler
import app.cash.redwood.protocol.guest.ProtocolWidget

/**
 * Module-level holder for the direct host→guest event sink.
 *
 * The guest adapter in use ([FastGuestProtocolAdapter] in JSON mode,
 * [BridgeGuestProtocolAdapterImpl] in bridge mode) binds a [router] via [bindDirectEventSink]:
 * the host JS_Calls `globalThis[DIRECT_EVENT_SINK_NAME]` with `(id, tag, args...)`, the entry
 * looks the widget up by id and dispatches the raw (already-converted) args to its
 * [DirectEventDispatcher]. The host probes for the global sink at runtime; when present it skips
 * JSON event encoding entirely.
 */
internal object DirectEventSink {
  var router: ((id: Int, tag: Int, args: Array<Any?>) -> Unit)? = null
}

/** The globalThis entry the host JS_Calls: looks up the widget and dispatches. */
internal fun directEventSinkEntry(id: Int, tag: Int, args: Array<Any?>) {
  val router = DirectEventSink.router
    ?: error("direct event sink not bound")
  router(id, tag, args)
}

/**
 * Installs the [DIRECT_EVENT_SINK_NAME] function on globalThis once per guest session (guarded so
 * re-running the guest does not clobber an existing registration). Called from
 * [bindDirectEventSink], which every guest adapter runs in its constructor, so the host's presence
 * probe succeeds in JSON and bridge builds alike; dispatch routes through whatever router the live
 * adapter installed.
 */
internal fun installDirectEventSinkIfAbsent() {
  val entry: dynamic = ::directEventSinkEntry
  js(
    """
    if (typeof globalThis['${DIRECT_EVENT_SINK_NAME}'] === 'undefined') {
      globalThis['${DIRECT_EVENT_SINK_NAME}'] = entry
    }
    """,
  )
}

/** Binds the [DirectEventSink.router] to the adapter's widget lookup and mismatch handler. */
internal fun bindDirectEventSink(
  widgets: (id: Int) -> ProtocolWidget?,
  mismatchHandler: ProtocolMismatchHandler,
) {
  installDirectEventSinkIfAbsent()
  DirectEventSink.router = { id, tag, args ->
    val widget = widgets(id)
    if (widget != null && widget is DirectEventDispatcher) {
      widget.sendDirectEvent(EventTag(tag), args)
    } else {
      mismatchHandler.onUnknownEventNode(Id(id), EventTag(tag))
    }
  }
}
