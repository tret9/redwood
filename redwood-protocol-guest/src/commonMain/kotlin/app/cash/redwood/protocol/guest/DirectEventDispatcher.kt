package app.cash.redwood.protocol.guest

import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.EventTag

/**
 * A [ProtocolWidget] that can receive events whose arguments arrive as real JS objects (built
 * host-side by the @WithHost2JSBridge machinery) instead of JSON-encoded [Event] payloads.
 *
 * @suppress For generated code use only.
 */
@RedwoodCodegenApi
public interface DirectEventDispatcher {
  /** Dispatch a JSON-free event: [tag] plus already-converted raw JS-visible args. */
  public fun sendDirectEvent(tag: EventTag, args: Array<Any?>)
}
