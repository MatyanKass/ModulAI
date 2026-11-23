package com.modulai.app

import android.app.Application

/** Holds the singletons for the life of the process, so rotating the screen keeps the loaded model and chat. */
class ModulAiApp : Application() {
    val container by lazy { AppContainer(this) }
}
