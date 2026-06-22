package app.cash.redwood.treehouse
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
public object RdmaBridge : ChangesSink {
    @Volatile
    internal var callsink: ChangesSink? = null
    public override fun sendChanges(changes: List<Change>) {
        callsink?.sendChanges(changes)
    }
    @JvmStatic public fun createCreate(id: Int, tag: Int): Create = Create(Id(id), WidgetTag(tag))
    @JvmStatic public fun createAdd(id: Int, tag: Int, childId: Int, index: Int): ChildrenChange = ChildrenChange.Add(Id(id), ChildrenTag(tag), Id(childId), index)
    @JvmStatic public fun createRemove(id: Int, tag: Int, index: Int, detach: Boolean): ChildrenChange = ChildrenChange.Remove(Id(id), ChildrenTag(tag), index, detach)
    @JvmStatic public fun createMove(id: Int, tag: Int, fromIndex: Int, toIndex: Int, count: Int): ChildrenChange = ChildrenChange.Move(Id(id), ChildrenTag(tag), fromIndex, toIndex, count)
    @JvmStatic public fun createPropertyChange(id: Int, widgetTag: Int, propertyTag: Int, value: JsonElement): PropertyChange = PropertyChange(Id(id), WidgetTag(widgetTag), PropertyTag(propertyTag), value)
    @JvmStatic public fun createModifierChange(id: Int, elements: List<ModifierElement>): ModifierChange = ModifierChange(Id(id), elements)
    @JvmStatic public fun createModifierElement(tag: Int, value: JsonElement): ModifierElement = ModifierElement(ModifierTag(tag), value)
    @JvmStatic public fun jsonPrimitiveString(value: String): JsonPrimitive = JsonPrimitive(value)
    @JvmStatic public fun jsonPrimitiveInt(value: Int): JsonPrimitive = JsonPrimitive(value)
    @JvmStatic public fun jsonPrimitiveLong(value: Long): JsonPrimitive = JsonPrimitive(value)
    @JvmStatic public fun jsonPrimitiveDouble(value: Double): JsonPrimitive = JsonPrimitive(value)
    @JvmStatic public fun jsonPrimitiveBoolean(value: Boolean): JsonPrimitive = JsonPrimitive(value)
    @JvmStatic public fun jsonNull(): JsonNull = JsonNull
    @JvmStatic public fun createJsonArray(elements: List<JsonElement>): JsonArray = buildJsonArray { elements.forEach { add(it) } }
    @JvmStatic public fun createJsonObject(keys: List<String>, values: List<JsonElement>): JsonObject = buildJsonObject { keys.zip(values).forEach { (k, v) -> put(k, v) } }
}
