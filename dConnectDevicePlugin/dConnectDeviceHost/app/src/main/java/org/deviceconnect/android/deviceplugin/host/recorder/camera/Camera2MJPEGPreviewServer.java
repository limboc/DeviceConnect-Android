/*
 CameraPreviewServer.java
 Copyright (c) 2017 NTT DOCOMO,INC.
 Released under the MIT license
 http://opensource.org/licenses/mit-license.php
 */
package org.deviceconnect.android.deviceplugin.host.recorder.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.glutils.EGLBase;
import com.serenegiant.glutils.EglTask;
import com.serenegiant.glutils.GLDrawer2D;

import org.deviceconnect.android.deviceplugin.host.BuildConfig;
import org.deviceconnect.android.deviceplugin.host.camera.CameraWrapperException;
import org.deviceconnect.android.deviceplugin.host.camera.CameraWrapper;
import org.deviceconnect.android.deviceplugin.host.recorder.HostDeviceRecorder;
import org.deviceconnect.android.deviceplugin.host.recorder.PreviewServer;
import org.deviceconnect.android.deviceplugin.host.recorder.util.MixedReplaceMediaServer;

import java.io.ByteArrayOutputStream;
import java.nio.IntBuffer;


class Camera2MJPEGPreviewServer implements PreviewServer {

    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static final String TAG = "host.dplugin";

    private static final String MIME_TYPE = "video/x-mjpeg";

    private final Camera2Recorder mRecorder;

    private final Object mLockObj = new Object();

    private MixedReplaceMediaServer mServer;

    private ImageReader mPreviewReader;

    private HandlerThread mPreviewThread;

    private Handler mPreviewHandler;

    private boolean mIsRecording;

    private boolean requestDraw;

    private Object mSync = new Object();

    private final MixedReplaceMediaServer.Callback mMediaServerCallback = new MixedReplaceMediaServer.Callback() {
        @Override
        public boolean onAccept() {
            try {
                if (DEBUG) {
                    Log.d(TAG, "MediaServerCallback.onAccept: recorder=" + mRecorder.getName());
                }
                CameraWrapper camera = mRecorder.getCameraWrapper();
                mPreviewReader = camera.createPreviewReader(ImageFormat.JPEG);
                mPreviewReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(final ImageReader reader) {
                        try {
                            Image image = reader.acquireNextImage();
                            Log.d(TAG, "onImageAvailable");
                            if (image == null) {
                                return;
                            }
                            image.close();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }

                    }
                }, mPreviewHandler);
                new Thread(new DrawTask()).start();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start preview.", e);
                return false;
            }
        }
    };

    private ImageReader.OnImageAvailableListener mPreviewListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {
            try {
                // ビットマップ取得
                final Image image = reader.acquireNextImage();
                if (image == null || image.getPlanes() == null) {
                    return;
                }

                byte[] jpeg = Camera2Recorder.convertToJPEG(image);
                jpeg = mRecorder.rotateJPEG(jpeg, 100); // NOTE: swap width and height.
                image.close();

                offerMedia(jpeg);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send preview frame.", e);
            }
        }
    };

    Camera2MJPEGPreviewServer(final Camera2Recorder recorder) {
        mRecorder = recorder;
    }

    private void offerMedia(final byte[] jpeg) {
        MixedReplaceMediaServer server = mServer;
        if (server != null) {
            server.offerMedia(jpeg);
        }
    }

    @Override
    public int getQuality() {
        return mRecorder.getCameraWrapper().getPreviewJpegQuality();
    }

    @Override
    public void setQuality(int quality) {
        mRecorder.getCameraWrapper().setPreviewJpegQuality(quality);
    }

    @Override
    public String getMimeType() {
        return MIME_TYPE;
    }

    @Override
    public void startWebServer(final OnWebServerStartCallback callback) {
        synchronized (mLockObj) {
            if (mIsRecording) {
                callback.onStart(mServer.getUrl());
                return;
            }
            mIsRecording = true;

            mPreviewThread = new HandlerThread("MotionJPEG");
            mPreviewThread.start();
            mPreviewHandler = new Handler(mPreviewThread.getLooper());

            final String uri;
            if (mServer == null) {
                mServer = new MixedReplaceMediaServer();
                mServer.setServerName("HostDevicePlugin Server");
                mServer.setContentType("image/jpg");
                mServer.setCallback(mMediaServerCallback);
                uri = mServer.start();
            } else {
                uri = mServer.getUrl();
            }
            callback.onStart(uri);
        }
    }

    @Override
    public void stopWebServer() {
        synchronized (mLockObj) {
            if (!mIsRecording) {
                return;
            }
            mIsRecording = false;
            if (mServer != null) {
                mServer.stop();
                mServer = null;
            }

            CameraWrapper camera = mRecorder.getCameraWrapper();
            if (camera != null) {
                try {
                    camera.stopPreview();
                } catch (CameraWrapperException e) {
                    Log.e(TAG, "Failed to stop preview.", e);
                }
            }
            if (mPreviewReader != null) {
                mPreviewReader.close();
                mPreviewReader = null;
            }
            mPreviewThread.quit();
            mPreviewThread = null;
            mPreviewHandler = null;

            mRecorder.hideNotification();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private final class DrawTask extends EglTask {

        // 取得したピクセルデータは R (赤) と B (青) が逆になっています。
        // また垂直方向も逆になっているので以下のように ColorMatrix と Matrix を使用して修正します。
        /*
         * カラーチャネルを交換するために ColorMatrix と ColorMatrixFilter を使用します。
         *
         * 5x4 のマトリックス: [
         *   a, b, c, d, e,
         *   f, g, h, i, j,
         *   k, l, m, n, o,
         *   p, q, r, s, t
         * ]
         *
         * RGBA カラーへ適用する場合、以下のように計算します:
         *
         * R' = a * R + b * G + c * B + d * A + e;
         * G' = f * R + g * G + h * B + i * A + j;
         * B' = k * R + l * G + m * B + n * A + o;
         * A' = p * R + q * G + r * B + s * A + t;
         *
         * R (赤) と B (青) を交換したいので以下の様になります。
         *
         * R' = B => 0, 0, 1, 0, 0
         * G' = G => 0, 1, 0, 0, 0
         * B' = R => 1, 0, 0, 0, 0
         * A' = A => 0, 0, 0, 1, 0
         */
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        {
            // R (赤) と B (青) が逆なので交換します。
            mPaint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0, 0, 1, 0, 0,
                0, 1, 0, 0, 0,
                1, 0, 0, 0, 0,
                0, 0, 0, 1, 0
            })));
        }

        private long intervals;
        private EGLBase.IEglSurface mEncoderSurface;
        private GLDrawer2D mDrawer;
        private final float[] mTexMatrix = new float[16];
        private int mTexId;
        private SurfaceTexture mSourceTexture;
        private Surface mSourceSurface;

        private HostDeviceRecorder.PictureSize mSize;
        private Bitmap mBitmap;
        private Canvas mCanvas;
        private int[] mPixels;
        private IntBuffer mPixelBuffer;
        private ByteArrayOutputStream mOutput;
        private int mJpegQuality;

        public DrawTask() {
            super(null, 0);
        }

        @Override
        protected void onStart() {
            mDrawer = new GLDrawer2D(true);
            mTexId = mDrawer.initTex();
            mSourceTexture = new SurfaceTexture(mTexId);

            HostDeviceRecorder.PictureSize size = mRecorder.getPreviewSize();
            mSize = new HostDeviceRecorder.PictureSize(size.getHeight(), size.getWidth());
            mBitmap = Bitmap.createBitmap(mSize.getWidth(), mSize.getHeight(), Bitmap.Config.ARGB_8888);

            // 上下が逆さまなので垂直方向に反転させます。
            final Matrix matrix = new Matrix();
            matrix.postScale(1.0f, -1.0f);
            matrix.postTranslate(0, mSize.getHeight());
            mCanvas = new Canvas(mBitmap);
            mCanvas.concat(matrix);

            mPixels = new int[mSize.getWidth() * mSize.getHeight()];
            mPixelBuffer = IntBuffer.wrap(mPixels);
            mOutput = new ByteArrayOutputStream(mSize.getWidth() * mSize.getHeight());

            mSourceTexture.setDefaultBufferSize(mSize.getWidth(), mSize.getHeight());	// これを入れないと映像が取れない
            mSourceSurface = new Surface(mSourceTexture);
            mSourceTexture.setOnFrameAvailableListener(mOnFrameAvailableListener, mPreviewHandler);
            mEncoderSurface = getEgl().createOffscreen(mSize.getWidth(), mSize.getHeight()); //.createFromSurface(mPreviewReader.getSurface());
            intervals = (long)(1000f / mRecorder.getMaxFrameRate());
            mJpegQuality = getQuality();

            try {
                mRecorder.startPreview(mSourceSurface);
                Log.d(TAG, "Started camera preview.");
            } catch (CameraWrapperException e) {
                Log.e(TAG, "Failed to start camera preview.", e);
            }

            // 録画タスクを起床
            queueEvent(mDrawTask);
        }

        @Override
        protected void onStop() {
            if (mDrawer != null) {
                mDrawer.release();
                mDrawer = null;
            }
            if (mSourceSurface != null) {
                mSourceSurface.release();
                mSourceSurface = null;
            }
            if (mSourceTexture != null) {
                mSourceTexture.release();
                mSourceTexture = null;
            }
            if (mEncoderSurface != null) {
                mEncoderSurface.release();
                mEncoderSurface = null;
            }
            if (mBitmap != null && !mBitmap.isRecycled()) {
                mBitmap.recycle();
            }
            try {
                mRecorder.stopPreview();
            } catch (CameraWrapperException e) {
                Log.e(TAG, "Failed to stop camera preview.", e);
            }
            makeCurrent();
        }

        @Override
        protected boolean onError(final Exception e) {
            Log.w(TAG, "mScreenCaptureTask:", e);
            return false;
        }

        @Override
        protected Object processRequest(final int request, final int arg1, final int arg2, final Object obj) {
            return null;
        }

        // TextureSurfaceで映像を受け取った際のコールバックリスナー
        private final SurfaceTexture.OnFrameAvailableListener mOnFrameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(final SurfaceTexture surfaceTexture) {
                if (mIsRecording) {
                    synchronized (mSync) {
                        requestDraw = true;
                        mSync.notifyAll();
                    }
                }
            }
        };

        private long mLastTime = 0;

        private final Runnable mDrawTask = new Runnable() {
            @Override
            public void run() {
                boolean localRequestDraw;
                synchronized (mSync) {
                    localRequestDraw = requestDraw;
                    if (!requestDraw) {
                        try {
                            mSync.wait(intervals);
                            localRequestDraw = requestDraw;
                            requestDraw = false;
                        } catch (final InterruptedException e) {
                            Log.v(TAG, "draw:InterruptedException");
                            return;
                        }
                    }
                }

                long now = System.currentTimeMillis();
                long interval = intervals - (now - mLastTime);
                if (interval > 0) {
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                mLastTime = now;

                if (mIsRecording) {
                    if (localRequestDraw) {
                        mSourceTexture.updateTexImage();
                        mSourceTexture.getTransformMatrix(mTexMatrix);
                    }
                    // SurfaceTextureで受け取った画像をMediaCodecの入力用Surfaceへ描画する
                    mEncoderSurface.makeCurrent();
                    mDrawer.draw(mTexId, mTexMatrix, 0);


                    GLES20.glFinish();
                    mPixelBuffer.clear();
                    mPixelBuffer.position(0);
                    GLES20.glReadPixels(0, 0, mSize.getWidth(), mSize.getHeight(), GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mPixelBuffer);

                    mCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    mCanvas.drawBitmap(mPixels, 0, mSize.getWidth(), 0, 0, mSize.getWidth(), mSize.getHeight(), false, mPaint);

                    mOutput.reset();
                    mBitmap.compress(Bitmap.CompressFormat.JPEG, mJpegQuality, mOutput);
                    mServer.offerMedia(mOutput.toByteArray());

                    queueEvent(this);
                } else {
                    releaseSelf();
                }
            }
        };

    }
}
