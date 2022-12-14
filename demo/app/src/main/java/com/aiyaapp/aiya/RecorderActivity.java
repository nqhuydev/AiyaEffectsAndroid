package com.aiyaapp.aiya;

import static com.aiyaapp.aiya.CameraActivity.setCameraDisplayOrientation;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageContentMode.kAYGPUImageScaleAspectFill;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageContentMode.kAYGPUImageScaleAspectFit;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageRotateRight;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageRotateRightFlipHorizontal;
import static com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper.getAvcSupportedFormatInfo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.aiyaapp.aiya.cameraTool.AYCameraPreviewListener;
import com.aiyaapp.aiya.cameraTool.AYCameraPreviewWrap;
import com.aiyaapp.aiya.cameraTool.AYPreviewView;
import com.aiyaapp.aiya.cameraTool.AYPreviewViewListener;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants;
import com.aiyaapp.aiya.recorderTool.AYAudioRecorderListener;
import com.aiyaapp.aiya.recorderTool.AYAudioRecorderWrap;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoder;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper.CodecInfo;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderListener;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.UUID;

public class RecorderActivity extends AppCompatActivity implements AYCameraPreviewListener, AYAudioRecorderListener, AYPreviewViewListener, AYMediaCodecEncoderListener {

    private static final String TAG = "RecorderActivity";

    // ??????
    Camera camera;
    AYCameraPreviewWrap cameraPreviewWrap;
    public static final int FRONT_CAMERA_ID = 1;
    public static final int BACK_CAMERA_ID = 0;
    int mCurrentCameraID = FRONT_CAMERA_ID;

    // ?????????
    AudioRecord audioRecord;
    AYAudioRecorderWrap audioRecordWrap;

    // ????????????
    AYEffectHandler effectHandler;

    // ?????????surface
    AYPreviewView surfaceView;

    // ??????????????????
    volatile AYMediaCodecEncoder encoder;
    volatile boolean videoCodecConfigResult = false;
    volatile boolean audioCodecConfigResult = false;
    volatile boolean isCodecInit = false;

    // ??????????????????
    String videoPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_recorder);

        surfaceView = findViewById(R.id.recorder_preview);
        surfaceView.setContentMode(kAYGPUImageScaleAspectFit);
        surfaceView.setListener(this);

        ToggleButton recorderToggle = findViewById(R.id.recorder_toggle);
        recorderToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });

        findViewById(R.id.switch_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (encoder == null) {
                    switchCamera();
                }
            }
        });
    }

    @Override
    public void createGLEnvironment() {
        openHardware();

        surfaceView.eglContext.syncRunOnRenderThread(this::createEffectHandler);
    }

    @Override
    public void destroyGLEnvironment() {
        closeHardware();

        surfaceView.eglContext.syncRunOnRenderThread(this::destroyEffectHandler);
    }

    /**
     * ??????????????????
     */
    private void openHardware() {
        // ??????????????????
        openFrontCamera();

        // ??????????????????
//        openBackCamera();

        // ???????????????
        openAudioRecorder();
    }

    /**
     * ??????????????????
     */
    private void openFrontCamera() {
        if (cameraPreviewWrap != null) {
            cameraPreviewWrap.stopPreview();
        }
        if (camera != null) {
            camera.release();
        }

        Log.d(TAG, "??????????????????");
        mCurrentCameraID = FRONT_CAMERA_ID;
        camera = Camera.open(mCurrentCameraID); // TODO ?????????????????????????????????
        setCameraDisplayOrientation(this, camera);

        cameraPreviewWrap = new AYCameraPreviewWrap(camera);
        cameraPreviewWrap.setPreviewListener(this);

        cameraPreviewWrap.setRotateMode(kAYGPUImageRotateRight); // TODO ????????????????????????, ????????????
        cameraPreviewWrap.startPreview(surfaceView.eglContext);
    }

    /**
     * ??????????????????
     */
    private void openBackCamera() {
        if (cameraPreviewWrap != null) {
            cameraPreviewWrap.stopPreview();
        }
        if (camera != null) {
            camera.release();
        }

        Log.d(TAG, "??????????????????");
        mCurrentCameraID = BACK_CAMERA_ID;
        camera = Camera.open(mCurrentCameraID);
        setCameraDisplayOrientation(this, camera);

        cameraPreviewWrap = new AYCameraPreviewWrap(camera);
        cameraPreviewWrap.setPreviewListener(this);

        cameraPreviewWrap.setRotateMode(kAYGPUImageRotateRightFlipHorizontal); // TODO ????????????????????????, ????????????
        cameraPreviewWrap.startPreview(surfaceView.eglContext);
    }

    /**
     * ???????????????
     */
    private void openAudioRecorder() {
        Log.d(TAG, "???????????????");

        // ????????????????????????
        final int audioSampleRate = 16000;   //???????????????
        final int audioChannel = AudioFormat.CHANNEL_IN_MONO;   //?????????
        final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; //??????????????????

        int bufferSize = AudioRecord.getMinBufferSize(audioSampleRate, audioChannel, audioFormat);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, audioChannel,
                audioFormat, bufferSize);
        if (audioRecordWrap == null) {
            audioRecordWrap = new AYAudioRecorderWrap(audioRecord, bufferSize);
            audioRecordWrap.setAudioRecorderListener(this);
        }
        audioRecordWrap.startRecording();
    }

    /**
     * ??????????????????
     */
    private void closeHardware() {
        // ????????????
        if (camera != null) {
            Log.d(TAG, "????????????");
            cameraPreviewWrap.stopPreview();
            cameraPreviewWrap = null;
            camera.release();
            camera = null;
        }

        // ???????????????
        if (audioRecord != null) {
            Log.d(TAG, "???????????????");
            audioRecordWrap.stop();
            audioRecordWrap = null;
            audioRecord.release();
            audioRecord = null;
        }

        // ??????????????????, ????????????
        closeMediaCodec();
    }

    public void createEffectHandler() {
        effectHandler = new AYEffectHandler(this);
        effectHandler.setRotateMode(AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageFlipVertical);
        // ????????????
        effectHandler.setEffectPath(getCacheDir().getPath() + "/effect/data/2017/meta.json");
        effectHandler.setEffectPlayCount(0);
        // ??????????????????
        effectHandler.setBeautyType(AyBeauty.AY_BEAUTY_TYPE.AY_BEAUTY_TYPE_3);
        effectHandler.setIntensityOfSmooth(0.8f);
        effectHandler.setIntensityOfSaturation(0.2f);
        effectHandler.setIntensityOfWhite(0f);

        // ??????????????????
        effectHandler.setIntensityOfBigEye(0.2f);
        effectHandler.setIntensityOfSlimFace(0.2f);

        // ????????????
        effectHandler.setStyle(BitmapFactory.decodeFile(getCacheDir().getPath() + "/style/data/03??????.png"));
    }

    public void destroyEffectHandler() {
        if (effectHandler != null) {
            effectHandler.destroy();
            effectHandler = null;
        }
    }

    /**
     * ??????????????????
     * ??????: ?????????????????????????????????
     * This timestamp is in nanoseconds
     */
    @Override
    public void cameraVideoOutput(int texture, int width, int height, long timestamp) {

        // ??????????????????
        if (effectHandler != null) {
            effectHandler.processWithTexture(texture, width, height);
        }

        // ?????????surfaceView
        surfaceView.render(texture, width, height);

        // ??????????????????
        if (encoder != null && isCodecInit) {
            encoder.writeImageTexture(texture, width, height, timestamp);
        }
    }

    /**
     * ?????????????????????
     * ??????: ?????????????????????????????????
     * This timestamp is in nanoseconds
     */
    @Override
    public void audioRecorderOutput(ByteBuffer byteBuffer, long timestamp) {

        // ??????????????????
        if (encoder != null && isCodecInit) {
            encoder.writePCMByteBuffer(byteBuffer, timestamp);
        }
    }

    public void startRecord() {
        // ??????????????????
//        if (getExternalCacheDir() != null) {
//            videoPath = getExternalCacheDir().getAbsolutePath();
//        } else {
//            videoPath = getCacheDir().getAbsolutePath();
//        }
        videoPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();
        videoPath = videoPath + File.separator + getString(R.string.app_name) + UUID.randomUUID().toString().replace("-", "") + ".mp4";

        if (!startMediaCodec()) {
            stopRecord();
            ToggleButton toggleButton = findViewById(R.id.recorder_toggle);
            toggleButton.setChecked(false);
        }
    }

    public void stopRecord() {
//        if (closeMediaCodec() && new File(videoPath).exists()) {
//            showVideo();
//        }
        new MyAsyncTask(this).execute();
    }


    public void showVideo() {
        try {
            Intent intent = new Intent("android.intent.action.VIEW");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri contentUri = FileProvider.getUriForFile(getBaseContext(), "com.aiyaapp.aiya.test.fileprovider", new File(videoPath));
                intent.setDataAndType(contentUri, "video/mp4");
            } else {
                intent.setDataAndType(Uri.fromFile(new File(videoPath)), "video/mp4");
            }

            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    class MyAsyncTask extends AsyncTask<Void, Integer, Boolean> {

        Activity contextParent;

        public MyAsyncTask(Activity contextParent) {
            this.contextParent = contextParent;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            //H??m n??y s??? ch???y ?????u ti??n khi AsyncTask n??y ???????c g???i
            //??? ????y m??nh s??? th??ng b??o qu?? tr??nh load b???t ????u "Start"
            Toast.makeText(contextParent, "Start", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            //H??m ???????c ???????c hi???n ti???p sau h??m onPreExecute()
            //H??m n??y th???c hi???n c??c t??c v??? ch???y ng???m
            //Tuy???t ?????i k v??? giao di???n trong h??m n??y

            return closeMediaCodec() && new File(videoPath).exists();
        }


        @Override
        protected void onPostExecute(Boolean value) {
            super.onPostExecute(value);
            //H??m n??y ???????c th???c hi???n khi ti???n tr??nh k???t th??c
            //??? ????y m??nh th??ng b??o l?? ???? "Finshed" ????? ng?????i d??ng bi???t


            // ??? ????y ????? hi???n Video n??y
//            if (value) showVideo();
            Toast.makeText(contextParent, "Finished", Toast.LENGTH_SHORT).show();
        }
    }

    public void switchCamera() {
        if (mCurrentCameraID == FRONT_CAMERA_ID) {
            openBackCamera();
        } else if (mCurrentCameraID == BACK_CAMERA_ID) {
            openFrontCamera();
        }
    }

    /**
     * ???????????????
     */
    private boolean startMediaCodec() {

        // ??????????????????
        int width = 1920; // ??????????????????????????????90???
        int height = 1080;
        int bitRate = 5000000; // ??????: 1Mbps
        int fps = 120; // ??????: 30
        int iFrameInterval = 1; // GOP: 30

        // ??????????????????
        int audioBitRate = 128000; // ??????: 128kbps
        int sampleRate = 16000; // ?????????

        // ???????????????
        CodecInfo codecInfo = getAvcSupportedFormatInfo();
        if (codecInfo == null) {
            Log.d(TAG, "??????????????????");
            return false;
        }

        // ???????????????????????????????????????????????????
        if (width > codecInfo.maxWidth) {
            width = codecInfo.maxWidth;
        }
        if (height > codecInfo.maxHeight) {
            height = codecInfo.maxHeight;
        }
        if (bitRate > codecInfo.bitRate) {
            bitRate = codecInfo.bitRate;
        }
        if (fps > codecInfo.fps) {
            fps = codecInfo.fps;
        }

        Log.d(TAG, "?????????????????????????????????" + "width = " + width + "height = " + height + "bitRate = " + bitRate
                + "fps = " + fps + "IFrameInterval = " + iFrameInterval);

        // ????????????
        encoder = new AYMediaCodecEncoder(videoPath);
        encoder.setContentMode(kAYGPUImageScaleAspectFill);
        encoder.setMediaCodecEncoderListener(this);
        boolean videoCodecInitResult = encoder.configureVideoCodec(surfaceView.eglContext, width, height, bitRate, fps, iFrameInterval);
        boolean audioCodecInitResult = encoder.configureAudioCodec(audioBitRate, sampleRate, 1);

        isCodecInit = videoCodecInitResult && audioCodecInitResult;
        return isCodecInit;
    }

    /**
     * ???????????????
     */
    private boolean closeMediaCodec() {
        // ????????????
        if (encoder != null) {
            Log.d(TAG, "???????????????");
            encoder.finish();
            encoder = null;
        }

        boolean recordSuccess = videoCodecConfigResult && audioCodecConfigResult;

        // ?????????????????????
        videoCodecConfigResult = false;
        audioCodecConfigResult = false;

        return recordSuccess;
    }

    @Override
    public void encoderOutputVideoFormat(MediaFormat format) {
        videoCodecConfigResult = true;
        if (videoCodecConfigResult && audioCodecConfigResult) {
            encoder.start();
        }
    }

    @Override
    public void encoderOutputAudioFormat(MediaFormat format) {
        audioCodecConfigResult = true;
        if (videoCodecConfigResult && audioCodecConfigResult) {
            encoder.start();
        }
    }
}
