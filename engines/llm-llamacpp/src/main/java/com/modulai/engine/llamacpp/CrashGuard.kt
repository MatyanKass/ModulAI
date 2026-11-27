package com.modulai.engine.llamacpp

import java.io.File

/**
 * The accelerator backends (OpenCL GPU, Hexagon NPU) end the whole process (exit/abort) when
 * a driver or the DSP misbehaves, which nothing in the process can catch. Every step that
 * enters one runs inside [run], which leaves [marker] on disk while the step is in progress;
 * a marker found afterwards means the step never returned, and that backend is left alone
 * until [reset]. Each backend has its own marker, so a crash in one never costs the other.
 *
 * [stamp] identifies the installed build, so an app update gives the backend another chance.
 */
internal class CrashGuard(private val marker: File, private val stamp: String) {
    val tripped: Boolean
        get() = try {
            marker.readText() == stamp
        } catch (e: java.io.IOException) {
            false
        }

    fun <T> run(step: () -> T): T {
        marker.writeText(stamp)
        try {
            return step()
        } finally {
            marker.delete()
        }
    }

    fun reset() {
        marker.delete()
    }
}
