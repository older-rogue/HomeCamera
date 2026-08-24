package com.zx.homecamera.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmGainApplierTest {
    @Test
    fun amplifiesPositiveAndNegativeSamples() {
        // 小端 16 位：+1000 = E8 03，-1000 = 18 FC
        val pcm = byteArrayOf(0xE8.toByte(), 0x03, 0x18.toByte(), 0xFC.toByte())

        PcmGainApplier.apply(pcm, pcm.size, gain = 2.0f)

        assertEquals(2000, pcm.readSample(0))
        assertEquals(-2000, pcm.readSample(1))
    }

    @Test
    fun clipsToShortRangeInsteadOfWrapping() {
        // +20000 * 3 = 60000 > 32767，应削波到 32767 而非回绕
        val positive = sampleBytes(20_000)
        PcmGainApplier.apply(positive, positive.size, gain = 3.0f)
        assertEquals(32_767, positive.readSample(0))

        // -20000 * 3 = -60000 < -32768，应削波到 -32768
        val negative = sampleBytes(-20_000)
        PcmGainApplier.apply(negative, negative.size, gain = 3.0f)
        assertEquals(-32_768, negative.readSample(0))
    }

    @Test
    fun unityGainIsNoOp() {
        val pcm = sampleBytes(12_345)
        PcmGainApplier.apply(pcm, pcm.size, gain = 1.0f)
        assertEquals(12_345, pcm.readSample(0))
    }

    @Test
    fun zeroGainSilencesAudio() {
        val pcm = sampleBytes(-12_345)
        PcmGainApplier.apply(pcm, pcm.size, gain = 0.0f)
        assertEquals(0, pcm.readSample(0))
    }

    @Test
    fun oddLengthIgnoresTrailingByte() {
        val pcm = byteArrayOf(0xE8.toByte(), 0x03, 0x7F)
        PcmGainApplier.apply(pcm, pcm.size, gain = 2.0f)
        assertEquals(2000, pcm.readSample(0))
        assertEquals(0x7F, pcm[2].toInt())
    }

    @Test
    fun lengthLimitsTouchedBytes() {
        // 只放大前 2 字节（1 个采样），后 2 字节不动
        val pcm = byteArrayOf(0xE8.toByte(), 0x03, 0x18.toByte(), 0xFC.toByte())

        PcmGainApplier.apply(pcm, length = 2, gain = 2.0f)

        assertEquals(2000, pcm.readSample(0))
        assertEquals(-1000, pcm.readSample(1))
    }

    private fun sampleBytes(sample: Int): ByteArray = byteArrayOf(
        (sample and 0xFF).toByte(),
        ((sample shr 8) and 0xFF).toByte(),
    )

    private fun ByteArray.readSample(index: Int): Int =
        (this[index * 2 + 1].toInt() shl 8) or (this[index * 2].toInt() and 0xFF)
}
