package es.tappx.sdldisplayexample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.txusballesteros.widgets.FitChart;


public class MainActivity extends AppCompatActivity {

    private Intent proxyIntent;

    private FitChart fitChart;
    private TextView main;

    private Float oldValue;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //If we are connected to a module we want to start our SdlService
        if(BuildConfig.TRANSPORT.equals("MULTI") || BuildConfig.TRANSPORT.equals("MULTI_HB")) {
            SdlReceiver.queryForConnectedService(this);
        }else if(BuildConfig.TRANSPORT.equals("TCP")) {
            proxyIntent = new Intent(this, SdlService.class);
            //startService(proxyIntent);
        }

        fitChart = (FitChart)findViewById(R.id.rings);
        fitChart.setMinValue(0f);
        fitChart.setMaxValue(100f);

        main = (TextView)findViewById(R.id.editText);
        oldValue = 0.0f;
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String rpm = intent.getStringExtra("RPM");

            Log.i("MainActivity","receive data from SdlService (" + rpm + ")");

            Float rpmPercentaje = 0.0f;

            try {
                rpmPercentaje = Float.parseFloat(rpm);
            }catch(Exception e){
                rpmPercentaje = 0.0f;
            }

            if (oldValue != rpmPercentaje) {
                fitChart.setValue(rpmPercentaje);
                main.setText("RPM " + rpm + "%");
                oldValue = rpmPercentaje;
            }
        }
    };

    @Override
    public void onResume() {

        if (BuildConfig.TRANSPORT.equals("TCP")) {

            super.onResume();
            startService(proxyIntent);
            registerReceiver(broadcastReceiver, new IntentFilter(SdlService.BROADCAST_ACTION));
        }
    }

    @Override
    public void onPause() {

        if(BuildConfig.TRANSPORT.equals("TCP")) {
            super.onPause();
            unregisterReceiver(broadcastReceiver);
            stopService(proxyIntent);
        }
    }




}
