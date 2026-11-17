#ifndef RAGE_ANDROID_TOUCH_H
#define RAGE_ANDROID_TOUCH_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

extern volatile uint32_t g_AndroidTouchPadMask;

void AndroidTouch_SetMask(uint32_t pressed, uint32_t released);

#ifdef __cplusplus
}
#endif

#endif // RAGE_ANDROID_TOUCH_H
