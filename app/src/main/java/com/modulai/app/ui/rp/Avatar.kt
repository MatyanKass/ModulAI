package com.modulai.app.ui.rp

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.modulai.core.characters.CharacterCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal const val AVATAR_SMALL_PX = 384
internal const val AVATAR_LARGE_PX = 768

/** Decoded avatars, so that scrolling the grid back does not decode and flash them again. */
private val cache = object : LruCache<String, ImageBitmap>(24 shl 20) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

internal fun CharacterCard.avatarUri(): Uri? = avatarPath?.let { Uri.fromFile(File(it)) }

/**
 * The image at [source], or the first letter of [name] while there is none.
 * [version] must change when the file behind an unchanged [source] is replaced.
 */
@Composable
internal fun Avatar(source: Uri?, version: Int, name: String, sizePx: Int, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val key = "$source#$version#$sizePx"
    val image by produceState(initialValue = source?.let { cache.get(key) }, key) {
        value = when (source) {
            null -> null
            else -> cache.get(key)
                ?: withContext(Dispatchers.IO) { decode(resolver, source, sizePx) }?.also { cache.put(key, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            // Cards are portraits with the face near the top, so crop from the top rather than the middle.
            Image(bitmap, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
        } else {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Decodes at a power-of-two fraction of the original size: the short side stays at least [sizePx] when it can. */
private fun decode(resolver: ContentResolver, source: Uri, sizePx: Int): ImageBitmap? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            val short = minOf(bounds.outWidth, bounds.outHeight)
            val long = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            // The second condition keeps extreme aspect ratios from decoding into a huge bitmap.
            while (short / (sample * 2) >= sizePx || long / sample > sizePx * 8) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
