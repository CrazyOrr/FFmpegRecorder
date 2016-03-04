package com.github.crazyorr.ffmpegrecorder;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.github.crazyorr.ffmpegrecorder.data.RecordFragment;
import com.github.crazyorr.ffmpegrecorder.data.RecordedFrame;
import com.github.crazyorr.ffmpegrecorder.util.CameraHelper;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;

import butterknife.Bind;
import butterknife.ButterKnife;

import static java.lang.Thread.State.WAITING;

public class FFmpegRecordActivity extends AppCompatActivity implements
        TextureView.SurfaceTextureListener, View.OnClickListener {
    private static final String LOG_TAG = FFmpegRecordActivity.class.getSimpleName();

    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 480;

    // both in milliseconds
    private static final long MIN_VIDEO_LENGTH = 1 * 1000;
    private static final long MAX_VIDEO_LENGTH = 90 * 1000;

    @Bind(R.id.camera_preview)
    FixedRatioCroppedTextureView mPreview;
    @Bind(R.id.btn_resume_or_pause)
    Button mBtnResumeOrPause;
    @Bind(R.id.btn_done)
    Button mBtnDone;
    @Bind(R.id.btn_switch_camera)
    Button mBtnSwitchCamera;

    private int mCameraId;
    private Camera mCamera;
    private FFmpegFrameRecorder mFrameRecorder;
    private VideoRecordThread mVideoRecordThread;
    private AudioRecord mAudioRecord;
    private AudioRecordThread mAudioRecordThread;
    private volatile boolean mRecording = false;
    private File mVideo;
    private LinkedBlockingQueue<RecordedFrame> mRecordedFrameQueue;
    private int mRecordedFrameCount;
    private int mProcessedFrameCount;
    private long mTotalProcessFrameTime;
    private Stack<RecordFragment> mRecordFragments;

    private int sampleAudioRateInHz = 44100;
    /* The sides of width and height are based on camera orientation.
    That is, the preview size is the size before it is rotated. */
    private int previewWidth = PREFERRED_PREVIEW_WIDTH;
    private int previewHeight = PREFERRED_PREVIEW_HEIGHT;
    // Output video size
    private int videoWidth = 320;
    private int videoHeight = 240;
    private int frameRate = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ffmpeg_record);
        ButterKnife.bind(this);

//        mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        // Switch width and height
        mPreview.setPreviewSize(previewHeight, previewWidth);
        mPreview.setCroppedSizeWeight(videoWidth, videoHeight);
        mPreview.setSurfaceTextureListener(this);
        mBtnResumeOrPause.setOnClickListener(this);
        mBtnDone.setOnClickListener(this);
        mBtnSwitchCamera.setOnClickListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecorder(false);
        releaseRecorder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        acquireCamera();
        SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
        if (surfaceTexture != null) {
            startPreview(surfaceTexture);
            if (mFrameRecorder == null) {
                new Thread() {
                    @Override
                    public void run() {
                        initRecorder();
                        startRecorder();
                        startRecording();
                    }
                }.start();
            } else {
                startRecording();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseRecording();
        stopRecording();
        stopPreview();
        releaseCamera();
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, int width, int height) {
        new Thread() {
            @Override
            public void run() {
                initRecorder();
                startPreview(surface);
                startRecorder();
                startRecording();
            }
        }.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_resume_or_pause:
                if (mRecording) {
                    pauseRecording();
                } else {
                    resumeRecording();
                }
                break;
            case R.id.btn_done:
                pauseRecording();
                // check video length
                if (calculateTotalRecordedTime(mRecordFragments) < MIN_VIDEO_LENGTH) {
                    Toast.makeText(this, R.string.video_too_short, Toast.LENGTH_SHORT).show();
                    return;
                }
                new FinishRecordingTask().execute();
                break;
            case R.id.btn_switch_camera:
                new Thread() {
                    @Override
                    public void run() {
                        stopRecording();
                        stopPreview();
                        releaseCamera();

                        mCameraId = (mCameraId + 1) % 2;

                        acquireCamera();
                        startPreview(mPreview.getSurfaceTexture());
                        startRecording();
                    }
                }.start();
                break;
        }
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        if (mCamera == null) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        Camera.Size previewSize = CameraHelper.getOptimalSize(previewSizes,
                PREFERRED_PREVIEW_WIDTH, PREFERRED_PREVIEW_HEIGHT);
        // if changed, reassign values and request layout
        if (previewWidth != previewSize.width || previewHeight != previewSize.height) {
            previewWidth = previewSize.width;
            previewHeight = previewSize.height;
            // Switch width and height
            mPreview.setPreviewSize(previewHeight, previewWidth);
            mPreview.requestLayout();
        }
        parameters.setPreviewSize(previewWidth, previewHeight);
//        parameters.setPreviewFormat(ImageFormat.NV21);
        mCamera.setParameters(parameters);

        mCamera.setDisplayOrientation(CameraHelper.getCameraDisplayOrientation(
                this, mCameraId));

        mCamera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                // get video data
                if (mRecording) {
                    // wait for AudioRecord to init and start
                    if (mAudioRecord == null || mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        mRecordFragments.peek().setStartTimestamp(System.currentTimeMillis());
                        return;
                    }

                    // pop the current record fragment when calculate total recorded time
                    RecordFragment curFragment = mRecordFragments.pop();
                    long recordedTime = calculateTotalRecordedTime(mRecordFragments);
                    // push it back after calculation
                    mRecordFragments.push(curFragment);
                    long curRecordedTime = System.currentTimeMillis()
                            - curFragment.getStartTimestamp() + recordedTime;
                    // check if exceeds time limit
                    if (curRecordedTime > MAX_VIDEO_LENGTH) {
                        new FinishRecordingTask().execute();
                    }
                    try {
                        mRecordedFrameQueue.put(new RecordedFrame(1000 * curRecordedTime, data));
                        mRecordedFrameCount++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        mCamera.startPreview();
    }

    private void stopPreview() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
        }
    }

    private void acquireCamera() {
        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }

    private void initRecorder() {
        mRecordedFrameQueue = new LinkedBlockingQueue<>();
        mRecordFragments = new Stack<>();

        Log.i(LOG_TAG, "init mFrameRecorder");

        String recordedTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        mVideo = CameraHelper.getOutputMediaFile(recordedTime, CameraHelper.MEDIA_TYPE_VIDEO);
        Log.i(LOG_TAG, "Output Video: " + mVideo);

        mFrameRecorder = new FFmpegFrameRecorder(mVideo, videoWidth, videoHeight, 1);
        mFrameRecorder.setFormat("mp4");
        mFrameRecorder.setSampleRate(sampleAudioRateInHz);
        mFrameRecorder.setFrameRate(frameRate);
        // Use H264
        mFrameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);

        Log.i(LOG_TAG, "mFrameRecorder initialize success");
    }

    private void releaseRecorder() {
        mRecordedFrameQueue = null;
        mRecordFragments = null;

        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }
        mFrameRecorder = null;
    }

    private void startRecorder() {
        try {
            mFrameRecorder.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecorder(boolean saveFile) {
        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.stop();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            if (!saveFile) {
                mVideo.delete();
            }
        }
    }

    private void startRecording() {
        mVideoRecordThread = new VideoRecordThread();
        mVideoRecordThread.start();

        mAudioRecordThread = new AudioRecordThread();
        mAudioRecordThread.start();
    }

    private void stopRecording() {
        if (mAudioRecordThread != null) {
            mAudioRecordThread.stopRunning();
            try {
                mAudioRecordThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mAudioRecordThread = null;
        }

        if (mVideoRecordThread != null) {
            mVideoRecordThread.stopRunning();
            try {
                mVideoRecordThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mVideoRecordThread = null;
        }
    }

    private void resumeRecording() {
        if (!mRecording) {
            RecordFragment recordFragment = new RecordFragment();
            recordFragment.setStartTimestamp(System.currentTimeMillis());
            mRecordFragments.push(recordFragment);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBtnSwitchCamera.setVisibility(View.INVISIBLE);
                    mBtnResumeOrPause.setText(R.string.pause);
                }
            });
            mRecording = true;
        }
    }

    private void pauseRecording() {
        if (mRecording) {
            mRecordFragments.peek().setEndTimestamp(System.currentTimeMillis());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBtnSwitchCamera.setVisibility(View.VISIBLE);
                    mBtnResumeOrPause.setText(R.string.resume);
                }
            });
            mRecording = false;
        }
    }

    private long calculateTotalRecordedTime(Stack<RecordFragment> recordFragments) {
        long recordedTime = 0;
        for (RecordFragment recordFragment : recordFragments) {
            recordedTime += recordFragment.getDuration();
        }
        return recordedTime;
    }

    class AudioRecordThread extends Thread {

        private boolean isRunning;

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            ShortBuffer audioData = ShortBuffer.allocate(bufferSize);

            Log.d(LOG_TAG, "mAudioRecord.startRecording()");
            mAudioRecord.startRecording();

            isRunning = true;
            /* ffmpeg_audio encoding loop */
            while (isRunning) {
                if (mRecording && mFrameRecorder != null) {
                    int bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                    audioData.limit(bufferReadResult);
                    if (bufferReadResult > 0) {
                        Log.v(LOG_TAG, "bufferReadResult: " + bufferReadResult);
                        try {
                            mFrameRecorder.recordSamples(audioData);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(LOG_TAG, "AudioThread Finished, release mAudioRecord");

            /* encoding finish, release mFrameRecorder */
            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
                Log.v(LOG_TAG, "mAudioRecord released");
            }
        }

        public void stopRunning() {
            this.isRunning = false;
        }
    }

    class VideoRecordThread extends Thread {

        private boolean isRunning;

        @Override
        public void run() {
            List<String> filters = new ArrayList<>();
            // Transpose
            String transpose = null;
            android.hardware.Camera.CameraInfo info =
                    new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(mCameraId, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                switch (info.orientation) {
                    case 270:
//                        transpose = "transpose=clock_flip"; // Same as preview display
                        transpose = "transpose=cclock"; // Mirrored horizontally as preview display
                        break;
                    case 90:
//                        transpose = "transpose=cclock_flip"; // Same as preview display
                        transpose = "transpose=clock"; // Mirrored horizontally as preview display
                        break;
                }
            } else {
                switch (info.orientation) {
                    case 270:
                        transpose = "transpose=cclock";
                        break;
                    case 90:
                        transpose = "transpose=clock";
                        break;
                }
            }
            if (transpose != null) {
                filters.add(transpose);
            }
            // Crop (only vertically)
            int width = previewHeight;
            int height = width * videoHeight / videoWidth;
            String crop = String.format("crop=%d:%d:%d:%d",
                    width, height,
                    (previewHeight - width) / 2, (previewWidth - height) / 2);
            filters.add(crop);
            // Scale (to designated size)
            String scale = String.format("scale=%d:%d", videoHeight, videoWidth);
            filters.add(scale);

            FFmpegFrameFilter frameFilter = new FFmpegFrameFilter(TextUtils.join(",", filters),
                    previewWidth, previewHeight);
            frameFilter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
            frameFilter.setFrameRate(frameRate);
            try {
                frameFilter.start();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }

            Frame frame = new Frame(previewWidth, previewHeight, Frame.DEPTH_UBYTE, 2);
            isRunning = true;
            RecordedFrame recordedFrame;

            int frameIndex = 0;
            int step = 1;
            final int SAMPLE_LENGTH = 30;
            long[] processFrameTimeSample = new long[SAMPLE_LENGTH];
            int sampleIndex = 0;

            while (isRunning) {
                try {
                    recordedFrame = mRecordedFrameQueue.take();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    try {
                        frameFilter.stop();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                mProcessedFrameCount++;
                /* Process only 1st frame in every [step] frames,
                in case the recorded frame queue gets bigger and bigger,
                eventually run out of memory. */
                frameIndex = (frameIndex + 1) % step;
                if (frameIndex != 0) {
                    continue;
                }

                if (mFrameRecorder != null) {
                    ((ByteBuffer) frame.image[0].position(0)).put(recordedFrame.getData());

                    try {
                        Log.v(LOG_TAG, "Writing Frame");
                        long timestamp = recordedFrame.getTimestamp();
                        if (timestamp > mFrameRecorder.getTimestamp()) {
                            mFrameRecorder.setTimestamp(timestamp);
                        }
                        try {
                            long startTime = System.currentTimeMillis();
                            frameFilter.push(frame);
                            Frame filteredFrame = frameFilter.pull();
                            mFrameRecorder.record(filteredFrame, avutil.AV_PIX_FMT_NV21);
                            long endTime = System.currentTimeMillis();
                            long processTime = endTime - startTime;
                            processFrameTimeSample[sampleIndex] = processTime;
                            mTotalProcessFrameTime += processTime;
                            Log.d(LOG_TAG, "this process time: " + processTime);
                            long totalAvg = mTotalProcessFrameTime / mProcessedFrameCount;
                            Log.d(LOG_TAG, "avg process time: " + totalAvg);
                            // TODO looking for a better way to adjust the process time per frame, hopefully to keep up with the onPreviewFrame callback frequency
                            if (sampleIndex == SAMPLE_LENGTH - 1) {
                                long sampleSum = 0;
                                for (long pft : processFrameTimeSample) {
                                    sampleSum += pft;
                                }
                                long sampleAvg = sampleSum / SAMPLE_LENGTH;
                                double tolerance = 0.25;
                                if (sampleAvg > totalAvg * (1 + tolerance)) {
                                    // ignore more frames
                                    step++;
                                    Log.i(LOG_TAG, "increase step to " + step);
                                } else if (sampleAvg < totalAvg * (1 - tolerance)) {
                                    // ignore less frames
                                    if (step > 1) {
                                        step--;
                                        Log.i(LOG_TAG, "decrease step to " + step);
                                    }
                                }
                            }
                            sampleIndex = (sampleIndex + 1) % SAMPLE_LENGTH;
                        } catch (FrameFilter.Exception e) {
                            e.printStackTrace();
                        }
                    } catch (FFmpegFrameRecorder.Exception e) {
                        Log.v(LOG_TAG, e.getMessage());
                        e.printStackTrace();
                    }
                }
                Log.d(LOG_TAG, mProcessedFrameCount + " / " + mRecordedFrameCount);
            }
        }

        public void stopRunning() {
            while (getState() != WAITING) {
            }
            this.isRunning = false;
            interrupt();
        }
    }

    class FinishRecordingTask extends AsyncTask<Void, Integer, Void> {

        ProgressDialog mProgressDialog;

        @Override
        protected Void doInBackground(Void... params) {
            pauseRecording();
            stopRecording();
            stopRecorder(true);
            releaseRecorder();
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mProgressDialog = ProgressDialog.show(FFmpegRecordActivity.this,
                    null, getString(R.string.processing), true);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            mProgressDialog.dismiss();

            Intent intent = new Intent(FFmpegRecordActivity.this, PlaybackActivity.class);
            intent.putExtra(PlaybackActivity.INTENT_NAME_VIDEO_PATH, mVideo.getPath());
            startActivity(intent);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
//            mProgressDialog.setProgress(values[0]);
        }
    }
}
