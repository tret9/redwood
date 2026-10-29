/*
 * Copyright (C) 2024-2026 Square, Inc.
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

import app.cash.redwood.Modifier
import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.Change
import app.cash.redwood.protocol.ChangesSink
import app.cash.redwood.protocol.ChildrenChange
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Create
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.ModifierChange
import app.cash.redwood.protocol.ModifierElement
import app.cash.redwood.protocol.PropertyChange
import app.cash.redwood.protocol.PropertyTag
import app.cash.redwood.protocol.RedwoodVersion
import app.cash.redwood.protocol.WidgetTag
import app.cash.redwood.protocol.guest.GuestProtocolAdapter
import app.cash.redwood.protocol.guest.ProtocolMismatchHandler
import app.cash.redwood.protocol.guest.ProtocolWidget
import app.cash.redwood.protocol.guest.ProtocolWidget.Companion.INVALID_INDEX
import app.cash.redwood.protocol.guest.ProtocolWidgetChildren
import app.cash.redwood.protocol.guest.ProtocolWidgetSystemFactory
import app.cash.redwood.widget.WidgetSystem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal actual fun GuestProtocolAdapter(
  json: Json,
  hostVersion: RedwoodVersion,
  widgetSystemFactory: ProtocolWidgetSystemFactory,
  mismatchHandler: ProtocolMismatchHandler,
): GuestProtocolAdapter {
  return FastGuestProtocolAdapter(
    json = json,
    hostVersion = hostVersion,
    widgetSystemFactory = widgetSystemFactory,
    mismatchHandler = mismatchHandler,
  )
}

@OptIn(ExperimentalSerializationApi::class, RedwoodCodegenApi::class)
internal class FastGuestProtocolAdapter(
  override val json: Json = Json.Default,
  hostVersion: RedwoodVersion,
  private val widgetSystemFactory: ProtocolWidgetSystemFactory,
  private val mismatchHandler: ProtocolMismatchHandler = ProtocolMismatchHandler.Throwing,
  bindDirectEvents: Boolean = true,
) : GuestProtocolAdapter(hostVersion) {
  private var nextValue = Id.Root.value + 1
  private val widgets = JsMap<Int, ProtocolWidget>()
  private val removed = JsSet<Int>()
  private val changes = mutableListOf<Change>()
  private lateinit var changesSink: ChangesSink

  override val widgetSystem: WidgetSystem<Unit> =
    widgetSystemFactory.create(this, mismatchHandler)

  override val root: ProtocolWidgetChildren =
    ProtocolWidgetChildren(Id.Root, ChildrenTag.Root, this)

  override fun sendEvent(event: Event) {
    val node = widgets[event.id.value]
    if (node != null) {
      node.sendEvent(event)
    } else {
      mismatchHandler.onUnknownEventNode(event.id, event.tag)
    }
  }

  override fun initChangesSink(changesSink: ChangesSink) {
    this.changesSink = changesSink
  }

  override fun nextId(): Id {
    val value = nextValue
    nextValue = value + 1
    return Id(value)
  }

  override fun appendCreate(
    id: Id,
    tag: WidgetTag,
  ) {
    changes.add(Create(id, tag))
  }

  override fun appendBridgeChange(
    id: Id,
    wrapped: Any?,
  ) {
    val directBridgeObj: dynamic = js("globalThis.app_cash_redwood_directSendChanges")
    directBridgeObj.appendBridgeChange(id.value, wrapped)
  }

  override fun <T> appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    serializer: KSerializer<T>,
    value: T,
  ) {
    changes.add(PropertyChange(id, widgetTag, propertyTag, json.encodeToJsonElement(serializer, value)))
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: Boolean,
  ) {
    changes.add(PropertyChange(id, widgetTag, propertyTag, JsonPrimitive(value)))
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: UInt,
  ) {
    changes.add(PropertyChange(id, widgetTag, propertyTag, JsonPrimitive(value)))
  }

  override fun appendBridgedPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: Any?,
  ) {
    throw IllegalStateException(
      "appendBridgedPropertyChange requires the direct (bridge) guest adapter — " +
        "JSON property encoding is disabled in direct-only builds",
    )
  }

  override fun appendModifierChange(id: Id, value: Modifier) {
    val elements = mutableListOf<ModifierElement>()

    value.forEach { element ->
      elements += modifierElement(element)
    }

    changes.add(ModifierChange(id, elements))
  }

  private fun <T : Modifier.Element> modifierElement(element: T): ModifierElement {
    val (tag, serializer) = widgetSystemFactory.modifierTagAndSerializationStrategy(element)
    if (serializer == null) return ModifierElement(tag)
    return ModifierElement(tag, json.encodeToJsonElement(serializer, element))
  }

  override fun appendAdd(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    val childId = child.id.value
    if (child.removeIndex != INVALID_INDEX) {
      check(hostSupportsRemoveDetach) { "Host v$hostVersion does not support widget re-attach" }
      check(widgets.has(childId)) { "Attempted to re-attach unknown widget with ID $childId" }
      removed.delete(childId)

      // Update the remove change to indicate it's only a detach.
      val remove = changes[child.removeIndex] as ChildrenChange.Remove
      changes[child.removeIndex] = ChildrenChange.Remove(remove.id, remove.tag, remove.index, detach = true)
    } else {
      check(!widgets.has(childId)) { "Attempted to add widget with existing ID $childId" }
      widgets.set(childId, child)
    }

    changes.add(ChildrenChange.Add(id, tag, child.id, index))
  }

  override fun appendMove(
    id: Id,
    tag: ChildrenTag,
    fromIndex: Int,
    toIndex: Int,
    count: Int,
  ) {
    changes.add(ChildrenChange.Move(id, tag, fromIndex, toIndex, count))
  }

  override fun appendRemove(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    removed.add(child.id.value)
    child.removeIndex = changes.size
    changes.add(ChildrenChange.Remove(id, tag, index, detach = false))
  }

  override fun emitChanges() {
    if (changes.isNotEmpty()) {
      changesSink.sendChanges(takeChanges())
    }
  }

  /** Returns the changes accumulated since the last call to this function. */
  internal fun takeChanges(): List<Change> {
    val result = changes.toList()
    this.changes.clear()

    removed.forEach { id ->
      val widget = widgets[id]
        ?: throw IllegalStateException("Removed widget not present in map: $id")
      widgets.delete(id)
      widget.depthFirstWalk(childrenRemover)
    }
    removed.clear()

    return result
  }

  private val childrenRemover: ProtocolWidget.ChildrenVisitor =
    object : ProtocolWidget.ChildrenVisitor {
      override fun visit(
        parent: ProtocolWidget,
        childrenTag: ChildrenTag,
        children: ProtocolWidgetChildren,
      ) {
        for (childWidget in children.widgets) {
          widgets.delete(childWidget.id.value)
        }
      }
    }

  /**
   * Sends this composition's changes through [ZiplineTreehouseUi.Host.sendCompositionChanges].
   */
  internal fun initCompositionChangesSink(
    host: ZiplineTreehouseUi.Host,
    compositionId: String,
  ) {
    initChangesSink(
      ChangesSink { changes ->
        host.sendCompositionChanges(compositionId, changes)
      },
    )
  }

  init {
    if (bindDirectEvents) {
      bindDirectEventSink(widgets = { widgets[it] }, mismatchHandler = mismatchHandler)
    }
  }
}

internal actual fun compositionProtocolAdapter(
  appLifecycle: StandardAppLifecycle,
  host: ZiplineTreehouseUi.Host,
  compositionId: String,
): GuestProtocolAdapter {
  // Per-composition changes go through sendCompositionChanges, not the root sink.
  return FastGuestProtocolAdapter(
    json = appLifecycle.json,
    hostVersion = appLifecycle.hostProtocolVersion,
    widgetSystemFactory = appLifecycle.protocolWidgetSystemFactory,
    mismatchHandler = appLifecycle.mismatchHandler,
    bindDirectEvents = false,
  ).also { adapter ->
    adapter.initCompositionChangesSink(host, compositionId)
  }
}
