package org.deviceconnect.android.deviceplugin.switchbot.profiles;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.deviceconnect.android.deviceplugin.switchbot.BuildConfig;
import org.deviceconnect.android.deviceplugin.switchbot.R;
import org.deviceconnect.android.deviceplugin.switchbot.device.SwitchBotDevice;
import org.deviceconnect.android.message.MessageUtils;
import org.deviceconnect.android.profile.DConnectProfile;
import org.deviceconnect.android.profile.api.PostApi;
import org.deviceconnect.message.DConnectMessage;

public class SwitchBotSwitchProfile extends DConnectProfile {
    private static final String TAG = "SwitchBotSwitchProfile";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    public SwitchBotSwitchProfile(final Context context, final SwitchBotDevice switchBotDevice) {
        if (DEBUG) {
            Log.d(TAG, "SwitchBotSwitchProfile()");
        }
        if (switchBotDevice != null) {
            // POST /gotapi/switch/turnOff
            addApi(new PostApi() {
                @Override
                public String getAttribute() {
                    return "turnOff";
                }

                @Override
                public boolean onRequest(final Intent request, final Intent response) {
                    Bundle extras = request.getExtras();
                    if (extras != null) {
                        String serviceId = (String) request.getExtras().get("serviceId");
                        if (DEBUG) {
                            Log.d(TAG, "serviceId : " + serviceId);
                        }

                        if (switchBotDevice.getDeviceMode() == SwitchBotDevice.Mode.BUTTON) {
                            MessageUtils.setIllegalServerStateError(response, context.getString(R.string.error_response_mode_unmatched));
                            return true;
                        }

                        // TODO ここでAPIを実装してください. 以下はサンプルのレスポンス作成処理です.
                        switchBotDevice.connect(new SwitchBotDevice.ConnectCallback() {
                            @Override
                            public void onSuccess() {
                                if (switchBotDevice.turnOff()) {
                                    setResult(response, DConnectMessage.RESULT_OK);
                                } else {
                                    MessageUtils.setIllegalServerStateError(response, context.getString(R.string.error_response_device_busy));
                                }
                                sendResponse(response);
                            }

                            @Override
                            public void onFailure() {
                                MessageUtils.setIllegalServerStateError(response, context.getString(R.string.error_response_connect_failure));
                                sendResponse(response);
                            }
                        });
                    }
                    return false;
                }
            });

            // POST /gotapi/switch/turnOn
            addApi(new PostApi() {
                @Override
                public String getAttribute() {
                    return "turnOn";
                }

                @Override
                public boolean onRequest(final Intent request, final Intent response) {
                    Bundle extras = request.getExtras();
                    if (extras != null) {
                        String serviceId = (String) request.getExtras().get("serviceId");
                        if (DEBUG) {
                            Log.d(TAG, "serviceId : " + serviceId);
                        }

                        if (switchBotDevice.getDeviceMode() == SwitchBotDevice.Mode.BUTTON) {
                            MessageUtils.setIllegalServerStateError(response, context.getString(R.string.error_response_mode_unmatched));
                            return true;
                        }

                        // TODO ここでAPIを実装してください. 以下はサンプルのレスポンス作成処理です.
                        switchBotDevice.connect(new SwitchBotDevice.ConnectCallback() {
                            @Override
                            public void onSuccess() {
                                if (switchBotDevice.turnOn()) {
                                    setResult(response, DConnectMessage.RESULT_OK);
                                } else {
                                    MessageUtils.setIllegalServerStateError(response, context.getString(R.string.error_response_device_busy));
                                }
                                sendResponse(response);
                            }

                            @Override
                            public void onFailure() {
                                MessageUtils.setIllegalServerStateError(response, context.getString(R.string.error_response_connect_failure));
                                sendResponse(response);
                            }
                        });
                    }
                    return false;
                }
            });
        }
    }

    @Override
    public String getProfileName() {
        return "switch";
    }
}