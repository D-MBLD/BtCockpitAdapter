package eb.ohrh.bfvadapt.service;

import java.util.ArrayList;
import java.util.Observable;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;
import eb.ohrh.bfvadapt.activity.MainActivity;
import eb.ohrh.bfvadapt.bluetooth.BluetoothConnectionManager;
import eb.ohrh.bfvadapt.bluetooth.BluetoothConnectionManager.Actions;
import eb.ohrh.bfvadapt.bluetooth.BluetoothConnectionManager.Listener;
import eb.ohrh.bfvadapt.bluetooth.BluetoothConnectionManager.State;
import eb.ohrh.bfvadapt.debug.R;
import eb.ohrh.bfvadapt.model.Model;
import eb.ohrh.bfvadapt.model.ModelListener;

/**
 * Sends pressure data to the remote client. The service listens to data changes
 * in the model and to status changes in BluetoothConnectionManager and sends a
 * corresponding message to each bound client.
 * 
 * The client(s) must register by sending a Message (what = CONNECT) containing
 * a Messenger in the replyTo field. This Messenger receives the pressure data
 * updates as soon as a Message REQUEST_PRESSURE_UPDATE is sent. The updates are
 * send as messages with what = SEND_PRESSURE_DATA having arg1 containing the
 * value.
 */
public class BFVAdapterService extends Service implements ModelListener,
        Listener {
    private static final String TAG = BFVAdapterService.class.getSimpleName();

    static public final String INTENT_ACTION_START = "eb.ohrh.bfvadapt.service.START";

    /** Keys for information send to the client */
    /** Message contains pressure in Pascal and time as int */
    static final int SEND_PRESSURE_UPDATE = 1;
    /** Message contains Battery level in milliVolts and time as int */
    static final int SEND_BATTERY_UPDATE = 2;
    /**
     * Message contains one of the State-Values defined by the enum ordinals of
     * BluetoothConnectionManager.State
     */
    static final int SEND_STATE_UPDATE = 3;
    /** Commands allowed by clients */
    static final int REQUEST_PRESSURE_UPDATE = 1;
    static final int CONNECT = 4;

    private static final boolean DEBUG = false;

    private BluetoothConnectionManager mConnectionManager;

    private Handler dummyHandler;

    /**
     * The Messenger we publish to clients, such that clients can send back
     * information to the service. The messages are received through the
     * IncomingHandler.
     */
    private Messenger mMessenger;

    /** Values from previous update, used to decide whether an update is needed. */
    private double previousBattery;

    /** Keeps track of all current registered clients. */
    ArrayList<Messenger> mClients = new ArrayList<Messenger>();

    private int mId = 1;

    /**
     * Handler of incoming messages from clients. This is mainly needed by the
     * Configuration App. For usage with PG Dashboard, the service just sends
     * data after it is started and could successfully connect. There is no
     * further dialog between the PG Dashboard and the service needed.
     */
    class IncomingHandler extends Handler {

        private BluetoothConnectionManager connectionManager;

        private IncomingHandler(BluetoothConnectionManager mgr) {
            // TODO: Check the warning, which says, that the class should be
            // static to avoid leaks.
            this.connectionManager = mgr;
        }

        @Override
        public void handleMessage(Message msg) {
            BluetoothConnectionManager.Actions action;
            Actions[] actions = BluetoothConnectionManager.Actions.values();
            if (msg.what < actions.length) {
                action = actions[msg.what];
            } else {
                // Illegal action
                return;
            }
            if (action == Actions.REGISTER_CLIENT) {
                mClients.add(msg.replyTo);
                // Send the current state immediately to all clients
                // including especially the newly registered one.
                update(connectionManager);
            } else if (action == Actions.UNREGISTER_CLIENT) {
                boolean removed = mClients.remove(msg.replyTo);
                if (!removed) {
                    Log.e(TAG, "Client could not be removed !");
                }
            } else {
                connectionManager.performAction(action);
            }
        }

    }

    @Override
    public void onCreate() {
        mConnectionManager = new BluetoothConnectionManager(this);
        mMessenger = new Messenger(new IncomingHandler(mConnectionManager));
        mConnectionManager.addListener(this);
        Model model = Model.getInstance();
        model.addObserver(this);
        dummyHandler = new Handler();

        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) {
            showNotification();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void showNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, Intent.FLAG_ACTIVITY_NEW_TASK);

        /*
         * Show a notification as long as the service is running (not destroyed)
         */
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                this).setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("BFV Adapter")
                .setContentText("BFV Adapter Running");
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify(mId, mBuilder.build());
    }

    @Override
    public void onDestroy() {
        mConnectionManager.onFinish();
        Model model = Model.getInstance();
        model.deleteObservers();

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(mId);

        super.onDestroy();
    }

    /**
     * When binding to the service, we return an interface to our messenger for
     * sending messages to the service.
     * 
     * Note: This is only called once, when the first client binds to the
     * service. For subsequent binds, Android just returns the same IBinder
     * interface.
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Toast.makeText(this, "Bound to BFV adapter", Toast.LENGTH_SHORT)
                    .show();
            showNotification();
        }
        return mMessenger.getBinder();
    }

    @Override
    public void onRebind(Intent intent) {
        if (DEBUG) {
            Toast.makeText(this, "re-binding", Toast.LENGTH_SHORT).show();
        }
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (DEBUG) {
            Toast.makeText(this, "Unbinding from BFV adapter",
                    Toast.LENGTH_SHORT).show();
        }
        return super.onUnbind(intent);
    }

    /** Listener for Model changes */
    @Override
    public void update(Observable observable, Object data) {
        if (observable instanceof Model) {
            final Model model = (Model) observable;
            double battery = model.getBattery();
            long[] pressureAndTime = model.getPressureAndTime();
            int pressure = (int) pressureAndTime[0];
            long time = pressureAndTime[1];
            if (battery != previousBattery) {
                previousBattery = battery;
                Message msg = Message.obtain(dummyHandler, SEND_BATTERY_UPDATE,
                        (int) (battery * 1000), (int) time);
                sendToClients(msg);
            }
            Message msg = Message.obtain(dummyHandler, SEND_PRESSURE_UPDATE,
                    pressure, (int) time);
            sendToClients(msg);
        }
    }

    /** Listener for Status changes */
    @Override
    public void update(BluetoothConnectionManager mgr) {
        // Inform the bounded clients, if any.
        State state = mgr.getState();
        int ordinal = state.ordinal();
        long time = System.currentTimeMillis();
        String deviceName = mgr.getDeviceName();
        Log.v(TAG, "Informing clients about status change to status " + state
                + " (" + ordinal + ")");
        Message msg = Message.obtain(dummyHandler, SEND_STATE_UPDATE, ordinal,
                (int) time);
        if (deviceName != null) {
            Bundle b = new Bundle();
            b.putCharSequence("DEVICE", deviceName);
            msg.obj = b;
        }
        sendToClients(msg);

    }

    private int sendCount = 0;

    private void sendToClients(Message msg) {
        Messenger deadClient = null;
        sendCount++;
        if (sendCount % 500 == 0) {
            Log.v(TAG, "Sending to " + mClients.size() + " clients");
        }
        for (Messenger channelToClient : mClients) {
            try {
                channelToClient.send(msg);
            } catch (DeadObjectException e) {
                Log.e(TAG, "Error sending to client", e);
                deadClient = channelToClient;
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending to client", e);
            }
        }
        if (deadClient != null) {
            mClients.remove(deadClient);
            // If this was the last one, stop the service.
            if (mClients.size() == 0) {
                Log.v(TAG, "Service stopped. No clients listening");
                stopSelf();
            }
        }

    }

}
