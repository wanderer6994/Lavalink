package lavalink.server.io

import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant

class CleanupTask(private val context: SocketContext, private val thresholdSecs: Long) : Runnable {
    private val log: Logger = LoggerFactory.getLogger(javaClass)

    override fun run() {
        try {
            runChecked()
        } catch (e: Exception) {
            log.error("Exception while running cleanup", e)
        }
    }

    private fun runChecked() {
        val cutoff = Instant.now().minusSeconds(thresholdSecs)
        val cleaned = context.getPlayers().values.filter {
            it.lastTimeActive.isBefore(cutoff) && it.playingTrack == null && !it.isVoiceConnected
        }.map {
            try {
                context.destroy(it.guildId)
            } catch (e: Exception) {
                log.error("Failed destroying track", e)
            }
            it.guildId
        }

        log.info("Cleaned up {} players with guild IDs {}", cleaned.size, cleaned)
        cleaned.forEach {
            context.send(JSONObject().apply {
                put("op", "cleaned")
                put("guildId", it.toString())
            })
        }
    }
}