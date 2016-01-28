package com.github.crazyorr.ffmpegrecorder;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import butterknife.Bind;
import butterknife.ButterKnife;

public class PlaybackActivity extends AppCompatActivity {

    public static final String INTENT_NAME_VIDEO_PATH = "INTENT_NAME_VIDEO_PATH";

    @Bind(R.id.tv_video_path)
    TextView mTvVideoPath;
    @Bind(R.id.vv_playback)
    VideoView mVvPlayback;

    private int mVideoCurPos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);
        ButterKnife.bind(this);

        String path = getIntent().getStringExtra(INTENT_NAME_VIDEO_PATH);
        if (path == null) {
            finish();
        }

        mTvVideoPath.setText(path);
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
