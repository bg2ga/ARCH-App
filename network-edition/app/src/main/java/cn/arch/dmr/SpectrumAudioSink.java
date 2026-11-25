package cn.arch.dmr;

import androidx.media3.common.Format;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.nio.ByteBuffer;

/** [3.2.42] 在 PCM 写入声卡前截一份样本给 RadioSpectrum 做真实频谱。 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SpectrumAudioSink extends ForwardingAudioSink {
    private int sampleRate = 48000;
    private int channelCount = 2;

    public SpectrumAudioSink(AudioSink sink) { super(sink); }

    @Override
    public void configure(Format inputFormat, int specifiedBufferSize, int[] outputChannels)
            throws ConfigurationException {
        super.configure(inputFormat, specifiedBufferSize, outputChannels);
        if (inputFormat != null) {
            if (inputFormat.sampleRate > 0) sampleRate = inputFormat.sampleRate;
            if (inputFormat.channelCount > 0) channelCount = inputFormat.channelCount;
        }
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws InitializationException, WriteException {
        try {
            RadioSpectrum.feedPcm(buffer, sampleRate, channelCount);
        } catch (Throwable ignored) {
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    }
}
