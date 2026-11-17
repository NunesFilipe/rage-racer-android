#include "android_touch.h"
#include <jni.h>

volatile uint32_t g_AndroidTouchPadMask = 0;

void AndroidTouch_SetMask(uint32_t pressed, uint32_t released) {
    g_AndroidTouchPadMask = (g_AndroidTouchPadMask | pressed) & ~released;
}

JNIEXPORT void JNICALL
Java_org_libsdl_app_RageRacerActivity_nativeSetVirtualPad(JNIEnv *env, jclass cls, jint pressed, jint released) {
    (void)env;
    (void)cls;
    AndroidTouch_SetMask((uint32_t)pressed, (uint32_t)released);
}

JNIEXPORT void JNICALL
Java_org_libsdl_app_SDLActivity_nativeSetVirtualPad(JNIEnv *env, jclass cls, jint pressed, jint released) {
    (void)env;
    (void)cls;
    AndroidTouch_SetMask((uint32_t)pressed, (uint32_t)released);
}
