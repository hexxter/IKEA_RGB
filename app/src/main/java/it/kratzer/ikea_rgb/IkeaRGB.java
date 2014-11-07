package it.kratzer.ikea_rgb;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import it.kratzer.ikea_rgb.aes.AES;
import it.kratzer.ikea_rgb.ble.Ble;
import it.kratzer.ikea_rgb.ble.BleConnection;


public class IkeaRGB extends Activity implements View.OnClickListener, BleConnection {

    final static String TAG = IkeaRGB.class.getSimpleName();

    byte[] plain;
    private Button btn;
    private TextView txt;
    private Spinner spinner;

    private String bleName, bleDevId;

    private Context appcontext;
    private Ble ble;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ikea_rgb);

        this.appcontext = getApplicationContext();

        // Use this check to determine whether BLE is supported on the device. Then
        // you can selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }


        btn = (Button) findViewById(R.id.buttonScan);
        btn.setOnClickListener( this );
        btn.setClickable( false );

        txt = (TextView) findViewById(R.id.textView);

        spinner = (Spinner) findViewById(R.id.spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,R.array.programs, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        this.ble = new Ble( appcontext );
        this.ble.setListener( this );
        this.ble.scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i( TAG, "Scanning for devices...");
        ble.scan();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.ikea_rgb, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private byte[] secToken() throws NoSuchPaddingException, InvalidKeyException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {

        SecureRandom sr = new SecureRandom();
        plain = new byte[16];
        sr.nextBytes(plain);
        ByteBuffer bbuf = ByteBuffer.wrap( plain );
        bbuf.put( "ALEX".getBytes(), 0, 4 );

        AES aes = new AES();
        return aes.encrypt( this.plain );
    }

    public static byte[] escape(byte[] block){
        ByteBuffer bbuf = ByteBuffer.allocate( block.length*2 );
        for( byte n : block ){
            if( n == 0x00 ){
                bbuf.put( (byte)0xFF );
                bbuf.put( (byte)0x00 );
            }else if( n == (byte)0xFF ){
                bbuf.put( (byte)0xFF );
                bbuf.put( (byte)0x55 );
            }else{
                bbuf.put( n );
            }
        }
        //EOF
        bbuf.put( (byte)0x00 );
        byte[] result = new byte[bbuf.position()];
        for( int i =0; i < bbuf.position(); i++ ){
            result[i] = bbuf.array()[i];
        }
        return result;
    }

    private byte[] buildRandomFrame( byte brightness ){

        byte[] token = new byte[1];
        try {
            token = secToken();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Log.i("it.kratzer.ikea_rgb", String.format("B: %02X", brightness) );
        Log.i("it.kratzer.ikea_rgb", "T: "+AES.getHexString( token ) );

        ArrayList<Byte> alist = new ArrayList<Byte>();
        alist.add( brightness );
        for(byte b : token) {
            alist.add(new Byte(b));
        }
        SecureRandom sr = new SecureRandom();
        byte[] rand = new byte[3];
        for( int i = 0; i<10; i++ ){
            sr.nextBytes(rand);
            for( int n = 0; n<3; n++ ){
                alist.add( rand[0] );
                alist.add( rand[1] );
                alist.add( rand[2] );
            }
        }
        byte[] complete = new byte[ alist.size() ];
        for( int i = 0; i< alist.size(); i++ ){
            complete[i] = alist.get(i).byteValue();
        }

        byte[] frame = escape( complete );
        Log.i("it.kratzer.ikea_rgb", "C: "+AES.getHexString( frame ) );
        return frame;
    }

    @Override
    public void onClick(View v) {
        Log.i("it.kratzer.ikea_rgb", "klick ");
        byte prog = 0;
        if( spinner.getSelectedItem().toString().contentEquals( "Off" ) ){
            prog = 0;
        }else if( spinner.getSelectedItem().toString().contentEquals( "Rainbow" ) ){
            prog = 1;
        }else if( spinner.getSelectedItem().toString().contentEquals( "Tick" ) ){
            prog = 2;
        }else if( spinner.getSelectedItem().toString().contentEquals( "Random" ) ){
            prog = (byte)0xFF;
        }
        sendFrame(buildRandomFrame( prog ) );
    }

    private void sendFrame( byte[] frame ){

        if (ble.getTx() == null || frame == null || frame.length == 0) {
            Log.i(TAG, "tx:" + ble.getTx());
            // Do nothing if there is no device or message to send.
            return;
        }
        btn.setClickable( false );
        int SNIPPED = 10;
        byte[] sendbuf;
        for( int n = 0; n < frame.length; ){
            if( frame.length-n < SNIPPED ){
                sendbuf = new byte[frame.length-n];
                System.arraycopy( frame, n, sendbuf, 0, frame.length-n );
                n = frame.length;
            }else{
                sendbuf = new byte[SNIPPED];
                System.arraycopy( frame, n, sendbuf, 0, SNIPPED );
                n += SNIPPED;
            }

            // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
            ble.getTx().setValue(sendbuf);
            if (ble.getGatt().writeCharacteristic(ble.getTx())) {
                Log.i(TAG, "send:"+ AES.getHexString( sendbuf ) );
            }
            else {
                Log.i(TAG, "Couldn't write TX characteristic!");
            }

        }
        btn.setClickable( true );

    }

    @Override
    protected void onStop() {
        super.onStop();
        if (ble.getGatt() != null) {
            // For better reliability be careful to disconnect and close the connection.
            ble.getGatt().disconnect();
            ble.getGatt().close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        System.exit(0);
    }

    @Override
    public void connected( String name, String devId ) {
        Log.i( TAG, "BLE connected: "+name );
        this.bleName = name;
        this.bleDevId = devId;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btn.setClickable( true );
                txt.setText( getString( R.string.connect )+bleName );
            }
        });
    }

    @Override
    public void disconnected(String name, String devId) {
        Log.i( TAG, "BLE disconnected: "+name );
        bleName = name;
        bleDevId = devId;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btn.setClickable(false);
                txt.setText( getString(R.string.nconnect) );
            }
        });
    }
}
