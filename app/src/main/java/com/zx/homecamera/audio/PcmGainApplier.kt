package com.zx.homecamera.audio

/**
 * 对 16 位小端单声道/多声道交错 PCM 施加固定软件增益。
 *
 * 采集链路使用 [android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION] 音源并启用
 * AEC/NS（防止两台设备靠近时啸叫），平台语音处理链对环境音的电平衰减明显，
 * 导致录像音轨与实时播放声音都偏小。在 PCM 送入 AAC 编码器前统一放大，
 * 录制文件与实时流共用同一链路，一处增益两边受益。
 *
 * 纯函数实现（原地修改字节数组），不依赖 Android API，可在 JVM 单测中验证。
 */
object PcmGainApplier {
    /**
     * 就地放大 [pcm] 前 [length] 字节（即 length/2 个 16 位采样）。
     * 增益为 1.0 或不足一个采样时直接返回；溢出按削波处理（clamp 到 Short 范围），
     * 避免回绕产生爆音。奇数长度时末尾单个字节忽略。
     */
    fun apply(pcm: ByteArray, length: Int, gain: Float) {
        if (gain == 1.0f || length < 2) return
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val index = i * 2
            // 小端：低字节在前，高字节符号扩展为 16 位有符号数
            val sample = (pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)
            val amplified = (sample * gain).toInt().coerceIn(SHORT_MIN_VALUE, SHORT_MAX_VALUE)
            pcm[index] = (amplified and 0xFF).toByte()
            pcm[index + 1] = ((amplified shr 8) and 0xFF).toByte()
        }
    }

    private const val SHORT_MIN_VALUE = -32768
    private const val SHORT_MAX_VALUE = 32767
}
