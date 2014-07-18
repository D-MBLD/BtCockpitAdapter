package eb.ohrh.bfvadapt.bluetooth;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.AsyncTask;
import android.os.ParcelUuid;
import android.util.Log;

/* Background task, which tries to establish the bluetooth connection.
 * On successful connection, the callback handler, which must be provided,
 * receives a BluetoothSocket, where the data is received. 
 * 
 */
public class AsyncConnectTask extends
        AsyncTask<BluetoothDevice, String, String> {
    private static final int CONNECT_NORMAL = 1;
    private static final int CONNECT_REFLECT = 2;
    private static int connectMethod = CONNECT_NORMAL;
    private static final UUID MY_UUID = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String TAG = AsyncConnectTask.class.getSimpleName();
    private BluetoothSocket socket;
    private CallbackHandler handler;
    private boolean success = false;

    public interface CallbackHandler {
        void connected(String msg, BluetoothSocket socket);

        void connectionFailed(String msg);

        void progress(String msg);
    }

    public AsyncConnectTask(CallbackHandler handler) {
        super();
        this.handler = handler;
    }

    @Override
    protected String doInBackground(BluetoothDevice... devices) {
        BluetoothDevice device = devices[0];
        // Log.i(BFVService.TAG, "address " + device.getAddress());
        // Log.i(BFVService.TAG, "name " + device.getName());
        // Log.i(BFVService.TAG, "class " +
        // device.getBluetoothClass().toString());
        // Log.i(BFVService.TAG, "bondstate " + device.getBondState());

        // try uuidLookup - sometimes it might help for api v14 or later.
        uuidLookup(device);

        /* Alternate the ConnectMethod on each try (test only ) */
        // TODO: Store last successful connectMethod in preferences.
        if (connectMethod == CONNECT_NORMAL) {
            connectMethod = CONNECT_REFLECT;
        } else {
            connectMethod = CONNECT_NORMAL;
        }
        Log.i(TAG, "connectMethod: " + connectMethod);

        if (connectMethod == CONNECT_NORMAL) { // this should be
                                               // the default
            try {
                // Log.i(BFVService.TAG, "Try create secure");
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                tryConnectToSocket(device, socket);
                return "Connected via createRfcommSocketToServiceRecord";
            } catch (IOException e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                    }
                }
                // Try again below
            }
            try {
                // Log.i(BFVService.TAG, "Try create insecure");
                socket = device
                        .createInsecureRfcommSocketToServiceRecord(MY_UUID);
                tryConnectToSocket(device, socket);
                return "Connected via createInsecureRfcommSocketToServiceRecord";

            } catch (IOException e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                    }
                }
                return "IOExeception: " + e.getLocalizedMessage();
            }
        }

        if (connectMethod == CONNECT_REFLECT) {
            try {
                // Log.i(BFVService.TAG, "Try create from reflect");
                Method m;
                m = device.getClass().getMethod("createRfcommSocket",
                        new Class[] { int.class });
                socket = (BluetoothSocket) m.invoke(device, Integer.valueOf(1));
                tryConnectToSocket(device, socket);
                return "Connected via reflection";
            } catch (IOException e) {
                return "IOException: " + e.getLocalizedMessage();
            } catch (NoSuchMethodException e) {
                return "NoSuchMethodException: " + e.getLocalizedMessage();
            } catch (IllegalAccessException e) {
                return "IllegalAccessException: " + e.getLocalizedMessage();
            } catch (InvocationTargetException e) {
                return "InvocationTargetException: " + e.getLocalizedMessage();
            }
        }
        throw new IllegalStateException();
    }

    private void tryConnectToSocket(BluetoothDevice device,
            BluetoothSocket socket) throws IOException {
        // Log.i(TAG, "Try cancelDiscovery");
        // Cancel possibly ongoing discovery. It is highly recommended
        // to do so, as device discovery takes a lot of the adapters resources
        // and may disturb the connection attempt.
        // Unfortunately this requires to add the BLUETOOTH_ADMIN permission.
        BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
        // This is a blocking call and will only return on a
        // successful connection or an exception
        publishProgress("Connecting to " + device.getName() + " ...");
        if (!isCancelled()) {
            socket.connect();
            if (isCancelled()) {
                // immediately close the socket again
                socket.close();
                return;
            }
            success = true;
        }
        publishProgress("Connected successfully");
    }

    private boolean uuidLookup(BluetoothDevice device) {

        // Log.i(BFVService.TAG, "Finding UUIDs");
        try {
            /*
             * Use reflection, as the methods are only available with API Level
             * 15 (4.0.3)
             */
            Method m = device.getClass().getMethod("getUuids", null);
            ParcelUuid[] uuids = (ParcelUuid[]) m.invoke(device, null);
            if (uuids != null) {
                Log.i(TAG, "Device supports following UUIDs:");
                for (int i = 0; i < uuids.length; i++) {
                    ParcelUuid uuid = uuids[i];
                    Log.i(TAG, uuid.toString());
                    if (uuid.getUuid().compareTo(MY_UUID) == 0) {
                        // UUID is already cached
                        return true;
                    }

                }
            }
            // Refresh the local cache with the UUIDs supported by the device
            Method fetch = device.getClass().getMethod("fetchUuidsWithSdp",
                    null);
            fetch.invoke(device, null);
            return false;

        } catch (NoSuchMethodException e) {
            Log.e(TAG, "", e);
        } catch (IllegalAccessException e) {
            Log.e(TAG, "", e);
        } catch (InvocationTargetException e) {
            Log.e(TAG, "", e);
        }
        Log.i(TAG, "Finding Uuids could not be invoked");
        return false;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
    }

    @Override
    protected void onPostExecute(String resultMsg) {
        Log.v(TAG, "onPostExecute: " + resultMsg);
        if (success) {
            handler.connected(resultMsg, socket);
        } else {
            handler.connectionFailed(resultMsg);
        }
    }

    @Override
    protected void onProgressUpdate(String... progressMsg) {
        handler.progress(progressMsg[0]);
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "close() of connect socket failed", e);
        }
    }
}
