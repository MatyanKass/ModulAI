# modulai_audio.cpp looks these methods up by name through JNI, so the shrinker must not
# rename or remove them.
-keep interface com.modulai.engine.audio.WhisperCallback { *; }
-keepclassmembers class * implements com.modulai.engine.audio.WhisperCallback {
    public void onProgress(int);
    public void onSegment(long, long, byte[]);
}
# Native methods are bound by class and method name.
-keep class com.modulai.engine.audio.WhisperNative { native <methods>; }

# ONNX Runtime's JNI layer builds Java objects and reads their fields by name, and its
# package ships no shrinker rules of its own.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
