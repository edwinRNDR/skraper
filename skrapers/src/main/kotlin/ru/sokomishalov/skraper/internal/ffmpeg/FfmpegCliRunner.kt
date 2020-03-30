package ru.sokomishalov.skraper.internal.ffmpeg

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration


/**
 * @author sokomishalov
 */
internal object FfmpegCliRunner {

    private const val PROCESS_LIVENESS_CHECK_INTERVAL_MS = 50L

    init {
        checkFfmpegExistence()
    }

    suspend fun run(cmd: String, timeout: Duration = Duration.ofHours(1)): Int {
        val process = Runtime
                .getRuntime()
                .exec("ffmpeg $cmd")

        withTimeout(timeout.toMillis()) {
            while (process.isAlive) {
                delay(PROCESS_LIVENESS_CHECK_INTERVAL_MS)
            }
        }

        return process.exitValue()
    }

    private fun checkFfmpegExistence() {
        runBlocking {
            run(cmd = "-version", timeout = Duration.ofSeconds(1)).let { code ->
                if (code != 0) System.err.println("`ffmpeg` is not present in OS, some functions may work unreliably")
            }
        }
    }
}