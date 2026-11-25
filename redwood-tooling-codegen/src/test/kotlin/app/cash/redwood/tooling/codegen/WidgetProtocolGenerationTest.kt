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
import app.cash.redwood.tooling.schema.ProtocolSchemaSet
import app.cash.redwood.tooling.schema.parseTestSchema
import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.example.redwood.testapp.TestSchema
import org.junit.Test

class WidgetProtocolGenerationTest {
  @Test fun `dependency layout modifier are included in serialization`() {
    val schema = ProtocolSchemaSet.load(TestSchema::class)

    val fileSpec = generateHostProtocol(schema)
    assertThat(fileSpec.toString()).all {
      contains("1 -> TestRowVerticalAlignmentImpl.serializer()")
      contains("2_000_001 -> GrowImpl.serializer()")
    }
  }

  @Schema(
    [
      NullableNumericNode::class,
    ],
  )
  interface NullableNumericSchema

  @Widget(1)
  data class NullableNumericNode(
    @Property(1) val progress: Float?,
    @Property(2) val size: Float,
    @Property(3) val count: Int?,
  )

  /**
   * A property value that crosses the direct bridge arrives as a boxed `Any?`, and JavaScript has one
   * number type: `progress = 0.5f` shows up as a `Double`, `progress = 1f` as an `Integer`. So the
   * host has to coerce through `Number` rather than cast to the declared type.
   *
   * The classification that decides between the two ran on the declared type *including* its
   * nullability, so `Float?` matched nothing and was emitted as `change.value as Float?`. On a device
   * that is `ClassCastException: java.lang.Double cannot be cast to java.lang.Float`, thrown inside
   * `HostProtocolAdapter.sendChanges`, which kills the Treehouse session and leaves the screen on
   * whatever it had painted last.
   */
  @Test fun `nullable numeric properties are coerced through Number rather than cast`() {
    val schema = parseTestSchema(NullableNumericSchema::class).schema

    // The cast lives in the per-widget node's `apply`, not in the aggregate host protocol.
    val fileSpec = generateProtocolNode(schema, schema, schema.widgets.single()).toString()
    assertThat(fileSpec).all {
      contains("widget.progress((change.value as Number?)?.toFloat())")
      contains("widget.count((change.value as Number?)?.toInt())")
      // The non-nullable case must keep its unsafe cast: a null there is a protocol error, and
      // coercing it away would hide it.
      contains("widget.size((change.value as Number).toFloat())")
      doesNotContain("change.value as Float?")
    }
  }
}
