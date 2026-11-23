# modulai_llama.cpp looks these methods up by name through JNI, so the shrinker must not
# rename or remove them.
-keep interface com.modulai.engine.llamacpp.PieceCallback { *; }
-keepclassmembers class * implements com.modulai.engine.llamacpp.PieceCallback {
    public void onPiece(byte[]);
    public void onToolCall(byte[], byte[]);
}
