package com.aiyaapp.aiya.decoderTool;

import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.aiyaapp.aiya.gpuImage.AYGLProgram;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode;
import com.aiyaapp.aiya.gpuImage.AYGPUImageEGLContext;
import com.aiyaapp.aiya.gpuImage.AYGPUImageFilter;
import com.aiyaapp.aiya.gpuImage.AYGPUImageFramebuffer;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_TEXTURE2;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES20.GL_TRIANGLE_STRIP;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glDisableVertexAttribArray;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glFinish;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glTexParameterf;
import static android.opengl.GLES20.glUniform1i;
import static android.opengl.GLES20.glVertexAttribPointer;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageFlipHorizontal;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageFlipVertical;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageRotateRightFlipHorizontal;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageRotationMode.kAYGPUImageRotateRightFlipVertical;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.needExchangeWidthAndHeightWithRotation;

/**
 *
 * MediaCodec?????????????????????Google Codec Sample
 * https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/DecoderTest.java
 *
 */
public class AYMediaCodecDecoder implements SurfaceTexture.OnFrameAvailableListener {

    // ----- GLES ???????????? -----
    private static final String kAYOESTextureFragmentShader = "" +
            "#extension GL_OES_EGL_image_external : require\n" +
            "\n" +
            "varying highp vec2 textureCoordinate;\n" +
            "\n" +
            "uniform samplerExternalOES inputImageTexture;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(inputImageTexture, textureCoordinate);\n" +
            "}";

    private AYGPUImageEGLContext eglContext;

    private SurfaceTexture surfaceTexture;

    private Surface surface;

    private int oesTexture;

    private AYGPUImageFramebuffer outputFramebuffer;

    private AYGPUImageRotationMode rotationMode = kAYGPUImageFlipVertical;
    private int inputWidth;
    private int inputHeight;

    private AYGLProgram filterProgram;

    private int filterPositionAttribute, filterTextureCoordinateAttribute;
    private int filterInputTextureUniform;

    private Buffer imageVertices = AYGPUImageConstants.floatArrayToBuffer(AYGPUImageConstants.imageVertices);

    // ----- MediaCodec ???????????? -----
    private static final int TIMEOUT = 1000;

    private MediaExtractor videoExtractor;
    private MediaExtractor audioExtractor;

    // ?????????
    private MediaCodec videoDecoder;
    private MediaCodec audioDecoder;

    // ?????????????????????????????????
    private Boolean isDecoderAbort = false;
    private ReadWriteLock decoderAbortLock = new ReentrantReadWriteLock(true);

    // ??????????????????
    private final Object decoderFrameSyncObject = new Object();     // guards decoderFrameAvailable
    private boolean decoderFrameAvailable = false;

    // ??????
    volatile private boolean isStart = false;

    private AYMediaCodecDecoderListener decoderListener;

    private int renderCount;

    public AYMediaCodecDecoder(String path) throws IOException {
        videoExtractor = new MediaExtractor();
        audioExtractor = new MediaExtractor();

        videoExtractor.setDataSource(path);
        audioExtractor.setDataSource(path);
    }

    public AYMediaCodecDecoder(AssetFileDescriptor fileDescriptor) throws IOException {
        videoExtractor = new MediaExtractor();
        audioExtractor = new MediaExtractor();

        videoExtractor.setDataSource(fileDescriptor.getFileDescriptor(), fileDescriptor.getStartOffset(), fileDescriptor.getLength());
        audioExtractor.setDataSource(fileDescriptor.getFileDescriptor(), fileDescriptor.getStartOffset(), fileDescriptor.getLength());
    }

    public boolean configureVideoCodec(AYGPUImageEGLContext eglContext) {
        this.eglContext = eglContext;

        // ??????????????????
        MediaFormat videoFormat = null;

        int videoTrack = 0;

        for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
            MediaFormat format = videoExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video")) {
                videoFormat = format;
                videoTrack = i;
            }
        }

        if (videoFormat == null) {
            Log.w(AYGPUImageConstants.TAG, "????  decoder -> no exist video track");
            return false;
        }

        // ??????MediaCodec????????????
        boolean hadError = false;

        createGLEnvironment();

        // ?????????????????????
        try {
            videoDecoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
            videoDecoder.configure(videoFormat, surface, null, 0);
        } catch (Throwable e) {
            Log.w(AYGPUImageConstants.TAG, "????  decoder -> video mediaCodec create error: " + e);
            hadError = true;
        } finally {
            if (videoDecoder != null && hadError) {
                videoDecoder.stop();
                videoDecoder.release();
                videoDecoder = null;
            }
        }

        if (hadError || videoDecoder == null) {
            return false;
        }

        if (videoFormat.containsKey("rotation-degrees")) {
            int rotation = videoFormat.getInteger("rotation-degrees");
            if ((rotation % 90) == 0) {
                switch ((rotation / 90) & 3) {
                    case 1:
                        rotationMode = kAYGPUImageRotateRightFlipHorizontal;
                        break;
                    case 2:
                        rotationMode = kAYGPUImageFlipHorizontal;
                        break;
                    case 3:
                        rotationMode = kAYGPUImageRotateRightFlipVertical;
                        break;
                }
            }
        }
        inputWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        inputHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);

        if (needExchangeWidthAndHeightWithRotation(rotationMode)) {
            int temp = inputWidth;
            inputWidth = inputHeight;
            inputHeight = temp;
        }

        videoDecoder.start();

        videoExtractor.selectTrack(videoTrack);

        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                boolean outputDone = false;
                boolean inputDone = false;
                boolean isVideoDecoderReady = false;

                while (!outputDone) {

                    decoderAbortLock.readLock().lock();

                    if (isDecoderAbort) {
                        Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)????????????");
                        decoderAbortLock.readLock().unlock();
                        return;
                    }

                    if (!inputDone) {

                        // ??????????????????????????????
                        int inputBufIndex = videoDecoder.dequeueInputBuffer(TIMEOUT);

                        if (inputBufIndex >= 0) {

                            ByteBuffer inputBuf;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                inputBuf = videoDecoder.getInputBuffer(inputBufIndex);
                            } else {
                                inputBuf = videoDecoder.getInputBuffers()[inputBufIndex];
                            }

                            int sampleSize = videoExtractor.readSampleData(inputBuf, 0);
                            long presentationTimeUs = 0;
                            if (sampleSize < 0) {
                                inputDone = true;
                                sampleSize = 0;
                            } else {
                                presentationTimeUs = videoExtractor.getSampleTime();
                            }

                            videoDecoder.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    inputDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                            if (!inputDone) {
                                // ??????????????????
                                videoExtractor.advance();
                            }
                        }
                    }

                    // ??????????????????????????????, ??????????????????
                    if (isVideoDecoderReady && !isStart) {
                        decoderAbortLock.readLock().unlock();
                        SystemClock.sleep(1);
                        continue;
                    }

                    int outputBufIndex = videoDecoder.dequeueOutputBuffer(info, TIMEOUT);

                    if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)???????????????");
                        isVideoDecoderReady = true;

                        if (decoderListener != null) {
                            decoderListener.decoderOutputVideoFormat(inputWidth, inputHeight);
                        }

                    } else if (outputBufIndex >= 0) {

                        // ??????????????????
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)????????????");
                            outputDone = true;
                        }

                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                        // that the texture will be available before the call returns, so we
                        // need to wait for the onFrameAvailable callback to fire.
                        videoDecoder.releaseOutputBuffer(outputBufIndex, info.size != 0);
                        if (info.size != 0) {
                            awaitNewImage();
                        }
                    } else {
                        SystemClock.sleep(1);
                    }

                    decoderAbortLock.readLock().unlock();
                }

                decoderAbortLock.readLock().lock();

                if (isDecoderAbort) {
                    Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)????????????");
                    decoderAbortLock.readLock().unlock();
                    return;
                }

                releaseVideoDecoder();

                if (decoderListener != null) {
                    decoderListener.decoderVideoEOS();
                }

                decoderAbortLock.readLock().unlock();
            }
        }).start();

        return true;
    }

    public boolean configureAudioCodec() {
        // ??????????????????
        MediaFormat audioFormat = null;

        int audioTrack = 0;

        for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
            MediaFormat format = videoExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio")) {
                audioFormat = format;
                audioTrack = i;
            }
        }

        if (audioFormat == null) {
            Log.w(AYGPUImageConstants.TAG, "????  decoder -> no exist audio track");
            return false;
        }

        // ??????MediaCodec????????????
        boolean hadError = false;

        // ?????????????????????
        try {
            audioDecoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
            audioDecoder.configure(audioFormat, null, null, 0);
        } catch (Throwable e) {
            Log.w(AYGPUImageConstants.TAG, "????  decoder -> audio mediaCodec create error: " + e);
            hadError = true;
        } finally {
            if (audioDecoder != null && hadError) {
                audioDecoder.stop();
                audioDecoder.release();
                audioDecoder = null;
            }
        }

        if (hadError || audioDecoder == null) {
            return false;
        }

        final int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); // ?????????
        final int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT); // ?????????

        audioDecoder.start();

        audioExtractor.selectTrack(audioTrack);

        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                boolean outputDone = false;
                boolean inputDone = false;
                boolean isAudioDecoderReady = false;

                while (!outputDone) {

                    decoderAbortLock.readLock().lock();

                    if (isDecoderAbort) {
                        Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)????????????");
                        decoderAbortLock.readLock().unlock();
                        return;
                    }

                    if (!inputDone) {

                        // ??????????????????????????????
                        int inputBufIndex = audioDecoder.dequeueInputBuffer(TIMEOUT);
                        if (inputBufIndex >= 0) {

                            ByteBuffer inputBuf;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                inputBuf = audioDecoder.getInputBuffer(inputBufIndex);
                            } else {
                                inputBuf = audioDecoder.getInputBuffers()[inputBufIndex];
                            }

                            int sampleSize = audioExtractor.readSampleData(inputBuf, 0);
                            long presentationTimeUs = 0;
                            if (sampleSize < 0) {
                                inputDone = true;
                                sampleSize = 0;
                            } else {
                                presentationTimeUs = audioExtractor.getSampleTime();
                            }

                            audioDecoder.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    inputDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                            if (!inputDone) {
                                // ??????????????????
                                audioExtractor.advance();
                            }
                        }
                    }

                    // ??????????????????????????????, ??????????????????
                    if (isAudioDecoderReady && !isStart) {
                        decoderAbortLock.readLock().unlock();
                        SystemClock.sleep(1);
                        continue;
                    }

                    int outputBufIndex = audioDecoder.dequeueOutputBuffer(info, TIMEOUT);

                    if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)???????????????");
                        isAudioDecoderReady = true;

                        if (decoderListener != null) {
                            decoderListener.decoderOutputAudioFormat(sampleRate, channelCount);
                        }

                    } else if (outputBufIndex >= 0) {

                        // ??????????????????
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)????????????");
                            outputDone = true;
                        }

                        if (!outputDone) {
                            ByteBuffer outputBuf;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                outputBuf = audioDecoder.getOutputBuffer(outputBufIndex);
                            } else {
                                outputBuf = audioDecoder.getOutputBuffers()[outputBufIndex];
                            }

                            if (decoderListener != null) {
                                decoderListener.decoderAudioOutput(outputBuf, info.presentationTimeUs * 1000);
                            }
                        }

                        audioDecoder.releaseOutputBuffer(outputBufIndex, false);

                    } else {
                        SystemClock.sleep(1);
                    }

                    decoderAbortLock.readLock().unlock();
                }

                decoderAbortLock.readLock().lock();

                if (isDecoderAbort) {
                    Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????????(??????)????????????");
                    decoderAbortLock.readLock().unlock();
                    return;
                }

                releaseAudioDecoder();

                if (decoderListener != null) {
                    decoderListener.decoderAudioEOS();
                }

                decoderAbortLock.readLock().unlock();
            }
        }).start();

        return true;
    }

    public void setDecoderListener(AYMediaCodecDecoderListener decoderListener) {
        this.decoderListener = decoderListener;
    }

    /**
     * ??????????????????
     */
    public void start() {
        isStart = true;
    }

    /**
     * ???????????????, ?????????????????????????????????, ?????????????????????
     */
    public void abortDecoder() {

        // ??????MediaCodec????????????
        decoderAbortLock.writeLock().lock();
        isDecoderAbort = true;
        decoderAbortLock.writeLock().unlock();

        releaseVideoDecoder();

        releaseAudioDecoder();

    }

    private void releaseVideoDecoder() {
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder.release();
            videoDecoder = null;

            Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????? ?????????(??????) ?????????????????????: " + renderCount);
            renderCount = 0;
        }

        if (videoExtractor != null) {
            videoExtractor.release();
            videoExtractor = null;
        }

        if (eglContext != null) {
            destroyGLEnvironment();
            eglContext = null;
        }
    }

    private void releaseAudioDecoder() {
        if (audioDecoder != null) {
            audioDecoder.stop();
            audioDecoder.release();
            audioDecoder = null;
            Log.i(AYGPUImageConstants.TAG, "????  decoder -> ?????? ?????????(??????)");
        }

        if (audioExtractor != null) {
            audioExtractor.release();
            audioExtractor = null;
        }
    }

    private void createGLEnvironment() {
        eglContext.syncRunOnRenderThread(() -> {
            eglContext.makeCurrent();

            oesTexture = createOESTextureID();
            surfaceTexture = new SurfaceTexture(oesTexture);
            surfaceTexture.setOnFrameAvailableListener(AYMediaCodecDecoder.this);

            surface = new Surface(surfaceTexture);

            filterProgram = new AYGLProgram(AYGPUImageFilter.kAYGPUImageVertexShaderString, kAYOESTextureFragmentShader);
            filterProgram.link();

            filterPositionAttribute = filterProgram.attributeIndex("position");
            filterTextureCoordinateAttribute = filterProgram.attributeIndex("inputTextureCoordinate");
            filterInputTextureUniform = filterProgram.uniformIndex("inputImageTexture");
        });
    }

    private void awaitNewImage() {
        final int TIMEOUT_MS = 500;
        synchronized (decoderFrameSyncObject) {
            if (!decoderFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    decoderFrameSyncObject.wait(TIMEOUT_MS);
                } catch (InterruptedException ie) {
                    // shouldn't happen
                    throw new RuntimeException(ie);
                }
            }
            decoderFrameAvailable = false;
        }
    }

    @Override
    public void onFrameAvailable(final SurfaceTexture surfaceTexture) {
        synchronized (decoderFrameSyncObject) {
            decoderFrameAvailable = true;
            decoderFrameSyncObject.notifyAll();
        }

        decoderAbortLock.readLock().lock();

        if (isDecoderAbort) {
            decoderAbortLock.readLock().unlock();
            return;
        }

        eglContext.syncRunOnRenderThread(() -> {
            eglContext.makeCurrent();

            if (EGL14.eglGetCurrentDisplay() != EGL14.EGL_NO_DISPLAY) {
                surfaceTexture.updateTexImage();

                glFinish();

                // ?????????shader?????????oes?????????????????????????????????, ???????????????????????????????????????????????????
                // ??????????????????????????????????????????????????????????????????
                renderToFramebuffer(oesTexture);
                renderCount++;

                if (decoderListener != null) {
                    decoderListener.decoderVideoOutput(outputFramebuffer.texture[0], inputWidth, inputHeight, surfaceTexture.getTimestamp());
                }

            }
        });

        decoderAbortLock.readLock().unlock();
    }

    private void renderToFramebuffer(int oesTexture) {

        filterProgram.use();

        if (outputFramebuffer != null) {
            if (inputWidth != outputFramebuffer.width || inputHeight != outputFramebuffer.height) {
                outputFramebuffer.destroy();
                outputFramebuffer = null;
            }
        }

        if (outputFramebuffer == null) {
            outputFramebuffer = new AYGPUImageFramebuffer(inputWidth, inputHeight);
        }

        outputFramebuffer.activateFramebuffer();

        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture);

        glUniform1i(filterInputTextureUniform, 2);

        glEnableVertexAttribArray(filterPositionAttribute);
        glEnableVertexAttribArray(filterTextureCoordinateAttribute);

        glVertexAttribPointer(filterPositionAttribute, 2, GL_FLOAT, false, 0, imageVertices);
        glVertexAttribPointer(filterTextureCoordinateAttribute, 2, GL_FLOAT, false, 0, AYGPUImageConstants.floatArrayToBuffer(AYGPUImageConstants.textureCoordinatesForRotation(rotationMode)));

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(filterPositionAttribute);
        glDisableVertexAttribArray(filterTextureCoordinateAttribute);
    }

    private int createOESTextureID() {
        int[] texture = new int[1];
        glGenTextures(1, texture, 0);

        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture[0]);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        return texture[0];
    }

    private void destroyGLEnvironment() {
        eglContext.syncRunOnRenderThread(() -> {

            if (filterProgram != null) {
                filterProgram.destroy();
                filterProgram = null;
            }

            if (outputFramebuffer != null) {
                outputFramebuffer.destroy();
                outputFramebuffer = null;
            }

            if (surface != null) {
                surface.release();
                surface = null;
            }
        });
    }
}
