package com.vrpndt.bouncesmsclient;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private ListView btDeviceList;
    //private Button refreshDevicesBtn;
    private Button dcBtDeviceBtn;
    private TextView connDeviceText;
    private TextView lastSyncText;

    private BtClientThread btClientThread;
    private BtConnectionManager btConnectionManager = new BtConnectionManager();

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
                outStream.write(data);
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
    }

    public static class BtConnectionManager {
        private static final BtConnectionManager ourInstance = new BtConnectionManager();
        public static BtConnectionManager getInstance() {
            return ourInstance;
        }
        private volatile BtClientThread connection = null;
        private volatile BtClientConnector connectorThread = null;

        private BtConnectionManager() {
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
                    Log.d("BT_OUT", "Send data via singleton call!");
                }
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
            if(!btConnectionManager.isConnected()){return;} //cannot send sms if no connection

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
                btConnectionManager.write(encodeSMS(msg.address, msg.body));

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



    void displayPairedDevices(List<BluetoothDevice> pairedDevices, BluetoothAdapter btAdapter){
        Set<BluetoothDevice> bluetoothDevices = btAdapter.getBondedDevices();
        List<String> deviceNames = new ArrayList<String>(bluetoothDevices.size());
        if (bluetoothDevices.size() > 0) {

            for (BluetoothDevice device : bluetoothDevices) {
                if(pairedDevices.indexOf(device) == -1){
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
        BtClientConnector btClientConnector = new BtClientConnector(adapter);
        btClientConnector.getConnectionSocket(device);
        btClientConnector.start();
        btConnectionManager.setConnectorThread(btClientConnector);
    }

    void onBtDeviceConnected(BluetoothSocket socket){
        btClientThread = new BtClientThread(socket);
        btClientThread.start();
        btConnectionManager.setConnection(btClientThread);
        long lastUpdate = getTimestamp();
        if(lastUpdate != -1){
            Date timestampDate = new Date(lastUpdate);
            Log.d("TIMESTAMP", String.format("Read timestamp as: %s",
                    timestampDate.toGMTString()));
        }else{
            lastUpdate = System.currentTimeMillis();
            updateTimestamp();
            Date timestampDate = new Date(System.currentTimeMillis());
            Log.d("TIMESTAMP", String.format("Set timestamp as: %s",
                    timestampDate.toGMTString()));
        }
        //updateBtDeviceText(socket.getRemoteDevice().getName(), lastUpdate);
    }

    void onBtInputReceived(byte[] data){
        final String[] decoded = decodeSMS(data);
        final String smsAuthor = decoded[0];
        final String smsContent = decoded[1].trim();
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
        insertValues.put("read", false);
        insertValues.put("type", 1);
        getContentResolver().insert(Uri.parse("content://sms/inbox"), insertValues);

        Intent notificationIntent = new Intent(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_DEFAULT);
        notificationIntent.setType("vnd.android-dir/mms-sms");
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        String notifPreview;
        if(smsContent.length() > 17){
            notifPreview = String.format("%s...", smsContent.substring(0, 17));
        }else{
            notifPreview = smsContent;
        }

        String contactName = getContactName(smsAuthor);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.notifc_icon_bubble);
        builder.setContentTitle(contactName);
        builder.setContentText(notifPreview);
        builder.setDefaults(Notification.DEFAULT_VIBRATE);
        builder.setAutoCancel(true);
        builder.setContentIntent(contentIntent);
        Notification notif = builder.build();

        NotificationManager notificationManager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, notif);
    }

    void onBtDeviceDisconnected(){
        Toast.makeText(this,
                "Bluetooth Device Disconnected!",
                Toast.LENGTH_SHORT).show();
        updateTimestamp();
        Date timestampDate = new Date(System.currentTimeMillis());
        Log.d("TIMESTAMP", String.format("Set timestamp as: %s",
                timestampDate.toGMTString()));
        //updateBtDeviceText("No Device Connected!", -1);
    }

    //returns -1 if error, otherwise returns Unix time for last update
    long getTimestamp(){
        Context context = getApplicationContext();
        File timestampFile = new File(context.getFilesDir(), "timestamp.json");
        String timestampContents;
        if(timestampFile.exists()){
            try{
                FileInputStream fis = context.openFileInput(timestampFile.getName());
                InputStreamReader isr = new InputStreamReader(fis);
                StringBuilder stringBuilder = new StringBuilder();
                BufferedReader reader = new BufferedReader(isr);

                String line = reader.readLine();
                while (line != null) {
                    stringBuilder.append(line).append('\n');
                    line = reader.readLine();
                }
                timestampContents = stringBuilder.toString();
            }catch(Exception e){
                Log.e("OPEN_FILE", "timestamp.json could not be opened.", e);
                 return -1;
            }
        }else{
            Log.e("OPEN_FILE", "timestamp.json does not exist! What the fuck!!");
            return -1;
        }

        try{
            JSONObject timestampJSON = new JSONObject(timestampContents);
            long lastUpdate = timestampJSON.getLong("lastUpdate");
            Log.d("TIMESTAMP", String.format("Read timestamp as %d", lastUpdate));

            return lastUpdate;

        }catch(JSONException e){
            Log.e("PARSE_JSON", "Could not parse the contents of timestamp.json.", e);
            return -1;
        }
    }

    void updateTimestamp(){
        Context context = getApplicationContext();
        File timestampFile = new File(context.getFilesDir(), "timestamp.json");
        long lastUpdate = System.currentTimeMillis();

        JSONObject timestampJSON = new JSONObject();
        String JSONString = null;
        try{
            timestampJSON.put("lastUpdate", lastUpdate);
            JSONString = timestampJSON.toString();

        }catch(JSONException e){
            Log.e("WRITE_JSON", "Could not write lastUpdate to JSON.", e);
        }

        if(JSONString != null){
            try{
                FileOutputStream fos = context.openFileOutput(timestampFile.getName(),
                        Context.MODE_PRIVATE);
                fos.write(JSONString.getBytes());
                Log.d("TIMESTAMP", String.format("Updated timestamp to %d", lastUpdate));
            }catch(Exception e){
                Log.e("WRITE_FILE", "Could not write to timestamp.json.", e);
            }
        }
    }

    byte[] encodeSMS(String number, String content){
        String numLen = String.format("%s", number.length());
        if(number.length() < 10){
            numLen = "0" + numLen;
        }
        return (numLen + number + content).getBytes();
    }

    String[] decodeSMS(byte[] data){
        String dataStr = new String(data);
        int numLen = Integer.parseInt(dataStr.substring(0, 2));
        String number = dataStr.substring(2, numLen+2);
        String content = dataStr.substring(numLen+2, dataStr.length());
        String[] returnArray = {number, content};
        return returnArray;
    }

    String getContactName(String number){
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
        //connDeviceText = (TextView) findViewById(R.id.connDeviceName);
        //lastSyncText = (TextView) findViewById(R.id.lastSyncText);

        displayPairedDevices(pairedDevices, btAdapter);


        /*connDeviceText.setText(getString(R.string.connDeviceText, "No Device Connected!"));
        lastSyncText.setText(getString(R.string.lastSyncText, "N/A"));*/

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
                btConnectionManager.disconnectDevice();
                Toast.makeText(getApplicationContext(),
                        "Bluetooth Device Disconnected!",
                        Toast.LENGTH_SHORT).show();
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
