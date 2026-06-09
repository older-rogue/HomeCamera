package com.zx.homecamera.audio

data class AacAudioConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val codec: String = CODEC,
    val sampleRate: Int = SAMPLE_RATE,
    val channelCount: Int = CHANNEL_COUNT,
    val bitrate: Int = BITRATE,
) {
    companion object {
        const val DEFAULT_ENABLED = true
        const val CODEC = "aac"
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 1
        const val BITRATE = 64_000

        val Default = AacAudioConfig()
    }
}
