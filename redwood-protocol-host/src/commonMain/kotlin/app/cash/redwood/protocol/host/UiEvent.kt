/*
 * Copyright (C) 2024 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.redwood.protocol.host

import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.EventSink
import app.cash.redwood.protocol.EventTag
import app.cash.redwood.protocol.Id
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * A version of [Event] whose arguments have not yet been serialized to JSON and is thus
 * cheap to create on the UI thread.
 */
public interface UiEvent {
  /** Serialize this UI event into its protocol representation. */
  public fun toProtocol(): Event
}

/** A version of [EventSink] which consumes [UiEvent]s. */
public fun interface UiEventSink {
  public fun sendEvent(uiEvent: UiEvent)
}

/**
 * A [UiEvent] whose typed arguments are available as raw host objects and can therefore be
 * transported to the guest without JSON encoding (via the direct event sink). Implemented by
 * [GeneratedUiEvent], which always carries its [id], [tag], and raw [args] on hand.
 *
 * @suppress For generated code use only.
 */
@RedwoodCodegenApi
public interface DirectTransportUiEvent : UiEvent {
  public val id: Id
  public val tag: EventTag
  /** Raw typed args (null when the event has none). */
  public val args: Array<Any?>?
}

/** @suppress For generated code use only. */
@RedwoodCodegenApi
public class GeneratedUiEvent(
  override val id: Id,
  override val tag: EventTag,
  private val json: Json?,
  override val args: Array<Any?>?,
  private val serializationStrategies: Array<out SerializationStrategy<Any?>>?,
) : UiEvent,
  DirectTransportUiEvent {
  override fun toProtocol(): Event {
    return Event(
      id = id,
      tag = tag,
      args = if (args == null) {
        emptyList()
      } else {
        val json = json!!
        val serializationStrategies = serializationStrategies!!
        List(args.size) { i ->
          json.encodeToJsonElement(serializationStrategies[i], args[i])
        }
      },
    )
  }
}
