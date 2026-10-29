/*
 * Copyright (C) 2022 Square, Inc.
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
package app.cash.redwood.treehouse

import app.cash.redwood.protocol.Change
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.EventSink
import app.cash.redwood.ui.UiConfiguration
import app.cash.redwood.ui.core.api.FocusRequester
import app.cash.zipline.ZiplineService
import kotlin.native.ObjCName
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapt TreehouseComposition to conform the limitations of Zipline interfaces.
 *
 * Most callers shouldn't use this directly; instead use `TreehouseUi`.
 */
@ObjCName("ZiplineTreehouseUi", exact = true)
public interface ZiplineTreehouseUi :
  ZiplineService,
  EventSink {
  public fun start(host: Host)

  public fun snapshotState(): StateSnapshot? = null

  /**
   * Delivers an event to the composition identified by [compositionId].
   * The root composition continues to use [sendEvent].
   */
  public fun sendCompositionEvent(compositionId: String, event: Event) {
  }

  /** Stops one mounted composition without stopping this UI or the Zipline session. */
  public fun abortComposition(compositionId: String) {
  }

  /** Access to the host that presents this UI. */
  public interface Host :
    ZiplineService,
    ChangesSinkService {
    public val uiConfigurations: StateFlow<UiConfiguration>
    public val stateSnapshot: StateSnapshot?

    public fun addOnBackPressedCallback(onBackPressedCallbackService: OnBackPressedCallbackService): CancellableService
    public fun hideSoftwareKeyboard()
    public fun requestFocus(focusRequester: FocusRequester)

    /** Applies [changes] to the composition identified by [compositionId], not the root view. */
    public fun sendCompositionChanges(compositionId: String, changes: List<Change>) {
    }

    /** The composition failed on its own and was cancelled. The Zipline session keeps running. */
    public fun onCompositionFailed(compositionId: String, message: String) {
    }

    /** Drops the host widget tree for a composition that was unmounted. */
    public fun closeComposition(compositionId: String) {
    }
  }
}
