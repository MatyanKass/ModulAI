# modulai_sd.cpp looks this method up by name through JNI, so the shrinker must not
# rename or remove it.
-keep interface com.modulai.engine.imagesdcpp.ProgressCallback { *; }
-keepclassmembers class * implements com.modulai.engine.imagesdcpp.ProgressCallback {
    public void onProgress(int, int, int);
}
