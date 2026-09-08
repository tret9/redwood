package app.cash.redwood.treehouse

import app.cash.redwood.protocol.BridgeChange
import app.cash.redwood.protocol.ChangesSink
import app.cash.redwood.protocol.ChildrenChange
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Change
import app.cash.redwood.protocol.Create
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.ModifierChange
import app.cash.redwood.protocol.ModifierElement
import app.cash.redwood.protocol.ModifierTag
import app.cash.redwood.protocol.PropertyChange
import app.cash.redwood.protocol.PropertyTag
import app.cash.redwood.protocol.WidgetTag
import app.cash.zipline.RdmaChangeSink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-session RDMA bridge for the Kotlin/Native (iOS) runtime. One instance per zipline session;
 * all state ([callsink], [batchAccumulator]) is instance-scoped so concurrent sessions never
 * route changes into each other's UI.
 */
public actual class RdmaBridge : ChangesSink {

  internal actual var callsink: ChangesSink? = null

  private var batchAccumulator: MutableList<Change>? = null

  public actual override fun sendChanges(changes: List<Change>) {
    val acc = batchAccumulator
    val all = if (acc == null) changes else acc + changes
    batchAccumulator = null
    callsink?.sendChanges(all)
  }

  public actual fun sendBatch(changes: List<Change>) {
    val acc = batchAccumulator ?: mutableListOf<Change>().also { batchAccumulator = it }
    acc.addAll(changes)
  }

  public actual fun asRdmaChangeSink(): RdmaChangeSink = RdmaChangeSinkAdapter(this)

  private class RdmaChangeSinkAdapter(
    private val bridge: RdmaBridge,
  ) : RdmaChangeSink {
    private var accumulator: MutableList<Change> = mutableListOf()

    override fun createCreate(id: Int, tag: Int) {
      accumulator.add(Create(Id(id), WidgetTag(tag)))
    }

    override fun createPropertyChange(id: Int, widgetTag: Int, propertyTag: Int, value: JsonElement) {
      accumulator.add(PropertyChange(Id(id), WidgetTag(widgetTag), PropertyTag(propertyTag), value))
    }

    override fun createModifierChange(id: Int, elements: List<Pair<Int, JsonElement>>) {
      val modifierElements = ArrayList<ModifierElement>(elements.size)
      for ((tag, value) in elements) {
        modifierElements.add(ModifierElement(ModifierTag(tag), value))
      }
      accumulator.add(ModifierChange(Id(id), modifierElements))
    }

    override fun createAdd(id: Int, childrenTag: Int, childId: Int, index: Int) {
      accumulator.add(ChildrenChange.Add(Id(id), ChildrenTag(childrenTag), Id(childId), index))
    }

    override fun createMove(id: Int, childrenTag: Int, fromIndex: Int, toIndex: Int, count: Int) {
      accumulator.add(ChildrenChange.Move(Id(id), ChildrenTag(childrenTag), fromIndex, toIndex, count))
    }

    override fun createBridgeChange(id: Int, wrapped: Any?) {
      accumulator.add(BridgeChange(Id(id), wrapped))
    }

    override fun createRemove(id: Int, childrenTag: Int, index: Int, detach: Boolean) {
      accumulator.add(ChildrenChange.Remove(Id(id), ChildrenTag(childrenTag), index, detach))
    }

    override fun setRemoveDetach(index: Int) {
      var removesSeen = 0
      for (i in accumulator.indices.reversed()) {
        val change = accumulator[i]
        if (change is ChildrenChange.Remove) {
          if (removesSeen == index) {
            change.detach = true
            return
          }
          removesSeen++
        }
      }
    }

    override fun sendBatch() {
      val batch = accumulator
      accumulator = mutableListOf()
      if (batch.isNotEmpty()) {
        bridge.sendBatch(batch)
      }
    }

    override fun sendChanges() {
      val batch = accumulator
      accumulator = mutableListOf()
      if (batch.isNotEmpty()) {
        bridge.sendChanges(batch)
      }
    }
  }

  public actual companion object {
    public actual fun createCreate(id: Int, tag: Int): Create = Create(Id(id), WidgetTag(tag))

    public actual fun createAdd(id: Int, tag: Int, childId: Int, index: Int): ChildrenChange =
      ChildrenChange.Add(Id(id), ChildrenTag(tag), Id(childId), index)

    public actual fun createRemove(id: Int, tag: Int, index: Int, detach: Boolean): ChildrenChange =
      ChildrenChange.Remove(Id(id), ChildrenTag(tag), index, detach)

    public actual fun createMove(id: Int, tag: Int, fromIndex: Int, toIndex: Int, count: Int): ChildrenChange =
      ChildrenChange.Move(Id(id), ChildrenTag(tag), fromIndex, toIndex, count)

    public actual fun createPropertyChange(id: Int, widgetTag: Int, propertyTag: Int, value: JsonElement): PropertyChange =
      PropertyChange(Id(id), WidgetTag(widgetTag), PropertyTag(propertyTag), value)

    public actual fun createModifierChange(id: Int, elements: List<ModifierElement>): ModifierChange =
      ModifierChange(Id(id), elements)

    public actual fun createModifierElement(tag: Int, value: JsonElement): ModifierElement =
      ModifierElement(ModifierTag(tag), value)

    public actual fun createBridgeChange(id: Int, wrapped: Any?): BridgeChange =
      BridgeChange(Id(id), wrapped)

    public actual fun jsonPrimitiveString(value: String): JsonPrimitive = JsonPrimitive(value)

    public actual fun jsonPrimitiveInt(value: Int): JsonPrimitive = JsonPrimitive(value)

    public actual fun jsonPrimitiveLong(value: Long): JsonPrimitive = JsonPrimitive(value)

    public actual fun jsonPrimitiveDouble(value: Double): JsonPrimitive = JsonPrimitive(value)

    public actual fun jsonPrimitiveBoolean(value: Boolean): JsonPrimitive = JsonPrimitive(value)

    public actual fun jsonNull(): JsonNull = JsonNull

    public actual fun createJsonArray(elements: List<JsonElement>): JsonArray =
      buildJsonArray { elements.forEach { add(it) } }

    public actual fun createJsonObject(keys: List<String>, values: List<JsonElement>): JsonObject =
      buildJsonObject { keys.zip(values).forEach { (k, v) -> put(k, v) } }
  }
}
