/*
 * Copyright (C) 2023 Square, Inc.
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

import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.ChangesSink
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.guest.DefaultGuestProtocolAdapter
import app.cash.redwood.protocol.guest.GuestProtocolAdapter
import app.cash.redwood.protocol.guest.ProtocolMismatchHandler
import app.cash.redwood.protocol.guest.ProtocolWidget
import app.cash.redwood.protocol.guest.guestRedwoodVersion
import app.cash.redwood.ui.core.api.FocusDirector
import app.cash.redwood.ui.core.api.FocusRequester
import app.cash.zipline.ZiplineException
import com.example.redwood.testapp.protocol.guest.TestSchemaProtocolWidgetSystemFactory
import com.example.redwood.testapp.widget.Button
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * This class pretends to be guest UI. We publish UI changes here with [addWidget], and the
 * Treehouse content propagates them to a bound [TreehouseView].
 */
class FakeZiplineTreehouseUi(
  private val name: String,
  private val json: Json,
  private val eventLog: EventLog,
) : ZiplineTreehouseUi,
  FocusDirector {
  private var messageToThrowOnNextEvent: String? = null

  private lateinit var host: ZiplineTreehouseUi.Host
  private val extraServicesToClose = mutableListOf<CancellableService>()

  private val guestProtocolAdapter: GuestProtocolAdapter = DefaultGuestProtocolAdapter(
    hostVersion = guestRedwoodVersion,
    json = json,
    widgetSystemFactory = TestSchemaProtocolWidgetSystemFactory,
  )

  private val widgetSystem = TestSchemaProtocolWidgetSystemFactory.create(
    guestAdapter = guestProtocolAdapter,
    mismatchHandler = ProtocolMismatchHandler.Throwing,
  )

  private var nextFocusRequesterId = 3000
  private var compositionEventException: ZiplineException? = null
  private val compositionAdapters = mutableMapOf<String, GuestProtocolAdapter>()

  override fun start(host: ZiplineTreehouseUi.Host) {
    eventLog += "$name.start()"
    this.host = host
    this.guestProtocolAdapter.initChangesSink(host)
  }

  @OptIn(RedwoodCodegenApi::class)
  fun addWidget(label: String): Button<Unit> {
    val button = widgetSystem.TestSchema.Button()
    val protocolButton = button as ProtocolWidget
    button.text(label)
    button.onClick {}
    button.color(0u)
    guestProtocolAdapter.appendAdd(Id.Root, ChildrenTag.Root, 0, protocolButton)
    guestProtocolAdapter.emitChanges()
    return button
  }

  fun emitChanges() {
    guestProtocolAdapter.emitChanges()
  }

  @OptIn(RedwoodCodegenApi::class)
  fun addCompositionWidget(compositionId: String, label: String): Button<Unit> {
    val adapter = compositionAdapter(compositionId)
    val widgetSystem = TestSchemaProtocolWidgetSystemFactory.create(
      guestAdapter = adapter,
      mismatchHandler = ProtocolMismatchHandler.Throwing,
    )
    val button = widgetSystem.TestSchema.Button()
    val protocolButton = button as ProtocolWidget
    button.text(label)
    button.onClick {}
    button.color(0u)
    adapter.appendAdd(Id.Root, ChildrenTag.Root, 0, protocolButton)
    adapter.emitChanges()
    return button
  }

  fun emitCompositionChanges(compositionId: String) {
    compositionAdapters.getValue(compositionId).emitChanges()
  }

  fun throwZiplineExceptionOnNextCompositionEvent(message: String) {
    compositionEventException = ZiplineException(message)
  }

  private fun compositionAdapter(compositionId: String): GuestProtocolAdapter {
    return compositionAdapters.getOrPut(compositionId) {
      DefaultGuestProtocolAdapter(
        hostVersion = guestRedwoodVersion,
        json = json,
        widgetSystemFactory = TestSchemaProtocolWidgetSystemFactory,
      ).also { adapter ->
        adapter.initChangesSink(
          ChangesSink { changes ->
            host.sendCompositionChanges(compositionId, changes)
          },
        )
      }
    }
  }

  override fun sendCompositionEvent(compositionId: String, event: Event) {
    eventLog += "$name.sendCompositionEvent($compositionId)"
    val toThrow = compositionEventException
    if (toThrow != null) {
      compositionEventException = null
      throw toThrow
    }
  }

  override fun abortComposition(compositionId: String) {
    eventLog += "$name.abortComposition($compositionId)"
  }

  fun throwOnNextEvent(message: String) {
    messageToThrowOnNextEvent = message
  }

  override fun sendEvent(event: Event) {
    eventLog += "$name.sendEvent()"

    val toThrow = messageToThrowOnNextEvent
    if (toThrow != null) {
      messageToThrowOnNextEvent = null
      throw Exception(toThrow)
    }
  }

  fun addBackHandler(isEnabled: Boolean): CancellableService {
    val result = host.addOnBackPressedCallback(object : OnBackPressedCallbackService {
      override var isEnabled = MutableStateFlow(isEnabled)

      override fun handleOnBackPressed() {
        eventLog += "$name.onBackPressed()"
      }
    })

    // Keep track of services produced by this ZiplineTreehouseUi so we can close them when this
    // service is itself closed. In production code the ZiplineScope does this automatically.
    extraServicesToClose += result

    return result
  }

  override fun hideSoftwareKeyboard() {
    // TODO: complete this.
  }

  override fun newFocusRequester(): FocusRequester = FakeFocusRequester()

  override fun close() {
    for (cancellableService in extraServicesToClose) {
      cancellableService.close()
    }
    eventLog += "$name.close()"
  }

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // For SerializableFocusRequester.
  private inner class FakeFocusRequester : app.cash.redwood.ui.core.api.SerializableFocusRequester {
    override val id = nextFocusRequesterId++

    override fun requestFocus() {
      host.requestFocus(reserialized())
    }

    /** Simulate the real host call, that serializes and deserializes this FocusRequester. */
    private fun reserialized(): FocusRequester {
      val serialized = json.encodeToJsonElement<FocusRequester>(this)
      return json.decodeFromJsonElement<FocusRequester>(serialized)
    }
  }
}
