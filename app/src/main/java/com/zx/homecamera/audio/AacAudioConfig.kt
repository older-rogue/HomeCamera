package com.zx.homecamera.audio

data class AacAudioConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val codec: String = CODEC,
    val sampleRate: Int = SAMPLE_RATE,
    val channelCount: Int = CHANNEL_COUNT,
    val bitrate: Int = BITRATE,
    /**
     * 采集 PCM 送入编码器前的软件增益（线性倍数，1.0 = 不放大）。
     * VOICE_COMMUNICATION 音源 + AEC/NS 的语音处理链会把环境音电平压得很低，
     * 靠固定增益补偿，使录像音轨与实时流可听。录制与在线播放共用此链路。
     */
    val pcmGain: Float = PCM_GAIN,
) {
    companion object {
        const val DEFAULT_ENABLED = true
        const val CODEC = "aac"
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 1
        const val BITRATE = 64_000
        const val PCM_GAIN = 3.0f

        val Default = AacAudioConfig()
    }
}
