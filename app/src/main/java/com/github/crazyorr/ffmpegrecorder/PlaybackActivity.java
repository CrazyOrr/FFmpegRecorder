package com.github.crazyorr.ffmpegrecorder;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

public class PlaybackActivity extends AppCompatActivity {

    public static final String INTENT_NAME_VIDEO_PATH = "INTENT_NAME_VIDEO_PATH";

    private VideoView mVvPlayback;

    private int mVideoCurPos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);
        TextView tvVideoPath = (TextView) findViewById(R.id.tv_video_path);
        mVvPlayback = (VideoView) findViewById(R.id.vv_playback);

        String path = getIntent().getStringExtra(INTENT_NAME_VIDEO_PATH);
        if (path == null) {
            finish();
        }

        tvVideoPath.setText(path);
        mVvPlayback.setVideoPath(path);
        mVvPlayback.setKeepScreenOn(true);
        mVvPlayback.setMediaController(new MediaController(this));
        mVvPlayback.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

            @Override
            public void onCompletion(MediaPlayer mp) {
            }
        });
        mVvPlayback.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mVvPlayback.stopPlayback();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mVvPlayback.pause();
        mVideoCurPos = mVvPlayback.getCurrentPosition();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mVvPlayback.seekTo(mVideoCurPos);
        mVvPlayback.start();
    }
}
