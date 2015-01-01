package eb.ohrh.bfvadapt.activity;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import eb.ohrh.bfvadapt.bluetooth.BluetoothConnectionManager;
import eb.ohrh.bfvadapt.debug.R;

/* The StartScreen of the BlueFlyVario-Adapter App */
public class MainActivity extends Activity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String ROOT_EN = "file:///android_asset/help/en/";
    private static final String ROOT_DE = "file:///android_asset/help/de/";
    private static final String TOP_PAGE = "top.html";

    static private final String SERVICE_CLASS = "eb.ohrh.bfvadapt.service.BFVAdapterService";

    // private BluetoothConnectionManager mgr;
    private ViewHolder vh;

    /**
     * Actions supported by BFVAdapterService. The sequence (ordinal) must be
     * exactly the same as in BluetoothConnectionManager.
     * 
     * NOTE: EXIT is an additional action, which is not sent to the service, but
     * used as possible Action of the buttons.
     */
    public enum Actions {
        CONNECT, // 0
        DISCONNECT, // 1
        CANCEL_CONNECTING, // 2
        ENABLE_BLUETOOTH, // 3
        SELECT_DEVICE, // 4
        REGISTER_CLIENT, // 5:
        UNREGISTER_CLIENT, // 6
        EXIT, START_SERVICE; // Extra values not supported by the service
    }

    /**
     * Duplicate of State defined in BluetoothConnectionManager Duplicate is not
     * needed here, but only in external Clients. This is just to test such full
     * decoupling.
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
        /*
         * Additional initial states
         */
        SERVICE_STARTED, SERVICE_STOPPED

    };

    /****** Values defined by the service for sending messages *****************/
    /** Message contains pressure in Pascal and time as int */
    static final int SEND_PRESSURE_UPDATE = 1;
    /** Message contains Battery level in milliVolts and time as int */
    static final int SEND_BATTERY_UPDATE = 2;
    /**
     * Message contains one of the State-Values defined by the enum ordinals of
     * { {@link BluetoothConnectionManager.State}
     */
    static final int SEND_STATE_UPDATE = 3;

    /** Messenger for sending requests to the service. */
    private Messenger mService = null;

    /** Flag indicating whether we have called bind on the service. */
    private boolean mBound;

    /**
     * Target we publish to the service to send back its messages.
     */
    final Messenger replyTo = new Messenger(new IncomingHandler());

    /** Receiver for calls from service */
    class IncomingHandler extends Handler {
        // TODO: Check, the warning regarding leaks
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SEND_PRESSURE_UPDATE:
                vh.pressureDisplay.setText("" + msg.arg1 / 100f + " hPa");
                break;
            case SEND_BATTERY_UPDATE:
                vh.batteryDisplay.setText("" + msg.arg1 / 1000f + " V");
                break;
            case SEND_STATE_UPDATE:
                if (msg.arg1 < State.values().length) {
                    State state = State.values()[msg.arg1];
                    Bundle b = (Bundle) msg.obj;
                    String deviceName = null;
                    if (b != null) {
                        deviceName = b.getString("DEVICE");
                    }
                    setButtonTextAndAction(state, deviceName);
                } else {
                    throw new IllegalStateException("State with no " + msg.arg1
                            + " unknown");
                }
                break;
            default:
                super.handleMessage(msg);
            }
        }
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // Initialize the buttons. Will be overwritten, as soon as
            // the service sends it current state as reaction to
            // the registration below.
            setButtonTextAndAction(State.SERVICE_STARTED, "");
            // This is called when the connection with the service has been
            // established, giving us the object we can use to
            // interact with the service. We are communicating with the
            // service using a Messenger, so here we get a client-side
            // representation of that from the raw IBinder object.
            mService = new Messenger(service);
            mBound = true;
            // Send a client channel to the service, which allows the
            // service to send messages.
            Message msg = Message.obtain(null,
                    Actions.REGISTER_CLIENT.ordinal());
            msg.replyTo = replyTo;
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending to service", e);
            }
            // TODO: Ask for current status (may have changed during the client
            // was unbound.
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mService = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.start_page);
        findViews();
        String url = null;
        if (savedInstanceState != null) {
            vh.webView.restoreState(savedInstanceState);
            vh.webView.setBackgroundColor(0x00000000);
        } else {
            String startPage = TOP_PAGE;
            if ("de".equals(getResources().getConfiguration().locale
                    .getLanguage())) {
                url = ROOT_DE;
            } else {
                url = ROOT_EN;
            }
            url += startPage;
            vh.webView.loadUrl(url);
            vh.webView.setBackgroundColor(0x00000000);
        }
        /** Set initially meaningful values */
        setButtonTextAndAction(State.INIT, "");
        /**
         * Start the service in onCreate, to make sure it is not always
         * disconnecting, when the activity is un-binding in onPause or on
         * configuration changes.
         */
        Intent bfvService = getBfvServiceIntent();
        startService(bfvService);
    }

    private Intent getBfvServiceIntent() {
        Intent bfvService = new Intent();
        ComponentName serviceComponentName = new ComponentName(this,
                SERVICE_CLASS);
        bfvService.setComponent(serviceComponentName);
        return bfvService;
    }

    private void findViews() {
        vh = new ViewHolder();
        vh.webView = (WebView) findViewById(R.id.webview);
        vh.statusMsgView = (TextView) findViewById(R.id.statusMsgView);
        vh.button1 = (Button) findViewById(R.id.button1);
        vh.button2 = (Button) findViewById(R.id.button2);
        vh.pressureDisplay = (TextView) findViewById(R.id.pressureDisplay);
        vh.batteryDisplay = (TextView) findViewById(R.id.batteryDisplay);
        vh.pressureAndBatteryFrame = findViewById(R.id.pressureAndBattery);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Bind the activity to the service
        Intent bfvService = getBfvServiceIntent();
        // BIND_AUTO_CREATE: Binding is stronger than a stopService call
        this.bindService(bfvService, mConnection, Service.BIND_AUTO_CREATE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.activity_main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menuid_force_stop_service) {
            forceStopService();
        }
        return super.onOptionsItemSelected(item);
    }

    private void forceStopService() {
        this.unbindService(mConnection);
        mBound = false;
        Intent bfvService = getBfvServiceIntent();
        if (stopService(bfvService)) {
            setButtonTextAndAction(State.SERVICE_STOPPED, null);
        }
        ;
    }

    /**
     * Make sure the webView content is not lost, when the device is turned.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        vh.webView.saveState(outState);
    }

    private void setButtonTextAndAction(State currentState, String deviceName) {
        int buttonText;
        int buttonText2 = 0;
        String text2Param = null;
        Actions action;
        Actions action2 = null;
        vh.pressureAndBatteryFrame.setVisibility(View.GONE);
        switch (currentState) {
        case NOT_SUPPORTED:
            buttonText = R.string.button_label_exit;
            action = Actions.EXIT;
            break;
        case BLUETOOTH_OFF:
            buttonText = R.string.button_label_enable_bt;
            action = Actions.ENABLE_BLUETOOTH;
            break;
        case SELECT_DEVICE:
            buttonText = R.string.button_label_select_bfv;
            action = Actions.SELECT_DEVICE;
            break;
        case CONNECTING:
            buttonText = R.string.button_label_cancel;
            action = Actions.CANCEL_CONNECTING;
            break;
        case RECONNECTING:
            buttonText = R.string.button_label_cancel;
            action = Actions.CANCEL_CONNECTING;
            break;
        case CONNECTED:
            buttonText = R.string.button_label_disconnect;
            action = Actions.DISCONNECT;
            vh.pressureAndBatteryFrame.setVisibility(View.VISIBLE);
            break;
        case CONNECTION_FAILED:
            buttonText = R.string.button_label_select_other;
            action = Actions.SELECT_DEVICE;
            buttonText2 = R.string.button_label_retry;
            action2 = Actions.CONNECT;
            break;
        case DISCONNECTED:
            buttonText = R.string.button_label_select_other;
            action = Actions.SELECT_DEVICE;
            buttonText2 = R.string.button_label_connect_to;
            text2Param = deviceName;
            action2 = Actions.CONNECT;
            break;
        case SERVICE_STARTED:
            buttonText = R.string.button_label_init;
            action = Actions.SELECT_DEVICE;
            break;
        case SERVICE_STOPPED:
            buttonText = R.string.button_label_start_service;
            action = Actions.START_SERVICE;
            break;
        case INIT:
            buttonText = R.string.button_label_exit;
            action = Actions.EXIT;
            break;
        default:
            buttonText = R.string.button_label_illegal_state;
            action = Actions.EXIT;
            break;
        }
        vh.button1.setText(buttonText);
        vh.button1.setTag(action);
        if (buttonText2 == 0) {
            vh.button2.setVisibility(View.GONE);
        } else {
            vh.button2.setVisibility(View.VISIBLE);
            String text = getString(buttonText2, text2Param);
            vh.button2.setText(text);
            vh.button2.setTag(action2);
        }
        setStatusText(currentState, deviceName);
    }

    private void setStatusText(State currentState, String deviceName) {
        int statusTextLine1;
        int statusTextLine2 = 0;
        String line1Param = null;
        String line2Param = null;
        switch (currentState) {
        case NOT_SUPPORTED:
            statusTextLine1 = R.string.status_text_no_bt;
            break;
        case BLUETOOTH_OFF:
            statusTextLine1 = R.string.status_text_bt_disabled;
            if (deviceName != null) {
                statusTextLine2 = R.string.status_text_bfv_selected;
                line2Param = deviceName;
            } else {
                statusTextLine2 = R.string.status_text_no_bfv_selected;
            }
            break;
        case SELECT_DEVICE:
            statusTextLine1 = R.string.status_text_no_bfv_selected;
            break;
        case CONNECTING:
            statusTextLine1 = R.string.status_text_connecting;
            line1Param = deviceName;
            break;
        case RECONNECTING:
            statusTextLine1 = R.string.status_text_reconnecting;
            line1Param = deviceName;
            break;
        case CONNECTED:
            statusTextLine1 = R.string.status_text_connected;
            line1Param = deviceName;
            break;
        case CONNECTION_FAILED:
            statusTextLine1 = R.string.status_text_connect_failed;
            line1Param = deviceName;
            statusTextLine2 = R.string.status_text_retry_pending;
            break;
        case DISCONNECTED:
            statusTextLine1 = R.string.status_text_manual_disconnected;
            line1Param = deviceName;
            break;
        case SERVICE_STARTED:
            statusTextLine1 = R.string.status_text_service_started;
            break;
        case SERVICE_STOPPED:
            statusTextLine1 = R.string.status_text_service_stopped;
            break;
        case INIT:
            statusTextLine1 = R.string.status_text_initializing;
            break;
        default:
            statusTextLine1 = R.string.status_text_illegalstate;
            break;
        }
        String text = getString(statusTextLine1, line1Param);
        if (statusTextLine2 != 0) {
            text += "\n" + getString(statusTextLine2, line2Param);
        }
        vh.statusMsgView.setText(text);

    }

    /* Click-Handler of the connect button. */
    public void onButtonClick(View v) {
        Actions action = (Actions) v.getTag();
        switch (action) {
        case EXIT:
            finish();
            break;
        case START_SERVICE:
            Intent bfvService = getBfvServiceIntent();
            startService(bfvService);
            bindService(bfvService, mConnection, Service.BIND_AUTO_CREATE);
        default:
            Message message = Message.obtain(null, action.ordinal());
            try {
                mService.send(message);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending to service", e);
            }
        }
    }

    @Override
    protected void onPause() {
        // mgr.unregisterReceiver();
        super.onPause();
        if (mBound) {
            if (mService != null) {
                try {
                    Message msg = Message.obtain(null,
                            Actions.UNREGISTER_CLIENT.ordinal());
                    msg.replyTo = replyTo;
                    mService.send(msg);
                } catch (RemoteException e) {
                    // There is nothing special we need to do if the service
                    // has crashed.
                }
            }
            unbindService(mConnection);
            mBound = false;
        }
        if (isFinishing()) {
            Intent bfvService = getBfvServiceIntent();
            stopService(bfvService);
        }

    }

    class ViewHolder {

        WebView webView;

        TextView statusMsgView;
        View pressureAndBatteryFrame;
        TextView pressureDisplay;
        TextView batteryDisplay;

        Button button1;
        Button button2;
    }

}
