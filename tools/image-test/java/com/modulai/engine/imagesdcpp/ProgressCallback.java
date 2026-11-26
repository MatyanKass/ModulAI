package com.modulai.engine.imagesdcpp;

public interface ProgressCallback {
    void onProgress(int stage, int done, int total);
}
