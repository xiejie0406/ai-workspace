package com.ruoyi.interview.infrastructure.provider;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;
class AudioContainerDurationTest {
    static byte[] mp3Frames(int count) {
        byte[] audio = new byte[count * 192]; // MPEG-2 Layer III，64 kbps / 24000 Hz，每帧 24 ms。
        for (int i = 0; i < count; i++) {
            audio[i * 192] = (byte) 0xff;
            audio[i * 192 + 1] = (byte) 0xf3;
            audio[i * 192 + 2] = (byte) 0x84;
        }
        return audio;
    }

    @Test void readsMp3WithoutSubtitleTimestampsAndRejectsTruncatedFrames() {
        byte[] audio = mp3Frames(10);
        assertEquals(240, AudioContainerDuration.millis(audio, "mp3", 24000));
        assertEquals(0, AudioContainerDuration.millis(java.util.Arrays.copyOf(audio, audio.length - 1), "mp3", 24000));
        assertEquals(0, AudioContainerDuration.millis("{\"code\":401}".getBytes(), "mp3", 24000));
    }

    @Test void skipsId3AndSupportsVariableBitrateMp3() {
        byte[] audio = new byte[14 + 192 + 144];
        System.arraycopy(new byte[] {'I','D','3',4,0,0,0,0,0,4}, 0, audio, 0, 10);
        System.arraycopy(mp3Frames(1), 0, audio, 14, 192);
        audio[206] = (byte) 0xff;
        audio[207] = (byte) 0xf3;
        audio[208] = (byte) 0x64; // 第二帧 48 kbps；时长仍为 24 ms。
        assertEquals(48, AudioContainerDuration.millis(audio, "mp3", 24000));
    }

    @Test void readsWaveDataAndRejectsTruncatedContainers() {
        byte[] wave=new byte[32044]; var b=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(32036).put("WAVEfmt ".getBytes()).putInt(16)
            .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2)
            .putShort((short)16).put("data".getBytes()).putInt(32000);
        assertEquals(1000,AudioContainerDuration.millis(wave,"wav",16000));
        assertEquals(0,AudioContainerDuration.millis(new byte[4],"ogg_opus",24000));
        assertEquals(1000,AudioContainerDuration.millis(new byte[48000],"pcm",24000));
    }
}
