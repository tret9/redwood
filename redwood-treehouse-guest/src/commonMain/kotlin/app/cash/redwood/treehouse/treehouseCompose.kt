/*
 * Copyright (C) 2022-2026 Square, Inc.
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
import androidx.compose.runtime.saveable.SaveableStateRegistry
import app.cash.redwood.compose.RedwoodComposition
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.EventSink
import app.cash.redwood.protocol.guest.GuestProtocolAdapter
import app.cash.redwood.protocol.guest.ProtocolRedwoodComposition
import app.cash.redwood.ui.Cancellable
import app.cash.redwood.ui.Dp
import app.cash.redwood.ui.OnBackPressedCallback
import app.cash.redwood.ui.OnBackPressedDispatcher
import app.cash.redwood.ui.Size
import app.cash.redwood.ui.UiConfiguration
import app.cash.redwood.ui.core.api.FocusDirector
import app.cash.zipline.ZiplineScope
import app.cash.zipline.ZiplineScoped
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The Kotlin/JS side of a treehouse UI.
 */
public fun TreehouseUi.asZiplineTreehouseUi(
  appLifecycle: StandardAppLifecycle,
  rdmaEnabled: Boolean = false,
): ZiplineTreehouseUi = asMountableZiplineTreehouseUi(appLifecycle, rdmaEnabled)

/**
 * One Zipline UI that can run extra compositions beside [TreehouseUi.Show].
 * Each mounted composition has its own protocol adapter and recomposer.
 * A failure cancels that composition and reports it to the host. It does not
 * call [app.cash.redwood.treehouse.AppLifecycle.Host.handleUncaughtException].
 */
public fun TreehouseUi.asMountableZiplineTreehouseUi(
  appLifecycle: StandardAppLifecycle,
  rdmaEnabled: Boolean = false,
): MountableZiplineTreehouseUi {
  val guestAdapter = if (rdmaEnabled) {
    bridgeGuestProtocolAdapter(
      hostVersion = appLifecycle.hostProtocolVersion,
      json = appLifecycle.json,
      widgetSystemFactory = appLifecycle.protocolWidgetSystemFactory,
      mismatchHandler = appLifecycle.mismatchHandler,
    )
  } else {
    GuestProtocolAdapter(
      hostVersion = appLifecycle.hostProtocolVersion,
      json = appLifecycle.json,
      widgetSystemFactory = appLifecycle.protocolWidgetSystemFactory,
      mismatchHandler = appLifecycle.mismatchHandler,
    )
  }
  return RedwoodZiplineTreehouseUi(appLifecycle, this, guestAdapter)
}

/** Guest-side handle for compositions added to one [ZiplineTreehouseUi]. */
public interface MountableZiplineTreehouseUi : ZiplineTreehouseUi {
  public fun mount(id: String, content: @Composable () -> Unit)
  public fun updateViewport(id: String, width: Double, height: Double)
  public fun unmount(id: String)
}

private class RedwoodZiplineTreehouseUi(
  private val appLifecycle: StandardAppLifecycle,
  private val treehouseUi: TreehouseUi,
  private val guestAdapter: GuestProtocolAdapter,
) : MountableZiplineTreehouseUi,
  ZiplineScoped,
  EventSink by guestAdapter,
  StandardAppLifecycle.FrameListener {

  private val mounted = LinkedHashMap<String, MountedComposition>()
  private var hostOrNull: ZiplineTreehouseUi.Host? = null
  private var onBackPressedDispatcher: OnBackPressedDispatcher? = null
  private var focusDirector: FocusDirector? = null

  override fun onFrame(timeNanos: Long) {
    // Root changes have no composition to fail. A throw here still stops the session.
    guestAdapter.emitChanges()
    for ((id, composition) in mounted.entries.toList()) {
      try {
        composition.adapter.emitChanges()
      } catch (throwable: CancellationException) {
        throw throwable
      } catch (throwable: Exception) {
        fail(id, throwable)
      }
    }
  }

  /**
   * By overriding [ZiplineScoped.scope], all services passed into [start] are added to this scope,
   * and will all be closed when the scope is closed. This is the only mechanism that can close the
   * host configurations flow.
   */
  override val scope = (treehouseUi as? ZiplineScoped)?.scope ?: ZiplineScope()

  private val coroutineScope = CoroutineScope(
    appLifecycle.coroutineScope.coroutineContext +
      Job(appLifecycle.coroutineScope.coroutineContext.job),
  )

  private lateinit var composition: RedwoodComposition

  private lateinit var saveableStateRegistry: SaveableStateRegistry

  override fun start(host: ZiplineTreehouseUi.Host) {
    hostOrNull = host
    onBackPressedDispatcher = host.asOnBackPressedDispatcher()
    focusDirector = GuestFocusDirector(host)
    this.saveableStateRegistry = SaveableStateRegistry(
      restoredValues = host.stateSnapshot?.content,
      // Note: values will only be restored by SaveableStateRegistry if `canBeSaved` returns true.
      // With current serialization mechanism of stateSnapshot, this field is always true, an update
      // to lambda of this field might be needed when serialization mechanism of stateSnapshot
      // is changed.
      canBeSaved = { true },
    )

    guestAdapter.initChangesSink(host)

    appLifecycle.addFrameListener(this)

    val composition = ProtocolRedwoodComposition(
      scope = coroutineScope + appLifecycle.frameClock,
      guestAdapter = guestAdapter,
      widgetVersion = appLifecycle.widgetVersion,
      onBackPressedDispatcher = onBackPressedDispatcher!!,
      focusDirector = focusDirector!!,
      saveableStateRegistry = saveableStateRegistry,
      uiConfigurations = host.uiConfigurations,
    )
    this.composition = composition

    composition.bind(treehouseUi)

    // Explicitly emit the initial changes produced by calling 'setContent' (within 'bind').
    // All other changes are initiated and emitted by the [onFrame] callback.
    guestAdapter.emitChanges()
  }

  override fun mount(id: String, content: @Composable () -> Unit) {
    val host = hostOrNull ?: error("mount before start")
    if (id in mounted) unmount(id)

    val adapter = compositionProtocolAdapter(
      appLifecycle = appLifecycle,
      host = host,
      compositionId = id,
    )

    val viewport = MutableStateFlow<Size?>(null)
    val hostConfiguration = MutableStateFlow<UiConfiguration?>(null)
    val uiConfiguration = MutableStateFlow(UiConfiguration())
    val job = SupervisorJob(coroutineScope.coroutineContext.job)
    val handler = CoroutineExceptionHandler { _, throwable ->
      if (throwable is CancellationException) return@CoroutineExceptionHandler
      // Error (OOM and the like) must reach the platform handler. fail() would
      // turn it into a composition failure and swallow it.
      if (throwable !is Exception) throw throwable
      fail(id, throwable)
    }
    // Inherit the Zipline dispatcher. A fresh scope with only a frame clock
    // would run the recomposer on Dispatchers.Default.
    val scope = CoroutineScope(
      coroutineScope.coroutineContext + job + handler + appLifecycle.frameClock,
    )
    val composition = ProtocolRedwoodComposition(
      scope = scope,
      guestAdapter = adapter,
      widgetVersion = appLifecycle.widgetVersion,
      onBackPressedDispatcher = onBackPressedDispatcher!!,
      focusDirector = focusDirector!!,
      saveableStateRegistry = SaveableStateRegistry(
        restoredValues = null,
        canBeSaved = { true },
      ),
      uiConfigurations = uiConfiguration,
    )
    val mountedComposition = MountedComposition(
      adapter,
      composition,
      scope,
      viewport,
      hostConfiguration,
      uiConfiguration,
    )
    mounted[id] = mountedComposition
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      host.uiConfigurations.collect { incoming ->
        hostConfiguration.value = incoming
        uiConfiguration.value = incoming.withViewport(viewport.value)
      }
    }
    try {
      composition.setContent(content)
      adapter.emitChanges()
    } catch (throwable: Exception) {
      if (throwable is CancellationException) throw throwable
      fail(id, throwable)
    }
  }

  override fun updateViewport(id: String, width: Double, height: Double) {
    val composition = mounted[id] ?: return
    val size = Size(Dp(width), Dp(height))
    composition.viewport.value = size
    val incoming = composition.hostConfiguration.value ?: return
    composition.uiConfiguration.value = incoming.withViewport(size)
  }

  override fun unmount(id: String) {
    cancelMounted(id) ?: return
    hostOrNull?.closeComposition(id)
  }

  override fun sendCompositionEvent(compositionId: String, event: Event) {
    try {
      mounted[compositionId]?.adapter?.sendEvent(event)
    } catch (throwable: CancellationException) {
      throw throwable
    } catch (throwable: Exception) {
      // A click lambda throws on this call. Across Zipline that would surface as
      // ZiplineException and the host would abort the composition; in-process the
      // exception is still an Exception, so fail it here.
      fail(compositionId, throwable)
    }
  }

  override fun abortComposition(compositionId: String) {
    // Caller reports the original exception. Notifying here would publish
    // "composition aborted" first, and the host keeps the first failure.
    cancelMounted(compositionId)
  }

  private fun cancelMounted(id: String): MountedComposition? {
    val composition = mounted.remove(id) ?: return null
    composition.composition.cancel()
    composition.scope.cancel()
    return composition
  }

  private fun fail(id: String, throwable: Throwable) {
    cancelMounted(id) ?: return
    hostOrNull?.onCompositionFailed(id, throwable.stackTraceToString())
  }

  override fun snapshotState(): StateSnapshot {
    val savedState = saveableStateRegistry.performSave()
    return StateSnapshot(savedState)
  }

  override fun close() {
    for (id in mounted.keys.toList()) {
      unmount(id)
    }
    appLifecycle.removeFrameListener(this)
    coroutineScope.coroutineContext.job.invokeOnCompletion {
      scope.close()
    }
    coroutineScope.cancel()
    composition.cancel()
    treehouseUi.close()
  }
}

private class MountedComposition(
  val adapter: GuestProtocolAdapter,
  val composition: RedwoodComposition,
  val scope: CoroutineScope,
  val viewport: MutableStateFlow<Size?>,
  val hostConfiguration: MutableStateFlow<UiConfiguration?>,
  val uiConfiguration: MutableStateFlow<UiConfiguration>,
)

private fun UiConfiguration.withViewport(viewport: Size?): UiConfiguration {
  return if (viewport == null) this else copy(viewportSize = viewport)
}

private fun ZiplineTreehouseUi.Host.asOnBackPressedDispatcher() = object : OnBackPressedDispatcher {
  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
    return this@asOnBackPressedDispatcher.addOnBackPressedCallback(
      onBackPressedCallback.asService(),
    )
  }
}

private fun OnBackPressedCallback.asService() = object : OnBackPressedCallbackService {
  override val isEnabled = MutableStateFlow(this@asService.isEnabled)

  init {
    enabledChangedCallback = {
      isEnabled.value = this@asService.isEnabled
    }
  }

  override fun handleOnBackPressed() {
    this@asService.handleOnBackPressed()
  }

  override fun close() {
    enabledChangedCallback = null
  }
}
