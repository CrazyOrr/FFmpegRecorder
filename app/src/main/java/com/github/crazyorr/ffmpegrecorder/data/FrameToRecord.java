package com.github.crazyorr.ffmpegrecorder.data;

import org.bytedeco.javacv.Frame;

/**
 * Created by wanglei02 on 2016/1/21.
 */
public class FrameToRecord {
    private long timestamp;
    private Frame frame;

    public FrameToRecord(long timestamp, Frame frame) {
        this.timestamp = timestamp;
        this.frame = frame;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Frame getFrame() {
        return frame;
    }

    public void setFrame(Frame frame) {
        this.frame = frame;
    }
}
