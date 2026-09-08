#ifdef __ANDROID__
#include <jni.h>
#include <jsi/jsi.h>
#include "ContextBase.h"
#include "bridge_dispatch.h"

namespace jsi = facebook::jsi;

JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_bridgeInitAllNative(JNIEnv* env, jclass, jlong jsContext) {
    init_all(env);
    if (jsContext) {
        ContextBase* ctx = reinterpret_cast<ContextBase*>(jsContext);
        register_all(ctx->getRuntime());
    }
}
#endif
