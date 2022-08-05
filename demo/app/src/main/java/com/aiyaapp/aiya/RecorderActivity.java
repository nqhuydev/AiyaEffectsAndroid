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

    // 相机
    Camera camera;
    AYCameraPreviewWrap cameraPreviewWrap;
    public static final int FRONT_CAMERA_ID = 1;
    public static final int BACK_CAMERA_ID = 0;
    int mCurrentCameraID = FRONT_CAMERA_ID;

    // 麦克风
    AudioRecord audioRecord;
    AYAudioRecorderWrap audioRecordWrap;

    // 相机处理
    AYEffectHandler effectHandler;

    // 预览的surface
    AYPreviewView surfaceView;

    // 音视频硬编码
    volatile AYMediaCodecEncoder encoder;
    volatile boolean videoCodecConfigResult = false;
    volatile boolean audioCodecConfigResult = false;
    volatile boolean isCodecInit = false;

    // 视频保存路径
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
     * 打开硬件设备
     */
    private void openHardware() {
        // 打开前置相机
        openFrontCamera();

        // 打开后置相机
//        openBackCamera();

        // 打开麦克风
        openAudioRecorder();
    }

    /**
     * 打开前置相机
     */
    private void openFrontCamera() {
        if (cameraPreviewWrap != null) {
            cameraPreviewWrap.stopPreview();
        }
        if (camera != null) {
            camera.release();
        }

        Log.d(TAG, "打开前置相机");
        mCurrentCameraID = FRONT_CAMERA_ID;
        camera = Camera.open(mCurrentCameraID); // TODO 省略判断是否有前置相机
        setCameraDisplayOrientation(this, camera);

        cameraPreviewWrap = new AYCameraPreviewWrap(camera);
        cameraPreviewWrap.setPreviewListener(this);

        cameraPreviewWrap.setRotateMode(kAYGPUImageRotateRight); // TODO 如果画面方向不对, 修改此值
        cameraPreviewWrap.startPreview(surfaceView.eglContext);
    }

    /**
     * 打开后置相机
     */
    private void openBackCamera() {
        if (cameraPreviewWrap != null) {
            cameraPreviewWrap.stopPreview();
        }
        if (camera != null) {
            camera.release();
        }

        Log.d(TAG, "打开后置相机");
        mCurrentCameraID = BACK_CAMERA_ID;
        camera = Camera.open(mCurrentCameraID);
        setCameraDisplayOrientation(this, camera);

        cameraPreviewWrap = new AYCameraPreviewWrap(camera);
        cameraPreviewWrap.setPreviewListener(this);

        cameraPreviewWrap.setRotateMode(kAYGPUImageRotateRightFlipHorizontal); // TODO 如果画面方向不对, 修改此值
        cameraPreviewWrap.startPreview(surfaceView.eglContext);
    }

    /**
     * 打开麦克风
     */
    private void openAudioRecorder() {
        Log.d(TAG, "打开麦克风");

        // 音频编码固定参数
        final int audioSampleRate = 16000;   //音频采样率
        final int audioChannel = AudioFormat.CHANNEL_IN_MONO;   //单声道
        final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; //音频录制格式

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
     * 关闭硬件设备
     */
    private void closeHardware() {
        // 关闭相机
        if (camera != null) {
            Log.d(TAG, "关闭相机");
            cameraPreviewWrap.stopPreview();
            cameraPreviewWrap = null;
            camera.release();
            camera = null;
        }

        // 关闭麦克风
        if (audioRecord != null) {
            Log.d(TAG, "关闭麦克风");
            audioRecordWrap.stop();
            audioRecordWrap = null;
            audioRecord.release();
            audioRecord = null;
        }

        // 如果正在录像, 停止录像
        closeMediaCodec();
    }

    public void createEffectHandler() {
        effectHandler = new AYEffectHandler(this);
        effectHandler.setRotateMode(AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageFlipVertical);
        // 设置特效
        effectHandler.setEffectPath(getCacheDir().getPath() + "/effect/data/2017/meta.json");
        effectHandler.setEffectPlayCount(0);
        // 设置美颜程度
        effectHandler.setBeautyType(AyBeauty.AY_BEAUTY_TYPE.AY_BEAUTY_TYPE_3);
        effectHandler.setIntensityOfSmooth(0.8f);
        effectHandler.setIntensityOfSaturation(0.2f);
        effectHandler.setIntensityOfWhite(0f);

        // 设置大眼瘦脸
        effectHandler.setIntensityOfBigEye(0.2f);
        effectHandler.setIntensityOfSlimFace(0.2f);

        // 添加滤镜
        effectHandler.setStyle(BitmapFactory.decodeFile(getCacheDir().getPath() + "/style/data/03桃花.png"));
    }

    public void destroyEffectHandler() {
        if (effectHandler != null) {
            effectHandler.destroy();
            effectHandler = null;
        }
    }

    /**
     * 相机数据回调
     * 注意: 函数运行在视频处理线程
     * This timestamp is in nanoseconds
     */
    @Override
    public void cameraVideoOutput(int texture, int width, int height, long timestamp) {

        // 渲染特效美颜
        if (effectHandler != null) {
            effectHandler.processWithTexture(texture, width, height);
        }

        // 渲染到surfaceView
        surfaceView.render(texture, width, height);

        // 进行视频编码
        if (encoder != null && isCodecInit) {
            encoder.writeImageTexture(texture, width, height, timestamp);
        }
    }

    /**
     * 麦克风数据回调
     * 注意: 函数运行在音频处理线程
     * This timestamp is in nanoseconds
     */
    @Override
    public void audioRecorderOutput(ByteBuffer byteBuffer, long timestamp) {

        // 进行音频编码
        if (encoder != null && isCodecInit) {
            encoder.writePCMByteBuffer(byteBuffer, timestamp);
        }
    }

    public void startRecord() {
        // 设置视频路径
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
            //Hàm này sẽ chạy đầu tiên khi AsyncTask này được gọi
            //Ở đây mình sẽ thông báo quá trình load bắt đâu "Start"
            Toast.makeText(contextParent, "Start", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            //Hàm được được hiện tiếp sau hàm onPreExecute()
            //Hàm này thực hiện các tác vụ chạy ngầm
            //Tuyệt đối k vẽ giao diện trong hàm này

            return closeMediaCodec() && new File(videoPath).exists();
        }


        @Override
        protected void onPostExecute(Boolean value) {
            super.onPostExecute(value);
            //Hàm này được thực hiện khi tiến trình kết thúc
            //Ở đây mình thông báo là đã "Finshed" để người dùng biết


            // Ở đây để hiện Video này
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
     * 启动编码器
     */
    private boolean startMediaCodec() {

        // 图像编码参数
        int width = 1920; // 视频编码时图像旋转了90度
        int height = 1080;
        int bitRate = 5000000; // 码率: 1Mbps
        int fps = 120; // 帧率: 30
        int iFrameInterval = 1; // GOP: 30

        // 音频编码参数
        int audioBitRate = 128000; // 码率: 128kbps
        int sampleRate = 16000; // 采样率

        // 编码器信息
        CodecInfo codecInfo = getAvcSupportedFormatInfo();
        if (codecInfo == null) {
            Log.d(TAG, "不支持硬编码");
            return false;
        }

        // 设置给编码器的参数不能超过其最大值
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

        Log.d(TAG, "开始编码，初始化参数；" + "width = " + width + "height = " + height + "bitRate = " + bitRate
                + "fps = " + fps + "IFrameInterval = " + iFrameInterval);

        // 启动编码
        encoder = new AYMediaCodecEncoder(videoPath);
        encoder.setContentMode(kAYGPUImageScaleAspectFill);
        encoder.setMediaCodecEncoderListener(this);
        boolean videoCodecInitResult = encoder.configureVideoCodec(surfaceView.eglContext, width, height, bitRate, fps, iFrameInterval);
        boolean audioCodecInitResult = encoder.configureAudioCodec(audioBitRate, sampleRate, 1);

        isCodecInit = videoCodecInitResult && audioCodecInitResult;
        return isCodecInit;
    }

    /**
     * 关闭编码器
     */
    private boolean closeMediaCodec() {
        // 关闭编码
        if (encoder != null) {
            Log.d(TAG, "关闭编码器");
            encoder.finish();
            encoder = null;
        }

        boolean recordSuccess = videoCodecConfigResult && audioCodecConfigResult;

        // 关闭编码器标志
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
