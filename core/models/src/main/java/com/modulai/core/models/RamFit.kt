package com.modulai.core.models

enum class RamFit { FITS, TIGHT, TOO_LARGE }

/** Context, Android and the app itself, on top of the weights. */
private const val OVERHEAD_BYTES = 1_500_000_000L
private const val GIB = 1L shl 30

/**
 * Whether a model of [sizeBytes] can run on a device with [totalRamBytes] of memory.
 * [minRamGb] is the catalog's own requirement in marketing gigabytes; 0 means unknown.
 */
fun ramFit(sizeBytes: Long, totalRamBytes: Long, minRamGb: Int = 0): RamFit {
    // The kernel reports less than the phone is sold with (about 7.4 GiB on an "8 GB" one),
    // so round up before comparing with the catalog's figure.
    val nominalGb = (totalRamBytes + GIB - 1) / GIB
    val need = sizeBytes + OVERHEAD_BYTES
    return when {
        need > totalRamBytes || minRamGb > nominalGb -> RamFit.TOO_LARGE
        need > totalRamBytes * 0.7 -> RamFit.TIGHT
        else -> RamFit.FITS
    }
}
