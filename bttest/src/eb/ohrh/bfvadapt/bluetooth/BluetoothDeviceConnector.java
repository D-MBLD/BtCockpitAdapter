package eb.ohrh.bfvadapt.bluetooth;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

public class BluetoothDeviceConnector {
    private static final String TAG = BluetoothDeviceConnector.class.getSimpleName();
    private static final int CONNECT_NORMAL = 1;
    private static final int CONNECT_REFLECT = 2;

    private BluetoothSocket socket;
    private String errorMessage;
    private Context context;
    // Unique UUID for this application
    public static final UUID MY_UUID = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");

    public BluetoothDeviceConnector(Context ctx) {
        context = ctx;
    }

    public void connect(BluetoothDevice device) {
        // try uuidLookup - sometimes it might help for api v14 or later.
        uuidLookup(device);

        int connectMethod = CONNECT_NORMAL;
        // TODO: Save the successful method in preferences

        if (connectMethod == CONNECT_NORMAL) { // this should be
                                               // the default
            try {
                // Log.i(BFVService.TAG, "Try create secure");
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                boolean success = tryConnectToSocket(socket);
                if (!success) {
                    // Log.i(BFVService.TAG, "Try create insecure");
                    socket = device
                            .createInsecureRfcommSocketToServiceRecord(MY_UUID);
                }
                success = tryConnectToSocket(socket);

                if (!success) {
                    connectionFailed("Socket: " + errorMessage);
                } else {
                    Toast.makeText(context, "Connected", Toast.LENGTH_LONG)
                            .show();
                }

            } catch (IOException e) {

                connectionFailed("CS: " + e.getLocalizedMessage());
            }
        }

        if (connectMethod == CONNECT_REFLECT) {
            try {
                // Log.i(BFVService.TAG, "Try create from reflect");
                Method m;
                m = device.getClass().getMethod("createRfcommSocket",
                        new Class[] { int.class });
                socket = (BluetoothSocket) m.invoke(device, Integer.valueOf(1));
                boolean success = tryConnectToSocket(socket);
                if (!success) {
                    Toast.makeText(context, "Connected (method reflection)",
                            Toast.LENGTH_LONG).show();
                } else {
                    connectionFailed("Reflect Socket: " + errorMessage);
                }
            } catch (NoSuchMethodException e) {
                connectionFailed("RCS1: " + e.getLocalizedMessage());
            } catch (IllegalAccessException e) {
                connectionFailed("RCS2: " + e.getLocalizedMessage());
            } catch (InvocationTargetException e) {
                connectionFailed("RCS3: " + e.getLocalizedMessage());
            }

        }
    }

    private boolean uuidLookup(BluetoothDevice device) {

        // Log.i(BFVService.TAG, "Finding UUIDs");
        try {
            Method m = device.getClass().getMethod("getUuids", null);
            ParcelUuid[] uuids = (ParcelUuid[]) m.invoke(device, null);
            if (uuids == null) {
                // Log.i(BFVService.TAG, "Null UUIDs");
                // return false;
            }

            if (uuids != null) {
                for (int i = 0; i < uuids.length; i++) {
                    ParcelUuid uuid = uuids[i];
                    Log.i(TAG, uuid.toString());
                    if (uuid.getUuid().compareTo(MY_UUID) == 0) {
                        // Log.i(BFVService.TAG, "Contains SPP UUID");
                        return true;
                    }

                }
            }

            // Log.i(BFVService.TAG, "SPP UUID Not found");
            // Log.i(BFVService.TAG, "Invoking fetch");
            Method fetch = device.getClass().getMethod("fetchUuidsWithSdp",
                    null);
            fetch.invoke(device, null);
            return false;

        } catch (NoSuchMethodException e) {
            e.printStackTrace(); // To change body of catch statement use
                                 // File | Settings | File Templates.
        } catch (IllegalAccessException e) {
            e.printStackTrace(); // To change body of catch statement use
                                 // File | Settings | File Templates.
        } catch (InvocationTargetException e) {
            e.printStackTrace(); // To change body of catch statement use
                                 // File | Settings | File Templates.
        }
        Log.i(TAG, "Finding Uuids could not be invoked");
        return false;

    }

    private boolean tryConnectToSocket(BluetoothSocket socket) {
        // TODO: Add if implemented as AsyncTask
        // if (isCancelled()) {
        // Log.i(TAG, "tryConectToSocket Cancelled ");
        // return false;
        // }
        Log.i(TAG, "Try cancelDiscovery");
        BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
        try {
            // This is a blocking call and will only return on a
            // successful connection or an exception
            Log.i(TAG, "Try connect");
            socket.connect();
            Log.i(TAG, "Passed Connect");
            // connected(socket, device);
            return true;
        } catch (IOException e) {

            // Close the socket
            try {
                socket.close();
            } catch (IOException e2) {
                Log.e(TAG,
                        "unable to close() socket during connection failure",
                        e2);
            }
            errorMessage = e.getLocalizedMessage();

        }
        return false;

    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    public void connectionFailed(String s) {
        Toast.makeText(context, s, Toast.LENGTH_LONG).show();
    }

}
