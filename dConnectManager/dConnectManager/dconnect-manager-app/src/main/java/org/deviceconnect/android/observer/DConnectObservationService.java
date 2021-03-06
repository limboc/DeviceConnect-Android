/*
 DConnectObservationService.java
 Copyright (c) 2014 NTT DOCOMO,INC.
 Released under the MIT license
 http://opensource.org/licenses/mit-license.php
 */
package org.deviceconnect.android.observer;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import androidx.annotation.NonNull;


import org.deviceconnect.android.activity.PermissionUtility;
import org.deviceconnect.android.manager.R;
import org.deviceconnect.android.observer.activity.WarningDialogActivity;
import org.deviceconnect.android.observer.receiver.ObserverReceiver;
import org.deviceconnect.android.observer.util.AndroidSocket;
import org.deviceconnect.android.observer.util.SockStatUtil;
import org.deviceconnect.android.observer.util.SocketState;

import java.util.ArrayList;

/**
 * DeviceConnectの生存確認を行うサービス.
 * 
 * @author NTT DOCOMO, INC.
 */
public class DConnectObservationService extends Service {

    /**
     * Overserver Notification id.
     */
    private static final int OBSERVER_NOTIFICATION_ID = 9999;
    /**
     * オブザーバー監視開始アクション.
     */
    public static final String ACTION_START = "org.deviceconnect.android.intent.action.observer.START";

    /**
     * 監視するインターバル.
     */
    public static final String PARAM_OBSERVATION_INTERVAL = "org.deviceconnect.android.intent.param.observer.OBSERVATION_INTERVAL";

    /**
     * オブザーバー監視停止アクション.
     */
    public static final String ACTION_STOP = "org.deviceconnect.android.intent.action.observer.STOP";

    /**
     * 監視アクション.
     */
    public static final String ACTION_CHECK = "org.deviceconnect.android.intent.action.observer.CHECK";

    /**
     * 占有しているパッケージ名.
     */
    public static final String PARAM_PACKAGE_NAME = "org.deviceconnect.android.intent.param.observer.PACKAGE_NAME";

    /**
     * 監視または占有されているポート番号.
     */
    public static final String PARAM_PORT = "org.deviceconnect.android.intent.param.observer.PORT";

    /**
     * 結果返却用コールバックオブジェクト
     */
    public static final String PARAM_RESULT_RECEIVER = "org.deviceconnect.android.intent.param.observer.RESULT_RECEIVER";

    /**
     * リクエストコード.
     */
    private static final int REQUEST_CODE = 0x0F0F0F;

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // onDestroyが呼ばれずに死ぬこともあるようなので必ず最初に解除処理を入れる。
        stopObservation();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopObservation();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {

        if (intent == null) {
            return START_STICKY;
        }
        startForegroundObserverService();
        String action = intent.getAction();
        if (ACTION_CHECK.equals(action)
            && intent.hasExtra(PARAM_PORT)) {
            final int port = intent.getIntExtra(PARAM_PORT, -1);
            new Thread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                    stopObservation();
                    String appName = getResources().getString(R.string.app_name);
                    String title = getResources().getString(R.string.service_observation_warning);
                    new AlertDialog.Builder(DConnectObservationService.this).setTitle(appName + ": " + title)
                            .setMessage(R.string.service_observation_msg_no_permission)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    }).show();
                    stopSelf();
                    return;
                }

                String appPackage = getHoldingAppSocketInfo(port);
                if (appPackage != null) {
                    stopObservation();
                    Intent i = new Intent();
                    i.setClass(getApplicationContext(), WarningDialogActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    i.putExtra(PARAM_PACKAGE_NAME, appPackage);
                    i.putExtra(PARAM_PORT, port);
                    getApplication().startActivity(i);
                    stopSelf();
                }
            }).start();
        } else if (ACTION_START.equals(action)
                && intent.hasExtra(PARAM_PORT)
                && intent.hasExtra(PARAM_OBSERVATION_INTERVAL)
                && intent.hasExtra(PARAM_RESULT_RECEIVER)) {
            final int port = intent.getIntExtra(PARAM_PORT, -1);
            final int interval = intent.getIntExtra(PARAM_OBSERVATION_INTERVAL, 300000);
            final ResultReceiver resultReceiver = intent.getParcelableExtra(PARAM_RESULT_RECEIVER);
            if (resultReceiver == null) {
                return START_STICKY;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                startObservation(port, interval);
                resultReceiver.send(Activity.RESULT_OK, null);
            } else {
                PermissionUtility.requestPermissions(this, new Handler(Looper.getMainLooper()),
                        new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                        new PermissionUtility.PermissionRequestCallback() {
                            @Override
                            public void onSuccess() {
                                startObservation(port, interval);
                                resultReceiver.send(Activity.RESULT_OK, null);
                            }

                            @Override
                            public void onFail(@NonNull String deniedPermission) {
                                resultReceiver.send(Activity.RESULT_CANCELED, null);
                                stopObservation();
                                stopSelf();
                            }
                        });
            }
        } else if (ACTION_STOP.equals(action)) {
            stopObservation();
            stopSelf();
        }

        return START_STICKY;
    }

    private void startForegroundObserverService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = getApplicationContext().getResources().getString(R.string.observation_service_on_channel_id);
            Notification.Builder builder = new Notification.Builder(getApplicationContext(), channelId);
            builder.setContentTitle(getString(R.string.activity_settings_observer));
            builder.setContentText(getString(R.string.activity_settings_observer_summary));
            int iconType = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ?
                    R.drawable.icon : R.drawable.on_icon;
            builder.setSmallIcon(iconType);
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    getApplicationContext().getResources().getString(R.string.activity_settings_observer),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getApplicationContext().getResources().getString(R.string.activity_settings_observer_summary));
            NotificationManager mNotification = (NotificationManager) getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            mNotification.createNotificationChannel(channel);
            builder.setChannelId(channelId);
            startForeground(OBSERVER_NOTIFICATION_ID, builder.build());
        }
    }

    /**
     * 監視を開始する.
     * @param port ポート番号
     * @param interval インターバル
     */
    private synchronized void startObservation(final int port, final long interval) {
        Intent intent = new Intent(this, ObserverReceiver.class);
        intent.setAction(ACTION_CHECK);
        intent.putExtra(PARAM_PORT, port);
        PendingIntent sender = PendingIntent.getBroadcast(this, REQUEST_CODE, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + interval, interval, sender);
    }

    /**
     * 監視を終了する.
     */
    private synchronized void stopObservation() {
        Intent intent = new Intent(this, ObserverReceiver.class);
        intent.setAction(ACTION_CHECK);
        PendingIntent sender = PendingIntent.getBroadcast(this, REQUEST_CODE, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.cancel(sender);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }

    /**
     * ポートを占有しているアプリを取得する.
     *
     * @param port ポート番号
     * @return 占有しているアプリのパッケージ名
     */
    private String getHoldingAppSocketInfo(final int port) {
        ArrayList<AndroidSocket> sockets = SockStatUtil.getSocketList(this);
        String deviceConnectPackageName = getPackageName();
        for (AndroidSocket aSocket : sockets) {
            if (!aSocket.getAppName().equals(deviceConnectPackageName) && aSocket.getLocalPort() == port
                    && aSocket.getState() == SocketState.TCP_LISTEN) {
                return aSocket.getAppName();
            }
        }
        return null;
    }
}
