package com.modulai.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LoadConfigTest {
    @Test fun offloadsEveryLayerUnlessTold() {
        assertEquals(LoadConfig.ALL_LAYERS, LoadConfig(Backend.GPU).gpuLayers)
    }

    @Test fun callersThatPredateGpuLayersKeepTheirMeaning() {
        val old = LoadConfig(Backend.CPU, 2048, 6, true, false)
        assertEquals(LoadConfig(Backend.CPU, 2048, 6, true, false, LoadConfig.ALL_LAYERS), old)
    }

    // The app reloads the model when the config it loaded with no longer equals the current one.
    @Test fun layerCountIsPartOfTheConfigIdentity() {
        assertNotEquals(LoadConfig(Backend.GPU), LoadConfig(Backend.GPU, gpuLayers = 12))
    }
}
