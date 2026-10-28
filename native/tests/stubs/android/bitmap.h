// Desktop stub of the NDK header, used only to compile-check the JNI bridge.
#pragma once
#include <jni.h>
#include <stdint.h>

enum { ANDROID_BITMAP_RESULT_SUCCESS = 0 };
enum { ANDROID_BITMAP_FORMAT_RGBA_8888 = 1 };

typedef struct {
  uint32_t width;
  uint32_t height;
  uint32_t stride;
  int32_t format;
  uint32_t flags;
} AndroidBitmapInfo;

int AndroidBitmap_getInfo(JNIEnv* env, jobject jbitmap, AndroidBitmapInfo* info);
int AndroidBitmap_lockPixels(JNIEnv* env, jobject jbitmap, void** addrPtr);
int AndroidBitmap_unlockPixels(JNIEnv* env, jobject jbitmap);
