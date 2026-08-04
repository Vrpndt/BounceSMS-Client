package com.vrpndt.bouncesmsclient;

import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import com.vrpndt.bouncesmsclient.util.Appdata;

import static java.lang.Integer.parseInt;

public class SettingsActivity extends AppCompatActivity{

    private CheckBox autoSyncOn;
    private CheckBox autoSaveMms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button syncNowBtn = (Button) findViewById(R.id.syncNowBtn);
        Button fixedSyncBtn = (Button) findViewById(R.id.fixedSyncBtn);
        Button saveExitBtn = (Button) findViewById(R.id.saveExitBtn);
        autoSyncOn = (CheckBox) findViewById(R.id.autoSyncOn);
        autoSaveMms = (CheckBox) findViewById(R.id.autoSaveMms);

        final boolean autoSync = (boolean)Appdata.get(getApplicationContext(),
                Appdata.Keys.AUTO_SYNC, false);
        autoSyncOn.setChecked(autoSync);
        boolean saveMms = (boolean) Appdata.get(getApplicationContext(),
                Appdata.Keys.SAVE_MEDIA, true);
        autoSaveMms.setChecked(saveMms);

        if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
            autoSaveMms.setEnabled(true);
            AlphaAnimation alpha = new AlphaAnimation(0.4f, 1.0f);
            alpha.setDuration(0);
            alpha.setFillAfter(true);
            autoSaveMms.startAnimation(alpha);
        }else{
            //if sd card cannot be read/written to
            autoSaveMms.setChecked(false);
            autoSaveMms.setEnabled(false);
            AlphaAnimation alpha = new AlphaAnimation(1.0f, 0.4f);
            alpha.setDuration(0);
            alpha.setFillAfter(true);
            autoSaveMms.startAnimation(alpha);
            Appdata.put(getApplicationContext(), Appdata.Keys.SAVE_MEDIA, false);
        }

        syncNowBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view) {
                //send sync request + timestamp to host device
                long lastSync = (long) Appdata.get(getApplicationContext(),
                        Appdata.Keys.LAST_SYNC, -1L);
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
                long lastSync = (long) Appdata.get(getApplicationContext(), Appdata.Keys.LAST_SYNC, -1);
                if(lastSync != -1){
                    MainActivity.sendSyncRequest(lastSync);
                }else{
                    MainActivity.sendSyncRequest(14);
                }

                boolean autoSyncBool = autoSyncOn.isChecked();
                Appdata.put(getApplicationContext(),
                        Appdata.Keys.AUTO_SYNC, autoSyncBool);

                boolean saveMmsBool = autoSaveMms.isChecked();
                Appdata.put(getApplicationContext(),
                        Appdata.Keys.SAVE_MEDIA, saveMmsBool);

                finish();
            }
        });
    }
}
