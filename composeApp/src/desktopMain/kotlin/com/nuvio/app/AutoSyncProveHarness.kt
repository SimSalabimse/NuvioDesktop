package com.nuvio.app

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.io.File
import kotlin.system.exitProcess

/**
 * Headless Auto Sync prove harness for automated testing without GUI/Accessibility.
 * 
 * Usage:
 *   NUVIO_AUTOSYNC_PROVE=1 ./NuvioDesktop.app/Contents/MacOS/NuvioDesktop
 *   NUVIO_AUTOSYNC_PROVE=/path/to/video.mp4 ./NuvioDesktop.app/Contents/MacOS/NuvioDesktop
 * 
 * Tests audio capture (startAudioEnergyCapture → stopAudioEnergyCapture) with real MPV playback.
 * Logs results with unique markers for binary verification.
 */
object AutoSyncProveHarness {
    private const val DEFAULT_MEDIA = "test-media/autosync/autosync-fixture.mp4"
    private const val CAPTURE_DURATION_MS = 30000L
    private const val AUDIO_START_DELAY_MS = 2000L
    
    fun checkAndRun(): Boolean {
        val proveEnv = System.getenv("NUVIO_AUTOSYNC_PROVE") ?: return false
        
        println("========================================")
        println("[AutoSyncProve] HEADLESS AUTO SYNC PROVE HARNESS [v5_DIRECT_TAP_PROVE]")
        println("[AutoSyncProve] Tip: 3c704252+ (v5 direct tap architecture)")
        println("========================================")
        
        val mediaPath = when {
            proveEnv == "1" || proveEnv.isBlank() -> DEFAULT_MEDIA
            else -> proveEnv
        }
        
        println("[AutoSyncProve] Media path: $mediaPath")
        
        val mediaFile = File(mediaPath)
        if (!mediaFile.exists()) {
            println("[AutoSyncProve] ❌ FATAL: Media file not found: ${mediaFile.absolutePath}")
            println("[AutoSyncProve] Searched: ${mediaFile.absolutePath}")
            println("[AutoSyncProve] Current dir: ${System.getProperty("user.dir")}")
            println("[AutoSyncProve] Tip: Place fixture at test-media/autosync/autosync-fixture.mp4")
            exitProcess(1)
        }
        
        println("[AutoSyncProve] Found media: ${mediaFile.absolutePath} (${mediaFile.length()} bytes)")
        
        runProveTest(mediaFile.absolutePath)
        return true
    }
    
    private fun runProveTest(mediaPath: String) {
        println("[AutoSyncProve] Initializing native player bridge...")
        
        // Initialize MPV/player bridge
        val initResult = runCatching {
            NativePlayerBridge.load()
        }
        
        if (initResult.isFailure) {
            println("[AutoSyncProve] ❌ FATAL: Failed to load native player bridge")
            initResult.exceptionOrNull()?.printStackTrace()
            exitProcess(1)
        }
        
        println("[AutoSyncProve] Creating player instance...")
        
        // Create player with minimal config (no UI host)
        val handle = try {
            NativePlayerBridge.createPlayer(
                sourceUrl = "file://$mediaPath",
                headerLines = emptyArray(),
                playWhenReady = true,
                initialPositionMs = 0,
                controlsUrl = "",  // No controls UI needed
                decoderPriority = 0
            )
        } catch (e: Exception) {
            println("[AutoSyncProve] ❌ FATAL: Failed to create player")
            e.printStackTrace()
            exitProcess(1)
        }
        
        if (handle == 0L) {
            println("[AutoSyncProve] ❌ FATAL: createPlayer returned null handle")
            exitProcess(1)
        }
        
        println("[AutoSyncProve] Player created: handle=$handle")
        println("[AutoSyncProve] Waiting ${AUDIO_START_DELAY_MS}ms for audio to start...")
        
        Thread.sleep(AUDIO_START_DELAY_MS)
        
        println("[AutoSyncProve] ========================================")
        println("[AutoSyncProve] STARTING AUDIO ENERGY CAPTURE [v5_DIRECT_TAP_PROVE]")
        println("[AutoSyncProve] Duration: ${CAPTURE_DURATION_MS}ms (~${CAPTURE_DURATION_MS / 1000}s)")
        println("[AutoSyncProve] ========================================")
        
        val captureStartTime = System.currentTimeMillis()
        
        // Call the same native method as Auto Sync UI
        try {
            NativePlayerBridge.startAudioEnergyCapture(handle, captureStartTime)
            println("[AutoSyncProve] startAudioEnergyCapture called successfully")
        } catch (e: Exception) {
            println("[AutoSyncProve] ❌ FATAL: startAudioEnergyCapture threw exception")
            e.printStackTrace()
            NativePlayerBridge.destroyPlayer(handle)
            exitProcess(1)
        }
        
        // Wait for capture duration
        println("[AutoSyncProve] Capturing audio...")
        val checkInterval = 5000L
        var elapsed = 0L
        
        while (elapsed < CAPTURE_DURATION_MS) {
            Thread.sleep(checkInterval)
            elapsed += checkInterval
            val progress = (elapsed * 100 / CAPTURE_DURATION_MS).toInt()
            println("[AutoSyncProve] Progress: ${elapsed}ms / ${CAPTURE_DURATION_MS}ms ($progress%)")
        }
        
        println("[AutoSyncProve] ========================================")
        println("[AutoSyncProve] STOPPING AUDIO ENERGY CAPTURE [v5_DIRECT_TAP_PROVE]")
        println("[AutoSyncProve] ========================================")
        
        // Stop capture and get results (JSON string with samples)
        val resultJson = try {
            NativePlayerBridge.stopAudioEnergyCapture(handle)
        } catch (e: Exception) {
            println("[AutoSyncProve] ❌ WARNING: stopAudioEnergyCapture threw exception")
            e.printStackTrace()
            "{}"
        }
        
        println("[AutoSyncProve] Raw result JSON length: ${resultJson.length} chars")
        
        // Parse result to get sample count (simple JSON parsing)
        val sampleCount = extractSampleCount(resultJson)
        
        println("[AutoSyncProve] ========================================")
        println("[AutoSyncProve] RESULTS [v5_DIRECT_TAP_PROVE]")
        println("[AutoSyncProve] ========================================")
        println("[AutoSyncProve] Sample count: $sampleCount")
        println("[AutoSyncProve] Expected: N > 0 (varying energy samples)")
        
        if (sampleCount > 0) {
            println("[AutoSyncProve] ✅✅✅ SUCCESS - Got $sampleCount non-zero samples")
            println("[AutoSyncProve] IOProc received audio data (not empty buffers)")
            println("[AutoSyncProve] Process tap is capturing mpv audio output")
        } else {
            println("[AutoSyncProve] ❌❌❌ FAILURE - Got 0 samples (empty IOProc buffers)")
            println("[AutoSyncProve] Check Console.app for IOProc logs:")
            println("[AutoSyncProve]   - Look for [v5_DIRECT_TAP] and [v5_TAP_READ] markers")
            println("[AutoSyncProve]   - Check 'Called but no input data' vs 'Captured audio'")
            println("[AutoSyncProve]   - Verify tap format query succeeded")
        }
        
        println("[AutoSyncProve] Full result JSON:")
        println(resultJson)
        println("[AutoSyncProve] ========================================")
        
        // Cleanup
        println("[AutoSyncProve] Cleaning up player...")
        NativePlayerBridge.destroyPlayer(handle)
        
        println("[AutoSyncProve] Test complete, exiting.")
        
        exitProcess(if (sampleCount > 0) 0 else 1)
    }
    
    private fun extractSampleCount(json: String): Int {
        // Simple regex to extract sample count from JSON like:
        // {"samples":[...],"startTimeMs":123,...}
        // Just count array entries or look for "samples" array length
        
        val samplesMatch = """samples"\s*:\s*\[([^\]]*)\]""".toRegex().find(json)
        if (samplesMatch != null) {
            val samplesContent = samplesMatch.groupValues[1]
            if (samplesContent.isBlank()) return 0
            // Count objects by counting opening braces
            return samplesContent.count { it == '{' }
        }
        
        return 0
    }
}
