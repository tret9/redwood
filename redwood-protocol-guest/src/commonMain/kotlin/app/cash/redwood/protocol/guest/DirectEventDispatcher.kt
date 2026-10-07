/*
 * Copyright (C) 2026 Square, Inc.
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
