package com.vrpndt.bouncesmsclient.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class Appdata {
    public static final String appdataFilename = "appdata.json";

    public static final class Keys{
        public static final String SYNC_AFTER = "syncAfter";
        public static final String AUTO_SYNC = "autoSync";
        public static final String SAVE_MEDIA = "saveMedia";
        public static final String LAST_SYNC = "lastSync";
        public static final String LAST_HOST = "lastHost";
        public static final String INTERNAL_STORAGE = "internalStorage";
    }

    private static JSONObject getAppdataJSON(Context context) {
        File appdataFile = new File(context.getFilesDir(), appdataFilename);
        String fileContents;
        if (appdataFile.exists()) {
            try {
                //read the appdata.json file
                FileInputStream fis = context.openFileInput(appdataFile.getName());
                InputStreamReader isr = new InputStreamReader(fis);
                StringBuilder stringBuilder = new StringBuilder();
                BufferedReader reader = new BufferedReader(isr);

                String line = reader.readLine();
                while (line != null) {
                    stringBuilder.append(line).append('\n');
                    line = reader.readLine();
                }
                fileContents = stringBuilder.toString();
                fis.close();
            } catch (IOException e) {
                Log.e("APPDATA",
                        String.format("%s could not be opened. Returning new JSONObject...",
                                appdataFilename), e);
                return new JSONObject();
            }

            try {
                //parse the file's contents into a JSON Object
                return new JSONObject(fileContents);

            } catch (JSONException e) {
                Log.e("APPDATA",
                        String.format("%s could not be parsed. Returning new JSONObject.",
                                appdataFilename), e);
            }

        } else {
            Log.e("APPDATA",
                    String.format("%s does not exist! Returning new JSONObject.",
                            appdataFilename));
        }
        return new JSONObject();
    }

    public static Object get(Context context, String key, Object defaultValue) {
        JSONObject appdataJSON = getAppdataJSON(context);
        try {
            Object value = appdataJSON.get(key);
            Log.d("APPDATA", String.format("Got %s as %s from appdata",
                    key, value));
            return value;
        } catch (JSONException e) {
            Log.e("APPDATA", String.format("Could not get %s from appdata!", key));
            return defaultValue;
        }
    }

    public static boolean put(Context context, String key, Object value) {
        JSONObject appdataJSON = getAppdataJSON(context);
        try {
            appdataJSON.put(key, value);
            String JSONString = appdataJSON.toString();
            if (JSONString != null) {
                try {
                    //overwrite current file contents with updated JSON string
                    FileOutputStream fos = context.openFileOutput(appdataFilename,
                            Context.MODE_PRIVATE);
                    fos.write(JSONString.getBytes());
                    fos.close();
                    Log.d("APPDATA", String.format("Updated %s to %s", key, value));
                } catch (Exception e) {
                    Log.e("APPDATA", String.format("Could not write to %s",
                            appdataFilename), e);
                    return false;
                }
            }
        } catch (JSONException e) {
            Log.e("APPDATA", "Unable to write values to JSONObject!", e);
            return false;
        }
        return true;
    }
}
