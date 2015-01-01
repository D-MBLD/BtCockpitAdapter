package eb.ohrh.bfvadapt.bluetooth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Parcelable;
import android.util.Log;
import eb.ohrh.bfvadapt.bluetooth.BluetoothDevicePicker.BluetoothDevicePickResultHandler;
import eb.ohrh.bfvadapt.model.Model;

/** Manages the state and state transitions of the bluetooth connection. */
public class BluetoothConnectionManager extends BroadcastReceiver implements
        BluetoothDevicePickResultHandler {

    public interface Listener {
        void update(BluetoothConnectionManager mgr);
    }

    /**
     * Initially it is checked, whether a device is selected in the preferences,
     * and whether bluetooth is switched on. Dependend on that, one of the first
     * 3 states is the initial state.
     * 
     * The state connected and connect_failed allow to select another device,
     * which is then immediately connected.
     * 
     * From connected state it is also possible to disconnect, and hereby going
     * to state MANUAL_CONNECT, which allows to select another device or to
     * connect again.
     * 
     * The numeric value is used for remote communication when sending the
     * current state.
     * 
     */
    public enum State {
        INIT, // 0 Temporary initial state. Check and move to NOT_SUPPORTED,
              // BLUETOOTH_OFF, SHOW_SELECTION_DIALOG or CONNECTING.
        NOT_SUPPORTED, // 1 No Bluetooth adapter found
        BLUETOOTH_OFF, // 2 Bluetooth is off. Listen for Bluetooth-on event,
                       // then continue to SHOW_SELECTION_DIALOG or CONNECTING.
        SELECT_DEVICE, // 3 No device selected. On Device selection event
                       // continue to CONNECTING
        CONNECTING, // 4 Trying to connect
        RECONNECTING, // 5 Trying to connect after connection failed
        CONNECTED, // 6 Connection successful
        CONNECTION_FAILED, // 7 Connection failed
        DISCONNECTED, // 8 Disconnected by intention
        RECEIVING, // 9 Data receiving ongoing
    };

    /**
     * List of actions, which may be send by clients. Remote clients will use
     * the ordinal value rather than the enum. Thus the sequence must never be
     * changed !!!
     */
    public enum Actions {
        CONNECT, // 0
        DISCONNECT, // 1
        CANCEL_CONNECTING, // 2
        ENABLE_BLUETOOTH, // 3
        SELECT_DEVICE, // 4
        REGISTER_CLIENT, // 5:
        UNREGISTER_CLIENT; // 6
    }

    private static final String TAG = BluetoothConnectionManager.class
            .getSimpleName();

    /**
     * Retry every 15 seconds to connect to a predefined device, which might be
     * switched off.
     */
    private static final long RETRY_INTERVAL = 15000;

    private Context context;
    private State currentState;
    private BluetoothDevicePicker mgr;
    private BluetoothDevice device;
    private BluetoothSocket socket;
    private ConnectedThread connectedThread;
    private List<Listener> listeners = new ArrayList<Listener>();

    private Handler retryHandler;

    private AsyncTask<BluetoothDevice, String, String> asyncConnectionTask;

    public BluetoothConnectionManager(Context context) {
        this.context = context;
        onInit();
    }

    private void onInit() {
        boolean hasNoAdapter = BluetoothAdapter.getDefaultAdapter() == null;

        if (hasNoAdapter) {
            setCurrentState(State.NOT_SUPPORTED);
            return;
        }
        retryHandler = new Handler();
        /*
         * Check, whether bluetooth is on or off, and whether a device is
         * already stored in the preferences.
         */
        mgr = new BluetoothDevicePicker(context);
        device = mgr.getDeviceFromPrefs();
        if (isBluetoothEnabled()) {
            if (device != null) {
                // connect directly
                doConnect();
            } else {
                // offer to select a device
                doSelectDevice();
            }
        } else {
            doSwitchOnBluetooth();
        }
        // TODO: Isn't that too late ???
        IntentFilter filter = new IntentFilter(
                BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        /* Register for notifications about changes in the bluetooth state */
        context.registerReceiver(this, filter);

    }

    private void doSwitchOnBluetooth() {
        setCurrentState(State.BLUETOOTH_OFF);
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter
                .getDefaultAdapter();
        boolean isEnabled = bluetoothAdapter.isEnabled();
        if (isEnabled) {
            // Was already enabled, fire next event
            onBluetoothEnabled();
            return;
        }
        // Use intent to first open dialog box as confirmation
        Intent bluetoothEnableDialog = new Intent(
                BluetoothAdapter.ACTION_REQUEST_ENABLE);
        bluetoothEnableDialog
                .setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(bluetoothEnableDialog);
        // Result will be received as notification in onReceive
    }

    /**
     * Call the device picker. The result is returned asynchronously via a
     * receiver which calls back to onDevicePicked();
     */
    private void doSelectDevice() {
        // TODO: Remove the selected device from the preferences (?)

        doDisconnect();
        setCurrentState(State.SELECT_DEVICE);
        device = null;
        /*
         * Make sure, the state is still SELECT_DEVICE, if the dialog is
         * cancelled
         */
        mgr.pickDevice(this);
    }

    public void onFinish() {
        try {
            context.unregisterReceiver(this);
        } catch (Exception e) {
            // Don't crash the app, because
            // receiver registration got lost.
            Log.e(TAG, "Error when unregistering receiver", e);
        }
        listeners.clear();
        doDisconnect();
    }

    private void doConnect() {
        /**
         * If current state is connection failed, then its rather a RECONNECTING
         * than a CONNECTING.
         */
        if (currentState == State.CONNECTION_FAILED) {
            setCurrentState(State.RECONNECTING);
        } else {
            setCurrentState(State.CONNECTING);
        }
        asyncConnectionTask = new AsyncConnectTask(new ConnectionListener())
                .execute(device);
        // currentState will be changed to CONNECT or CONNECT_FAILED by the
        // Callback-Handler
    }

    private void doDisconnect() {
        setCurrentState(State.DISCONNECTED);
        if (asyncConnectionTask != null) {
            asyncConnectionTask.cancel(true);
        }
        if (socket != null) {
            try {
                socket.close();
                socket = null;
            } catch (IOException e) {
            }
        }
    }

    public State getState() {
        return currentState;
    }

    public String getDeviceName() {
        if (device != null) {
            /**
             * Seems that the device name is not available as long as Bluetooth
             * is switched off.
             */
            String name = device.getName();
            if (name != null) {
                return name;
            }
            return device.getAddress();
        } else {
            return null;
        }
    }

    private void setCurrentState(State state) {
        currentState = state;
        Log.v(TAG, "State set to " + state);
        notifyObservers();
    }

    /**
     * Start a Handler, which starts itself again, as long as the state is
     * CONNECTION_FAILED.
     */
    private void startRetryHandler() {
        Runnable retryThread = new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, "Trying to reconnect");
                if (currentState == State.CONNECTION_FAILED) {
                    doConnect();
                    /*
                     * This will change the state to CONNECTING. If that fails
                     * again, startRetryHandler will be called so there is no
                     * need, to repeat the call inside the Runnable.
                     */

                }
            }
        };
        retryHandler.postDelayed(retryThread, RETRY_INTERVAL);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListeners() {
        listeners.clear();
    }

    private void notifyObservers() {
        for (BluetoothConnectionManager.Listener listener : listeners) {
            listener.update(this);
        }
    }

    private void startReadingPressure() {
        // Start a Thread, which receives the pressure reading
        // and sends the values to the model.
        Model model = Model.getInstance();
        if (connectedThread != null && connectedThread.isAlive()) {
            connectedThread.interrupt();
        }
        connectedThread = new ConnectedThread(socket, model);
        connectedThread.start();
    }

    private void setStatusMsg(String msg) {
        // TODO Auto-generated method stub
    }

    // Defined by BluetoothDevicePicker.BluetoothDevicePickResultHandler
    @Override
    public void onDevicePicked(BluetoothDevice device) {
        this.device = device;
        // try to connect (asynchronously)
        Log.v(TAG, "Device " + device.getName()
                + " was selected. Trying to connect");
        doConnect();
    }

    /** Listener for result of asynchronous connection task. */
    class ConnectionListener implements AsyncConnectTask.CallbackHandler {

        @Override
        public void connected(String msg, BluetoothSocket socket) {
            onConnectedSuccessfully(msg, socket);
        }

        @Override
        public void connectionFailed(String msg) {
            onConnectionFailed(msg);
        }

        @Override
        public void progress(String msg) {
            setStatusMsg(msg);
        }

    }

    private void onConnectedSuccessfully(String msg, BluetoothSocket socket) {
        setStatusMsg(msg);
        this.socket = socket;
        setCurrentState(State.CONNECTED);
        startReadingPressure();
    }

    private void onConnectionFailed(String msg) {
        setStatusMsg(msg);
        setCurrentState(State.CONNECTION_FAILED);
        startRetryHandler();
    }

    public boolean isBluetoothEnabled() {
        BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
        return (defaultAdapter != null && defaultAdapter.isEnabled());
    }

    /** Broadcast-Receiver implementation */
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR);
            switch (state) {
            case BluetoothAdapter.STATE_OFF:
                onBluetoothDisabled();
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                // onBluetoothDisabled(); // ???
                break;
            case BluetoothAdapter.STATE_ON:
                onBluetoothEnabled();
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                break;
            }
        } else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
            /*
             * Detected that device was disconnected. If the state is already
             * DISCONNECTED, then the device was disconnected by intention, and
             */
            if (currentState == State.DISCONNECTED || device == null) {
                return;
            }
            if (device == null) {
                setCurrentState(State.SELECT_DEVICE);
            }
            Parcelable parcelable = intent
                    .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            BluetoothDevice disconnectedDevice = (BluetoothDevice) parcelable;
            if (disconnectedDevice.getAddress().equals(device.getAddress())) {
                setCurrentState(State.CONNECTION_FAILED);
                startRetryHandler();
            }
        }
    }

    /**
     * If device is known and only waiting for bluetooth, then connect, else
     * just notify, that the state remained the same, but the state information
     * may need to be updated.
     */
    private void onBluetoothEnabled() {
        if (currentState == State.BLUETOOTH_OFF) {
            if (device != null) {
                doConnect();
            } else {
                doSelectDevice();
            }
        } else {
            setCurrentState(currentState);
        }
    }

    /**
     * Call disconnect, to cleanup the threads, and set the state to
     * BLUETOOTH_OFF.
     */
    private void onBluetoothDisabled() {
        doDisconnect();
        setCurrentState(State.BLUETOOTH_OFF);
    }

    /** Request for actions from outside */
    public void performAction(Actions action) {
        switch (action) {
        case CONNECT:
            doConnect();
            break;
        case DISCONNECT:
            doDisconnect();
            break;
        case CANCEL_CONNECTING:
            doDisconnect();
            break;
        case SELECT_DEVICE:
            doSelectDevice();
            break;
        case ENABLE_BLUETOOTH:
            doSwitchOnBluetooth();
            break;
        }
    }

}
