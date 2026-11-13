package com.joyos.launcher.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes, scales, caches and extracts vibrant ambient colors from album artwork
 * across MediaSession, AVRCP intents, local MP3/FLAC tags and optional online lookup.
 */
object JoyMediaArtworkHelper {

    private const val TAG = "JoyMediaArtworkHelper"
    private const val MAX_CACHE_ENTRIES = 48

    data class ArtworkResult(
        val dataUri: String,
        val color1: String,
        val color2: String
    )

    private val remoteCache = ConcurrentHashMap<String, ArtworkResult>()
    private val failedKeys = ConcurrentHashMap<String, Long>()

    fun processBitmapToArtwork(
        bitmap: Bitmap?,
        maxDim: Int = 320,
        quality: Int = 80
    ): ArtworkResult? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }
        var scaled: Bitmap? = null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val targetBitmap = if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / max(w, h).toFloat()
                val nw = max(1, (w * scale).roundToInt())
                val nh = max(1, (h * scale).roundToInt())
                scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                scaled
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), baos)
            val jpegBytes = baos.toByteArray()
            if (jpegBytes.isEmpty()) return null

            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val (c1, c2) = extractDominantColors(targetBitmap)
            ArtworkResult(
                dataUri = "data:image/jpeg;base64,$b64",
                color1 = c1,
                color2 = c2
            )
        } catch (e: Throwable) {
            Log.w(TAG, "processBitmapToArtwork warning: ${e.message}")
            null
        } finally {
            try {
                if (scaled != null && scaled !== bitmap && !scaled.isRecycled) {
                    scaled.recycle()
                }
            } catch (_: Throwable) {}
        }
    }

    fun processBytesToArtwork(
        bytes: ByteArray?,
        maxDim: Int = 260,
        quality: Int = 78
    ): ArtworkResult? {
        if (bytes == null || bytes.isEmpty()) return null
        var decoded: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            processBitmapToArtwork(decoded, maxDim = maxDim, quality = quality)
        } catch (e: Throwable) {
            Log.w(TAG, "processBytesToArtwork warning: ${e.message}")
            null
        } finally {
            try {
                if (decoded != null && !decoded.isRecycled) decoded.recycle()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Extracts artwork Bitmap/local-URI from Android MediaMetadata, plus optional http URL hint
     * when metadata exposes a remote image URL rather than an embedded Bitmap.
     */
    fun processMetadataArtwork(
        context: Context,
        meta: MediaMetadata?
    ): Pair<ArtworkResult?, String?> {
        if (meta == null) return Pair(null, null)

        val bitmapKeys = arrayOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        for (key in bitmapKeys) {
            val bmp = try {
                meta.getBitmap(key)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) {
                val res = processBitmapToArtwork(bmp)
                if (res != null) return Pair(res, null)
            }
        }

        val uriKeys = arrayOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        )
        var httpHint: String? = null
        for (key in uriKeys) {
            val rawUri = try {
                meta.getString(key)
            } catch (_: Throwable) {
                null
            }?.trim() ?: continue
            if (rawUri.isEmpty()) continue

            if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
                if (httpHint == null) httpHint = rawUri
                continue
            }

            if (rawUri.startsWith("content://") || rawUri.startsWith("file://")) {
                val bytes = try {
                    context.contentResolver.openInputStream(Uri.parse(rawUri))?.use { it.readBytes() }
                } catch (_: Throwable) {
                    null
                }
                val res = processBytesToArtwork(bytes)
                if (res != null) return Pair(res, null)
            }
        }

        return Pair(null, httpHint)
    }

    /**
     * Synchronous remote artwork lookup (run on Dispatchers.IO).
     * Checks in-memory cache, optional artUrlHint, and falls back to iTunes public Search API.
     */
    fun fetchArtworkForTrack(
        title: String,
        artist: String,
        urlHint: String? = null
    ): ArtworkResult? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty()) return null

        val cacheKey = "${cleanTitle.lowercase()}|${cleanArtist.lowercase()}"
        remoteCache[cacheKey]?.let { return it }

        val lastFail = failedKeys[cacheKey]
        if (lastFail != null && System.currentTimeMillis() - lastFail < 5 * 60_000L && urlHint.isNullOrBlank()) {
            return null
        }

        // 1. Try direct HTTP hint if provided by MediaMetadata
        if (!urlHint.isNullOrBlank() && (urlHint.startsWith("http://") || urlHint.startsWith("https://"))) {
            val directBytes = downloadBytes(urlHint, timeoutMs = 4500)
            val directRes = processBytesToArtwork(directBytes, maxDim = 300, quality = 80)
            if (directRes != null) {
                putInCache(cacheKey, directRes)
                return directRes
            }
        }

        // 2. Skip generic placeholder labels
        val lowerTitle = cleanTitle.lowercase()
        if (lowerTitle.contains("esperando") || lowerTitle == "audio bluetooth" || lowerTitle == "audio del sistema") {
            return null
        }

        // 3. Query public iTunes artwork endpoint (no API key required, works when online)
        return try {
            val usableArtist = if (
                cleanArtist.equals("Bluetooth Audio", ignoreCase = true) ||
                cleanArtist.equals("Desconocido", ignoreCase = true) ||
                cleanArtist.contains("Bluetooth", ignoreCase = true)
            ) "" else cleanArtist
            val queryText = "$usableArtist $cleanTitle".trim()
            val encoded = URLEncoder.encode(queryText, "UTF-8")
            val apiUrl = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=1"

            val jsonBytes = downloadBytes(apiUrl, timeoutMs = 4000) ?: run {
                failedKeys[cacheKey] = System.currentTimeMillis()
                return null
            }
            val root = JSONObject(String(jsonBytes, Charsets.UTF_8))
            val results = root.optJSONArray("results")
            if (results == null || results.length() == 0) {
                failedKeys[cacheKey] = System.currentTimeMillis()
                return null
            }

            val art100 = results.getJSONObject(0).optString("artworkUrl100", "").trim()
            if (art100.isEmpty()) {
                failedKeys[cacheKey] = System.currentTimeMillis()
                return null
            }

            val art300 = art100.replace("100x100bb", "300x300bb")
            val imgBytes = downloadBytes(art300, timeoutMs = 4500) ?: downloadBytes(art100, timeoutMs = 4000)
            val res = processBytesToArtwork(imgBytes, maxDim = 300, quality = 80)
            if (res != null) {
                putInCache(cacheKey, res)
            } else {
                failedKeys[cacheKey] = System.currentTimeMillis()
            }
            res
        } catch (e: Throwable) {
            Log.w(TAG, "fetchArtworkForTrack skipped: ${e.message}")
            failedKeys[cacheKey] = System.currentTimeMillis()
            null
        }
    }

    private fun putInCache(key: String, value: ArtworkResult) {
        if (remoteCache.size >= MAX_CACHE_ENTRIES) {
            val firstKey = remoteCache.keys().let { if (it.hasMoreElements()) it.nextElement() else null }
            if (firstKey != null) remoteCache.remove(firstKey)
        }
        remoteCache[key] = value
        failedKeys.remove(key)
    }

    private fun downloadBytes(urlStr: String, timeoutMs: Int = 4000): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JoyOS-Automotive-Launcher/1.1")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > 2 * 1024 * 1024) return null // Guard against unexpectedly huge streams
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, targetMax: Int): Int {
        var sample = 1
        val maxSide = max(width, height)
        if (maxSide > targetMax) {
            val half = maxSide / 2
            while ((half / sample) >= targetMax) {
                sample *= 2
            }
        }
        return max(1, sample)
    }

    /**
     * Extracts two harmonious vibrant ambient hex colors from a scaled bitmap
     * by quantizing hue/saturation buckets on a 16x16 grid.
     */
    fun extractDominantColors(bitmap: Bitmap): Pair<String, String> {
        var grid: Bitmap? = null
        return try {
            grid = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
            val hueBuckets = FloatArray(12)
            val bucketR = FloatArray(12)
            val bucketG = FloatArray(12)
            val bucketB = FloatArray(12)
            val bucketWeight = FloatArray(12)
            val hsv = FloatArray(3)

            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val px = grid.getPixel(x, y)
                    val a = Color.alpha(px)
                    if (a < 128) continue
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)
                    Color.RGBToHSV(r, g, b, hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    // Down-weight near-black, pure white, or washed-out neutral pixels
                    if (v < 0.12f || (v > 0.94f && s < 0.14f)) continue
                    val weight = (0.25f + s * 1.8f) * (if (v in 0.22f..0.88f) 1.25f else 0.7f)
                    val bucket = min(11, ((hsv[0] / 30f).toInt()).coerceIn(0, 11))
                    hueBuckets[bucket] += weight
                    bucketR[bucket] += r * weight
                    bucketG[bucket] += g * weight
                    bucketB[bucket] += b * weight
                    bucketWeight[bucket] += weight
                }
            }

            var bestIdx = -1
            var bestScore = 0f
            for (i in 0 until 12) {
                if (hueBuckets[i] > bestScore) {
                    bestScore = hueBuckets[i]
                    bestIdx = i
                }
            }

            if (bestIdx == -1 || bucketWeight[bestIdx] <= 0f) {
                return Pair("#06b6d4", "#3b82f6")
            }

            val c1Int = avgBucketColor(bucketR[bestIdx], bucketG[bestIdx], bucketB[bestIdx], bucketWeight[bestIdx])

            var secondIdx = -1
            var secondScore = 0f
            for (i in 0 until 12) {
                if (i == bestIdx) continue
                val dist = minOf(kotlin.math.abs(i - bestIdx), 12 - kotlin.math.abs(i - bestIdx))
                if (dist >= 2 && hueBuckets[i] > secondScore) {
                    secondScore = hueBuckets[i]
                    secondIdx = i
                }
            }

            val c2Int = if (secondIdx != -1 && secondScore > bestScore * 0.22f) {
                avgBucketColor(bucketR[secondIdx], bucketG[secondIdx], bucketB[secondIdx], bucketWeight[secondIdx])
            } else {
                // Shift hue by 38 degrees for a smooth dual-orb gradient on monochrome art
                Color.colorToHSV(c1Int, hsv)
                hsv[0] = (hsv[0] + 38f) % 360f
                hsv[1] = max(0.55f, hsv[1])
                hsv[2] = max(0.62f, min(0.92f, hsv[2]))
                Color.HSVToColor(hsv)
            }

            Pair(toHex(c1Int), toHex(c2Int))
        } catch (_: Throwable) {
            Pair("#06b6d4", "#3b82f6")
        } finally {
            try {
                if (grid != null && grid !== bitmap && !grid.isRecycled) grid.recycle()
            } catch (_: Throwable) {}
        }
    }

    private fun avgBucketColor(sumR: Float, sumG: Float, sumB: Float, weight: Float): Int {
        val r = (sumR / weight).roundToInt().coerceIn(0, 255)
        val g = (sumG / weight).roundToInt().coerceIn(0, 255)
        val b = (sumB / weight).roundToInt().coerceIn(0, 255)
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        // Ensure ambient blur orbs remain rich enough for dark and light themes
        hsv[1] = max(0.42f, min(0.95f, hsv[1] * 1.15f))
        hsv[2] = max(0.56f, min(0.92f, hsv[2]))
        return Color.HSVToColor(hsv)
    }

    private fun toHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }
}
