package app.cash.redwood.treehouse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.redwood.protocol.Change
import app.cash.redwood.protocol.ChildrenChange
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Create
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.EventTag
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.ModifierChange
import app.cash.redwood.protocol.PropertyChange
import app.cash.redwood.protocol.PropertyTag
import app.cash.redwood.protocol.WidgetTag
import app.cash.redwood.protocol.guest.guestRedwoodVersion
import app.cash.redwood.ui.UiConfiguration
import app.cash.redwood.ui.core.api.FocusRequester
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.example.redwood.testapp.compose.Button
import com.example.redwood.testapp.protocol.guest.TestSchemaProtocolWidgetSystemFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Two compositions share one [ZiplineTreehouseUi] and the same widget ids.
 * Changes and events must stay on the composition they belong to.
 */
internal suspend fun assertChangesAndEventsStayOnTheirComposition() {
  val session = DeliverySession()
  try {
    session.open()
    var leftText by mutableStateOf("left")
    var rightText by mutableStateOf("right")
    var leftClicks = 0
    var rightClicks = 0
    session.mount("left") {
      Button(
        leftText,
        onClick = {
          leftClicks += 1
          leftText = "left-$leftClicks"
        },
      )
    }
    session.mount("right") {
      Button(
        rightText,
        onClick = {
          rightClicks += 1
          rightText = "right-$rightClicks"
        },
      )
    }

    assertThat(session.changes("left")).isEqualTo(buttonChanges("left"))
    assertThat(session.changes("right")).isEqualTo(buttonChanges("right"))
    assertThat(session.rootChanges).isEmpty()

    session.clearChanges()
    session.sendEvent("left", Event(Id(1), EventTag(2)))
    assertThat(leftClicks).isEqualTo(1)
    assertThat(rightClicks).isEqualTo(0)
    session.advanceUntil { session.changes("left") == listOf(textChange("left-1")) }
    assertThat(session.changes("right")).isEmpty()

    session.clearChanges()
    session.sendEvent("right", Event(Id(1), EventTag(2)))
    assertThat(leftClicks).isEqualTo(1)
    assertThat(rightClicks).isEqualTo(1)
    session.advanceUntil { session.changes("right") == listOf(textChange("right-1")) }
    assertThat(session.changes("left")).isEmpty()

    session.clearChanges()
    session.sendEvent("missing", Event(Id(1), EventTag(2)))
    repeat(5) { session.sendFrame() }
    assertThat(leftClicks).isEqualTo(1)
    assertThat(rightClicks).isEqualTo(1)
    assertThat(session.changes("left")).isEmpty()
    assertThat(session.changes("right")).isEmpty()
    assertThat(session.failures).isEmpty()
  } finally {
    session.close()
  }
}

@OptIn(ExperimentalSerializationApi::class)
private fun buttonChanges(text: String): List<Change> {
  return listOf(
    Create(Id(1), WidgetTag(4)),
    PropertyChange(Id(1), WidgetTag(4), PropertyTag(1), JsonPrimitive(text)),
    PropertyChange(Id(1), WidgetTag(4), PropertyTag(2), JsonPrimitive(true)),
    PropertyChange(Id(1), WidgetTag(4), PropertyTag(3), JsonPrimitive(0u)),
    ModifierChange(Id(1)),
    ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(1), 0),
  )
}

private fun textChange(text: String): PropertyChange {
  return PropertyChange(Id(1), WidgetTag(4), PropertyTag(1), JsonPrimitive(text))
}

private class DeliverySession {
  val rootChanges = mutableListOf<Change>()
  val failures = mutableListOf<Pair<String, String>>()
  private val compositionChanges = mutableMapOf<String, MutableList<Change>>()
  private var frameNanos = 0L
  private var ui: MountableZiplineTreehouseUi? = null

  fun changes(id: String): List<Change> = compositionChanges[id].orEmpty()

  fun clearChanges() {
    rootChanges.clear()
    compositionChanges.clear()
  }

  private val lifecycleHost = object : AppLifecycle.Host {
    override val hostProtocolVersion get() = guestRedwoodVersion
    override fun requestFrame() = Unit
    override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
    override fun onUnknownEventNode(id: Id, tag: EventTag) = Unit
    override fun handleUncaughtException(exception: Throwable) = Unit
  }

  private val ziplineHost = object : ZiplineTreehouseUi.Host {
    override val uiConfigurations = MutableStateFlow(UiConfiguration())
    override val stateSnapshot = null
    override fun sendChanges(changes: List<Change>) {
      rootChanges += changes
    }
    override fun sendCompositionChanges(compositionId: String, changes: List<Change>) {
      compositionChanges.getOrPut(compositionId) { mutableListOf() } += changes
    }
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

  fun sendEvent(compositionId: String, event: Event) {
    ui!!.sendCompositionEvent(compositionId, event)
  }

  suspend fun advanceUntil(condition: () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
      check(start.elapsedNow() < 3.seconds) {
        "timed out; left=${changes("left")} right=${changes("right")} failures=$failures"
      }
      sendFrame()
    }
  }

  suspend fun sendFrame() {
    Snapshot.sendApplyNotifications()
    appLifecycle.sendFrame(frameNanos++)
    yield()
    delay(1)
  }
}
