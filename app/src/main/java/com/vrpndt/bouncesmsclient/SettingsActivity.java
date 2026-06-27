package com.vrpndt.bouncesmsclient;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import static java.lang.Integer.parseInt;

public class SettingsActivity extends AppCompatActivity{

    private EditText autoSyncDays;
    private CheckBox autoSyncOn;
    private CheckBox autoSaveMms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button syncNowBtn = (Button) findViewById(R.id.syncNowBtn);
        Button fixedSyncBtn = (Button) findViewById(R.id.fixedSyncBtn);
        Button saveExitBtn = (Button) findViewById(R.id.saveExitBtn);
        autoSyncDays = (EditText) findViewById(R.id.autosyncInput);
        autoSyncOn = (CheckBox) findViewById(R.id.autoSyncOn);
        autoSaveMms = (CheckBox) findViewById(R.id.autoSaveMms);

        int daysValue = (int)MainActivity.Appdata.get(getApplicationContext(),
                MainActivity.Appdata.Keys.SYNC_AFTER, 7);
        autoSyncDays.setText(String.valueOf(daysValue));
        boolean autoSync = (boolean)MainActivity.Appdata.get(getApplicationContext(),
                MainActivity.Appdata.Keys.AUTO_SYNC, false);
        autoSyncOn.setChecked(autoSync);
        boolean saveMms = (boolean) MainActivity.Appdata.get(getApplicationContext(),
                MainActivity.Appdata.Keys.SAVE_MEDIA, true);
        autoSaveMms.setChecked(saveMms);

        syncNowBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                //send sync request + timestamp to host device
                long lastSync = (long) MainActivity.Appdata.get(getApplicationContext(),
                        MainActivity.Appdata.Keys.LAST_SYNC, -1L);
                MainActivity.sendSyncRequest(lastSync);
            }
        });

        fixedSyncBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                //send sync request and (current time - one week) to host device
                MainActivity.sendSyncRequest(7);
            }
        });

        saveExitBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                //save and finish() activity
                int autoSyncValue = 14; //put default value
                if(autoSyncDays.getText().toString().equals("")) {
                    //override default if user has given input
                    autoSyncValue = parseInt(autoSyncDays.getText().toString());
                }
                MainActivity.Appdata.put(getApplicationContext(),
                        MainActivity.Appdata.Keys.SYNC_AFTER, autoSyncValue);

                boolean autoSyncBool = autoSyncOn.isChecked();
                MainActivity.Appdata.put(getApplicationContext(),
                        MainActivity.Appdata.Keys.AUTO_SYNC, autoSyncBool);

                boolean saveMmsBool = autoSaveMms.isChecked();
                MainActivity.Appdata.put(getApplicationContext(),
                        MainActivity.Appdata.Keys.SAVE_MEDIA, saveMmsBool);

                finish();
            }
        });
    }
}
