/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.redwood.tooling.codegen

import app.cash.redwood.schema.Property
import app.cash.redwood.schema.Schema
import app.cash.redwood.schema.Widget
import app.cash.redwood.tooling.schema.parseTestSchema
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.Test

class ProtocolGuestGenerationTest {
  @Schema(
    [
      IdPropertyNameCollisionNode::class,
    ],
  )
  interface IdPropertyNameCollisionSchema

  @Widget(1)
  data class IdPropertyNameCollisionNode(
    @Property(1) val label: String,
    @Property(2) val id: String,
  )

  @Test fun `id property does not collide`() {
    val schema = parseTestSchema(IdPropertyNameCollisionSchema::class).schema

    val fileSpec = generateProtocolWidget(schema, schema, schema.widgets.single())
    assertThat(fileSpec.toString()).contains(
      """
      |  override fun id(id: String) {
      |    this.guestAdapter.appendPropertyChange(this.id,
      """.trimMargin(),
    )
  }

  @Schema(
    [
      EventfulNode::class,
    ],
  )
  interface EventfulSchema

  @Widget(2)
  data class EventfulNode(
    @Property(1) val label: String,
    @Property(2) val onChanged: ((text: String, index: Int) -> Unit)? = null,
  )

  @Test fun `dual mode emits sendEvent decode and sendDirectEvent dispatch`() {
    val schema = parseTestSchema(EventfulSchema::class).schema
    val fileSpec = generateProtocolWidget(schema, schema, schema.widgets.single()).toString()

    // Direct-event seam on the generated widget.
    assertThat(fileSpec).contains(": ProtocolWidget,")
    assertThat(fileSpec).contains("DirectEventDispatcher")
    assertThat(fileSpec).contains(
      "override fun sendDirectEvent(tag: EventTag, args: Array<Any?>) {",
    )
    assertThat(fileSpec).contains("onChanged?.invoke(args[0] as String, args[1] as Int)")
    assertThat(fileSpec).contains("else -> mismatchHandler.onUnknownEvent(this.tag, tag)")
    // JSON path retained in dual mode.
    assertThat(fileSpec).contains("override fun sendEvent(event: Event)")
    assertThat(fileSpec).contains("decodeFromJsonElement(serializer_0, event.args[0])")
    assertThat(fileSpec).contains("this.guestAdapter.appendPropertyChange(this.id,")
  }

  @Test fun `direct-only mode drops JSON decode and serializers`() {
    val schema = parseTestSchema(EventfulSchema::class).schema
    val fileSpec = generateProtocolWidget(
      schema, schema, schema.widgets.single(), directEventsOnly = true,
    ).toString()

    // Direct dispatch + bridged property changes present.
    assertThat(fileSpec).contains("override fun sendDirectEvent(")
    assertThat(fileSpec).contains("onChanged?.invoke(args[0] as String, args[1] as Int)")
    assertThat(fileSpec).contains("guestAdapter.appendBridgedPropertyChange(this.id,")
    // JSON machinery gone.
    assertThat(fileSpec).doesNotContain("decodeFromJsonElement")
    assertThat(fileSpec).doesNotContain("serializer_0")
    assertThat(fileSpec).doesNotContain("guestAdapter.json")
    assertThat(fileSpec).contains(
      "throw AssertionError(\"JSON events are not supported in a direct-only guest build\")",
    )
}
}
