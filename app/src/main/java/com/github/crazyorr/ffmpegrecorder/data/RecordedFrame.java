package com.github.crazyorr.ffmpegrecorder.data;

/**
 * Created by wanglei02 on 2016/1/21.
 */
public class RecordedFrame {
    private long timestamp;
    private byte[] data;

    public RecordedFrame(long timestamp, byte[] data) {
        this.timestamp = timestamp;
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
