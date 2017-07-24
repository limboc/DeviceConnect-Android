package org.deviceconnect.android.deviceplugin.hogp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import org.deviceconnect.android.deviceplugin.hogp.profiles.HOGPSystemProfile;
import org.deviceconnect.android.deviceplugin.hogp.server.AbstractHOGPServer;
import org.deviceconnect.android.deviceplugin.hogp.server.HOGPServer;
import org.deviceconnect.android.message.DConnectMessageService;
import org.deviceconnect.android.profile.SystemProfile;


public class HOGPMessageService extends DConnectMessageService {

    /**
     * デバッグ用フラグ.
     */
    private static final boolean DEBUG = BuildConfig.DEBUG;

    /**
     * デバッグ用タグ.
     */
    private static final String TAG = "HOGP";

    /**
     * HOGPサーバ.
     */
    private AbstractHOGPServer mHOGPServer;

    /**
     * HOGPプラグインの設定.
     */
    private HOGPSetting mHOGPSetting;

    /**
     * Bluetoothの状態通知を受け取るレシーバー.
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        if (DEBUG) {
                            Log.i(TAG, "Bluetooth is off.");
                        }
                        stopHOGPServer();
                        break;

                    case BluetoothAdapter.STATE_ON:
                        if (DEBUG) {
                            Log.i(TAG, "Bluetooth is on.");
                        }
                        if (mHOGPSetting.isEnabledServer()) {
                            try {
                                startHOGPServer();
                            } catch (Exception e) {
                                if (DEBUG) {
                                    Log.w(TAG, "Failed to start HOGPServer.", e);
                                }
                            }
                        }
                        break;
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mHOGPSetting = new HOGPSetting(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mReceiver, filter);

        if (mHOGPSetting.isEnabledServer()) {
            try {
                startHOGPServer();
            } catch (Exception e) {
                if (DEBUG) {
                    Log.w(TAG, "Failed to start HOGPServer.", e);
                }
            }
        }

        setUseLocalOAuth(mHOGPSetting.isEnabledOAuth());
    }

    @Override
    public void onDestroy() {
        stopHOGPServer();
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    protected SystemProfile getSystemProfile() {
        return new HOGPSystemProfile();
    }

    @Override
    protected void onManagerUninstalled() {
        // TODO Device Connect Managerアンインストール時に実行したい処理. 実装は任意.
    }

    @Override
    protected void onManagerTerminated() {
        // TODO Device Connect Manager停止時に実行したい処理. 実装は任意.
    }

    @Override
    protected void onManagerEventTransmitDisconnected(final String origin) {
        // TODO アプリとのWebSocket接続が切断された時に実行したい処理. 実装は任意.
    }

    @Override
    protected void onDevicePluginReset() {
        // TODO Device Connect Managerの設定画面上で「プラグイン再起動」を要求された場合の処理. 実装は任意.
    }

    /**
     * HOGPの設定を取得します.
     * @return HOGP設定
     */
    public HOGPSetting getHOGPSetting() {
        return mHOGPSetting;
    }

    public void setEnabledOuath(final boolean flag) {
        setUseLocalOAuth(flag);
        mHOGPSetting.setEnabledOAuth(flag);
    }

    /**
     * HOGPサーバを取得します.
     * <p>
     * HOGPサーバが開始されていない場合にはnullを返却します。
     * </p>
     * @return HOGPサーバ
     */
    public synchronized AbstractHOGPServer getHOGPServer() {
        return mHOGPServer;
    }

    /**
     * HOGPサーバを起動します.
     */
    public synchronized void startHOGPServer() {
        if (mHOGPServer != null) {
            if (DEBUG) {
                Log.d(TAG, "HOGP Server is already running.");
            }
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "Start the HOGP Server.");
        }

        mHOGPServer = new HOGPServer(this);
        mHOGPServer.setOnHOGPServerListener(new AbstractHOGPServer.OnHOGPServerListener() {
            @Override
            public void onConnected(final BluetoothDevice device) {
                if (DEBUG) {
                    Log.d(TAG, "Connected the device. " + device.getName());
                }

                HOGPService service = (HOGPService) getServiceProvider().getService(device.getAddress());
                if (service == null) {
                    service = new HOGPService(device);
                    getServiceProvider().addService(service);
                }
                service.setOnline(true);
            }

            @Override
            public void onDisconnected(final BluetoothDevice device) {
                if (DEBUG) {
                    Log.d(TAG, "Disconnected the device. " + device.getName());
                }

                HOGPService service = (HOGPService) getServiceProvider().getService(device.getAddress());
                if (service == null) {
                    service = new HOGPService(device);
                    getServiceProvider().addService(service);
                }
                service.setOnline(false);
            }
        });
        mHOGPServer.start();
    }

    /**
     * HOGPサーバを停止します.
     */
    public synchronized void stopHOGPServer() {
        if (mHOGPServer != null) {
            if (DEBUG) {
                Log.i(TAG, "Stop the HOGP Server.");
            }

            mHOGPServer.stop();
            mHOGPServer = null;
        }
    }
}