# modulai_llama.cpp looks this method up by name through JNI, so the shrinker must not
# rename or remove it.
-keep interface com.modulai.engine.llamacpp.PieceCallback { *; }
-keepclassmembers class * implements com.modulai.engine.llamacpp.PieceCallback {
    public void onPiece(byte[]);
}
