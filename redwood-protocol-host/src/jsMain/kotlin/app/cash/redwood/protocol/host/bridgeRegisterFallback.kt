/*
 * Copyright (C) 2026 Square, Inc.
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
@file:OptIn(ExperimentalStdlibApi::class)
@file:Suppress("DEPRECATION")

package app.cash.redwood.protocol.host

/**
 * Companions processed by the Zipline bridge compiler plugin call the global `__bridgeRegister`,
 * which only a Hermes host installs. Outside of one (Node, browsers, tests) nothing consumes the
 * registrations, so install a no-op instead of failing with a ReferenceError. Every module using
 * the bridge plugin has a copy of this, as only a module's own initialization is guaranteed to run
 * before its companions are used.
 */
@EagerInitialization
private val bridgeRegisterFallback: dynamic = js(
  "globalThis.__bridgeRegister = globalThis.__bridgeRegister || function() {}",
)
