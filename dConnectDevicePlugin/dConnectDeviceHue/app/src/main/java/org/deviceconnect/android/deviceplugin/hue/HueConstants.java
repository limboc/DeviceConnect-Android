/*
HueConstants
Copyright (c) 2014 NTT DOCOMO,INC.
Released under the MIT license
http://opensource.org/licenses/mit-license.php
*/

package org.deviceconnect.android.deviceplugin.hue;

/**
 * hueデバイスプラグインで使用する定数.
 */
public interface HueConstants {

    /**
     * アプリケーション名.
     */
    String APNAME = "DConnectDeviceHueAndroid";

    /**
     * ユーザ名.
     */
    String USERNAME = "DConnectDeviceHueAndroid";
    /**
     * デバイスがオフラインの時のユーザ名.
     */
    String OFFLINE_USERNAME = "=====OFFLINE====";

    /**
     * Hueブリッジをリスタートするためのアクション名.
     */
    String ACTION_RESTART_HUE_BRIDGE = "org.deviceconnect.android.deviceplugin.action.HUE_RESTART";

}
