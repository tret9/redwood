# Bridge native library for Redwood JNI dispatch (v4)
cmake_minimum_required(VERSION 3.12)
set(CMAKE_CXX_STANDARD 17)

# Header paths: extracted from zipline-android AAR during extractHermesJni
include_directories(
    ${ZIPLINE_NATIVE}
    ${ZIPLINE_NATIVE}/hermes/API/jsi
)

# Generated bridge C files
set(BRIDGE_PROTO "${CMAKE_CURRENT_SOURCE_DIR}/../../../../redwood-protocol/build/generated/bridge/c")
set(BRIDGE_HOST  "${CMAKE_CURRENT_SOURCE_DIR}/../../../../redwood-protocol-host/build/generated/bridge/c")
set(BRIDGE_RUNTIME "${CMAKE_CURRENT_SOURCE_DIR}/../../../../redwood-runtime/build/generated/bridge/c")

file(GLOB bridge_sources CONFIGURE_DEPENDS
    "${BRIDGE_PROTO}/app_cash_redwood_protocol_*.cpp"
    "${BRIDGE_HOST}/app_cash_redwood_protocol_host_*.cpp"
    "${BRIDGE_RUNTIME}/app_cash_redwood_*.cpp"
)

add_library(redwood-bridge SHARED
    ${bridge_sources}
)

# Prevent llvm-strip --strip-unneeded from removing the constructor function
target_link_options(redwood-bridge PRIVATE "-Wl,--export-dynamic")

# Link against libhermesvmlean.so (from zipline-android AAR, path resolved via Maven).
# AGP's packaging.jniLibs.excludes strips this from the AAR.
set(HERMES_LIB "${HERMES_JNI_DIR}/${ANDROID_ABI}/libhermesvmlean.so")
add_library(hermesvmlean SHARED IMPORTED)
set_target_properties(hermesvmlean PROPERTIES IMPORTED_LOCATION "${HERMES_LIB}")
target_link_libraries(redwood-bridge hermesvmlean log)
