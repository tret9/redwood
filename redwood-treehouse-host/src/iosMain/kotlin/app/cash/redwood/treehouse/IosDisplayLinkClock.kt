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

import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIWindowScene
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

/**
 * Sends the first requested frame immediately, then keeps later frames at the display rate.
 *
 * Waiting for CADisplayLink parked every guest composition until the
 * following vsync, which added about one frame to scroll-to-compose. Sending every request
 * immediately lets a still-invalid composition recompose as fast as the CPU allows.
 */
internal class IosDisplayLinkClock private constructor(
  private val scope: CoroutineScope,
  private val dispatchers: TreehouseDispatchers,
) : FrameClock {
  /** Non-null if a requested frame has not been sent yet. */
  private var appLifecycle: AppLifecycle? = null
  private var closed = false
  private var frameScheduled = false
  private var lastFrameNanos = 0L

  /**
   * Captured at creation. The clock is created on the Zipline thread, and UIKit is
   * main-thread confined.
   */
  private val frameIntervalNanos: Long =
    if (NSThread.isMainThread) {
      displayFrameIntervalNanos()
    } else {
      runBlocking(dispatchers.ui) { displayFrameIntervalNanos() }
    }

  override fun requestFrame(appLifecycle: AppLifecycle) {
    if (closed) return
    this.appLifecycle = appLifecycle
    if (frameScheduled) return
    frameScheduled = true
    val waitNanos = waitForNextFrame()
    scope.launch(dispatchers.zipline) {
      if (waitNanos > 0L) delay(waitNanos.nanoseconds)
      frameScheduled = false
      if (closed) return@launch
      val lifecycle = this@IosDisplayLinkClock.appLifecycle ?: return@launch
      this@IosDisplayLinkClock.appLifecycle = null
      lifecycle.sendFrame(nextFrameNanos())
    }
  }

  /** Zero when no frame has been sent yet, or the previous one is already a frame old. */
  private fun waitForNextFrame(): Long {
    if (lastFrameNanos == 0L) return 0L
    val earliest = lastFrameNanos + frameIntervalNanos
    return (earliest - monotonicNanos()).coerceAtLeast(0L)
  }

  private fun nextFrameNanos(): Long {
    val now = monotonicNanos()
    val time = if (now > lastFrameNanos) now else lastFrameNanos + 1
    lastFrameNanos = time
    return time
  }

  private fun monotonicNanos(): Long =
    clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.convert()).convert<Long>()

  override fun close() {
    closed = true
    appLifecycle = null
  }

  companion object : FrameClock.Factory {
    private const val DEFAULT_FRAMES_PER_SECOND = 60L

    /** One frame at the window scene's maximum rate, or 60 Hz when the scene reports 0. */
    internal fun displayFrameIntervalNanos(): Long =
      1_000_000_000L / displayFramesPerSecond()

    private fun displayFramesPerSecond(): Long {
      val reported = windowScene()?.screen?.maximumFramesPerSecond ?: 0L
      return if (reported > 0L) reported else DEFAULT_FRAMES_PER_SECOND
    }

    private fun windowScene(): UIWindowScene? {
      val application = UIApplication.sharedApplication
      application.keyWindow?.windowScene?.let { return it }
      val scenes = application.connectedScenes as? Set<*> ?: return null
      return scenes.firstNotNullOfOrNull { it as? UIWindowScene }
    }

    override fun create(
      scope: CoroutineScope,
      dispatchers: TreehouseDispatchers,
    ) = IosDisplayLinkClock(scope, dispatchers)
  }
}
