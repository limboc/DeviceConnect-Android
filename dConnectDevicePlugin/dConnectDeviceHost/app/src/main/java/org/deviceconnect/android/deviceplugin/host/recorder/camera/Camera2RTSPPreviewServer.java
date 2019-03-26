package org.deviceconnect.android.deviceplugin.host.recorder.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import com.serenegiant.glutils.EGLBase;
import com.serenegiant.glutils.EglTask;
import com.serenegiant.glutils.GLDrawer2D;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.rtsp.RtspServer;
import net.majorkernelpanic.streaming.rtsp.RtspServerImpl;
import net.majorkernelpanic.streaming.video.SurfaceH264Stream;
import net.majorkernelpanic.streaming.video.VideoQuality;

import org.deviceconnect.android.deviceplugin.host.BuildConfig;
import org.deviceconnect.android.deviceplugin.host.camera.CameraWrapperException;
import org.deviceconnect.android.deviceplugin.host.recorder.AbstractPreviewServerProvider;
import org.deviceconnect.android.deviceplugin.host.recorder.HostDeviceRecorder;

import java.io.IOException;
import java.net.Socket;


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class Camera2RTSPPreviewServer extends AbstractRTSPPreviewServer implements RtspServer.Delegate {

    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static final String TAG = Camera2RTSPPreviewServer.class.getSimpleName();

    static final String MIME_TYPE = "video/x-rtp";

    private static final String SERVER_NAME = "Android Host Screen RTSP Server";

    private final Object mLockObj = new Object();

    private Socket mClientSocket;

    private SurfaceH264Stream mVideoStream;

    private RtspServer mRtspServer;

    private VideoQuality mQuality;

    private final Camera2Recorder mRecorder;

    private Handler mHandler;
    private final Object mSync = new Object();
    private volatile boolean mIsRecording;
    private boolean requestDraw;
    private DrawTask mScreenCaptureTask;

    Camera2RTSPPreviewServer(final Context context,
                             final AbstractPreviewServerProvider serverProvider,
                             final Camera2Recorder recorder) {
        super(context, serverProvider);
        mRecorder = recorder;
    }

    @Override
    public String getMimeType() {
        return MIME_TYPE;
    }

    @Override
    public void startWebServer(final OnWebServerStartCallback callback) {
        synchronized (mLockObj) {
            if (mRtspServer == null) {
                mRtspServer = new RtspServerImpl(SERVER_NAME);
                mRtspServer.setPort(20000);
                mRtspServer.setDelegate(Camera2RTSPPreviewServer.this);
                if (!mRtspServer.start()) {
                    callback.onFail();
                    return;
                }
            }
            if (mHandler == null) {
                HandlerThread thread = new HandlerThread("ScreenCastRTSPPreviewServer");
                thread.start();
                mHandler = new Handler(thread.getLooper());
            }
            String uri = "rtsp://localhost:" + mRtspServer.getPort();
            callback.onStart(uri);
        }
    }

    @Override
    public void stopWebServer() {
        try {
            synchronized (mLockObj) {
                if (mRtspServer != null) {
                    mRtspServer.stop();
                    mRtspServer = null;
                }
                stopPreviewStreaming();
                mClientSocket = null;
            }
            stopDrawTask();
        } catch (Throwable e) {
            Log.e(TAG, "stopWebServer", e);
            throw e;
        }
    }

    // DrawTaskの後始末
    private void stopDrawTask() {
        synchronized (mSync) {
            mIsRecording = false;
            mSync.notifyAll();
        }
        if (mHandler != null) {
            mHandler.getLooper().quit();
            mHandler = null;
        }
    }

    @Override
    protected void onConfigChange() {
    }

    @Override
    public void onDisplayRotation(final int rotation) {
        if (DEBUG) {
            Log.d(TAG, "onDisplayRotation: rotation=" + rotation);
        }
        DrawTask drawTask = mScreenCaptureTask;
        synchronized (mLockObj) {
            if (drawTask != null) {
                drawTask.onDisplayRotationChange(rotation);
            }
        }
    }

    @Override
    public Session generateSession(final String uri, final Socket clientSocket) {
        try {
            return startPreviewStreaming(clientSocket);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void eraseSession(Session session) {}

    @Override
    public int getQuality() {
        return 0; // Not support.
    }

    @Override
    public void setQuality(int quality) {
        // Not support.
    }

    private Session startPreviewStreaming(final Socket clientSocket) throws IOException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        HostDeviceRecorder.PictureSize previewSize = getRotatedPreviewSize();

        VideoQuality videoQuality = new VideoQuality();

        //縦横切り替わった時に、初期化する必要のないように長い方に合わせる
        int resolution = previewSize.getWidth();
        if (resolution < previewSize.getHeight()) {
            resolution = previewSize.getHeight();
        }
        videoQuality.resX = resolution;
        videoQuality.resY = resolution;
        videoQuality.bitrate = mServerProvider.getPreviewBitRate();
        videoQuality.framerate = (int) mServerProvider.getMaxFrameRate();

        synchronized (mLockObj) {
            mClientSocket = clientSocket;
            mVideoStream = new SurfaceH264Stream(prefs, videoQuality);
            mScreenCaptureTask = new DrawTask(null, 0, videoQuality);
            mIsRecording = true;
            new Thread(mScreenCaptureTask, "ScreenCaptureThread").start();
        }

        SessionBuilder builder = new SessionBuilder();
        builder.setContext(mContext);
        builder.setVideoStream(mVideoStream);
        builder.setVideoQuality(videoQuality);

        Session session = builder.build();
        session.setOrigin(clientSocket.getLocalAddress().getHostAddress());
        if (session.getDestination() == null) {
            session.setDestination(clientSocket.getInetAddress().getHostAddress());
        }
        return session;
    }

    private void stopPreviewStreaming() {
        synchronized (mLockObj) {
            if (mIsRecording) {
                mVideoStream.stop();
                mVideoStream = null;
                mIsRecording = false;
            }
        }
    }

    private int getCurrentRotation() {
        WindowManager windowManager = (WindowManager) mRecorder.getContext().getSystemService(Context.WINDOW_SERVICE);
        return windowManager.getDefaultDisplay().getRotation();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private final class DrawTask extends EglTask {
        private long intervals;
        private EGLBase.IEglSurface mEncoderSurface;
        private GLDrawer2D mDrawer;
        private final float[] mTexMatrix = new float[16];
        private int mTexId;
        private SurfaceTexture mSourceTexture;
        private Surface mSourceSurface;

        private HostDeviceRecorder.PictureSize mPreviewSize;

        private final Object mDrawSync = new Object();
        private int mRotationDegree;
        private float mDeltaX;
        private float mDeltaY;

        public DrawTask(final EGLBase.IContext sharedContext, final int flags, final VideoQuality quality) {
            super(sharedContext, flags);
            mQuality = quality;
        }

        @Override
        protected void onStart() {
            mDrawer = new GLDrawer2D(true);
            mTexId = mDrawer.initTex();

            detectDisplayRotation(getCurrentRotation());

            mSourceTexture = new SurfaceTexture(mTexId);
            mSourceTexture.setDefaultBufferSize(mQuality.resX, mQuality.resY);	// これを入れないと映像が取れない
            mSourceSurface = new Surface(mSourceTexture);
            mSourceTexture.setOnFrameAvailableListener(mOnFrameAvailableListener, mHandler);
            mEncoderSurface = getEgl().createFromSurface(mVideoStream.getInputSurface());
            intervals = (long)(1000f / mQuality.framerate);

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
                if (mIsRecording) {
                    synchronized (mDrawSync) {
                        if (localRequestDraw) {
                            mSourceTexture.updateTexImage();
                            mSourceTexture.getTransformMatrix(mTexMatrix);
                            Matrix.rotateM(mTexMatrix, 0, mRotationDegree, 0, 0, 1);
                            Matrix.translateM(mTexMatrix, 0, mDeltaX, mDeltaY, 0);
                        }
                        // SurfaceTextureで受け取った画像をMediaCodecの入力用Surfaceへ描画する
                        mEncoderSurface.makeCurrent();
                        mDrawer.draw(mTexId, mTexMatrix, 0);
                        mEncoderSurface.swap();
                        // EGL保持用のオフスクリーンに描画しないとハングアップする機種の為のworkaround
                        makeCurrent();
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                        GLES20.glFlush();
                        queueEvent(this);
                    }
                } else {
                    releaseSelf();
                }
            }
        };

        private void onDisplayRotationChange(final int rotation) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    synchronized (mDrawSync) {
                        try {
                            if (mEncoderSurface != null) {
                                mEncoderSurface.release();
                            }

                            // プレビューサイズ更新
                            detectDisplayRotation(rotation);
                            if (DEBUG) {
                                Log.d(TAG, "Reset PreviewSize: " + mPreviewSize.getWidth() + "x" + mPreviewSize.getHeight());
                            }

                            int w = mPreviewSize.getWidth();
                            int h = mPreviewSize.getHeight();
                            mSourceTexture.setDefaultBufferSize(w, h);
                            mVideoStream.changeResolution(w, h);
                            mEncoderSurface = getEgl().createFromSurface(mVideoStream.getInputSurface());
                        } catch (Throwable e) {
                            Log.e(TAG, "Failed to update preview rotation.", e);
                        }
                    }
                }
            });
        }

        private void detectDisplayRotation(final int rotation) {
            switch (rotation) {
                case Surface.ROTATION_0:
                    mRotationDegree = 0;
                    mDeltaX = 0;
                    mDeltaY = 0;
                    break;
                case Surface.ROTATION_90:
                    mRotationDegree = -90;
                    mDeltaX = -1;
                    mDeltaY = 0;
                    break;
                case Surface.ROTATION_180:
                    mRotationDegree = -180;
                    mDeltaX = 1;
                    mDeltaY = -1;
                    break;
                case Surface.ROTATION_270:
                    mRotationDegree = -270;
                    mDeltaX = 0;
                    mDeltaY = -1;
                    break;
            }
            mPreviewSize = mRecorder.getRotatedPreviewSize();
        }

    }
}