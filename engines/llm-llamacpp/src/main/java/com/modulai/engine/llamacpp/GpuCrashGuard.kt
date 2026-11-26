package com.modulai.engine.llamacpp

import java.io.File

/**
 * The OpenCL backend ends the whole process (exit/abort) when the driver misbehaves, which
 * nothing in the process can catch. Every step that enters it runs inside [run], which
 * leaves [marker] on disk while the step is in progress; a marker found afterwards means
 * the step never returned, and the GPU is left alone until [reset].
 *
 * [stamp] identifies the installed build, so an app update gives the GPU another chance.
 */
internal class GpuCrashGuard(private val marker: File, private val stamp: String) {
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
