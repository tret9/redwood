package app.cash.redwood.treehouse

import app.cash.redwood.Modifier
import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.ChangesSink
import app.cash.redwood.protocol.ChildrenChange
import app.cash.redwood.protocol.ChildrenChange.Add.Companion.invoke
import app.cash.redwood.protocol.ChildrenChange.Move.Companion.invoke
import app.cash.redwood.protocol.ChildrenChange.Remove
import app.cash.redwood.protocol.ChildrenChange.Remove.Companion.invoke
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.PropertyTag
import app.cash.redwood.protocol.RedwoodVersion
import app.cash.redwood.protocol.WidgetTag
import app.cash.redwood.protocol.guest.GuestProtocolAdapter
import app.cash.redwood.protocol.guest.ProtocolMismatchHandler
import app.cash.redwood.protocol.guest.ProtocolWidget
import app.cash.redwood.protocol.guest.ProtocolWidget.Companion.INVALID_INDEX
import app.cash.redwood.protocol.guest.ProtocolWidgetChildren
import app.cash.redwood.protocol.guest.ProtocolWidgetSystemFactory
import app.cash.redwood.protocol.host.UiChange
import app.cash.redwood.protocol.host.UiChildrenChange
import app.cash.redwood.protocol.host.UiCreate
import app.cash.redwood.protocol.host.UiModifierChange
import app.cash.redwood.protocol.host.UiPropertyChange
import app.cash.redwood.widget.WidgetSystem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal actual fun BridgeGuestProtocolAdapter(
    json: Json,
    hostVersion: RedwoodVersion,
    widgetSystemFactory: ProtocolWidgetSystemFactory,
    mismatchHandler: ProtocolMismatchHandler
): GuestProtocolAdapter = BridgeGuestProtocolAdapterImpl(json, hostVersion, widgetSystemFactory, mismatchHandler)

/**
 * A [GuestProtocolAdapter] that constructs bridge-compatible JS [UiChange] objects on the JS side
 * and sends them via [app.cash.redwood.protocol.BridgeChange] through the RDMA channel.
 *
 * When the RDMA global has [appendBridgeChange] available, this adapter constructs JS objects
 * matching the host-side [UiChange] classes (whose JS prototypes carry [bridge_dispatch] from
 * [bridge_init_all]) and sends them via [appendBridgeChange]. The C++ host uses
 * [JniBridgeDispatch] to convert them to JVM [UiChange] objects, wrapping in
 * [app.cash.redwood.protocol.BridgeChange] for zero-deserialization delivery.
 *
 * If [appendBridgeChange] is unavailable, this adapter should not be used;
 * [FastGuestProtocolAdapter] (scalar RDMA) is the fallback.
 */
@OptIn(RedwoodCodegenApi::class, ExperimentalSerializationApi::class)
internal class BridgeGuestProtocolAdapterImpl(
  override val json: Json = Json.Default,
  hostVersion: RedwoodVersion,
  private val widgetSystemFactory: ProtocolWidgetSystemFactory,
  private val mismatchHandler: ProtocolMismatchHandler = ProtocolMismatchHandler.Throwing,
) : GuestProtocolAdapter(hostVersion) {
  private var nextValue = Id.Root.value + 1
  private val widgets = JsMap<Int, ProtocolWidget>()
  private val removed = JsSet<Int>()
  private var pinnedObjects: dynamic = js("[]")
  /** Tracks [ChildrenChange.Remove] JS objects by index for re-attach ([setRemoveDetach]). */
  private val pendingRemoveObjects = mutableListOf<dynamic>()

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
  }

  override fun nextId(): Id {
    val value = nextValue
    nextValue = value + 1
    return Id(value)
  }

  // -- Bridge path: constructs UiCreate JS object and sends via appendBridgeChange --

  override fun appendCreate(
    id: Id,
    tag: WidgetTag,
  ) {
    val change = UiCreate(id, tag)
    pinnedObjects.push(change)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, change)
  }

  override fun appendBridgeChange(
    id: Id,
    wrapped: Any?,
  ) {
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, wrapped)
  }

  // -- Bridge path for property changes --

  override fun <T> appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    serializer: KSerializer<T>,
    value: T,
  ) {
    val change = UiPropertyChange(id, propertyTag, value)
    pinnedObjects.push(change)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, change)
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: Boolean,
  ) {
    val change = UiPropertyChange(id, propertyTag, value)
    pinnedObjects.push(change)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, change)
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: UInt,
  ) {
    val change = UiPropertyChange(id, propertyTag, value)
    pinnedObjects.push(change)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, change)
  }

  override fun appendModifierChange(id: Id, value: Modifier) {
    val uiChange = UiModifierChange(id, reuse = false, value)
    pinnedObjects.push(uiChange)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, uiChange)
  }

  override fun appendAdd(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    val childId = child.id
    val knownId = widgets.has(childId.value)
    if (child.removeIndex != INVALID_INDEX) {
      check(hostSupportsRemoveDetach) { "Host v$hostVersion does not support widget re-attach" }
      check(knownId) { "Attempted to re-attach unknown widget with ID $childId" }
      removed.delete(childId.value)
      // Mark the pending Remove as detach so the host retains the widget subtree.
      pendingRemoveObjects[child.removeIndex].detach = true
    } else {
      check(!knownId) { "Attempted to add widget with existing ID $childId" }
      widgets.set(childId.value, child)
    }
    val protocolChange = ChildrenChange.Add(id, tag, childId, index)
    val uiChange = UiChildrenChange(protocolChange)
    pinnedObjects.push(uiChange)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, uiChange)
  }

  override fun appendMove(
    id: Id,
    tag: ChildrenTag,
    fromIndex: Int,
    toIndex: Int,
    count: Int,
  ) {
    val protocolChange = ChildrenChange.Move(id, tag, fromIndex, toIndex, count)
    val uiChange = UiChildrenChange(protocolChange)
    pinnedObjects.push(uiChange)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, uiChange)
  }

  override fun appendRemove(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    removed.add(child.id.value)
    val protocolChange = ChildrenChange.Remove(id, tag, index, detach = false)
    pinnedObjects.push(protocolChange)
    child.removeIndex = pendingRemoveObjects.size
    pendingRemoveObjects.add(protocolChange)
    val uiChange = UiChildrenChange(protocolChange)
    pinnedObjects.push(uiChange)
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    rdmaObj.appendBridgeChange(id.value, uiChange)
  }

  override fun emitChanges() {
    val rdmaObj: dynamic = js("globalThis.app_cash_redwood_rdmaSendChanges")
    if (rdmaObj == undefined) {
      throw AssertionError("RDMA changes channel not registered")
    }

    removed.forEach { id ->
      val widget = widgets[id]
        ?: throw IllegalStateException("Removed widget not present in map: $id")
      widgets.delete(id)
      widget.depthFirstWalk(childrenRemover)
    }
    removed.clear()

    rdmaObj.finishChanges()
    pendingRemoveObjects.clear()
    pinnedObjects = js("[]")
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
}
