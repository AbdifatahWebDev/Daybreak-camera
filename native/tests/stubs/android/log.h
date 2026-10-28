// Desktop stub of the NDK header, used only to compile-check the JNI bridge.
#pragma once

enum { ANDROID_LOG_ERROR = 6 };
int __android_log_print(int prio, const char* tag, const char* fmt, ...);
