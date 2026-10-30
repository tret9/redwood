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

import app.cash.redwood.protocol.RedwoodVersion
import app.cash.redwood.treehouse.AppLifecycle.Host
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.convert
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

class IosDisplayLinkClockTest : AbstractFrameClockTest() {
  override val frameClockFactory: FrameClock.Factory
    get() = IosDisplayLinkClock

  @Test
  fun requestedFrameArrivesBeforeTheNextVsync() = runBlocking {
    val dispatchers = IosTreehouseDispatchers("frame-clock-fast")
    val frameClock = frameClockFactory.create(this, dispatchers)
    try {
      val frameTimes = Channel<Long>(capacity = 4)
      val started = monotonicNanos()
      frameClock.requestFrame(recordingLifecycle(frameTimes))
      withTimeout(1.seconds) { frameTimes.receive() }
      val elapsed = monotonicNanos() - started
      assertTrue(
        elapsed < IosDisplayLinkClock.displayFrameIntervalNanos(),
        "frame took ${elapsed / 1_000_000}ms; a display link would wait a full frame",
      )
    } finally {
      frameClock.close()
      dispatchers.close()
    }
  }

  @Test
  fun sustainedFrameRequestsStayNearDisplayRate() = runBlocking {
    val dispatchers = IosTreehouseDispatchers("frame-clock-rate")
    val frameClock = frameClockFactory.create(this, dispatchers)
    try {
      // A handful of paced frames. A loaded simulator often runs delay() several times
      // slower than the requested interval, so this counts gaps instead of frames per window.
      val targetFrames = 6
      val frameTimes = Channel<Long>(Channel.UNLIMITED)
      val started = monotonicNanos()
      var sent = 0
      val appLifecycle = object : AppLifecycle {
        override val guestProtocolVersion get() = RedwoodVersion.Unknown
        override fun start(host: Host) = Unit
        override fun sendFrame(timeNanos: Long) {
          val elapsed = monotonicNanos() - started
          check(frameTimes.trySend(elapsed).isSuccess)
          sent++
          if (sent < targetFrames) {
            frameClock.requestFrame(this)
          }
        }
      }

      frameClock.requestFrame(appLifecycle)
      val elapsed = mutableListOf<Long>()
      withTimeout(3.seconds) {
        repeat(targetFrames) {
          elapsed += frameTimes.receive()
        }
      }

      val intervalNanos = IosDisplayLinkClock.displayFrameIntervalNanos()
      for (i in 1 until elapsed.size) {
        val gap = elapsed[i] - elapsed[i - 1]
        assertTrue(
          gap >= intervalNanos / 2,
          "frame $i followed after ${gap / 1_000_000}ms; display interval is ${intervalNanos / 1_000_000}ms",
        )
        assertTrue(
          gap < 1.seconds.inWholeNanoseconds,
          "frame $i stalled for ${gap / 1_000_000}ms",
        )
      }
    } finally {
      frameClock.close()
      dispatchers.close()
    }
  }

  private fun recordingLifecycle(frameTimes: Channel<Long>) = object : AppLifecycle {
    override val guestProtocolVersion get() = RedwoodVersion.Unknown
    override fun start(host: Host) = Unit
    override fun sendFrame(timeNanos: Long) {
      check(frameTimes.trySend(timeNanos).isSuccess)
    }
  }

  private fun monotonicNanos(): Long =
    clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.convert()).convert()
}
