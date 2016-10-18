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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Thread.State.WAITING;

public class FFmpegRecordActivity extends AppCompatActivity implements
        TextureView.SurfaceTextureListener, View.OnClickListener {
    private static final String LOG_TAG = FFmpegRecordActivity.class.getSimpleName();

    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 480;

    // both in milliseconds
    private static final long MIN_VIDEO_LENGTH = 1 * 1000;
    private static final long MAX_VIDEO_LENGTH = 90 * 1000;

    private FixedRatioCroppedTextureView mPreview;
    private Button mBtnResumeOrPause;
    private Button mBtnDone;
    private Button mBtnSwitchCamera;
    private Button mBtnReset;

    private int mCameraId;
    private Camera mCamera;
    private FFmpegFrameRecorder mFrameRecorder;
    private VideoRecordThread mVideoRecordThread;
    private AudioRecord mAudioRecord;
    private AudioRecordThread mAudioRecordThread;
    private volatile boolean mRecording = false;
    private File mVideo;
    private LinkedBlockingQueue<RecordedFrame> mRecordedFrameQueue;
    private ConcurrentLinkedQueue<RecordedFrame> mRecycledFrameQueue;
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
    private int frameDepth = Frame.DEPTH_UBYTE;
    private int frameChannels = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ffmpeg_record);
        mPreview = (FixedRatioCroppedTextureView) findViewById(R.id.camera_preview);
        mBtnResumeOrPause = (Button) findViewById(R.id.btn_resume_or_pause);
        mBtnDone = (Button) findViewById(R.id.btn_done);
        mBtnSwitchCamera = (Button) findViewById(R.id.btn_switch_camera);
        mBtnReset = (Button) findViewById(R.id.btn_reset);

//        mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        // Switch width and height
        mPreview.setPreviewSize(previewHeight, previewWidth);
        mPreview.setCroppedSizeWeight(videoWidth, videoHeight);
        mPreview.setSurfaceTextureListener(this);
        mBtnResumeOrPause.setOnClickListener(this);
        mBtnDone.setOnClickListener(this);
        mBtnSwitchCamera.setOnClickListener(this);
        mBtnReset.setOnClickListener(this);

        mRecordedFrameQueue = new LinkedBlockingQueue<>();
        mRecycledFrameQueue = new ConcurrentLinkedQueue<>();
        mRecordFragments = new Stack<>();
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
            // SurfaceTexture already created
            startPreview(surfaceTexture);
        }
        new ProgressDialogTask<Void, Integer, Void>(R.string.initiating) {

            @Override
            protected Void doInBackground(Void... params) {
                if (mFrameRecorder == null) {
                    initRecorder();
                    startRecorder();
                }
                startRecording();
                return null;
            }
        }.execute();
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
        startPreview(surface);
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
        int i = v.getId();
        if (i == R.id.btn_resume_or_pause) {
            if (mRecording) {
                pauseRecording();
            } else {
                resumeRecording();
            }

        } else if (i == R.id.btn_done) {
            pauseRecording();
            // check video length
            if (calculateTotalRecordedTime(mRecordFragments) < MIN_VIDEO_LENGTH) {
                Toast.makeText(this, R.string.video_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            new FinishRecordingTask().execute();

        } else if (i == R.id.btn_switch_camera) {
            final SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
            new ProgressDialogTask<Void, Integer, Void>(R.string.please_wait) {

                @Override
                protected Void doInBackground(Void... params) {
                    stopRecording();
                    stopPreview();
                    releaseCamera();

                    mCameraId = (mCameraId + 1) % 2;

                    acquireCamera();
                    startPreview(surfaceTexture);
                    startRecording();
                    return null;
                }
            }.execute();

        } else if (i == R.id.btn_reset) {
            pauseRecording();
            new ProgressDialogTask<Void, Integer, Void>(R.string.please_wait) {

                @Override
                protected Void doInBackground(Void... params) {
                    stopRecording();
                    stopRecorder(false);
                    releaseRecorder();

                    initRecorder();
                    startRecorder();
                    startRecording();
                    return null;
                }
            }.execute();
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

        // YCbCr_420_SP (NV21) format
        byte[] bufferByte = new byte[previewWidth * previewHeight * 3 / 2];
        mCamera.addCallbackBuffer(bufferByte);
        mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
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
                        pauseRecording();
                        new FinishRecordingTask().execute();
                        return;
                    }

                    long timestamp = 1000 * curRecordedTime;
                    Frame frame;
                    RecordedFrame recordedFrame = mRecycledFrameQueue.poll();
                    if (recordedFrame != null) {
                        frame = recordedFrame.getFrame();
                        recordedFrame.setTimestamp(timestamp);
                    } else {
                        frame = new Frame(previewWidth, previewHeight, frameDepth, frameChannels);
                        recordedFrame = new RecordedFrame(timestamp, frame);
                    }
                    ((ByteBuffer) frame.image[0].position(0)).put(data);

                    try {
                        mRecordedFrameQueue.put(recordedFrame);
                        mRecordedFrameCount++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mCamera.addCallbackBuffer(data);
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
            mCamera.setPreviewCallbackWithBuffer(null);
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
        mRecordedFrameQueue.clear();
        mRecycledFrameQueue.clear();
        mRecordFragments.clear();

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
                    mBtnReset.setVisibility(View.VISIBLE);
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
                if (frameIndex == 0) {
                    if (mFrameRecorder != null) {
                        long timestamp = recordedFrame.getTimestamp();
                        if (timestamp > mFrameRecorder.getTimestamp()) {
                            mFrameRecorder.setTimestamp(timestamp);
                        }
                        long startTime = System.currentTimeMillis();
                        Frame filteredFrame = null;
                        try {
                            frameFilter.push(recordedFrame.getFrame());
                            filteredFrame = frameFilter.pull();
                        } catch (FrameFilter.Exception e) {
                            e.printStackTrace();
                        }
                        try {
                            mFrameRecorder.record(filteredFrame, avutil.AV_PIX_FMT_NV21);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            e.printStackTrace();
                        }
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
                    }
                }
                Log.d(LOG_TAG, mProcessedFrameCount + " / " + mRecordedFrameCount);
                mRecycledFrameQueue.offer(recordedFrame);
            }
        }

        public void stopRunning() {
            while (getState() != WAITING) {
            }
            this.isRunning = false;
            interrupt();
        }
    }

    abstract class ProgressDialogTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {

        private int promptRes;
        private ProgressDialog mProgressDialog;

        public ProgressDialogTask(int promptRes) {
            this.promptRes = promptRes;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mProgressDialog = ProgressDialog.show(FFmpegRecordActivity.this,
                    null, getString(promptRes), true);
        }

        @Override
        protected void onProgressUpdate(Progress... values) {
            super.onProgressUpdate(values);
//            mProgressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Result result) {
            super.onPostExecute(result);
            mProgressDialog.dismiss();
        }
    }

    class FinishRecordingTask extends ProgressDialogTask<Void, Integer, Void> {

        public FinishRecordingTask() {
            super(R.string.processing);
        }

        @Override
        protected Void doInBackground(Void... params) {
            stopRecording();
            stopRecorder(true);
            releaseRecorder();
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            Intent intent = new Intent(FFmpegRecordActivity.this, PlaybackActivity.class);
            intent.putExtra(PlaybackActivity.INTENT_NAME_VIDEO_PATH, mVideo.getPath());
            startActivity(intent);
        }
    }
}
