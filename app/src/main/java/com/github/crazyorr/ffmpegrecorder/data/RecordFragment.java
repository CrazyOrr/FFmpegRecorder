package com.github.crazyorr.ffmpegrecorder.data;

/**
 * Created by wanglei02 on 2016/1/22.
 */
public class RecordFragment {
    private long startTimestamp;
    private long endTimestamp;

    public void setStartTimestamp(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public long getDuration() {
        return endTimestamp - startTimestamp;
    }
}
