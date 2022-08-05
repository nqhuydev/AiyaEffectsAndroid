package com.aiyaapp.aiya;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.BitmapFactory;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.aiyaapp.aiya.cameraTool.AYPreviewView;
import com.aiyaapp.aiya.cameraTool.AYPreviewViewListener;
import com.aiyaapp.aiya.decoderTool.AYMediaCodecDecoder;
import com.aiyaapp.aiya.decoderTool.AYMediaCodecDecoderListener;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoder;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper;
import com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderListener;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageContentMode.kAYGPUImageScaleAspectFill;
import static com.aiyaapp.aiya.recorderTool.AYMediaCodecEncoderHelper.getAvcSupportedFormatInfo;

/**
 * 解码和编码
 */
public class DecoderAndEncoderActivity extends AppCompatActivity implements AYPreviewViewListener, AYMediaCodecDecoderListener, AYMediaCodecEncoderListener {

    private static final String TAG = "DecoderAndEncoder";

    // 用于预览解码画面的surface
    private AYPreviewView surfaceView;
    volatile boolean foreground = false;

    // 音视频硬编码
    String videoPath;
    volatile AYMediaCodecEncoder encoder;
    volatile boolean videoEncoderInitResult = false;
    volatile boolean audioEncoderInitResult = false;
    volatile boolean videoEncoderConfigResult = false;
    volatile boolean audioEncoderConfigResult = false;
    
    // 音视频硬解码
    volatile AYMediaCodecDecoder decoder;
    volatile boolean videoDecoderEOS = false;
    volatile boolean audioDecoderEOS = false;

    // 相机处理
    AYEffectHandler effectHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_decoder_and_encoder);

        surfaceView = findViewById(R.id.decoder_and_encoder_preview);
        surfaceView.setListener(this);

        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean result = startDecoder();
                if (!result) {
                    Toast.makeText(DecoderAndEncoderActivity.this, "Failed to initialize decoder", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void createGLEnvironment() {
        foreground = true;
    }

    @Override
    public void destroyGLEnvironment() {
        foreground = false;

        if (decoder != null) {
            decoder.abortDecoder();
            decoder = null;
        }

        if (encoder != null) {
            encoder.finish();
            encoder = null;
        }

        surfaceView.eglContext.syncRunOnRenderThread(this::destroyEffectHandler);
    }

    private boolean startDecoder() {

        // 设置视频编辑完成后路径
        videoPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();
        videoPath = videoPath + File.separator + getString(R.string.app_name) + UUID.randomUUID().toString().replace("-", "") + ".mp4";

        // 启动编码, 每次都是先设置编码器
        videoEncoderInitResult = false;
        audioEncoderInitResult = false;
        videoEncoderConfigResult = false;
        audioEncoderConfigResult = false;
        encoder = new AYMediaCodecEncoder(videoPath);
        encoder.setContentMode(kAYGPUImageScaleAspectFill);
        encoder.setMediaCodecEncoderListener(this);

        // 启动解码
        videoDecoderEOS = false;
        audioDecoderEOS = false;
        AssetFileDescriptor masterFd = getResources().openRawResourceFd(R.raw.test);
        try {
            decoder = new AYMediaCodecDecoder(masterFd);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        decoder.setDecoderListener(this);
        boolean videoDecoderInitResult = decoder.configureVideoCodec(surfaceView.eglContext);
        boolean audioDecoderInitResult = decoder.configureAudioCodec();
        boolean initDecoderResult = videoDecoderInitResult && audioDecoderInitResult;

        // 初始化特效处理
        if (initDecoderResult) {
            surfaceView.eglContext.syncRunOnRenderThread(this::createEffectHandler);
        }

        return initDecoderResult;
    }

    @Override
    public void decoderOutputVideoFormat(int _width, int _height) {

        // 图像编码参数
        int height = _width; // 视频编码时图像旋转了90度
        int width = _height;
        int bitRate = 1000000; // 码率: 1Mbps
        int fps = 30; // 帧率: 30
        int iFrameInterval = 1; // GOP: 30

        // 编码器信息
        AYMediaCodecEncoderHelper.CodecInfo codecInfo = getAvcSupportedFormatInfo();
        if (codecInfo == null) {
            Log.d(TAG, "不支持硬编码");
            return;
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

        Log.d(TAG, "开始视频编码，初始化参数 : " + "width = " + width + "height = " + height + "bitRate = " + bitRate
                + "fps = " + fps + "IFrameInterval = " + iFrameInterval);

        int finalWidth = width;
        int finalHeight = height;
        int finalBitRate = bitRate;
        int finalFps = fps;

        videoEncoderInitResult = encoder.configureVideoCodec(surfaceView.eglContext, finalWidth, finalHeight, finalBitRate, finalFps, iFrameInterval);

        if (videoEncoderInitResult && audioEncoderInitResult) {
            decoder.start();
        }
    }

    @Override
    public void decoderOutputAudioFormat(int sampleRate, int channelCount) {

        // 音频编码参数
        int audioBitRate = 128000; // 码率: 128kbps

        audioEncoderInitResult = encoder.configureAudioCodec(audioBitRate, sampleRate, channelCount);

        if (videoEncoderInitResult && audioEncoderInitResult) {
            decoder.start();
        }
    }

    @Override
    public void decoderVideoOutput(int texture, int width, int height, long timestamp) {

        // 渲染特效美颜
        if (effectHandler != null) {
            effectHandler.processWithTexture(texture, width, height);
        }

        // 渲染到surfaceView
        surfaceView.render(texture, width, height);

        // 编码器视频编码
        encoder.writeImageTexture(texture, width, height, timestamp);
    }

    @Override
    public void decoderAudioOutput(ByteBuffer byteBuffer, long timestamp) {

        // 编码器音频编码
        encoder.writePCMByteBuffer(byteBuffer, timestamp);
    }

    @Override
    public void decoderVideoEOS() {
        videoDecoderEOS = true;
        if (videoDecoderEOS && audioDecoderEOS) {
            encoder.finish();

            if (new File(videoPath).exists()) {
                showVideo();
            }
        }
    }

    @Override
    public void decoderAudioEOS() {
        audioDecoderEOS = true;
        if (videoDecoderEOS && audioDecoderEOS) {
            encoder.finish();

            showVideo();
        }
    }

    @Override
    public void encoderOutputVideoFormat(MediaFormat format) {
        videoEncoderConfigResult = true;
        if (videoEncoderConfigResult && audioEncoderConfigResult) {
            encoder.start();
        }
    }

    @Override
    public void encoderOutputAudioFormat(MediaFormat format) {
        audioEncoderConfigResult = true;
        if (videoEncoderConfigResult && audioEncoderConfigResult) {
            encoder.start();
        }
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

    public void showVideo() {
        if (new File(videoPath).exists()) {
            Log.d(TAG, "PATH: " + videoPath);
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Uri contentUri = FileProvider.getUriForFile(getBaseContext(), BuildConfig.APPLICATION_ID + ".provider", new File(videoPath));
                    intent.setDataAndType(contentUri, "video/mp4");
                } else {
                    intent.setDataAndType(Uri.fromFile(new File(videoPath)), "video/mp4");
                }
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
