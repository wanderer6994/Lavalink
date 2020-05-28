/*
 * Copyright (c) 2017 Frederik Ar. Mikkelsen & NoobLance
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer
import com.sedmelluq.discord.lavaplayer.filter.equalizer.EqualizerFactory
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import io.netty.buffer.ByteBuf
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer.Companion.sendPlayerUpdate
import moe.kyokobot.koe.VoiceConnection
import moe.kyokobot.koe.media.OpusAudioFrameProvider
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Player(val socket: SocketContext, val guildId: Long, audioPlayerManager: AudioPlayerManager) : AudioEventAdapter() {
    private val player: AudioPlayer = audioPlayerManager.createPlayer()
    val audioLossCounter = AudioLossCounter()
    private var lastFrame: AudioFrame? = null
    private var playerUpdateFuture: ScheduledFuture<*>? = null
    private val equalizerFactory = EqualizerFactory()
    private var isEqualizerApplied = false
    private var voiceConnection: VoiceConnection? = null

    /**
     * Either the time of instantiation or the last time a track was ended. Useful for cleanup.
     */
    var lastTimeActive: Instant = Instant.now()
        private set

    fun play(track: AudioTrack?) {
        player.playTrack(track)
        sendPlayerUpdate(socket, this)
    }

    /**
     * Must only be invoked by [SocketContext.destroy]
     */
    fun destroy() {
        player.destroy()
    }

    /**
     * Stops the current track, without destroying the player
     */
    fun stop() {
        player.stopTrack()
    }

    fun setPause(b: Boolean) {
        player.isPaused = b
    }

    fun seekTo(position: Long) {
        val track = player.playingTrack ?: throw IllegalStateException("Can't seek when not playing anything")
        track.position = position
    }

    fun setVolume(volume: Int) {
        player.volume = volume
    }

    val isVoiceConnected: Boolean get() = voiceConnection?.gatewayConnection?.isOpen == true

    fun setBandGain(band: Int, gain: Float) {
        log.debug("Setting band {}'s gain to {}", band, gain)
        equalizerFactory.setGain(band, gain)
        if (gain == 0.0f) {
            if (!isEqualizerApplied) {
                return
            }
            var shouldDisable = true
            for (i in 0 until Equalizer.BAND_COUNT) {
                if (equalizerFactory.getGain(i) != 0.0f) {
                    shouldDisable = false
                }
            }
            if (shouldDisable) {
                player.setFilterFactory(null)
                isEqualizerApplied = false
            }
        } else if (!isEqualizerApplied) {
            player.setFilterFactory(equalizerFactory)
            isEqualizerApplied = true
        }
    }

    val state: JSONObject
        get() {
            val json = JSONObject()
            if (player.playingTrack != null) json.put("position", player.playingTrack.position)
            json.put("time", System.currentTimeMillis())
            return json
        }

    val playingTrack: AudioTrack?
        get() = player.playingTrack

    val isPaused: Boolean
        get() = player.isPaused

    val isPlaying: Boolean
        get() = player.playingTrack != null && !player.isPaused

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        playerUpdateFuture!!.cancel(false)
        lastTimeActive = Instant.now()
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        if (playerUpdateFuture?.isCancelled != false) {
            playerUpdateFuture = socket.playerUpdateService.scheduleAtFixedRate({
                if (socket.sessionPaused) return@scheduleAtFixedRate
                sendPlayerUpdate(socket, this)
            }, 0, 5, TimeUnit.SECONDS)
        }
    }

    fun provideTo(connection: VoiceConnection) {
        voiceConnection = connection
        connection.setAudioSender(Provider(connection))
    }

    private inner class Provider(connection: VoiceConnection?) : OpusAudioFrameProvider(connection) {
        override fun canProvide(): Boolean {
            lastFrame = player.provide()
            return if (lastFrame == null) {
                audioLossCounter.onLoss()
                false
            } else {
                true
            }
        }

        override fun retrieveOpusFrame(buf: ByteBuf) {
            audioLossCounter.onSuccess()
            buf.writeBytes(lastFrame!!.data)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Player::class.java)
    }

    init {
        player.addListener(this)
        player.addListener(EventEmitter(audioPlayerManager, this))
        player.addListener(audioLossCounter)
    }
}