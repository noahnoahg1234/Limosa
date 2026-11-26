// Minimal stand-in for <jni.h> so the runtime compiles in the desktop test build.
#pragma once
typedef void* jobject;
typedef struct JavaVM_ JavaVM;
