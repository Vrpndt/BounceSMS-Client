package com.vrpndt.bouncesmsclient;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.ContactsContract;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {


    public static final String delimiter = String.valueOf((char)0x1e);

    public static final class PayloadIDs{
        public static final char SMS_MESSAGE = 'S';
        public static final char SYNC_REQUEST = 'I';
        public static final char SYNC_UPDATE = 'U';
        public static final char MMS_HEADER = 'M';
        public static final char MMS_PART_ATTACHMENT = 'A';
        public static final char MMS_PART_LAYOUT = 'L';
        public static final String DELIMITER_STRING = String.valueOf((char)0x1e);
        public static final byte DELIMITER_BYTE = (byte)0x1e;
    }

    private ListView btDeviceList;
    //private Button refreshDevicesBtn;
    private Button dcBtDeviceBtn;
    private Button settingsBtn;
    private TextView connDeviceText;
    private TextView lastSyncText;

    private BtClientThread btClientThread;

    private OutboxObserver outboxObserver;
    private HandlerThread outboxThread;

    private class BtClientConnector extends Thread {
        BluetoothSocket btSocket;
        BluetoothAdapter btAdapter;
        BluetoothDevice targetDevice;
        Handler uiHandler = new Handler(getMainLooper());
        boolean connected;

        private BtClientConnector(BluetoothAdapter adapter){
            super();
            btAdapter = adapter;
        }

        private void getConnectionSocket(BluetoothDevice target){
            targetDevice = target;
            try{
                btSocket = targetDevice.
                        createRfcommSocketToServiceRecord(UUID.fromString(
                                "c786f003-6a89-47a7-897d-ee75fa968182"));
            }catch (IOException e){
                Log.e("BOUNCESMS_BTCONNECTOR", "device.createrfcomm() failed!", e);
            }finally{
                connected = true;
            }
        }

        @Override
        public void run() {
            try{
                btSocket.connect();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(),
                                String.format("Connected to %s!", targetDevice.getName()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }catch (IOException connectExc){
                try{
                    btSocket.close();
                    Log.e("BOUNCESMS_BTCONNECTOR", "btSocket.connect() failed! Closing btSocket...",
                            connectExc);
                    connected = false;
                }catch (IOException closeExc){
                    Log.e("BOUNCESMS_BTCONNECTOR", "btSocket.close() failed!", closeExc);
                }
                return;
            }
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    onBtDeviceConnected(btSocket);
                }
            });
        }

        public void close(){
            try{
                btSocket.close();

            }catch (IOException closeExc){
                Log.e("BOUNCESMS_BTCONNECTOR", "btSocket.close() failed!", closeExc);
            }finally{
                connected = false;
            }
        }
    }

    private class BtClientThread extends Thread {
        private BluetoothSocket btSocket;
        private InputStream inStream;
        private OutputStream outStream;
        Handler uiHandler = new Handler(getMainLooper());
        private byte[] ioBuffer = new byte[1024];
        private byte[] lastInputChunk = null;
        private MmsContainer latestMms = new MmsContainer();
        public AttachmentBuilder attachmentBuilder = new AttachmentBuilder();

        private BtClientThread(BluetoothSocket socket){
            btSocket = socket;
            try{
                inStream = socket.getInputStream();
            }catch (IOException e){
                Log.e("BOUNCESMS_BTIO", "Unable to create input stream.", e);
            }
            try{
                outStream = socket.getOutputStream();
            }catch (IOException e){
                Log.e("BOUNCESMS_BTIO", "Unable to create output stream.", e);
            }
        }

        @Override
        public void run() {
            ioBuffer = new byte[1024];
            int bytesIn;

            while(true){
                try{
                    bytesIn = inStream.read(ioBuffer);
                    if(bytesIn < 0){
                        Log.d("BT_IO", "inStream.read() returned -1, stream ended.");
                        break;
                    }
                }catch (IOException e){
                    Log.e("BT_IO", "Bluetooth Input Stream Ended.", e);
                    break;
                }

                final byte[] truncatedBuffer = new byte[bytesIn];
                System.arraycopy(ioBuffer, 0, truncatedBuffer, 0, bytesIn);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onBtInputReceived(truncatedBuffer);
                    }
                });
            }
        }

        public void write(byte[] data){
            try{
                Log.d("BT_OUT", String.format("Sending %d bytes...", data.length));
                outStream.write(data);
                Log.d("BT_OUT", "Sent!");
            }catch (IOException e){
                Log.e("BT_IO", "Unable to write to output stream", e);
            }
        }

        public void close(){
            try{
                btSocket.close();
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onBtDeviceDisconnected();
                    }
                });
            }catch (IOException e){
                Log.e("BT_IO", "Unable to close btSocket.", e);
            }
        }

        public byte[] getLastInputChunk(){
            return lastInputChunk;
        }
        public void setLastInputChunk(byte[] bytesIn){ lastInputChunk = bytesIn; }
        public MmsContainer getLatestMms(){ return latestMms; }
        public void setLatestMms(MmsContainer mms){ latestMms = mms; }
        public void clearLatestMms(){ latestMms = new MmsContainer(); }
    }

    public static class BtConnectionManager {
        private static volatile BtConnectionManager instance = null;
        private volatile BtClientThread connection = null;
        private volatile BtClientConnector connectorThread = null;

        private BtConnectionManager() {
        }

        public static BtConnectionManager getInstance() {
            if(instance == null){
                //ensure that only one instance can exist at any time
                synchronized (BtConnectionManager.class){
                    if(instance == null){
                        instance = new BtConnectionManager();
                    }
                }
            }
            return instance;
        }

        public void setConnection(BtClientThread conn){
            connection = conn;
        }

        public void setConnectorThread(BtClientConnector conn){
            connectorThread = conn;
        }

        public void write(byte[] data){
            BtClientThread conn = connection;
            BtClientConnector conThread = connectorThread;
            if(conn != null && conThread != null){
                if(conThread.connected){
                    Log.d("BT_OUT", String.format("Sending %d bytes via singleton call...",
                            data.length));
                    conn.write(data);
                    Log.d("BT_OUT", "Sent data via singleton call!");
                }
            }else{
                Log.e("BT_OUT", "Bluetooth connection or connector thread is null!");
            }
        }

        public void disconnectDevice(){
            if(connectorThread != null && connectorThread.connected){
                if(connection != null){
                    connection.close();
                    connection.btSocket = null;
                }
                connectorThread.close();
            }
            connection = null;
            connectorThread = null;
        }

        public boolean isConnected(){
            if(connectorThread != null && connectorThread.connected){
                if(connection != null){
                    return true;
                }
            }
            return false;
        }

        public String getDeviceName(){
            if(isConnected()){
                return connection.btSocket.getRemoteDevice().getName();
            }
            return "No Device Connected!";
        }

        public String getDeviceMAC(){
            if(isConnected()){
                return connection.btSocket.getRemoteDevice().getAddress();
            }
            return "N/A";
        }
    }

    static class Appdata {
        public static final String appdataFilename = "appdata.json";

        public static final class Keys{
            public static final String SYNC_AFTER = "syncAfter";
            public static final String AUTO_SYNC = "autoSync";
            public static final String SAVE_MEDIA = "saveMedia";
            public static final String LAST_SYNC = "lastSync";
            public static final String LAST_HOST = "lastHost";
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
                        String.format("%s does not exist! What the fuck!! Returning new JSONObject.",
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

    private class OutboxObserver extends ContentObserver {
        private Uri outboxUri = Uri.parse("content://sms");
        private boolean suppress = false;
        private HashSet<String> handledIDs = new HashSet<>();

        private class SmsData{
            String id;
            String address;
            String body;
            String read;
            String status;
            String thread_id;

            SmsData(String id, String address, String body, String read,
                    String status, String thread_id){
                this.id = id;
                this.address = address;
                this.body = body;
                this.read = read;
                this.status = status;
                this.thread_id = thread_id;
            }
        }

        public OutboxObserver(Handler handler){
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            if(suppress) {return;}
            //cannot send sms if no connection
            if(!BtConnectionManager.getInstance().isConnected()){return;}

            try {
                suppress = true;
                ArrayList<SmsData> pending = new ArrayList<>();

                super.onChange(selfChange);
                Log.d("OUTBOX_OBSERVER", "OutboxObserver.onChange() called.");

                //query outbox for any new items, return address, and body
                String[] columns = new String[]{"_id", "address", "body", "date", "status",
                        "read", "thread_id", "type"};
                //600,000ms = 10 minutes -- use this cutoff to prevent excessive results
                long smsTimeCutoff = System.currentTimeMillis() - 600000;
                String[] selectionArgs = {"2", "4", "5", "6", String.valueOf(smsTimeCutoff)};
                Cursor cursor = getContentResolver().query(outboxUri,
                        columns, "type IN (?, ?, ?, ?) AND date > ?",
                        selectionArgs, "_id ASC");
                if (cursor != null) { //if found, get content and address, then send via bt
                    if (cursor.moveToFirst()) {
                        do {
                            String msgID = cursor.getString(
                                    cursor.getColumnIndex("_id")
                            );

                            String msgAddress = cursor.getString(
                                    cursor.getColumnIndex("address")
                            );
                            String msgContent = cursor.getString(
                                    cursor.getColumnIndex("body")
                            );
                            String msgDate = cursor.getString(
                                    cursor.getColumnIndex("date")
                            );
                            String msgStatus = cursor.getString(
                                    cursor.getColumnIndex("status")
                            );
                            String msgRead = cursor.getString(
                                    cursor.getColumnIndex("read")
                            );
                            String msgThread = cursor.getString(
                                    cursor.getColumnIndex("thread_id")
                            );
                            String msgType = cursor.getString(
                                    cursor.getColumnIndex("type")
                            );

                            if(handledIDs.contains(msgID)){
                                if(msgType.equals("4") || msgType.equals("5") ||
                                        msgType.equals("6")){
                                    catchSecondarySmsUpdate(msgID);
                                }else if(msgType.equals("2") && Long.parseLong(msgDate) <=
                                        System.currentTimeMillis()-5000){
                                    //add 5-second buffer before deleting from handledIDs
                                    //for extra security against slow-running OSes
                                    handledIDs.remove(msgID);
                                }
                            }else{
                                if(msgType.equals("4") || msgType.equals("5") ||
                                        msgType.equals("6")){
                                    SmsData thisMsg = new SmsData(msgID, msgAddress, msgContent,
                                            msgRead, msgStatus, msgThread);
                                    pending.add(thisMsg);
                                    Log.d("OUTBOX_OBSERVER",
                                            String.format(
                                                    "address: %s\nbody: %s\ntype: %s\nstatus: %s",
                                                    msgAddress, msgContent, msgType, msgStatus));
                                }
                            }
                        } while(cursor.moveToNext());
                    }
                    cursor.close();
                }
                Log.d("OUTBOX_OBSERVER", String.format("%d messages pending.", pending.size()));
                handlePendingSms(pending);
                Log.d("OUTBOX_OBSERVER", "Outbox content updated. pending -> sent");
            }catch(Exception e){
                Log.e("OUTBOX_OBSERVER", "Failed to process outbox change!", e);
            }finally{
                suppress = false;
            }
        }

        private void handlePendingSms(ArrayList<SmsData> pending){
            for(SmsData msg: pending) {
                Log.d("OUTBOX_OBSERVER", String.format("\"%s\" -> %s", msg.body, msg.address));
                BtConnectionManager.getInstance().write(encodeSMS(msg.address, msg.body));

                //update type in messages in content://sms from 4/5/6 to 2
                ContentValues updatedSMS = new ContentValues();
                updatedSMS.put("type", 2);
                String[] idArgs = {msg.id};
                getContentResolver().update(
                        outboxUri,
                        updatedSMS,
                        "_id = ?",
                        idArgs
                );
                handledIDs.add(msg.id);
            }
        }

        private void catchSecondarySmsUpdate(String id){
            ContentValues updatedSMS = new ContentValues();
            updatedSMS.put("type", 2);
            String[] idArgs = {id};
            getContentResolver().update(
                    outboxUri,
                    updatedSMS,
                    "_id = ?",
                    idArgs
            );
            handledIDs.remove(id);
        }
    }

    static class MmsContainer{
        boolean textOnly = false;
        String text = "";
        String address = "";
        long date = -1;
        int msgBox = -1;
        int read = -1;
        int attachmentCount;
        ConcurrentHashMap<String, File> attachments = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> mimes = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, byte[]> finishingSequences = new ConcurrentHashMap<>();
        String smil = "";

        boolean isFilled(){
            boolean noNulls = date > -1 && msgBox > -1 && read > -1;
            boolean attachmentCondition = ((!textOnly) == (attachments.size() > 0)) &&
                    ((attachments.size() > 0) == mimes.size() > 0);
            boolean hasAllAtmnts = attachmentCount == attachments.size();
            return attachmentCondition && hasAllAtmnts && noNulls &&
                    address.length() > 0 && smil.length() > 0;
        }

        String getAttachmentCl(String attachmentKey){
            String fileExt = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimes.get(attachmentKey));
            return "media_" + attachmentKey + "." + fileExt;
        }
    }

    static class AttachmentBuilder {
        FileOutputStream fileOut = null;

        void openFileOut(File file){
            closeFileOut();
            try{
                fileOut = new FileOutputStream(file);
            }catch(FileNotFoundException e){
                Log.e("ATMNT_BUILDER", "Could not open new FileOutputStream!", e);
            }
        }

        void closeFileOut(){
            if(fileOut != null){
                try {
                    fileOut.flush();
                    fileOut.close();
                }catch(IOException e){
                    Log.e("ATMNT_BUILDER", "Could not close fieOut()!", e);
                }
            }
        }

        void write(byte[] data, int startIdx, int dataSize){
            if(fileOut != null){
                try{
                    fileOut.write(data, startIdx, dataSize);
                    fileOut.write(PayloadIDs.DELIMITER_BYTE);
                    fileOut.flush();
                } catch(IOException e){
                    Log.e("ATMNT_BUILDER", "Could not write to file!", e);
                }
            }else{
                Log.e("ATMNT_BUILDER","Cannot write when fileOut is null!",
                        new NullPointerException());
            }
        }

        void writeWithoutDelimiter(byte[] data, int startIdx, int dataSize){
            if(fileOut != null){
                try{
                    fileOut.write(data, startIdx, dataSize);
                    fileOut.flush();
                } catch(IOException e){
                    Log.e("ATMNT_BUILDER", "Could not write to file!", e);
                }
            }else{
                Log.e("ATMNT_BUILDER","Cannot write when fileOut is null!",
                        new NullPointerException());
            }
        }
    }

    class SmilObject{
        class Media{
            String type;
            String region;
            String src;

            Media(String type, String region, String source){
                this.type = type;
                this.region = region;
                this.src = source;
            }

            String toXmlString(){
                return String.format("<%s src=\"%s\" region=\"%s\"/>", type, src, region);
            }
        }
        class Slide{
            String duration;
            ArrayList<Media> media;

            Slide(String duration, ArrayList<Media> media){
                this.duration = duration;
                this.media = media;
            }

            String toXmlString(){
                String xmlString = String.format("<par dur=\"%s\">", duration);
                for(Media m: media){
                    xmlString += m.toXmlString();
                }
                xmlString += "</par>";
                return xmlString;
            }
        }

        ArrayList<String> regions = new ArrayList<>();
        ArrayList<Slide> slides = new ArrayList<>();

        SmilObject(String jsonString){
            parseJSON(jsonString);
        }

        SmilObject(ArrayList<String> regions, ArrayList<Slide> slides){
            this.regions = regions;
            this.slides = slides;
        }

        void parseJSON(String jsonString){
            JSONObject json;
            try{
                json = new JSONObject(jsonString);
            }catch(JSONException e){
                Log.e("SMIL_PARSER", "Could not parse SMIL JSON String!", e);
                return;
            }

            //get regions
            try {
                int length = json.getJSONArray("regions").length();
                for(int i=0; i < length; i++){
                    String region = json.getJSONArray("regions").getString(i);
                    regions.add(region);
                }
            }catch(JSONException e){
                Log.e("SMIL_PARSER", "Could not parse SMIL JSON 'regions' section!", e);
                return;
            }

            //get slides and media
            try{
                int parsLength = json.getJSONArray("pars").length();
                for(int i=0; i < parsLength; i++){
                    JSONObject par = json.getJSONArray("pars").getJSONObject(i);
                    String duration = par.getString("dur");

                    //get the media for each par
                    ArrayList<Media> parMedia = new ArrayList<>();
                    int mediaLength = par.getJSONArray("media").length();
                    for(int j=0; j < mediaLength; j++){
                        JSONObject media = par.getJSONArray("media").getJSONObject(j);
                        String mediaType = media.getString("type");
                        String mediaRegion = media.getString("region");
                        String mediaSource = media.getString("src");
                        Media mediaObj = new Media(mediaType, mediaRegion, mediaSource);
                        parMedia.add((mediaObj));
                    }
                    Slide slide = new Slide(duration, parMedia);
                    slides.add(slide);
                }
            }catch(JSONException e){
                Log.e("SMIL_PARSER", "Could not parse SMIL JSON 'pars' or media!", e);
                return;
            }
        }

        void remapMediaSource(String oldSrc, String newSrc){
            for(Slide slide: slides){
                for(Media media: slide.media){
                    if(media.src.equals(oldSrc)){
                        media.src = newSrc;
                    }
                }
            }
        }

        String toXml(){
            String xmlString = "";
            //open smil, head, and layout tags and add regions
            xmlString += "<smil><head><layout><root-layout/>";
            for(String region: regions){
                xmlString += String.format("<region id=\"%s\"/>", region);
            }

            //close layout and head, then open body tag and add slides
            xmlString += "</layout></head><body>";
            for(Slide slide: slides){
                xmlString += slide.toXmlString();
            }
            xmlString += "</body></smil>";
            return xmlString;
        }
    }



    void displayPairedDevices(List<BluetoothDevice> pairedDevices, BluetoothAdapter btAdapter){
        Set<BluetoothDevice> bluetoothDevices = btAdapter.getBondedDevices();
        List<String> deviceNames = new ArrayList<String>(bluetoothDevices.size());
        //if devices are paired...
        if (bluetoothDevices.size() > 0) {
            //check if each device is in the list, then add if not
            for (BluetoothDevice device : bluetoothDevices) {
                if(pairedDevices.indexOf(device) == -1){
                    //add to master list of devices for use in onCreate()
                    pairedDevices.add(device);
                }
                deviceNames.add(device.getName());
            }
        } else {
            Toast.makeText(getApplicationContext(),
                    "No paired devices.", Toast.LENGTH_SHORT).show();
        }

        ListAdapter btListAdapter = new ArrayAdapter<String>(getApplicationContext(),
                android.R.layout.simple_list_item_1, deviceNames);
        btDeviceList.setAdapter(btListAdapter);
    }

    void connectBtDevice(BluetoothAdapter adapter, BluetoothDevice device){
        //// TODO: 03/06/2026 add confirmation prompts when connecting to a different device
        BtClientConnector btClientConnector = new BtClientConnector(adapter);
        btClientConnector.getConnectionSocket(device);
        btClientConnector.start();
        BtConnectionManager.getInstance().setConnectorThread(btClientConnector);
    }

    void onBtDeviceConnected(BluetoothSocket socket){
        btClientThread = new BtClientThread(socket);
        btClientThread.start();
        BtConnectionManager.getInstance().setConnection(btClientThread);

        String hostMAC = socket.getRemoteDevice().getAddress();
        Appdata.put(getApplicationContext(), Appdata.Keys.LAST_HOST, hostMAC);

        boolean autoSync = (boolean) Appdata.get(getApplicationContext(),
                Appdata.Keys.AUTO_SYNC, false);

        //// TODO: 03/06/2026 if this isn't the last host, clear and rebuild sms db (with prompts)
        long lastSync = (long) Appdata.get(getApplicationContext(), Appdata.Keys.LAST_SYNC, -1L);
        if(autoSync){
            // send sync signal
            sendSyncRequest(lastSync);
        }else {
            updateBtDeviceText(socket.getRemoteDevice().getName(), lastSync);
        }
    }

    static ArrayList<byte[]> splitByteArray(byte[] dataIn, byte delimiter){
        ArrayList<byte[]> splits = new ArrayList<>();
        int lastSplit = 0;
        for(int i=0; i<dataIn.length; i++){
            if(dataIn[i] == delimiter){
                //delimiter here, split from previous split to this index non-inc
                int splitLength = i - lastSplit;
                byte[] newSplit = new byte[splitLength];
                System.arraycopy(dataIn, lastSplit, newSplit, 0, splitLength);
                splits.add(newSplit);
                lastSplit = i + 1; //add 1 to exclude the delimiter from the next split
            }else if(i == dataIn.length-1){
                //if this is the final index and there is no delimiter here, add the rest to list
                int splitLength = dataIn.length - lastSplit;
                byte[] newSplit = new byte[splitLength];
                System.arraycopy(dataIn, lastSplit, newSplit, 0, splitLength);
                splits.add(newSplit);
            }
        }
        return splits;
    }

    //more memory-efficient than splitting the array and checking that way for every loop
    static boolean isSubArray(byte[] data, byte[] subArray, int startIdx){
        if(subArray.length > data.length){
            return false;
        }

        for(int i=0; i < subArray.length; i++){
            if(data[startIdx + i] != subArray[i]){
                return false;
            }
        }

        return true;
    }

    static File newMmsMediaFile(String filename) throws IOException{
        String state = Environment.getExternalStorageState();
        if(!Environment.MEDIA_MOUNTED.equals(state)){
            throw new IOException(String.format(
                    "Failed to write to SD card because external storage is in state \"%s\"",
                    state));
        }
        //if SD card is mounted and writeable
        File sdRoot = Environment.getExternalStorageDirectory();
        File mediaDir = new File(sdRoot, "mms-media");
        if(!mediaDir.exists()){
            mediaDir.mkdirs();
        }
        return new File(mediaDir, filename);
    }

    static byte[] readFromFile(File file){
        FileInputStream attachmentIs;
        try{
            attachmentIs = new FileInputStream(file);
        }catch(FileNotFoundException e){
            Log.e("MMS_HANDLER", "Could not open Output Stream to File at " +
                    file.getAbsolutePath(), e);
            return null;
        }

        byte[] atmntBytes = new byte[(int)file.length()];
        try{
            attachmentIs.read(atmntBytes);
            attachmentIs.close();
        }catch(IOException e){
            Log.e("MMS_HANDLER", "Could not read from Output Stream to File at " +
                    file.getAbsolutePath(), e);
            return null;
        }finally{
            try{
                attachmentIs.close();
            }catch(IOException e){
                Log.e("MMS_HANDLER", "Could not close FileInputStream!", e);
            }
        }
        return atmntBytes;
    }

    static void writeToUri(Context context, Uri uri, byte[] data){
        OutputStream partOs;
        try{
            partOs = context.getContentResolver().openOutputStream(uri);
        }catch(FileNotFoundException e){
            Log.e("MMS_HANDLER", "Could not open Output Stream to URI " + uri.toString(), e);
            return;
        }

        try{
            if(partOs != null){
                partOs.write(data);
                partOs.flush();
                partOs.close();
            }
        }catch(IOException e){
            Log.e("MMS_HANDLER", "Could not write to OutputStream at URI " + uri.toString(), e);
            return;
        }finally{
            if (partOs != null){
                try{
                    partOs.close();
                }catch(IOException e){
                    Log.e("MMS_HANDLER", "Could not close OutputStream!", e);
                }
            }
        }
    }

    void onBtInputReceived(byte[] data){
        //Log.d("BT_IN", String.format("Received %d bytes!", data.length));
        ArrayList<byte[]> dataSplits = splitByteArray(data, PayloadIDs.DELIMITER_BYTE);

        //if lastChunk contains data, it must be the first part of what's in dataSplits[0]
        if(btClientThread.getLastInputChunk() != null){
            byte[] lastChunk = btClientThread.getLastInputChunk();
            byte[] newChunk = dataSplits.get(0);
            byte[] combinedBytes = new byte[lastChunk.length+newChunk.length];
            System.arraycopy(lastChunk, 0, combinedBytes, 0, lastChunk.length);
            System.arraycopy(newChunk, 0, combinedBytes, lastChunk.length, newChunk.length);
            dataSplits.set(0, combinedBytes);
            btClientThread.setLastInputChunk(null); //reset last chunk
        }
        //if data doesn't end with a delimiter, it must have gotten cut off
        if(data[data.length-1] != PayloadIDs.DELIMITER_BYTE){
            btClientThread.setLastInputChunk(dataSplits.get(dataSplits.size()-1));
            dataSplits.remove(dataSplits.size()-1);
        }

        //traditional for loop like this skips over null entries
        for(int i=0; i < dataSplits.size(); i++) {
            byte[] split = dataSplits.get(i);
            char dataType = (char)split[0];
            switch(dataType){
                case PayloadIDs.SMS_MESSAGE: { //Incoming SMS message (new)
                    String str = new String(split);
                    int numLen = Integer.parseInt(str.substring(1, 3));
                    String number = str.substring(3, numLen + 3);
                    String content = str.substring(numLen + 3, str.length());
                    handleNewSMS(number, content);
                    break;
                }
                case PayloadIDs.SYNC_UPDATE: { //Incoming SMS Database content (updating/syncing old messages)
                    String str = new String(split);
                    int msgType = Integer.parseInt(str.substring(1, 2));
                    int msgRead = Integer.parseInt(str.substring(2, 3));
                    int dateLen = Integer.parseInt(str.substring(3, 5));
                    long date = Long.parseLong(str.substring(5, dateLen+5));
                    int numLen = Integer.parseInt(str.substring(dateLen+5, dateLen+7));
                    String number = str.substring(dateLen+7, dateLen+numLen+7);
                    String content = str.substring(dateLen+numLen+7, str.length());
                    handleDatabaseUpdate(number, content, msgType, date, msgRead);
                    //update last sync timestamp
                    Appdata.put(getApplicationContext(), Appdata.Keys.LAST_SYNC,
                            System.currentTimeMillis());
                    break;
                }

                case PayloadIDs.MMS_HEADER: { //Incoming MMS metadata
                    String str = new String(split);
                    int textOnly = Integer.parseInt(str.substring(1, 2));
                    int attachmentCount = Integer.parseInt(str.substring(2, 4));
                    int numberLen = Integer.parseInt(str.substring(4, 6));
                    String number = str.substring(6, 6+numberLen);
                    String content = str.substring(6+numberLen, str.length());

                    MmsContainer mmsObj = btClientThread.getLatestMms();
                    mmsObj.address = number;
                    mmsObj.text = content;
                    mmsObj.textOnly = (textOnly == 1);
                    mmsObj.attachmentCount = attachmentCount;
                    btClientThread.setLatestMms(mmsObj);
                    break;
                }

                case PayloadIDs.MMS_PART_LAYOUT: { //Incoming MMS SMIL section
                    String str = new String(split);
                    String smilJson = str.substring(1, str.length());
                    MmsContainer mmsObj = btClientThread.getLatestMms();
                    mmsObj.smil = smilJson;
                    btClientThread.setLatestMms(mmsObj);
                    break;
                }

                case PayloadIDs.MMS_PART_ATTACHMENT: { //Incoming bytes for MMS attachment part
                    byte[] keyBytes = new byte[2];
                    System.arraycopy(split, 1, keyBytes, 0, 2);
                    String attachmentKey = new String(keyBytes);

                    Log.d("BT_IN", "Attachment " + attachmentKey);

                    MmsContainer mmsObj = btClientThread.getLatestMms();
                    if(!mmsObj.finishingSequences.containsKey(attachmentKey)){
                        //we do not have the finishing sequence for this key
                        //therefore this must be a new attachment
                        Log.d("BT_IN", "Decoding MMS attachment with ID " + attachmentKey);
                        byte[] mimeLenBytes = new byte[2];
                        System.arraycopy(split, 3, mimeLenBytes, 0, 2);
                        int mimeLen = Integer.parseInt(new String(mimeLenBytes));

                        byte[] mimeBytes = new byte[mimeLen];
                        System.arraycopy(split, 5, mimeBytes, 0, mimeLen);
                        String mime = new String(mimeBytes);

                        byte[] finSeqLenBytes = new byte[2]; //finishing sequence length
                        System.arraycopy(split, 5+mimeLen, finSeqLenBytes, 0, 2);
                        int finSeqLen = Integer.parseInt(new String(finSeqLenBytes));

                        byte[] finishingSequence = new byte[finSeqLen];
                        System.arraycopy(split, 7+mimeLen, finishingSequence, 0, finSeqLen);
                        Log.d("BT_IN", "Attachment " + attachmentKey + " has finSeq " +
                                new String(finishingSequence));

                        int contentSize = split.length - (7+mimeLen+finSeqLen);
                        long date = System.currentTimeMillis();

                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmSSSS");
                        String fileExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                        String filename = sdf.format(date) + "_" + attachmentKey + "." + fileExt;
                        File attachmentFile = null;
                        try{
                            attachmentFile = newMmsMediaFile(filename);
                        }catch(IOException e){
                            Log.e("MMS_IN", "Could not create new attachment file!", e);
                        }
                        if(attachmentFile != null){
                            btClientThread.attachmentBuilder.openFileOut(attachmentFile);
                            btClientThread.attachmentBuilder.write(split,
                                    7 + mimeLen + finSeqLen, contentSize);
                            mmsObj.attachments.put(attachmentKey, attachmentFile);
                            mmsObj.mimes.put(attachmentKey, mime);
                            mmsObj.finishingSequences.put(attachmentKey, finishingSequence);
                            mmsObj.date = date;
                            mmsObj.msgBox = 1; //msg_box=1 means INBOX
                            mmsObj.read = 0; //message has just come in, so it must be unread
                            btClientThread.setLatestMms(mmsObj);
                        }

                    }else{
                        //attachment is already being rebuilt
                        byte[] finSeq = mmsObj.finishingSequences.get(attachmentKey);
                        if(isSubArray(split, finSeq, split.length-finSeq.length)){
                            //this split ends in the finishing sequence, so it's the last part
                            int contentSize = split.length - finSeq.length - 3;
                            btClientThread.attachmentBuilder.write(split, 3, contentSize);
                            btClientThread.attachmentBuilder.closeFileOut();
                            btClientThread.setLatestMms(mmsObj);
                            Log.d("BT_IN", "Finished MMS attachment with ID " + attachmentKey);

                            if(mmsObj.isFilled()){
                                //MMS has been successfully received
                                handleNewMMS(btClientThread.getLatestMms());
                                btClientThread.clearLatestMms();
                            }

                        }else{
                            //this split contains attachment content and typical encoding data
                            int contentSize = split.length - 3;
                            btClientThread.attachmentBuilder.write(split, 3, contentSize);
                        }
                    }
                }
            }
        }
    }

    void onBtDeviceDisconnected(){
        Toast.makeText(this,
                "Bluetooth Device Disconnected!",
                Toast.LENGTH_SHORT).show();
        //updateTimestamp(getApplicationContext());
        Date timestampDate = new Date(System.currentTimeMillis());
        Log.d("TIMESTAMP", String.format("Set timestamp as: %s",
                timestampDate.toGMTString()));
        updateBtDeviceText("No Device Connected!", -1);
    }

    byte[] encodeSMS(String number, String content){
        String numLen = String.format("%s", number.length());
        if(number.length() < 10){
            numLen = "0" + numLen;
        }
        return (PayloadIDs.SMS_MESSAGE + numLen + number + content + delimiter).getBytes();
    }

    void handleNewSMS(String smsAuthor, String smsContent){
        /*runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, String.format("%s says \"%s\"",
                        smsAuthor, smsContent),
                        Toast.LENGTH_SHORT).show();
            }
        });*/
        ContentValues insertValues = new ContentValues();
        insertValues.put("address", smsAuthor);
        insertValues.put("body", smsContent);
        insertValues.put("date", System.currentTimeMillis());
        insertValues.put("read", 0); //false
        insertValues.put("type", 1); //1 = inbox/received
        getContentResolver().insert(Uri.parse("content://sms/inbox"), insertValues);

        String contactName = getContactName(smsAuthor);
        sendNotification(contactName, smsContent);
    }

    void handleNewMMS(MmsContainer mms){
        Log.i("MMS_IN", String.format("MMS Content:\n" +
                        "Text (if any): \"%s\"\n" +
                        "Address: %s\n" +
                        "SMIL?: %s\n" +
                        "Attachment Count: %d", mms.text, mms.address,
                (mms.smil.length()>0), mms.attachmentCount));

        //get the contact's thread ID
        long threadID = getThreadId(mms.address);
        if(threadID == -1){
            //could not get thread ID
            Log.e("MMS_HANDLER", "Could not get thread_id for address " + mms.address);
            return;
        }

        //insert data into content://mms to get the MMS's ID
        ContentValues mmsValues = new ContentValues();
        mmsValues.put("date", Math.floor(mms.date/1000L)); //time must be in seconds for db entry
        mmsValues.put("msg_box", mms.msgBox);
        mmsValues.put("read", mms.read);
        mmsValues.put("thread_id", threadID);
        mmsValues.put("m_type", 132); //message received (with confirmation)
        mmsValues.put("ct_t", "application/vnd.wap.multipart.related"); //mms message
        mmsValues.put("sub", "Multimedia Message"); //for message previews
        mmsValues.put("sub_cs", 106);
        Uri mmsRootUri = getContentResolver().insert(Uri.parse("content://mms"), mmsValues);

        if(mmsRootUri == null){
            Log.e("MMS_HANDLER", "Could not get MMS table URI!");
            return;
        }
        String mmsId = mmsRootUri.getLastPathSegment();

        //insert values into content://mms/id/addr
        ContentValues addrValues = new ContentValues();
        addrValues.put("address", mms.address);
        addrValues.put("type", 137); //137 indicates FROM
        addrValues.put("charset", 106); //Default which is UTF-8
        Uri addrUri = Uri.parse("content://mms/"+mmsId+"/addr");
        getContentResolver().insert(addrUri, addrValues);

        //get the SMIL and parse into a SmilObject
        SmilObject smil = new SmilObject(mms.smil);

        Uri partTableUri = Uri.parse("content://mms/" + mmsId + "/part");
        if(!mms.textOnly) {
            //edit the SMIL and save each attachment to content://mms/part
            Enumeration<String> keyEnum = mms.attachments.keys();
            while (keyEnum.hasMoreElements()) {
                String key = keyEnum.nextElement();
                String contentLocation = mms.getAttachmentCl(key);
                String mime = mms.mimes.get(key);

                //insert attachment into part table
                ContentValues attachmentVals = new ContentValues();
                attachmentVals.put("ct", mime);
                attachmentVals.put("cl", contentLocation);
                attachmentVals.put("name", contentLocation);
                Uri partUri = getContentResolver().insert(partTableUri, attachmentVals);

                if(partUri == null){
                    Log.e("MMS_HANDLER", "Could not get MMS Part URI!");
                    getContentResolver().delete(mmsRootUri, null, null);
                    return;
                }

                File atmntFile = mms.attachments.get(key);
                byte[] atmntBytes = readFromFile(atmntFile);
                if (atmntBytes == null) {
                    Log.e("MMS_HANDLER", "Could not read attachment from file!");
                    return;
                }
                writeToUri(this, partUri, atmntBytes);

                //update smil to replace attachment key with content location
                smil.remapMediaSource(key, contentLocation);

                //delete media from SD card if user has requested so in settings
                boolean saveMedia = (boolean) Appdata.get(this, Appdata.Keys.SAVE_MEDIA, true);
                if(!saveMedia){
                    boolean deleteSuccess = atmntFile.delete();
                    if(!deleteSuccess){
                        Log.e("MMS_HANDLER", "Could not delete " + atmntFile.getAbsolutePath() + "!");
                    }
                }
            }
        }

        if(mms.text.length() > 0){
            //add text part and edit SMIL accordingly if there's text to be added
            String contentLocation = "text_00.txt";
            ContentValues textValues = new ContentValues();
            textValues.put("text", mms.text);
            textValues.put("ct", "text/plain");
            textValues.put("cl", contentLocation);
            textValues.put("name", contentLocation);
            textValues.put("chset", 106);
            getContentResolver().insert(partTableUri, textValues);

            smil.remapMediaSource("text", contentLocation);
        }

        //save smil with its sources remapped to match table cl fields
        String smilXml = smil.toXml();
        ContentValues smilValues = new ContentValues();
        smilValues.put("text", smilXml);
        smilValues.put("ct", "application/smil");
        smilValues.put("cl", "smil.xml");
        smilValues.put("chset", 106);
        getContentResolver().insert(partTableUri, smilValues);

        //send attachment for new MMS
        String contactName = getContactName(mms.address);
        String notifBody;
        if(mms.textOnly){
            notifBody = mms.text;
        }else{
            if(contactName.equals(mms.address)) {
                //no contact saved for this number
                notifBody = String.format("You were sent %d media files!", mms.attachmentCount);
            }else{
                notifBody = String.format("%s has sent you %d media files!", contactName,
                        mms.attachmentCount);
            }
        }
        sendNotification(contactName, notifBody);
    }

    void handleDatabaseUpdate(String number, String content, int msgType, long date, int read){
        Uri uri = Uri.parse("content://sms/");
        String[] columns = {"_id"};
        String[] selectionArgs = {number, content, String.valueOf(msgType),
                String.valueOf(date-1000), String.valueOf(date+1000)};

        //check if this message already exists in db
        try{
            Cursor cursor = getContentResolver().query(uri, columns,
                    "address = ? AND body = ? AND type = ? AND date >= ? AND date <= ?",
                    selectionArgs, null);
            if(cursor != null){
                if(!cursor.moveToFirst()){
                    cursor.close();
                    //this message is not in the database, so insert it
                    ContentValues values = new ContentValues();
                    values.put("address", number);
                    values.put("body", content);
                    values.put("type", msgType);
                    values.put("date", date);
                    values.put("read", read);
                    getContentResolver().insert(uri, values);

                    //update lastSync and display text
                    Appdata.put(getApplicationContext(), Appdata.Keys.LAST_SYNC,
                            System.currentTimeMillis());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String name = BtConnectionManager.getInstance().getDeviceName();
                            updateBtDeviceText(name, System.currentTimeMillis());
                        }
                    });
                }else {
                    cursor.close();
                }
            }
        }catch (Exception e){
            Log.e("SMSDB_UPDATE", "Unable to update sms database.", e);
        }
    }

    public static void sendSyncRequest(long fromDate){
        String syncInfoBt = PayloadIDs.SYNC_REQUEST + String.valueOf(fromDate) +
                PayloadIDs.DELIMITER_STRING;
        Log.d("SYNC_REQ", String.format("Sending %s...", syncInfoBt));
        BtConnectionManager.getInstance().write(syncInfoBt.getBytes());
    }
    public static void sendSyncRequest(int days){
        long fromDate = System.currentTimeMillis() - (86400000 * days);
        String syncInfoBt = PayloadIDs.SYNC_REQUEST + String.valueOf(fromDate) +
                PayloadIDs.DELIMITER_STRING;
        Log.d("SYNC_REQ", String.format("Sending %s...", syncInfoBt));
        BtConnectionManager.getInstance().write(syncInfoBt.getBytes());
    }

    void sendNotification(String title, String content){
        Intent notificationIntent = new Intent(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_DEFAULT);
        notificationIntent.setType("vnd.android-dir/mms-sms");
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.notifc_icon_bubble);
        builder.setContentTitle(title);
        builder.setContentText(content);
        builder.setDefaults(Notification.DEFAULT_VIBRATE);
        builder.setAutoCancel(true);
        builder.setContentIntent(contentIntent);
        Notification notif = builder.build();

        NotificationManager notificationManager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, notif);
    }

    String getContactName(String number){
        //return contact name, if no contact then return number
        String name = number;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        String[] columns = new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup._ID};
        Cursor cursor = getContentResolver().query(uri, columns, null, null, null);

        if(cursor != null){
            if(cursor.moveToFirst()){
                name = cursor.getString(cursor.getColumnIndex(
                        ContactsContract.PhoneLookup.DISPLAY_NAME));
            }
            cursor.close();
        }
        return name;
    }

    long getThreadId(String contactNumber){
        String uriString = "content://mms-sms/threadID?recipient=" + contactNumber;
        String[] columns = new String[]{"_id"};
        Cursor cursor = getContentResolver().query(
                Uri.parse(uriString),
                columns,
                "",
                null,
                null
        );
        if(cursor != null){
            if(cursor.moveToFirst()){
                int columnIdx = cursor.getColumnIndex("_id");
                if(columnIdx != -1){
                    return cursor.getLong(columnIdx);
                }
            }
        }
        Log.d("THREAD_ID", "Could not get thread ID for " + contactNumber + "!");
        return -1;
    }

    int countUnreadSMS(){
        final Uri uri = Uri.parse("content://sms/inbox");
        Cursor cursor = getContentResolver().query(uri, null, "read = 0", null, null);

        int unreadCount = 0;
        if(cursor != null){
            unreadCount = cursor.getCount();
            cursor.close();
        }
        return unreadCount;
    }

    void updateBtDeviceText(String deviceName, long lastSync){
        connDeviceText.setText(getString(R.string.connDeviceText, String.format(
                "Connected to %s", deviceName)));
        if(lastSync == -1){
            lastSyncText.setText(getString(R.string.lastSyncText, "N/A"));
        }else {
            Date lastSyncDate = new Date(lastSync);
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy @ HH:mm");
            String syncString = sdf.format(lastSyncDate);
            lastSyncText.setText(getString(R.string.lastSyncText, syncString));
        }
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        final List<BluetoothDevice> pairedDevices = new ArrayList<BluetoothDevice>();

        btDeviceList = (ListView) findViewById(R.id.btDeviceList);
        //refreshDevicesBtn = (Button) findViewById(R.id.refreshDevicesBtn);
        dcBtDeviceBtn = (Button) findViewById(R.id.dcBtDeviceBtn);
        connDeviceText = (TextView) findViewById(R.id.connDeviceName);
        lastSyncText = (TextView) findViewById(R.id.lastSyncText);
        settingsBtn = (Button) findViewById(R.id.settingsBtn);

        //if bluetooth is off, open dialogue and request to enable it
        if(!btAdapter.isEnabled()){
            AlertDialog.Builder dialog = new AlertDialog.Builder(this);
            dialog.setTitle("Enable Bluetooth");
            dialog.setMessage("Please enable Bluetooth to use this app.");
            dialog.setPositiveButton("Enable", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    //enable bluetooth and display paired devices
                    btAdapter.enable();
                    displayPairedDevices(pairedDevices, btAdapter);
                }
            });
            dialog.setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    //exit app
                    finish();
                }
            });
            dialog.show();
        }

        displayPairedDevices(pairedDevices, btAdapter);
        connDeviceText.setText(getString(R.string.connDeviceText, "No Device Connected!"));
        lastSyncText.setText(getString(R.string.lastSyncText, "N/A"));

        /*refreshDevicesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                displayPairedDevices(pairedDevices, btAdapter);
            }
        });*/

        btDeviceList.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                BluetoothDevice selectedDevice = pairedDevices.get(i);
                Toast.makeText(getApplicationContext(),
                        String.format("Attempting to connect to %s...", selectedDevice.getName()),
                        Toast.LENGTH_SHORT).show();

                connectBtDevice(btAdapter, selectedDevice);
            }
        });

        dcBtDeviceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                BtConnectionManager.getInstance().disconnectDevice();
                Toast.makeText(getApplicationContext(),
                        "Bluetooth Device Disconnected!",
                        Toast.LENGTH_SHORT).show();
            }
        });

        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent settingsIntent = new Intent(getApplicationContext(),
                        SettingsActivity.class);
                startActivity(settingsIntent);
            }
        });


        outboxThread = new HandlerThread("OutboxObserver");
        outboxThread.start();
        Handler handler = new Handler(outboxThread.getLooper());
        outboxObserver = new OutboxObserver(handler);
        getContentResolver().registerContentObserver(
                outboxObserver.outboxUri,
                true,
                outboxObserver
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(outboxObserver);
        outboxThread.quit();
    }
}
