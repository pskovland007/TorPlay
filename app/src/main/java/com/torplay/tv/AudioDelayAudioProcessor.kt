package com.torplay.tv

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import kotlin.math.abs

@UnstableApi
class AudioDelayAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetDelayMs: Long = 0L

    private var sampleRate: Int = 0
    private var bytesPerFrame: Int = 0
    private var lastAppliedTargetMs: Long = 0L
    private var pendingDeltaBytes: Long = 0L

    fun setDelayMs(delayMs: Long) {
        targetDelayMs = delayMs.coerceIn(-3_000L, 3_000L)
    }

    fun getDelayMs(): Long = targetDelayMs

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding) ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.bytesPerFrame <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        lastAppliedTargetMs = targetDelayMs
        pendingDeltaBytes = signedMsToBytes(lastAppliedTargetMs)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bpf = bytesPerFrame
        if (bpf <= 0 || sampleRate <= 0) {
            val rem = inputBuffer.remaining()
            if (rem > 0) {
                val out = replaceOutputBuffer(rem)
                out.put(inputBuffer)
                out.flip()
            }
            return
        }

        val desiredMs = targetDelayMs
        if (desiredMs != lastAppliedTargetMs) {
            val diffMs = desiredMs - lastAppliedTargetMs
            pendingDeltaBytes += signedMsToBytes(diffMs)
            lastAppliedTargetMs = desiredMs
        }

        if (abs(pendingDeltaBytes) < bpf.toLong()) {
            pendingDeltaBytes = 0L
        }

        if (pendingDeltaBytes < 0L) {
            val avail = inputBuffer.remaining()
            val dropLimit = minOf(avail.toLong(), -pendingDeltaBytes).toInt()
            val alignedDrop = (dropLimit / bpf) * bpf
            if (alignedDrop > 0) {
                inputBuffer.position(inputBuffer.position() + alignedDrop)
                pendingDeltaBytes += alignedDrop.toLong()
            }
            if (abs(pendingDeltaBytes) < bpf.toLong()) {
                pendingDeltaBytes = 0L
            }
        }

        val remaining = inputBuffer.remaining()

        if (pendingDeltaBytes > 0L) {
            val maxChunk = ((sampleRate.toLong() / 5L) * bpf.toLong()).coerceAtLeast(bpf.toLong())
            val emitRaw = minOf(pendingDeltaBytes, maxChunk).toInt()
            val alignedSilence = (emitRaw / bpf) * bpf
            if (alignedSilence > 0 || remaining > 0) {
                val out = replaceOutputBuffer(alignedSilence + remaining)
                var z = 0
                while (z < alignedSilence) {
                    out.put(0.toByte())
                    z++
                }
                pendingDeltaBytes -= alignedSilence.toLong()
                if (abs(pendingDeltaBytes) < bpf.toLong()) {
                    pendingDeltaBytes = 0L
                }
                if (remaining > 0) {
                    out.put(inputBuffer)
                }
                out.flip()
            }
            return
        }

        if (remaining > 0) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        }
    }

    override fun onFlush() {
        lastAppliedTargetMs = targetDelayMs
        pendingDeltaBytes = signedMsToBytes(lastAppliedTargetMs)
    }

    override fun onReset() {
        sampleRate = 0
        bytesPerFrame = 0
        lastAppliedTargetMs = 0L
        pendingDeltaBytes = 0L
    }

    private fun signedMsToBytes(ms: Long): Long {
        if (ms == 0L || sampleRate <= 0 || bytesPerFrame <= 0) return 0L
        val frames = (ms * sampleRate.toLong()) / 1000L
        return frames * bytesPerFrame.toLong()
    }
}
