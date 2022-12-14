package com.aiyaapp.aiya;

import android.app.Activity;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.aiyaapp.aiya.adapter.Adapter;
import com.aiyaapp.aiya.cameraTool.AYCameraPreviewListener;
import com.aiyaapp.aiya.cameraTool.AYCameraPreviewWrap;
import com.aiyaapp.aiya.cameraTool.AYPreviewView;
import com.aiyaapp.aiya.cameraTool.AYPreviewViewListener;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants;
import com.aiyaapp.aiya.recorderTool.AYAudioRecorderListener;
import com.aiyaapp.aiya.recorderTool.AYAudioRecorderWrap;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoder;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageContentMode.kAYGPUImageScaleAspectFill;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageRotateRight;
import static com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper.getAvcSupportedFormatInfo;

public class CameraActivity extends AppCompatActivity implements AYCameraPreviewListener, AYAudioRecorderListener, AYMediaCodecEncoderListener, AYPreviewViewListener, Adapter.OnItemClickListener, SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "CameraActivity";
    Camera camera;
    AYCameraPreviewWrap cameraPreviewWrap;
    AYPreviewView surfaceView;

    AYEffectHandler effectHandler;

    private Adapter adapter;
    private SeekBar seekBar;

    private int mode;
    private Object data;
    String videoPath;
    // ?????????
    AudioRecord audioRecord;
    AYAudioRecorderWrap audioRecordWrap;

    volatile AYMediaCodecEncoder encoder;
    volatile boolean videoCodecConfigResult = false;
    volatile boolean audioCodecConfigResult = false;
    volatile boolean isCodecInit = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);
        surfaceView = findViewById(R.id.camera_preview);
        surfaceView.setListener(this);
        surfaceView.setContentMode(kAYGPUImageScaleAspectFill);

        initUI();

        try {
            initData();
        } catch (Exception e) {
            throw new IllegalStateException("??????????????????????????????");
        }
    }

    private void initUI() {
        RecyclerView recyclerView = findViewById(R.id.recycle_view);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getBaseContext(), LinearLayoutManager.HORIZONTAL, false);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new Adapter();
        adapter.setOnItemClickListener(this);

        recyclerView.setAdapter(adapter);

        seekBar = findViewById(R.id.seek_bar);
        seekBar.setOnSeekBarChangeListener(this);

        findViewById(R.id.effect_btn).setOnClickListener(v -> {
            adapter.setMode(0);
            seekBar.setProgress(0);
            seekBar.setVisibility(View.INVISIBLE);
        });

        findViewById(R.id.beauty_btn).setOnClickListener(v -> {
            adapter.setMode(1);
            seekBar.setProgress(0);
            seekBar.setVisibility(View.VISIBLE);
        });

        findViewById(R.id.style_btn).setOnClickListener(v -> {
            adapter.setMode(2);
            seekBar.setProgress(0);
            seekBar.setVisibility(View.VISIBLE);
        });

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
    }

    private void initData() throws IOException {
        String effectDataRootPath = getCacheDir() + File.separator + "effect" + File.separator + "data";
        String effectIconRootPath = getCacheDir() + File.separator + "effect" + File.separator + "icon";

        String styleDataRootPath = getCacheDir() + File.separator + "style" + File.separator + "data";
        String styleIconRootPath = getCacheDir() + File.separator + "style" + File.separator + "icon";

        // ?????????????????????
        List<String> effectNameList = new ArrayList<>();
        File[] effectRes = new File(effectDataRootPath).listFiles(pathname -> !pathname.getName().startsWith("."));
        for (File file : effectRes) {
            effectNameList.add(file.getName());
        }
        Collections.sort(effectNameList);

        // ????????????, ??????, ????????????
        List<Object[]> effectDataList = new ArrayList<>();
        for (String effectName : effectNameList) {

            String effectPath = effectDataRootPath + File.separator + effectName + File.separator + "meta.json";

            // ??????json????????????name
            StringBuilder stringBuilder = new StringBuilder();
            InputStream inputStream = new FileInputStream(effectPath);
            InputStreamReader isr = new InputStreamReader(inputStream);
            BufferedReader reader = new BufferedReader(isr);
            String jsonLine;
            while ((jsonLine = reader.readLine()) != null) {
                stringBuilder.append(jsonLine);
            }
            reader.close();
            isr.close();
            inputStream.close();

            JSONObject jsonObject = null;
            try {
                jsonObject = new JSONObject(stringBuilder.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }

            String iconPath = effectIconRootPath + File.separator + effectName + ".png";
            String name = jsonObject.optString("name");

            effectDataList.add(new Object[]{iconPath, name, effectPath});
        }
        adapter.refreshEffectData(effectDataList);

        // ????????????
        List<Object[]> beautyDataList = new ArrayList<>();
        beautyDataList.add(new Object[]{R.mipmap.beautify, "??????", "smooth"});
        beautyDataList.add(new Object[]{R.mipmap.beautify, "??????", "ruby"});
        beautyDataList.add(new Object[]{R.mipmap.beautify, "??????", "white"});
        beautyDataList.add(new Object[]{R.mipmap.beautify, "??????", "bigEye"});
        beautyDataList.add(new Object[]{R.mipmap.beautify, "??????", "slimFace"});
        adapter.refreshBeautifyData(beautyDataList);

        // ????????????
        List<String> styleNameList = new ArrayList<>();
        File[] styleRes = new File(styleDataRootPath).listFiles(pathname -> !pathname.getName().startsWith("."));
        for (File file : styleRes) {
            styleNameList.add(file.getName());
        }
        Collections.sort(styleNameList);

        // ????????????, ??????, ????????????
        List<Object[]> styleDataList = new ArrayList<>();
        for (String styleName : styleNameList) {
            String iconPath = styleIconRootPath + File.separator + styleName;
            String name = styleName.substring("00".length(), styleName.length() - ".png".length());
            String stylePath = styleDataRootPath + File.separator + styleName;
            styleDataList.add(new Object[]{iconPath, name, stylePath});
        }
        adapter.refreshStyleData(styleDataList);
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

    @Override
    public void audioRecorderOutput(ByteBuffer byteBuffer, long timestamp) {
        // ??????????????????
        if (encoder != null && isCodecInit) {
            encoder.writePCMByteBuffer(byteBuffer, timestamp);
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
        AYMediaCodecEncoderHelper.CodecInfo codecInfo = getAvcSupportedFormatInfo();
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



    // ---------- UI?????? ----------

    @Override
    public void onItemClick(int mode, Object data) {
        this.mode = mode;
        this.data = data;

        if (mode == 0) {
            // ????????????
            effectHandler.setEffectPath(data.toString());
            effectHandler.setEffectPlayCount(0);

        } else if (mode == 1) {
            // ????????????????????????
            effectHandler.setBeautyType(AyBeauty.AY_BEAUTY_TYPE.AY_BEAUTY_TYPE_3);

        } else if (mode == 2) {
            // ????????????
            effectHandler.setStyle(BitmapFactory.decodeFile(data.toString()));
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (mode == 1) {
            // ????????????
            if ("smooth".contentEquals(data.toString())) {
                effectHandler.setIntensityOfSmooth(progress / 100.f);

            } else if ("ruby".contentEquals(data.toString())) {
                effectHandler.setIntensityOfSaturation(progress / 100.f);

            } else if ("white".contentEquals(data.toString())) {
                effectHandler.setIntensityOfWhite(progress / 100.f);

            } else if ("bigEye".contentEquals(data.toString())) {
                effectHandler.setIntensityOfBigEye(progress / 100.f);

            } else if ("slimFace".contentEquals(data.toString())) {
                effectHandler.setIntensityOfSlimFace(progress / 100.f);

            }

        } else if (mode == 2) {
            // ????????????
            effectHandler.setIntensityOfStyle(progress / 100.f);

        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    // ---------- ???????????? ----------

    /**
     * ??????????????????
     */
    private void openHardware() {
        if (camera != null) {
            camera.release();
            camera = null;
        }
        camera = Camera.open(1);
        setCameraDisplayOrientation(this, camera);

        cameraPreviewWrap = new AYCameraPreviewWrap(camera);
        cameraPreviewWrap.setPreviewListener(this);
        cameraPreviewWrap.setRotateMode(kAYGPUImageRotateRight);
        cameraPreviewWrap.startPreview(surfaceView.eglContext);

        openAudioRecorder();
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
     * @see <a
     * href="http://stackoverflow.com/questions/12216148/android-screen-orientation-differs-between-devices">SO
     * post</a>
     */
    public static  void setCameraDisplayOrientation(Context context, Camera mCamera) {
        final int rotationOffset;
        // Check "normal" screen orientation and adjust accordingly
        int naturalOrientation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRotation();
        if (naturalOrientation == Surface.ROTATION_0) {
            rotationOffset = 0;
        } else if (naturalOrientation == Surface.ROTATION_90) {
            rotationOffset = 90;
        } else if (naturalOrientation == Surface.ROTATION_180) {
            rotationOffset = 180;
        } else if (naturalOrientation == Surface.ROTATION_270) {
            rotationOffset = 270;
        } else {
            // just hope for the best (shouldn't happen)
            rotationOffset = 0;
        }

        int result;

        /* check API level. If upper API level 21, re-calculate orientation. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.hardware.Camera.CameraInfo info =
                    new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(0, info);
            int cameraOrientation = info.orientation;
            result = (cameraOrientation - rotationOffset + 360) % 360;
        } else {
            /* if API level is lower than 21, use the default value */
            result = 90;
        }

        /*set display orientation*/
        mCamera.setDisplayOrientation(result);
    }


    /**
     * ??????????????????
     */
    private void closeHardware() {
        // ????????????
        if (camera != null) {
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
        effectHandler.setIntensityOfSlimFace(0);
        effectHandler.setIntensityOfBigEye(0);
        effectHandler.setEffectPath("");
    }


    public void destroyEffectHandler() {
        if (effectHandler != null) {
            effectHandler.destroy();
            effectHandler = null;
        }
    }


    @Override
    public void cameraVideoOutput(int texture, int width, int height, long timestamp) {
        // ??????????????????
        if (effectHandler != null) {
            effectHandler.processWithTexture(texture, width, height);
        }

        // ?????????surfaceView
        if (surfaceView != null) {
            surfaceView.render(texture, width, height);
        }

        // ??????????????????
        if (encoder != null && isCodecInit) {
            encoder.writeImageTexture(texture, width, height, timestamp);
        }
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
}
