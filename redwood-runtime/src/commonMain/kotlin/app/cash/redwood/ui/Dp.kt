/*
 * Copyright (C) 2023-2026 Square, Inc.
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
package app.cash.redwood.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Represents a density-independent pixel value. Values will be scaled and
 * converted into standard pixel values according to the device's density.
 */
@Suppress("NOTHING_TO_INLINE")
@[Immutable JvmInline Serializable]
@WithJS2HostBridge
@WithHost2JSBridge
public value class Dp(
  public val value: Double,
) : Comparable<Dp> {
  // Unspecified values should compare false against all other values. This always sets
  // them as comparing == 0, but the equality check fails, so Unspecified < 1.dp == false
  // and 1.dp < Unspecified == false
  @Stable
  override operator fun compareTo(other: Dp): Int = if (this == Unspecified || other == Unspecified) 0 else value.compareTo(other.value)

  /** Add two [Dp]s together. */
  @Stable
  public inline operator fun plus(other: Dp): Dp = Dp(this.value + other.value)

  /** Subtract a Dp from another one. */
  @Stable
  public inline operator fun minus(other: Dp): Dp = Dp(this.value - other.value)

  /** This is the same as multiplying the Dp by -1.0. */
  @Stable
  public inline operator fun unaryMinus(): Dp = Dp(-value)

  @Stable
  public inline operator fun div(other: Double): Dp = Dp(value / other)

  @Stable
  public inline operator fun div(other: Float): Dp = Dp(value / other)

  @Stable
  public inline operator fun div(other: Int): Dp = Dp(value / other)

  @Stable
  public inline operator fun div(other: Dp): Double = value / other.value

  @Stable
  public inline operator fun times(other: Double): Dp = Dp(value * other)

  @Stable
  public inline operator fun times(other: Float): Dp = Dp(value * other)

  @Stable
  public inline operator fun times(other: Int): Dp = Dp(value * other)

  @Stable
  override fun toString(): String = if (value == Unspecified.value) "Dp.Unspecified" else "$value.dp"

  /** Empty companion object used for extensions. */
  public companion object {
    // Can't pass Double.isNaN due to 53bit limit for js Number.
    @Stable
    public val Unspecified: Dp = Int.MAX_VALUE.dp
  }
}

/** Create a [Dp] from an [Int]. */
@Stable
public inline val Int.dp: Dp get() = Dp(toDouble())

/** Create a [Dp] from a [Float]. */
@Stable
public inline val Float.dp: Dp get() = Dp(toDouble())

/** Create a [Dp] from a [Double]. */
@Stable
public inline val Double.dp: Dp get() = Dp(toDouble())

/**
 * Convert device-agnostic density-independent pixel value into a
 * device-specific density-independent pixel value.
 */
public fun Dp.toPlatformDp(): Double {
  return value * DENSITY_MULTIPLIER
}

/**
 * Convert device-specific density-independent pixel value into a
 * device-agnostic density-independent pixel value.
 */
public fun Dp.Companion.fromPlatformDp(value: Double): Dp {
  return Dp(value / DENSITY_MULTIPLIER)
}
