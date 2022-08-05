package com.aiyaapp.aiya.cameraTool;

import android.content.Context;
import android.graphics.PointF;
import android.opengl.EGL14;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.TextView;

import com.aiyaapp.aiya.gpuImage.AYGLProgram;
import com.aiyaapp.aiya.gpuImage.AYGPUImageConstants;
import com.aiyaapp.aiya.gpuImage.AYGPUImageEGLContext;
import com.aiyaapp.aiya.gpuImage.AYGPUImageFilter;

import java.nio.Buffer;

import static android.opengl.GLES20.*;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.AYGPUImageContentMode.kAYGPUImageScaleAspectFit;
import static com.aiyaapp.aiya.gpuImage.AYGPUImageConstants.getAspectRatioInsideSize;

public class AYPreviewView extends SurfaceView implements SurfaceHolder.Callback {

    private AYPreviewViewListener listener;

    public AYGPUImageEGLContext eglContext;

    private int boundingWidth;
    private int boundingHeight;

    private AYGLProgram filterProgram;

    private int filterPositionAttribute, filterTextureCoordinateAttribute;
    private int filterInputTextureUniform;

    private Buffer textureCoordinates = AYGPUImageConstants.floatArrayToBuffer(AYGPUImageConstants.noRotationTextureCoordinates);

    private AYGPUImageConstants.AYGPUImageContentMode contentMode = kAYGPUImageScaleAspectFit;

    public AYPreviewView(Context context) {
        super(context);
        commonInit();
    }

    public AYPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        commonInit();
    }

    public AYPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        commonInit();
    }

    private void commonInit() {
        getHolder().addCallback(this);
    }

    /**
     * 设置窗口缩放方式
     */
    public void setContentMode(AYGPUImageConstants.AYGPUImageContentMode contentMode) {
        this.contentMode = contentMode;
    }

    /**
     * 渲染纹理图像到surface上
     */
    public void render(final int texture, final int width, final int height) {
        if (eglContext == null) {
            return;
        }

        eglContext.syncRunOnRenderThread(new Runnable() {
            @Override
            public void run() {
                eglContext.makeCurrent();

                filterProgram.use();

                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, boundingWidth, boundingHeight);

                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);

                glActiveTexture(GL_TEXTURE2);
                glBindTexture(GL_TEXTURE_2D, texture);

                glUniform1i(filterInputTextureUniform, 2);

                PointF insetSize = getAspectRatioInsideSize(new PointF(width, height), new PointF(boundingWidth, boundingHeight));

                float widthScaling = 0.0f, heightScaling = 0.0f;

                switch (contentMode) {
                    case kAYGPUImageScaleToFill:
                        widthScaling = 1.0f;
                        heightScaling = 1.0f;
                        break;
                    case kAYGPUImageScaleAspectFit:
                        widthScaling = insetSize.x / boundingWidth;
                        heightScaling = insetSize.y / boundingHeight;
                        break;
                    case kAYGPUImageScaleAspectFill:
                        widthScaling = boundingHeight / insetSize.y;
                        heightScaling = boundingWidth / insetSize.x;
                        break;
                }

                float squareVertices[] = new float[8];
                squareVertices[0] = -widthScaling;
                squareVertices[1] = -heightScaling;
                squareVertices[2] = widthScaling;
                squareVertices[3] = -heightScaling;
                squareVertices[4] = -widthScaling;
                squareVertices[5] = heightScaling;
                squareVertices[6] = widthScaling;
                squareVertices[7] = heightScaling;

                glEnableVertexAttribArray(filterPositionAttribute);
                glEnableVertexAttribArray(filterTextureCoordinateAttribute);

                Buffer imageVertices = AYGPUImageConstants.floatArrayToBuffer(squareVertices);

                glVertexAttribPointer(filterPositionAttribute, 2, GL_FLOAT, false, 0, imageVertices);
                glVertexAttribPointer(filterTextureCoordinateAttribute, 2, GL_FLOAT, false, 0, textureCoordinates);

                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

                glDisableVertexAttribArray(filterPositionAttribute);
                glDisableVertexAttribArray(filterTextureCoordinateAttribute);

                eglContext.swapBuffers();
            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        createGLEnvironment(holder);
        if (listener != null) {
            listener.createGLEnvironment();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.boundingWidth = width;
        this.boundingHeight = height;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (listener != null) {
            listener.destroyGLEnvironment();
        }
        destroyGLEnvironment();
    }

    public void setListener(AYPreviewViewListener listener) {
        this.listener = listener;
    }

    /**
     * 创建 GLES 环境
     */
    private void createGLEnvironment(Object object) {
        eglContext = new AYGPUImageEGLContext();
        eglContext.initWithEGLWindow(object);

        eglContext.syncRunOnRenderThread(new Runnable() {
            @Override
            public void run() {

                filterProgram = new AYGLProgram(AYGPUImageFilter.kAYGPUImageVertexShaderString, AYGPUImageFilter.kAYGPUImagePassthroughFragmentShaderString);
                filterProgram.link();

                filterPositionAttribute = filterProgram.attributeIndex("position");
                filterTextureCoordinateAttribute = filterProgram.attributeIndex("inputTextureCoordinate");
                filterInputTextureUniform = filterProgram.uniformIndex("inputImageTexture");
                filterProgram.use();
            }
        });
    }

    /**
     * 销毁 GLES 环境
     */
    private void destroyGLEnvironment() {
        eglContext.syncRunOnRenderThread(() -> {
            eglContext.makeCurrent();
            filterProgram.destroy();

            eglContext.destroyEGLWindow();
            eglContext = null;
        });
    }
}
