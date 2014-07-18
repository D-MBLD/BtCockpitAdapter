package eb.ohrh.bfvadapt.model;

import java.util.Observable;

import eb.ohrh.bfvadapt.bluetooth.ConnectedThread;

public class Model extends Observable implements
        ConnectedThread.BFVVarioListener {

    private static Model instance = new Model();
    private double battery; // Volts
    private long[] pressureAndTime = new long[2];

    private Model() {
    };

    public static Model getInstance() {
        return instance;
    }

    @Override
    public void connectionLost() {
    }

    @Override
    public void updatePressure(int pressure, long currentTime) {
        pressureAndTime[0] = pressure;
        pressureAndTime[1] = currentTime;
        this.setChanged();
        this.notifyObservers();
    }

    @Override
    public void updateBattery(double bat) {
        this.battery = bat;
    }

    public long[] getPressureAndTime() {
        return pressureAndTime;
    }

    public double getBattery() {
        return battery;
    }

}