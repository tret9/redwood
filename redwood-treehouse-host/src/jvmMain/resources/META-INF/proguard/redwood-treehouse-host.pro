# Zipline's JNI code resolves DirectBridge and its static factories by name and signature to build
# JSON values for the direct bridge. The Android AAR ships this file as a consumer rule too.
-keep class app.cash.redwood.treehouse.DirectBridge {
  public static <methods>;
}
-keepnames class kotlinx.serialization.json.JsonPrimitive
-keepnames class kotlinx.serialization.json.JsonNull
-keepnames class kotlinx.serialization.json.JsonArray
-keepnames class kotlinx.serialization.json.JsonObject
