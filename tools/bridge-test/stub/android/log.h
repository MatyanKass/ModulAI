// Host stand-in for <android/log.h>, used only by tools/bridge-test.
#pragma once
#include <cstdio>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "[%s] ", tag), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#define __android_log_write(prio, tag, text) fprintf(stderr, "[%s] %s", tag, text)
