/*
 BlueFlyVario flight instrument - http://www.alistairdickie.com/blueflyvario/
 Copyright (C) 2011-2012 Alistair Dickie

 BlueFlyVario is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 BlueFlyVario is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with BlueFlyVario.  If not, see <http://www.gnu.org/licenses/>.
 */

package eb.ohrh.bfvadapt.bluetooth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import android.bluetooth.BluetoothSocket;
//import android.os.Bundle;
//import android.os.Message;
import android.util.Log;

/**
 * This thread receives the data of the BFV via an open socket. (Opened by
 * AsyncConnectTask and handled by BluetoothConnectionManager)
 * 
 * It currently only cares for Pressure and Battery values. To connect other
 * protocols, mainly this class and especially the method handleLine would need
 * to be adapted.
 */
public class ConnectedThread extends Thread {
    public static final int UPDATE_NONE = 0;
    public static final int UPDATE_PRS = 1;
    public static final int UPDATE_TMP = 2;
    public static final int UPDATE_VER = 3;
    public static final int UPDATE_BAT = 4;
    public static final int UPDATE_KEYS = 5;
    public static final int UPDATE_VALUES = 6;
    private static final String TAG = ConnectedThread.class.getSimpleName();

    private final OutputStream mmOutStream;
    private final BufferedReader mmReader;

    //
    //
    // private long lastTime;
    //
    // private double pressurePressureDuration;
    // private double pauseTime;
    // private boolean pressureTimePaused;
    //
    // private int pressureMeasurements;
    // private int pauses;

    private BFVVarioListener service;
    private long currentTime;
    private boolean batUpdateReceived;

    public interface BFVVarioListener {

        void connectionLost();

        void updatePressure(int pressure, long currentTime);

        void updateBattery(double d);

    }

    public ConnectedThread(BluetoothSocket socket, BFVVarioListener service) {

        this.service = service;

        Log.d(TAG, "create ConnectedThread");
        InputStream tmpIn = null;
        OutputStream tmpOut = null;
        BufferedReader tmpReader = null;

        // Get the BluetoothSocket input and output streams
        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
            tmpReader = new BufferedReader(new InputStreamReader(tmpIn), 256);
        } catch (IOException e) {
            Log.e(TAG, "temp sockets not created", e);
        }

        mmOutStream = tmpOut;
        mmReader = tmpReader;
    }

    @Override
    public void run() {
        // Log.i(TAG, "BEGIN mConnectedThread");

        long sysTime = System.nanoTime();
        int nanoPerMSec = 1000000;
        currentTime = sysTime / nanoPerMSec;

        String line = null;

        while (!isInterrupted()) {
            try {
                // Read from the InputStream
                line = mmReader.readLine();
                this.handleLine(line);

            } catch (IOException e) {
                Log.d(TAG, "disconnected", e);
                service.connectionLost();
                break;
            }
        }
    }

    /**
     * Write to the connected OutStream. Not used here -> private
     * 
     * @param buffer
     *            The bytes to write
     */
    @SuppressWarnings("unused")
    private void write(byte[] buffer) {
        try {
            mmOutStream.write(buffer);

        } catch (IOException e) {
            Log.e(TAG, "Exception during write", e);
        }
    }

    public void handleLine(String line) {

        try {
            if (line == null || service == null) {
                return;
            }
            String[] split = line.split(" ");
            String prefix = split[0];
            if (prefix == null) {
                return;
            }

            if (prefix.equals("PRS")) {

                // Using system time does not work, because BFV sends
                // the pressure in equal time intervals, but the
                // buffered reader destroys this intervals.
                // So we either have to compute an average time,
                // or rely on the fact, that the BFV sends
                // one measurement every 20 mSecs, and sometimes
                // sends a Battery-Update in between.
                // long currentTime = System.currentTimeMillis();
                currentTime += 20;
                if (batUpdateReceived) {
                    batUpdateReceived = false;
                    currentTime += 20;
                }

                int pressure = Integer.parseInt(split[1], 16);
                service.updatePressure(pressure, currentTime);

            } else if (prefix.equals("BAT")) {

                int bat = Integer.parseInt(split[1], 16); // bat is in mV
                service.updateBattery(bat / 1000.0);
                batUpdateReceived = true;

            } else {
                /**
                 * Other prefixes sent by BFV: BFV, TMP, BST, and SET
                 */
                Log.v(TAG, "Ignored: " + line);
            }
        } catch (NumberFormatException e) {
            return;
        }

    }

}
