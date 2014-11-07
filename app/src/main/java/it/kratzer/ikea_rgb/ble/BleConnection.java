package it.kratzer.ikea_rgb.ble;

/**
 * Created by alex on 07.11.2014.
 */
public interface BleConnection {

    public void connected( String name, String devId );

    public void disconnected(String name, String devId);
}
