package org.deviceconnect.android.deviceplugin.switchbot.utility;

import android.bluetooth.BluetoothDevice;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.deviceconnect.android.deviceplugin.switchbot.BuildConfig;
import org.deviceconnect.android.deviceplugin.switchbot.R;
import org.deviceconnect.android.deviceplugin.switchbot.device.SwitchBotDevice;

class ListViewHolder<T> extends RecyclerView.ViewHolder implements CheckBox.OnCheckedChangeListener {
    private static final String TAG = "ListViewHolder";
    private static final Boolean DEBUG = BuildConfig.DEBUG;
    private TextView deviceAddress;
    private TextView deviceName;
    private T item;
    private EventListener eventListener;

    ListViewHolder(View itemView, EventListener eventListener) {
        super(itemView);
        if (DEBUG) {
            Log.d(TAG, "ListViewHolder()");
            Log.d(TAG, "itemView:" + itemView);
            Log.d(TAG, "eventListener:" + eventListener);
        }
        deviceAddress = itemView.findViewById(R.id.device_address);
        if (deviceAddress != null) {
            deviceAddress.setOnClickListener(view -> {
                if (DEBUG) {
                    Log.d(TAG, "deviceAddress onClick()");
                }
                if (eventListener != null) {
                    eventListener.onItemClick((BluetoothDevice) item);
                }
            });
        }
        deviceName = itemView.findViewById(R.id.device_name);
        if(deviceName != null) {
            deviceName.setOnClickListener(view -> {
                if(DEBUG){
                    Log.d(TAG, "deviceName onClick()");
                }
                if(eventListener != null){
                    eventListener.onItemClick((SwitchBotDevice) item);
                }
            });
        }
        CheckBox deleteCheckBox = itemView.findViewById(R.id.check_delete);
        if(deleteCheckBox != null) {
            deleteCheckBox.setOnCheckedChangeListener(this);
        }
        this.eventListener = eventListener;
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
        if (DEBUG) {
            Log.d(TAG, "onCheckedChanged()");
            Log.d(TAG, "compoundButton : " + compoundButton);
            Log.d(TAG, "checked : " + checked);
        }
        if (compoundButton.isFocusable()) {
            if (this.item instanceof SwitchBotDevice) {
                eventListener.onCheckedChange((SwitchBotDevice) this.item, checked);
            }
        }
    }

    void bind(T item) {
        if (DEBUG) {
            Log.d(TAG, "bind()");
        }
        this.item = item;
        if (this.item instanceof BluetoothDevice) {
            if (DEBUG) {
                Log.d(TAG, "device address:" + ((BluetoothDevice) this.item).getAddress());
            }
            deviceAddress.setText(((BluetoothDevice) this.item).getAddress());
        } else if (this.item instanceof SwitchBotDevice) {
            if (DEBUG) {
                Log.d(TAG, "device name:" + ((SwitchBotDevice) this.item).getDeviceName());
            }
            deviceName.setText(((SwitchBotDevice) this.item).getDeviceName());
        }
    }

    interface EventListener {
        void onItemClick(BluetoothDevice bluetoothDevice);
        void onItemClick(SwitchBotDevice switchBotDevice);

        void onCheckedChange(SwitchBotDevice device, Boolean checked);
    }
}
