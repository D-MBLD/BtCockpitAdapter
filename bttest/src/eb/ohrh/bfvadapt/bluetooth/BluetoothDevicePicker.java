package eb.ohrh.bfvadapt.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;
import android.util.Log;

public class BluetoothDevicePicker implements BluetoothDevicePickerConstants {
    private static final String SELECTED_DEVICE = "eb.ohrh.bfvadapt.selected_device";
    public static final String TAG = BluetoothDevicePicker.class
            .getSimpleName();
    protected Context context;
    private BluetoothDeviceManagerReceiver receiver;

    public BluetoothDevicePicker(Context context) {
        super();
        this.context = context;
    }

    /**
     * NOTE: Unfortunately there is no way to get aware of the user canceling
     * the picker dialog. That means, we can not unregister the receiver on
     * cancel. If possible, the activity, which calls this method should call
     * through inside its onResume method to the onResume method of this class.
     * By that the receiver gets unregistered every time the picker dialog is
     * closed and the activity comes back into foreground.
     * 
     * If pickDevice is called from a service, there is however no way to get
     * aware of the dialog being cancelled.
     * 
     */
    public void pickDevice(BluetoothDevicePickResultHandler handler) {
        unregisterReceiver(); // from previous call
        receiver = new BluetoothDeviceManagerReceiver(handler);
        context.registerReceiver(receiver, new IntentFilter(
                ACTION_DEVICE_SELECTED));
        // FLAG_ACTIVITY_NEW_TASK is needed when calling an Activity from a
        // Service
        // (Otherwise a AndroidRuntimeException is thrown)
        context.startActivity(new Intent(ACTION_LAUNCH_DEVICE_PICKER)
                .putExtra(EXTRA_NEED_AUTH, false)
                .putExtra(EXTRA_FILTER_TYPE, FILTER_TYPE_ALL)
                .setFlags(
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                | Intent.FLAG_ACTIVITY_NEW_TASK));
        // Result is captured by the registered receiver
    }

    public void onResume() {
        /* May be back from Device picker */
        unregisterReceiver();
    }

    public static interface BluetoothDevicePickResultHandler {
        void onDevicePicked(BluetoothDevice device);
    }

    private class BluetoothDeviceManagerReceiver extends BroadcastReceiver {

        private final BluetoothDevicePickResultHandler handler;

        public BluetoothDeviceManagerReceiver(
                BluetoothDevicePickResultHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevicePicker.this.unregisterReceiver();

            BluetoothDevice device = intent
                    .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            saveInPrefs(device);
            Log.v(TAG, "Picked device: " + device.getName());
            handler.onDevicePicked(device);
        }
    }

    private void unregisterReceiver() {
        if (receiver != null) {
            context.unregisterReceiver(receiver);
        }
        receiver = null;
    }

    private void saveInPrefs(BluetoothDevice device) {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        Editor editor = preferences.edit();
        editor.putString(SELECTED_DEVICE, device.getAddress());
        editor.commit();
    }

    /**
     * Read the device stored in preferences (device is automatically stored
     * there, when picked). If no device was ever picked, or the device is
     * unknown now (due to un-pairing it), null is returned.
     */
    public BluetoothDevice getDeviceFromPrefs() {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        String deviceMAC = preferences.getString(SELECTED_DEVICE, null);
        if (deviceMAC != null) {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter()
                    .getRemoteDevice(deviceMAC);
            return device;
        } else {
            return null;
        }
    }

}