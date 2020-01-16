package org.deviceconnect.android.deviceplugin.switchbot.device;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.deviceconnect.android.deviceplugin.switchbot.BuildConfig;

import java.util.List;
import java.util.UUID;

public class SwitchBotDevice {
    private static final String TAG = "SwitchBotDevice";
    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final UUID SWITCHBOT_BLE_GATT_SERVICE_UUID = UUID.fromString("cba20d00-224d-11e6-9fb8-0002a5d5c51b");
    private static final UUID SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID = UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b");
    private static final byte[] READ_SETTINGS_COMMAND = {0x57, 0x02};
    private static boolean sConnecting = false;
    private static final Object sLock = new Object();
    private static final long CONNECT_DELAY = 5000;

    /**
     * デバイスが実施している操作
     */
    public enum Command {
        NONE,               //何もなし(未接続)
        CONNECTING,         //接続中
        SERVICE_DISCOVERY,  //サービス検索中
        READ_SETTINGS,      //接続後の設定読み出し
        WRITE_SETTINGS,     //モード変更
        IDLE,               //何もなし(接続中)
        OTHERS,             //その他
    }

    /**
     * デバイスの動作モード
     */
    public enum Mode {
        BUTTON(0), SWITCH(1);
        private int value;

        Mode(int value) {
            this.value = value;
        }

        public static Mode getInstance(int id) {
            if (id == 0) {
                return BUTTON;
            } else if (id == 1) {
                return SWITCH;
            } else {
                throw new RuntimeException();
            }
        }

        public int getValue() {
            return value;
        }
    }

    private String mDeviceName;
    private String mDeviceAddress;
    private Mode mDeviceMode;
    private Context mContext;
    private BluetoothGatt mGatt;
    private BluetoothGattService mGattService;
    private Command mCommand = Command.NONE;
    private final Object mLock = new Object();
    private EventListener mEventListener;
    private Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
    private Runnable mConnectTimeout = new Runnable() {
        @Override
        public void run() {
            Log.e(TAG, "connectGatt() timeout");
            synchronized (sLock) {
                sConnecting = false;
                synchronized (mLock) {
                    if (mCommand == Command.CONNECTING) {
                        mCommand = Command.NONE;
                        if (mGatt != null) {
                            mGatt.disconnect();
                            mGatt.close();
                            mGatt = null;
                        }
                    }
                }
            }
        }
    };
    private static final long CONNECT_TIMEOUT = 10000;
    private BluetoothGattCallback mBluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (DEBUG) {
                Log.d(TAG, "onConnectionStateChange()");
                Log.d(TAG, "gatt : " + gatt);
                Log.d(TAG, "status : " + status);
                Log.d(TAG, "newState : " + newState);
            }
            synchronized (sLock) {
                synchronized (mLock) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "status : success");
                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            sConnecting = false;
                            if (mGatt != null) {
                                mCommand = Command.SERVICE_DISCOVERY;
                                mMainThreadHandler.removeCallbacks(mConnectTimeout);
                                mGatt.discoverServices();
                            }
                        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                            if (mGatt != null) {
                                mGatt.disconnect();
                                mGatt.close();
                            }
                            mCommand = Command.NONE;
                            mEventListener.onDisconnect(SwitchBotDevice.this);
                        }
                    } else if (status == 133) {
                        Log.e(TAG, "status : error");
                        sConnecting = false;
                        if (mGatt != null) {
                            mGatt.disconnect();
                            mGatt.close();
                        }
                        mCommand = Command.NONE;
                        mEventListener.onDisconnect(SwitchBotDevice.this);
                    } else if (status == 19) {
                        Log.e(TAG, "status : disconnected from remote device");
                        if (mGatt != null) {
                            mGatt.disconnect();
                            mGatt.close();
                        }
                        mCommand = Command.NONE;
                        mEventListener.onDisconnect(SwitchBotDevice.this);
                    }
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (DEBUG) {
                Log.d(TAG, "onServicesDiscovered()");
                Log.d(TAG, "gatt : " + gatt);
                Log.d(TAG, "status : " + status);
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mGattService = mGatt.getService(SWITCHBOT_BLE_GATT_SERVICE_UUID);
                if (mGattService != null) {
                    List<BluetoothGattCharacteristic> characteristics = mGattService.getCharacteristics();
                    if (characteristics != null) {
                        for (BluetoothGattCharacteristic bluetoothGattCharacteristic : characteristics) {
                            if (DEBUG) {
                                Log.d(TAG, "UUID(characteristic) : " + bluetoothGattCharacteristic.getUuid().toString());
                            }
                            List<BluetoothGattDescriptor> bluetoothGattDescriptors = bluetoothGattCharacteristic.getDescriptors();
                            if (bluetoothGattDescriptors != null) {
                                for (BluetoothGattDescriptor bluetoothGattDescriptor : bluetoothGattDescriptors) {
                                    if (DEBUG) {
                                        Log.d(TAG, "UUID(descriptor) : " + bluetoothGattDescriptor.getUuid().toString());
                                    }
                                    mGatt.setCharacteristicNotification(bluetoothGattCharacteristic, true);
                                    bluetoothGattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    mGatt.writeDescriptor(bluetoothGattDescriptor);
                                }
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (DEBUG) {
                Log.d(TAG, "onCharacteristicWrite()");
                Log.d(TAG, "gatt : " + gatt);
                Log.d(TAG, "characteristic : " + characteristic);
                Log.d(TAG, "status : " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (DEBUG) {
                Log.d(TAG, "onCharacteristicChanged()");
                Log.d(TAG, "gatt : " + gatt);
                Log.d(TAG, "characteristic : " + characteristic);
            }
            if (characteristic != null) {
                byte[] values = characteristic.getValue();
                if (DEBUG) {
                    for (byte it : values) {
                        Log.d(TAG, "value : " + it);
                    }
                }
                if (DEBUG) {
                    Log.d(TAG, "mCommand : " + mCommand);
                }
                if (mCommand == Command.READ_SETTINGS) {
                    Mode mode = Mode.getInstance((values[9] >> 4) & 0x01);
                    if (DEBUG) {
                        Log.d(TAG, "device mode(read) : " + mode);
                        Log.d(TAG, "mDeviceMode : " + mDeviceMode);
                    }
                    if (mode != mDeviceMode) {
                        modeChange();
                    } else {
                        mCommand = Command.IDLE;
                        mEventListener.onConnect(SwitchBotDevice.this);
                    }
                } else if (mCommand == Command.WRITE_SETTINGS) {
                    mCommand = Command.IDLE;
                    mEventListener.onConnect(SwitchBotDevice.this);
                } else if (mCommand == Command.OTHERS) {
                    mCommand = Command.IDLE;
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if (DEBUG) {
                Log.d(TAG, "onDescriptorWrite()");
                Log.d(TAG, "gatt : " + gatt);
                Log.d(TAG, "descriptor : " + descriptor);
                Log.d(TAG, "status : " + status);
            }
            if (gatt != null) {
                if (mGattService != null) {
                    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        mCommand = Command.READ_SETTINGS;
                        characteristic.setValue(READ_SETTINGS_COMMAND);
                        gatt.writeCharacteristic(characteristic);
                    }
                }
            }
        }
    };

    public SwitchBotDevice(Context context, final String deviceName, final String deviceAddress, Mode deviceMode) {
        if (DEBUG) {
            Log.d(TAG, "SwitchBotDevice()");
            Log.d(TAG, "context:" + context);
            Log.d(TAG, "device name:" + deviceName);
            Log.d(TAG, "device address:" + deviceAddress);
            Log.d(TAG, "device mode:" + deviceMode);
        }
        mContext = context;
        mDeviceName = deviceName;
        mDeviceAddress = deviceAddress;
        mDeviceMode = deviceMode;
    }

    public String getDeviceName() {
        return mDeviceName;
    }

    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    public Mode getDeviceMode() {
        return mDeviceMode;
    }

    public void setEventListener(EventListener eventListener) {
        this.mEventListener = eventListener;
    }

    /**
     * デバイスと接続中かどうか判定する
     *
     * @return true:接続中, false:未接続
     */
    public boolean isConnected() {
        synchronized (mLock) {
            return !(mCommand == Command.NONE || mCommand == Command.CONNECTING);
        }
    }

    /**
     * デバイスと接続する
     */
    public void connect() {
        if (DEBUG) {
            Log.d(TAG, "connect()");
        }
        synchronized (sLock) {
            if (sConnecting) {
                //他のデバイスが接続試行中の場合
                mMainThreadHandler.postDelayed(this::connect, CONNECT_DELAY);
            } else {
                //接続試行中のデバイス無し
                synchronized (mLock) {
                    if (mCommand != Command.NONE) {
                        Log.e(TAG, "device busy");
                    } else {
                        sConnecting = true;
                        mCommand = Command.CONNECTING;
                        if (mContext != null) {
                            BluetoothManager bluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
                            if (bluetoothManager != null) {
                                BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
                                if (bluetoothAdapter != null) {
                                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mDeviceAddress);
                                    if (device != null) {
                                        mGatt = device.connectGatt(mContext, false, mBluetoothGattCallback);
                                        mMainThreadHandler.postDelayed(mConnectTimeout, CONNECT_TIMEOUT);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * デバイスの動作モードを変更する
     * この呼出は接続完了後の設定読み出しでモードが不一致だった場合のみ実施される
     * deviceModeに設定されたモードに変更する
     */
    private void modeChange() {
        if (DEBUG) {
            Log.d(TAG, "modeChange()");
        }
        synchronized (mLock) {
            byte mode;
            if (mDeviceMode == Mode.BUTTON) {
                mode = 0x00;
            } else {
                mode = 0x10;
            }
            byte[] modeChangeCommand = {0x57, 0x03, 0x64, mode};
            if (mGattService != null && mGatt != null) {
                BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                if (characteristic != null) {
                    mCommand = Command.WRITE_SETTINGS;
                    characteristic.setValue(modeChangeCommand);
                    mGatt.writeCharacteristic(characteristic);
                }
            }
        }
    }

    /**
     * デバイスを切断する
     */
    public void disconnect() {
        if (DEBUG) {
            Log.d(TAG, "disconnect()");
        }
        synchronized (mLock) {
            if (mGatt != null) {
                mGatt.disconnect();
                mGatt.close();
            }
            mCommand = Command.NONE;
        }
    }

    /**
     * Press動作を行う
     *
     * @return true:成功, false:失敗(device busy)
     */
    public boolean press() {
        if (DEBUG) {
            Log.d(TAG, "press()");
        }
        synchronized (mLock) {
            if (mCommand == Command.IDLE) {
                byte[] commands = {0x57, 0x01, 0x00};
                if (mGattService != null && mGatt != null) {
                    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        mCommand = Command.OTHERS;
                        characteristic.setValue(commands);
                        mGatt.writeCharacteristic(characteristic);
                    }
                }
                return true;
            } else {
                Log.e(TAG, "device busy");
                return false;
            }
        }
    }

    /**
     * Up動作を行う
     *
     * @return true:成功, false:失敗(device busy)
     */
    public boolean up() {
        if (DEBUG) {
            Log.d(TAG, "up()");
        }
        synchronized (mLock) {
            if (mCommand == Command.IDLE) {
                byte[] commands = {0x57, 0x01, 0x04};
                if (mGattService != null && mGatt != null) {
                    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        mCommand = Command.OTHERS;
                        characteristic.setValue(commands);
                        mGatt.writeCharacteristic(characteristic);
                    }
                }
                return true;
            } else {
                Log.e(TAG, "device busy");
                return false;
            }
        }
    }

    /**
     * Down動作を行う
     *
     * @return true:成功, false:失敗(device busy)
     */
    public boolean down() {
        if (DEBUG) {
            Log.d(TAG, "down()");
        }
        synchronized (mLock) {
            if (mCommand == Command.IDLE) {
                byte[] commands = {0x57, 0x01, 0x03};
                if (mGattService != null && mGatt != null) {
                    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        mCommand = Command.OTHERS;
                        characteristic.setValue(commands);
                        mGatt.writeCharacteristic(characteristic);
                    }
                }
                return true;
            } else {
                Log.e(TAG, "device busy");
                return false;
            }
        }
    }

    /**
     * turn On動作を行う
     *
     * @return true:成功, false:失敗(device busy)
     */
    public boolean turnOn() {
        if (DEBUG) {
            Log.d(TAG, "turnOn()");
        }
        synchronized (mLock) {
            if (mCommand == Command.IDLE) {
                byte[] commands = {0x57, 0x01, 0x01};
                if (mGattService != null && mGatt != null) {
                    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        mCommand = Command.OTHERS;
                        characteristic.setValue(commands);
                        mGatt.writeCharacteristic(characteristic);
                    }
                }
                return true;
            } else {
                Log.e(TAG, "device busy");
                return false;
            }
        }
    }

    /**
     * turn Off動作を行う
     *
     * @return true:成功, false:失敗(device busy)
     */
    public boolean turnOff() {
        if (DEBUG) {
            Log.d(TAG, "turnOff()");
        }
        synchronized (mLock) {
            if (mCommand == Command.IDLE) {
                byte[] commands = {0x57, 0x01, 0x02};
                if (mGattService != null && mGatt != null) {
                    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(SWITCHBOT_BLE_GATT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        mCommand = Command.OTHERS;
                        characteristic.setValue(commands);
                        mGatt.writeCharacteristic(characteristic);
                    }
                }
                return true;
            } else {
                Log.e(TAG, "device busy");
                return false;
            }
        }
    }

    public interface EventListener {
        void onConnect(SwitchBotDevice switchBotDevice);

        void onDisconnect(SwitchBotDevice switchBotDevice);
    }
}
