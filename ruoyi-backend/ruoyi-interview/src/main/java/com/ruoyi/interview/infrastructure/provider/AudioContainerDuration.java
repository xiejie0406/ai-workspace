package com.ruoyi.interview.infrastructure.provider;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** 用真实容器元数据计算时长，不用文本长度猜测或伪造供应商计费量。 */
final class AudioContainerDuration {
    static long millis(byte[] bytes, String codec, int sampleRate) {
        if ("pcm".equals(codec)) return sampleRate > 0 ? bytes.length * 1000L / (sampleRate * 2L) : 0;
        if ("mp3".equals(codec)) return mp3Millis(bytes);
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length >= 12 && tag(bytes, 0, "RIFF") && tag(bytes, 8, "WAVE")) {
            long byteRate = 0;
            for (int offset = 12; offset + 8 <= bytes.length;) {
                long length = Integer.toUnsignedLong(data.getInt(offset + 4));
                if (tag(bytes, offset, "fmt ") && length >= 16 && offset + 24 <= bytes.length)
                    byteRate = Integer.toUnsignedLong(data.getInt(offset + 16));
                if (tag(bytes, offset, "data") && byteRate > 0)
                    return Math.min(length, bytes.length - offset - 8L) * 1000 / byteRate;
                long next = offset + 8L + length + (length & 1);
                if (next > bytes.length) break;
                offset = (int) next;
            }
        }
        long granule = 0; int preSkip = 0;
        for (int offset = 0; offset + 27 <= bytes.length && tag(bytes, offset, "OggS");) {
            int segments = bytes[offset + 26] & 255;
            int body = offset + 27 + segments;
            if (body > bytes.length) return 0;
            int length = 0;
            for (int i = offset + 27; i < body; i++) length += bytes[i] & 255;
            if (body + length > bytes.length) return 0;
            if (length >= 12 && tag(bytes, body, "OpusHead")) preSkip = Short.toUnsignedInt(data.getShort(body + 10));
            granule = Math.max(granule, data.getLong(offset + 6));
            offset = body + length;
        }
        return Math.max(0, granule - preSkip) * 1000 / 48000;
    }

    /** 逐帧累计真实 MPEG Layer III 时长，支持变码率和 ID3 元数据。 */
    private static long mp3Millis(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 10 && tag(bytes, 0, "ID3")) {
            int size = 0;
            for (int i = 6; i < 10; i++) {
                if ((bytes[i] & 128) != 0) return 0;
                size = (size << 7) | (bytes[i] & 127);
            }
            long end = 10L + size + ((bytes[5] & 16) != 0 ? 10 : 0);
            if (end > bytes.length) return 0;
            offset = (int) end;
        }
        int frames = 0;
        double duration = 0;
        int[] mpeg1Bitrates = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        int[] mpeg2Bitrates = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        int[] rates = {44100, 48000, 32000};
        while (offset < bytes.length) {
            if (bytes.length - offset == 128 && tag(bytes, offset, "TAG")) break;
            if (bytes[offset] == 0) {
                for (int i = offset; i < bytes.length; i++) if (bytes[i] != 0) return 0;
                break;
            }
            if (offset + 4 > bytes.length || (bytes[offset] & 255) != 255
                    || (bytes[offset + 1] & 224) != 224) return 0;
            int version = (bytes[offset + 1] >> 3) & 3;
            int layer = (bytes[offset + 1] >> 1) & 3;
            int bitrateIndex = (bytes[offset + 2] >> 4) & 15;
            int rateIndex = (bytes[offset + 2] >> 2) & 3;
            if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return 0;
            int rate = rates[rateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
            int bitrate = (version == 3 ? mpeg1Bitrates : mpeg2Bitrates)[bitrateIndex] * 1000;
            int length = (version == 3 ? 144 : 72) * bitrate / rate + ((bytes[offset + 2] >> 1) & 1);
            if (offset + length > bytes.length) return 0;
            duration += (version == 3 ? 1152.0 : 576.0) * 1000 / rate;
            frames++;
            offset += length;
        }
        return frames == 0 ? 0 : Math.round(duration);
    }
    private static boolean tag(byte[] bytes, int offset, String value) {
        return offset + value.length() <= bytes.length && value.equals(new String(bytes, offset, value.length(), StandardCharsets.US_ASCII));
    }
}
