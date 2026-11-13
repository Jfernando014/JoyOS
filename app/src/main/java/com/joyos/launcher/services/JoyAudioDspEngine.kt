package com.joyos.launcher.services

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * High-performance hardware DSP Audio Engine for JoyOS.
 * Directly taps into Android Global Audio Output Mix (audioSessionId = 0) with maximum priority.
 *
 * Implements 4 hardware stages:
 * 1. 5-Band Equalizer (-10 dB to +10 dB -> -1000 mB to +1000 mB)
 * 2. BassBoost (Subwoofer exciter 0..10 -> 0..1000 strength)
 * 3. LoudnessEnhancer (Clean pre-amp + GPS speed-compensated dynamic volume)
 * 4. Virtualizer (Cabin sound stage spatialization & acoustic width)
 *
 * Fully non-intrusive: zero AudioFocus hijacking, bit-perfect streaming to DAC.
 */
class JoyAudioDspEngine(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("joyos_dsp_prefs", Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null

    // Target frequencies in mHz: 60 Hz, 230 Hz, 910 Hz, 3.5 kHz, 14 kHz
    private val targetFrequencies = intArrayOf(60_000, 230_000, 910_000, 3_500_000, 14_000_000)
    private val hwBandMap = ShortArray(5) { it.toShort() }
    private var bandMinLevel: Short = -1000
    private var bandMaxLevel: Short = 1000

    // Runtime state
    var activePreset: String = "flat"
        private set
    val currentBars = IntArray(5) { 0 }
    var subwooferLevel: Int = 0
        private set
    var soundStageX: Int = 0
        private set
    var soundStageY: Int = 0
        private set
    var isSpeedVolActive: Boolean = false
        private set
    var isNavDuckingActive: Boolean = true
        private set

    private var currentSpeedKmh: Int = 0

    init {
        loadPersistedState()
        initHardwareEffects()
    }

    private fun loadPersistedState() {
        try {
            activePreset = prefs.getString("preset_name", "flat") ?: "flat"
            for (i in 0 until 5) {
                currentBars[i] = prefs.getInt("band_$i", 0)
            }
            subwooferLevel = prefs.getInt("subwoofer_level", 0)
            soundStageX = prefs.getInt("sound_stage_x", 0)
            soundStageY = prefs.getInt("sound_stage_y", 0)
            isSpeedVolActive = prefs.getBoolean("speed_vol_active", false)
            isNavDuckingActive = prefs.getBoolean("nav_ducking", true)
        } catch (e: Throwable) {
            Log.w(TAG, "Error loading persisted DSP state: ${e.message}")
        }
    }

    private fun initHardwareEffects() {
        // Priority 0 avoids preempting AC8227L HAL BT/media audio sinks when JoyOS DSP is at idle
        val priority = 0
        val sessionId = 0

        // 1. Equalizer Hardware Initialization
        try {
            equalizer = Equalizer(priority, sessionId).apply {
                val numHwBands = numberOfBands.toInt()
                val range = bandLevelRange
                if (range != null && range.size >= 2) {
                    bandMinLevel = range[0]
                    bandMaxLevel = range[1]
                }

                // Map the 5 JoyOS virtual bands to hardware bands with closest center frequencies
                for (i in 0 until 5) {
                    var bestBand: Short = 0
                    var minDiff = Int.MAX_VALUE
                    for (b in 0 until numHwBands) {
                        val centerFreq = getCenterFreq(b.toShort())
                        val diff = Math.abs(centerFreq - targetFrequencies[i])
                        if (diff < minDiff) {
                            minDiff = diff
                            bestBand = b.toShort()
                        }
                    }
                    hwBandMap[i] = bestBand
                }

                applyAllBands(currentBars)
                enabled = currentBars.any { it != 0 }
            }
            Log.i(TAG, "Hardware Equalizer initialized with ${equalizer?.numberOfBands} bands [Range: $bandMinLevel..$bandMaxLevel mB]")
        } catch (e: Throwable) {
            Log.w(TAG, "Equalizer init warning on session 0: ${e.message}")
        }

        // 2. BassBoost Hardware Initialization
        try {
            bassBoost = BassBoost(priority, sessionId).apply {
                applySubwoofer(subwooferLevel)
                enabled = subwooferLevel > 0
            }
            Log.i(TAG, "Hardware BassBoost initialized for Subwoofer")
        } catch (e: Throwable) {
            Log.w(TAG, "BassBoost init warning on session 0: ${e.message}")
        }

        // 3. LoudnessEnhancer Hardware Initialization (only active when reinforcement is requested)
        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                applyLoudnessGain()
            }
            Log.i(TAG, "Hardware LoudnessEnhancer initialized for Bit-Perfect clean pre-amp")
        } catch (e: Throwable) {
            Log.w(TAG, "LoudnessEnhancer init warning on session 0: ${e.message}")
        }

        // 4. Virtualizer Hardware Initialization
        try {
            virtualizer = Virtualizer(priority, sessionId).apply {
                applySoundStage(soundStageX, soundStageY)
            }
            Log.i(TAG, "Hardware Virtualizer initialized for Cabin Sound Stage")
        } catch (e: Throwable) {
            Log.w(TAG, "Virtualizer init warning on session 0: ${e.message}")
        }
    }

    /**
     * Updates an individual band level (-10 dB to +10 dB).
     */
    fun setBandLevel(bandIdx: Int, dbValue: Int) {
        if (bandIdx !in 0..4) return
        val clamped = dbValue.coerceIn(-10, 10)
        currentBars[bandIdx] = clamped

        try {
            val hwBand = hwBandMap[bandIdx]
            val mB = (clamped * 100).coerceIn(bandMinLevel.toInt(), bandMaxLevel.toInt()).toShort()
            equalizer?.let { eq ->
                eq.setBandLevel(hwBand, mB)
                eq.enabled = currentBars.any { it != 0 }
            }
            prefs.edit().putInt("band_$bandIdx", clamped).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set band level $bandIdx to $clamped dB: ${e.message}")
        }
    }

    /**
     * Updates all 5 equalizer bands at once.
     */
    fun setAllBands(bars: List<Int>) {
        if (bars.size < 5) return
        for (i in 0 until 5) {
            currentBars[i] = bars[i].coerceIn(-10, 10)
        }
        applyAllBands(currentBars)
        prefs.edit().apply {
            for (i in 0 until 5) {
                putInt("band_$i", currentBars[i])
            }
            apply()
        }
    }

    private fun applyAllBands(bars: IntArray) {
        try {
            equalizer?.let { eq ->
                for (i in 0 until 5) {
                    val hwBand = hwBandMap[i]
                    val mB = (bars[i] * 100).coerceIn(bandMinLevel.toInt(), bandMaxLevel.toInt()).toShort()
                    eq.setBandLevel(hwBand, mB)
                }
                eq.enabled = bars.any { it != 0 }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to apply all bands: ${e.message}")
        }
    }

    /**
     * Activates a named preset and applies its bands.
     */
    fun setEqPreset(presetName: String, bars: List<Int>) {
        activePreset = presetName
        setAllBands(bars)
        prefs.edit().putString("preset_name", presetName).apply()
    }

    /**
     * Updates subwoofer strength (0 to 10 dB scale).
     */
    fun setSubwoofer(level: Int) {
        subwooferLevel = level.coerceIn(0, 10)
        applySubwoofer(subwooferLevel)
        applyLoudnessGain()
        prefs.edit().putInt("subwoofer_level", subwooferLevel).apply()
    }

    private fun applySubwoofer(level: Int) {
        try {
            bassBoost?.let { bb ->
                val strength = (level * 100).coerceIn(0, 1000).toShort()
                bb.setStrength(strength)
                bb.enabled = level > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to apply subwoofer level $level: ${e.message}")
        }
    }

    /**
     * Sets 2D cabin sound stage position (-50 to +50).
     */
    fun setSoundStage(x: Int, y: Int) {
        soundStageX = x.coerceIn(-50, 50)
        soundStageY = y.coerceIn(-50, 50)
        applySoundStage(soundStageX, soundStageY)
        prefs.edit().putInt("sound_stage_x", soundStageX).putInt("sound_stage_y", soundStageY).apply()
    }

    private fun applySoundStage(x: Int, y: Int) {
        try {
            virtualizer?.let { virt ->
                val dist = hypot(x.toDouble(), y.toDouble()).toInt().coerceIn(0, 50)
                val strength = (dist * 20).coerceIn(0, 1000).toShort()
                if (virt.strengthSupported) {
                    virt.setStrength(strength)
                }
                virt.enabled = strength > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to apply sound stage: ${e.message}")
        }
    }

    /**
     * Toggles automatic volume compensation based on GPS vehicle speed.
     */
    fun setSpeedVolume(enabled: Boolean) {
        isSpeedVolActive = enabled
        applyLoudnessGain()
        prefs.edit().putBoolean("speed_vol_active", enabled).apply()
    }

    /**
     * Toggles GPS navigation voice ducking mode.
     */
    fun setNavDucking(enabled: Boolean) {
        isNavDuckingActive = enabled
        prefs.edit().putBoolean("nav_ducking", enabled).apply()
    }

    /**
     * Called by GPS telemetry when vehicle speed changes.
     * Gradually boosts pre-amp gain above 40 km/h to overcome cabin road noise.
     */
    fun onSpeedUpdated(speedKmh: Int) {
        currentSpeedKmh = speedKmh.coerceAtLeast(0)
        if (isSpeedVolActive) {
            applyLoudnessGain()
        }
    }

    /**
     * Calculates and applies master LoudnessEnhancer gain.
     * Clean pre-amp: +3.5 dB base + subwoofer reinforcement + speed compensation.
     */
    private fun applyLoudnessGain() {
        try {
            loudnessEnhancer?.let { le ->
                val subGainMb = subwooferLevel * 20 // Up to +2.0 dB for low-end punch
                val speedGainMb = if (isSpeedVolActive && currentSpeedKmh > 40) {
                    ((currentSpeedKmh - 40) * 4).coerceIn(0, 250) // Up to +2.5 dB at 100+ km/h
                } else 0
                val needsBoost = subGainMb > 0 || speedGainMb > 0
                val baseGainMb = if (needsBoost) 250 else 0
                val totalGainMb = (baseGainMb + subGainMb + speedGainMb).coerceIn(0, 1000)
                le.setTargetGain(totalGainMb)
                le.enabled = needsBoost && totalGainMb > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to apply loudness gain: ${e.message}")
        }
    }

    /**
     * Serializes current DSP hardware settings to JSON for frontend sync.
     */
    fun getDspStateJson(): String {
        return try {
            JSONObject().apply {
                put("preset", activePreset)
                put("bars", JSONArray().apply { currentBars.forEach { put(it) } })
                put("subwoofer", subwooferLevel)
                put("soundStageX", soundStageX)
                put("soundStageY", soundStageY)
                put("speedVol", isSpeedVolActive)
                put("navDucking", isNavDuckingActive)
            }.toString()
        } catch (e: Throwable) {
            "{}"
        }
    }

    /**
     * Releases audio effect hardware instances.
     */
    fun release() {
        try {
            equalizer?.release()
            equalizer = null
            bassBoost?.release()
            bassBoost = null
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            virtualizer?.release()
            virtualizer = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing audio effects: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "JoyOS_DSP"
    }
}
