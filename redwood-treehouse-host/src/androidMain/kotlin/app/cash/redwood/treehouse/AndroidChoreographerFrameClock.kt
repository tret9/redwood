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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends a frame as soon as one is requested.
 *
 * Waiting for the next [android.view.Choreographer] frame parked guest composition until the
 * following vsync, which added about one frame to scroll-to-compose.
 */
internal class AndroidChoreographerFrameClock private constructor(
  private val scope: CoroutineScope,
  private val dispatchers: TreehouseDispatchers,
) : FrameClock {
  private var closed = false
  private var frameScheduled = false
  private var lastFrameNanos = 0L

  override fun requestFrame(appLifecycle: AppLifecycle) {
    if (closed || frameScheduled) return
    frameScheduled = true
    scope.launch(dispatchers.zipline) {
      frameScheduled = false
      if (closed) return@launch
      appLifecycle.sendFrame(nextFrameNanos())
    }
  }

  private fun nextFrameNanos(): Long {
    val now = System.nanoTime()
    val time = if (now > lastFrameNanos) now else lastFrameNanos + 1
    lastFrameNanos = time
    return time
  }

  override fun close() {
    closed = true
  }

  class Factory : FrameClock.Factory {
    override fun create(
      scope: CoroutineScope,
      dispatchers: TreehouseDispatchers,
    ) = AndroidChoreographerFrameClock(scope, dispatchers)
  }
}
