package app.cash.redwood.treehouse

import app.cash.redwood.Modifier
import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.BridgeChange
import app.cash.redwood.protocol.ChangesSink
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
import app.cash.redwood.widget.WidgetSystem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToDynamic

internal actual fun RdmaGuestProtocolAdapter(
  json: Json,
  hostVersion: RedwoodVersion,
  widgetSystemFactory: ProtocolWidgetSystemFactory,
  mismatchHandler: ProtocolMismatchHandler,
): GuestProtocolAdapter = RdmaGuestProtocolAdapterImpl(
  json = json,
  hostVersion = hostVersion,
  widgetSystemFactory = widgetSystemFactory,
  mismatchHandler = mismatchHandler,
)

@OptIn(ExperimentalSerializationApi::class, RedwoodCodegenApi::class)
internal class RdmaGuestProtocolAdapterImpl(
  override val json: Json = Json.Default,
  hostVersion: RedwoodVersion,
  private val widgetSystemFactory: ProtocolWidgetSystemFactory,
  private val mismatchHandler: ProtocolMismatchHandler = ProtocolMismatchHandler.Throwing,
) : GuestProtocolAdapter(hostVersion) {
  private var nextValue = Id.Root.value + 1
  private val widgets = JsMap<Int, ProtocolWidget>()
  private val removed = JsSet<Int>()

  // To prevent early free because items can only be referenced from native
  private var pinnedObjects: dynamic = js("[]")

  private val rdmaObj: dynamic
    get() = js("globalThis.app_cash_redwood_rdmaSendChanges")

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

  override fun initChangesSink(changesSink: ChangesSink) {}

  override fun nextId(): Id {
    val value = nextValue
    nextValue = value + 1
    return Id(value)
  }

  override fun appendCreate(
    id: Id,
    tag: WidgetTag,
  ) {
    val id = id
    val tag = tag
    rdmaObj.appendCreate(id.value, tag.value)
  }

  override fun <T> appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    serializer: KSerializer<T>,
    value: T,
  ) {
    val id = id
    val widget = widgetTag
    val tag = propertyTag
    val encodedValue = value?.let { json.encodeToDynamic(serializer, it) }
    pinnedObjects.push(encodedValue)
    rdmaObj.appendPropertyChange(id.value, widget.value, tag.value, encodedValue)
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: Boolean,
  ) {
    val id = id
    val widget = widgetTag
    val tag = propertyTag
    val value = value
    rdmaObj.appendPropertyChange(id.value, widget.value, tag.value, value)
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: UInt,
  ) {
    val id = id
    val widget = widgetTag
    val tag = propertyTag
    val value = value.toDouble()
    rdmaObj.appendPropertyChange(id.value, widget.value, tag.value, value)
  }

  override fun appendModifierChange(id: Id, value: Modifier) {
    val elements = js("[]")

    value.forEach { element ->
      val (tag, serializer) = widgetSystemFactory.modifierTagAndSerializationStrategy(element)
      when {
        serializer != null -> {
          val value = json.encodeToDynamic(serializer, element)
          elements.push(js("""[tag,value]"""))
        }
        else -> {
          elements.push(js("""[tag]"""))
        }
      }
    }

    val id = id
    pinnedObjects.push(elements)
    rdmaObj.appendModifierChange(id.value, elements)
  }

  override fun appendAdd(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    val childId = child.id.value
    val knownId = widgets.has(childId)
    if (child.removeIndex != INVALID_INDEX) {
      check(hostSupportsRemoveDetach) { "Host v$hostVersion does not support widget re-attach" }
      check(knownId) { "Attempted to re-attach unknown widget with ID $childId" }
      removed.delete(childId)
      rdmaObj.setRemoveDetach(child.removeIndex)
    } else {
      check(!knownId) { "Attempted to add widget with existing ID $childId" }
      widgets.set(childId, child)
    }

    val id = id
    val tag = tag
    val index = index
    rdmaObj.appendAdd(id.value, tag.value, childId, index)
  }

  override fun appendMove(
    id: Id,
    tag: ChildrenTag,
    fromIndex: Int,
    toIndex: Int,
    count: Int,
  ) {
    val id = id
    val tag = tag
    val fromIndex = fromIndex
    val toIndex = toIndex
    val count = count
    rdmaObj.appendMove(id.value, tag.value, fromIndex, toIndex, count)
  }

  override fun appendRemove(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    removed.add(child.id.value)

    val id = id
    val tag = tag
    val index = index
    val rdmaIndex = rdmaObj.changesLength()
    child.removeIndex = rdmaIndex
    rdmaObj.appendRemove(id.value, tag.value, index)
  }

  override fun appendBridgeChange(id: Id, wrapped: Any?) {
    throw AssertionError("RdmaGuestProtocolAdapterImpl.appendBridgeChange should never be called")  // should be unreachable
  }

  override fun emitChanges() {
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
