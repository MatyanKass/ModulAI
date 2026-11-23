package com.modulai.app

import com.modulai.core.agent.ResearchDepth
import com.modulai.core.runtime.Effort

enum class Preset(val contextTokens: Int, val effort: Effort) {
    LOW(2048, Effort.LOW),
    MEDIUM(4096, Effort.MEDIUM),
    HIGH(8192, Effort.HIGH),
    CUSTOM(4096, Effort.MEDIUM),
}

data class RunSettings(
    val preset: Preset = Preset.MEDIUM,
    val contextTokens: Int = Preset.MEDIUM.contextTokens,
    val effort: Effort = Preset.MEDIUM.effort,
    val threads: Int = 4,
    val lockMemory: Boolean = false,
    val useMmap: Boolean = true,
    val temperature: Float = 0.7f,
    val systemPrompt: String = "",
    /** Let the chat model search the web, open pages and start research on its own. */
    val webTools: Boolean = false,
    val researchDepth: ResearchDepth = ResearchDepth.NORMAL,
) {
    fun withPreset(p: Preset) =
        if (p == Preset.CUSTOM) copy(preset = p) else copy(preset = p, contextTokens = p.contextTokens, effort = p.effort)

    /** Any manual change to a preset-controlled value switches to the custom preset. */
    fun custom(contextTokens: Int = this.contextTokens, effort: Effort = this.effort) =
        copy(preset = Preset.CUSTOM, contextTokens = contextTokens, effort = effort)

    companion object {
        val CONTEXT_CHOICES = listOf(1024, 2048, 4096, 8192, 16384, 32768)
    }
}
