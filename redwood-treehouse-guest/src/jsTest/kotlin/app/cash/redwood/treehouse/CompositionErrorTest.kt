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
package app.cash.redwood.treehouse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.redwood.protocol.Change
import app.cash.redwood.protocol.EventTag
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.WidgetTag
import app.cash.redwood.protocol.guest.guestRedwoodVersion
import app.cash.redwood.ui.UiConfiguration
import app.cash.redwood.ui.core.api.FocusRequester
import com.example.redwood.testapp.protocol.guest.TestSchemaProtocolWidgetSystemFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json

/**
 * An [Error] in one composition must reach the lifecycle uncaught handler.
 * Reporting it through [ZiplineTreehouseUi.Host.onCompositionFailed] would swallow it.
 */
class CompositionErrorTest {
  @Test fun errorInCompositionIsNotACompositionFailure() = CoroutineScope(Dispatchers.Default).promise {
    js(
      """
      if (typeof globalThis.__bridgeRegister !== 'function') {
        globalThis.__bridgeRegister = function () {}
      }
      """,
    )
    val session = ErrorSession()
    try {
      session.open()
      var tick by mutableIntStateOf(0)
      var seen = -1
      session.mount("sibling") { seen = tick }
      session.advanceFramesUntil { seen == 0 }

      session.mount("crash") {
        LaunchedEffect(Unit) { throw Error("oom-isolated") }
      }
      session.advanceFramesUntil { session.uncaught.isNotEmpty() || session.failures.isNotEmpty() }

      assertEquals(emptyList(), session.failures)
      assertTrue(session.uncaught.any { it is Error && it.message?.contains("oom-isolated") == true })
      tick = 1
      Snapshot.sendApplyNotifications()
      session.advanceFramesUntil { seen == 1 }
    } finally {
      session.close()
    }
  }
}

private class ErrorSession {
  val failures = mutableListOf<Pair<String, String>>()
  val uncaught = mutableListOf<Throwable>()
  private var frameNanos = 0L
  private var ui: MountableZiplineTreehouseUi? = null

  private val lifecycleHost = object : AppLifecycle.Host {
    override val hostProtocolVersion get() = guestRedwoodVersion
    override fun requestFrame() = Unit
    override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
    override fun onUnknownEventNode(id: Id, tag: EventTag) = Unit
    override fun handleUncaughtException(exception: Throwable) {
      uncaught += exception
    }
  }

  private val ziplineHost = object : ZiplineTreehouseUi.Host {
    override val uiConfigurations = MutableStateFlow(UiConfiguration())
    override val stateSnapshot = null
    override fun sendChanges(changes: List<Change>) = Unit
    override fun sendCompositionChanges(compositionId: String, changes: List<Change>) = Unit
    override fun onCompositionFailed(compositionId: String, message: String) {
      failures += compositionId to message
    }
    override fun closeComposition(compositionId: String) = Unit
    override fun addOnBackPressedCallback(
      onBackPressedCallbackService: OnBackPressedCallbackService,
    ): CancellableService = object : CancellableService {
      override fun cancel() = Unit
    }
    override fun hideSoftwareKeyboard() = Unit
    override fun requestFocus(focusRequester: FocusRequester) = Unit
  }

  private val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = TestSchemaProtocolWidgetSystemFactory,
    json = Json.Default,
    widgetVersion = 0U,
  )

  fun open() {
    appLifecycle.start(lifecycleHost)
    val mounted = object : TreehouseUi {
      @Composable
      override fun Show() = Unit
    }.asMountableZiplineTreehouseUi(appLifecycle)
    ui = mounted
    mounted.start(ziplineHost)
  }

  fun close() {
    runCatching { ui?.close() }
  }

  fun mount(id: String, content: @Composable () -> Unit) {
    ui!!.mount(id, content)
  }

  suspend fun advanceFramesUntil(condition: () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
      check(start.elapsedNow() < 3.seconds) {
        "timed out; failures=$failures uncaught=${uncaught.map { it::class.simpleName }}"
      }
      Snapshot.sendApplyNotifications()
      appLifecycle.sendFrame(frameNanos++)
      yield()
      delay(1)
    }
  }
}
