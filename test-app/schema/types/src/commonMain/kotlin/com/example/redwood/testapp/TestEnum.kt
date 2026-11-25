package com.example.redwood.testapp

import app.cash.zipline.bridge.support.WithHost2JSBridge
import kotlinx.serialization.Serializable

/**
 * A host-to-guest event parameter type, to pin that `@WithHost2JSBridge` enums arrive at the guest
 * as its own singleton entries — compatible with the generated `args[i] as TestEnum` cast.
 */
@Serializable
@WithHost2JSBridge
enum class TestEnum { A, B }
