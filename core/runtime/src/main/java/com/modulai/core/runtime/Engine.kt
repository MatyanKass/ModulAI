package com.modulai.core.runtime

/** What a module can ask the runtime to do. Modules never name an engine directly. */
enum class Capability {
    TEXT_GENERATION,
    IMAGE_GENERATION,
    IMAGE_UPSCALE,
    TEXT_TO_SPEECH,
    SPEECH_TO_TEXT,
    FRAME_INTERPOLATION,
}

/** Hardware an engine can run on, in default preference order. */
enum class Backend { NPU, GPU, CPU }

enum class ModelFormat { GGUF, SAFETENSORS, ONNX, QNN, NCNN }

data class ModelDescriptor(
    val id: String,
    val path: String,
    val format: ModelFormat,
    val capability: Capability,
    val sizeBytes: Long,
)

data class LoadConfig(
    val backend: Backend,
    val contextTokens: Int = 4096,
    val threads: Int = 4,
    /** Pin weights in RAM so the kernel cannot push them to swap/zram. */
    val lockMemory: Boolean = false,
    val useMmap: Boolean = true,
    /**
     * Model layers to run on the GPU when [backend] is [Backend.GPU]: [ALL_LAYERS] (any
     * negative value), or a count for partial offload when the model does not fit GPU memory;
     * 0 runs everything on the CPU. Ignored for other backends. The default keeps configs
     * written before this field existed meaning the same.
     */
    val gpuLayers: Int = ALL_LAYERS,
) {
    companion object {
        const val ALL_LAYERS = -1
    }
}

/** A loaded model. Capability-specific sessions extend this. */
interface Session : AutoCloseable {
    val model: ModelDescriptor
    val backend: Backend
}

/** One inference library (llama.cpp, stable-diffusion.cpp, QNN, ...). */
interface Engine {
    val id: String
    val capabilities: Set<Capability>
    val backends: Set<Backend>

    fun supports(model: ModelDescriptor): Boolean

    suspend fun load(model: ModelDescriptor, config: LoadConfig): Session
}
