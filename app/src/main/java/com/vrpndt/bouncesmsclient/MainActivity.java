package com.vrpndt.bouncesmsclient;

import android.app.Notification;
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
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
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

import com.vrpndt.bouncesmsclient.util.Notifications;
import com.vrpndt.bouncesmsclient.util.PayloadIDs;
import com.vrpndt.bouncesmsclient.util.MmsIO;
import com.vrpndt.bouncesmsclient.util.Appdata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private ListView btDeviceList;
    //private Button refreshDevicesBtn;
    private Button dcBtDeviceBtn;
    private Button settingsBtn;
    private TextView connDeviceText;
    private TextView lastSyncText;

    private BtClientThread btClientThread;

    private SmsOutboxObserver smsOutboxObserver;
    private MmsOutboxObserver mmsOutboxObserver;
    private HandlerThread outboxThread;

    public static File internalStorage;

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
        public AttachmentBuilder newMmsAb = new AttachmentBuilder();
        public AttachmentBuilder mmsSyncAb = new AttachmentBuilder();
        public HashMap<String, Uri> mmsSyncAtmntTable = new HashMap<>();

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
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateBtDeviceText(null, -1);
                            Notifications.sendInfoNotif(
                                    MainActivity.this,
                                    "Device Disconnected",
                                    "BounceSMS is no longer connected to the host device!");
                        }
                    });
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
                    conn.write(data);
                    Log.d("BT_OUT", String.format("Sent %d bytes!",
                            data.length));
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

    private class SmsOutboxObserver extends ContentObserver {
        private final Uri smsUri = Uri.parse("content://sms");

        public SmsOutboxObserver(Handler handler){
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            //cannot send sms if no connection
            //if(!BtConnectionManager.getInstance().isConnected() || selfChange){return;}

            try {
                super.onChange(selfChange);

                //query outbox for any new items, return address, and body
                String[] columns = new String[]{"_id", "address", "body", "date", "status",
                        "read", "thread_id", "type"};
                //2000ms = 2 seconds -- use this cutoff to prevent excessive results
                long smsTimeCutoff = System.currentTimeMillis() - 2000;
                String[] selectionArgs = {"5", "6", String.valueOf(smsTimeCutoff)};
                Cursor cursor = getContentResolver().query(smsUri,
                        columns, "type IN (?, ?) AND date > ?",
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
                            String msgType = cursor.getString(
                                    cursor.getColumnIndex("type")
                            );

                            Log.d("OUTBOX_OBSERVER", String.format("New SMS (%s) of type %s",
                                    msgID, msgType));

                            handlePendingSms(msgID, msgAddress, msgContent);

                        } while(cursor.moveToNext());
                    }
                    cursor.close();
                }
            }catch(Exception e){
                Log.e("OUTBOX_OBSERVER", "Failed to process outbox change!", e);
            }
        }

        private void handlePendingSms(String id, String address, String content){
            BtConnectionManager.getInstance().write(encodeSMS(id, address, content));

            //update type in messages in content://sms from 5/6 to 2
            /*ContentValues updatedSMS = new ContentValues();
            updatedSMS.put("type", 2);
            String[] idArgs = {id};
            getContentResolver().update(
                    smsUri,
                    updatedSMS,
                    "_id = ?",
                    idArgs
            );*/
        }
    }

    private class MmsOutboxObserver extends ContentObserver {
        private final Uri mmsRootUri = Uri.parse("content://mms");
        private final Uri mmsAddrUri = Uri.parse("content://mms/addr");
        private final Uri mmsPartUri = Uri.parse("content://mms/part");
        private HashMap<String, MmsContainer> pendingMms = new HashMap<>();
        private HashMap<String, String> threadIds = new HashMap<>();
        private ArrayList<String> pendingMmsIds = new ArrayList<>();
        private ArrayList<String> handledMmsIds = new ArrayList<>();

        MmsOutboxObserver(Handler handler){
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            //cannot send sms if no connection
            //if(!BtConnectionManager.getInstance().isConnected()){return;}
            super.onChange(selfChange);

            //first, check whether the latest entry in content://mms has been added to pendingMmsIds
            //if not, add it
            Cursor cursor = getContentResolver().query(
                mmsRootUri,
                new String[]{"_id", "date", "thread_id"},
                "msg_box IN (?, ?, ?)",
                new String[]{"3", "4", "5"},
                "date DESC"
            );
            if(cursor != null){
                if(cursor.moveToFirst()){
                    String mmsId = cursor.getString(cursor.getColumnIndex("_id"));
                    if(!pendingMmsIds.contains(mmsId) && !handledMmsIds.contains(mmsId)){
                        String threadId = cursor.getString(cursor.getColumnIndex("thread_id"));
                        long date = cursor.getLong(cursor.getColumnIndex("date"));
                        MmsContainer newMms = new MmsContainer();
                        newMms.id = mmsId;
                        newMms.textOnly = false;
                        newMms.msgBox = 2;
                        newMms.read = 0;
                        newMms.date = date;
                        newMms.attachmentCount = -1; //set -1 to indicate default/unset value
                        pendingMmsIds.add(mmsId);
                        pendingMms.put(mmsId, newMms);
                        threadIds.put(mmsId, threadId);
                        Log.d("MMS_OUTBOX_OBSERVER", "Set root info for new MMS "+mmsId);
                    }
                }
                cursor.close();
            }

            //if there are no pending MMS messages, there is no need to carry on
            if(pendingMmsIds.size() < 1){
                Log.d("MMS_OUTBOX_OBSERVER", "onChange() was called but no MMSes to process!");
                return;
            }

            //create a comma-separated list of all pending IDs
            String idsString = TextUtils.join(", ", pendingMmsIds);

            //obtain recipient address through content://mms-sms/conversations
            //this requires a for-loop
            for(String mmsId: pendingMmsIds){
                String recipients = "";
                String threadId = threadIds.get(mmsId);
                Log.d("MMS_OUTBOX_OBSERVER", "Searching thread ID: "+threadId);
                cursor = getContentResolver().query(
                        Uri.parse("content://mms-sms/conversations/"+threadId+"/recipients"),
                        new String[]{"recipient_ids"},
                        "",
                        null,
                        null
                );
                if(cursor != null){
                    if(cursor.moveToFirst()){
                        //Recipient IDs that can be referenced in content://mms-sms/canonical-address
                        recipients = cursor.getString(cursor.getColumnIndex("recipient_ids"));
                    }
                    cursor.close();
                }

                if(!recipients.equals("")){
                    String[] recipIds = TextUtils.split(recipients, " ");
                    if(recipIds.length == 1){
                        cursor = getContentResolver().query(
                                Uri.parse("content://mms-sms/canonical-address/"+recipIds[0]),
                                new String[]{"address"},
                                "",
                                null,
                                null
                        );
                        if(cursor != null){
                            if(cursor.moveToFirst()){
                                String address = cursor.getString(cursor.getColumnIndex("address"));
                                if(address != null){
                                    MmsContainer mms = pendingMms.get(mmsId);
                                    mms.address = address;
                                    pendingMms.put(mmsId, mms);
                                    Log.d("MMS_OUTBOX_OBSERVER", "Added address to MMS "+mmsId);
                                }
                            }
                            cursor.close();
                        }
                    }
                }
            }

            //now check content://mms/part
            cursor = getContentResolver().query(
                    mmsPartUri,
                    new String[]{"mid", "_id"},
                    String.format("mid IN (%s)", idsString),
                    null,
                    "mid DESC"
            );
            if(cursor != null){
                if(cursor.moveToFirst()){
                    do{
                        String partId = cursor.getString(cursor.getColumnIndex("_id"));
                        String mmsId = cursor.getString(cursor.getColumnIndex("mid"));
                        if(!pendingMms.get(mmsId).handledParts.contains(partId)){
                            //only process this part if it hasn't been handled
                            Uri uri = Uri.parse("content://mms/part/"+partId);
                            onMmsPart(uri);
                            MmsContainer mms = pendingMms.get(mmsId);
                            mms.handledParts.add(partId);
                            pendingMms.put(mmsId, mms);
                            Log.d("MMS_OUTBOX_OBSERVER", "Handled part with ID "+partId+" for MMS "+mmsId);
                        }

                    } while(cursor.moveToNext());
                }
                cursor.close();
            }

            //finally, handle all items in pendingMms that are filled
            for(String mmsId: pendingMmsIds){
                MmsContainer mms = pendingMms.get(mmsId);
                if(mms.isFilledForEncoding()){
                    pendingMms.remove(mmsId);
                    pendingMmsIds.remove(mmsId);

                    //if the handled mms list is getting large, clear old items before continuing
                    if(handledMmsIds.size() >= 5){
                        handledMmsIds.clear();
                    }
                    handledMmsIds.add(mmsId);
                    Log.d("MMS_OUTBOX_OBSERVER",
                            String.format(
                                    "Parsed MMS with ID %s\n" +
                                            "Address: %s\n" +
                                            "Text: %s\n" +
                                            "Attachment Count: %d\n" +
                                            "SMIL?: %s\n" +
                                            "Got filenames?: %s",
                                    mmsId, mms.address, mms.text, mms.attachmentCount,
                                    (mms.smil != null),
                                    mms.filenameMap.size() == mms.attachmentCount + 1
                            ));
                    mms.encodeAndSend();
                    //update msg_box in content://mms to 2 (sent)
                    /*ContentValues updatedMms = new ContentValues();
                    updatedMms.put("msg_box", 2);
                    String[] idArgs = {mmsId};
                    getContentResolver().update(
                            mmsRootUri,
                            updatedMms,
                            "_id = ?",
                            idArgs
                    );*/

                    Log.d("MMS_OUTBOX_OBSERVER", "Encoded and sent MMS with ID "+mmsId);
                }else{
                    Log.d("MMS_OUTBOX_OBSERVER", "Checked MMS "+mmsId+" but isFilledForEncoding() is false!");
                    Log.d("MMS_OUTBOX_OBSERVER", String.format("Encoded Atmnts: %d \nAtmnt Count: %d",
                            mms.encodedAttachmentCount, mms.attachmentCount));
                }
            }
        }

        private void onMmsPart(Uri uri){
           Cursor cursor = getContentResolver().query(
                    uri,
                    new String[]{"mid", "text", "ct", "cl"},
                    "",
                    null,
                    null
            );
            if(cursor != null){
                if(cursor.moveToFirst()){
                    String mmsId = cursor.getString(cursor.getColumnIndex("mid"));
                    String contentType = cursor.getString(cursor.getColumnIndex("ct"));
                    switch(contentType){
                        case "text/plain": {
                            //text content
                            String text = cursor.getString(cursor.getColumnIndex("text"));
                            String filename = cursor.getString(cursor.getColumnIndex("cl"));
                            MmsContainer mms = pendingMms.get(mmsId);
                            mms.text = text;
                            mms.filenameMap.put(filename, "text");
                            pendingMms.put(mmsId, mms);
                            break;
                        }
                        case "application/smil": {
                            //smil xml
                            String smilXml = cursor.getString(cursor.getColumnIndex("text"));
                            InputStream smilStream = new ByteArrayInputStream(smilXml.getBytes());
                            SmilObject smil = new SmilObject();
                            smil.parseXml(smilStream);
                            MmsContainer mms = pendingMms.get(mmsId);
                            mms.smil = smil;
                            mms.attachmentCount = smil.totalMedia;
                            pendingMms.put(mmsId, mms);
                            break;
                        }
                        default: {
                            //attachment content
                            String filename = cursor.getString(cursor.getColumnIndex("cl"));

                            //instead of saving to sd card then reloading to encode, just
                            //encode on the first pass through when reading from mms part table
                            MmsContainer mms = pendingMms.get(mmsId);
                            String attachmentKey = twoDigitNumber(mms.attachmentCount);
                            File atmntFile = mms.encodedAttachments;

                            if(atmntFile == null){
                                //attachment file has not been created, so do that
                                try{
                                    long time = System.currentTimeMillis();
                                    atmntFile = MmsIO.newMmsMediaFile(
                                            String.format("outgoing_%d.atmnt", time));
                                    mms.encodedAttachments = atmntFile;
                                }catch(IOException e){
                                    Log.e("MMS_OUTBOX_OBSERVER",
                                            "Could not create encoded attachment file!", e);
                                    return;
                                }
                            }

                            MmsIO.readAndEncodeAtmnt(getApplicationContext(), uri,
                                    attachmentKey, contentType, atmntFile);
                            mms.mimes.put(attachmentKey, contentType);
                            mms.filenameMap.put(filename, attachmentKey);
                            mms.encodedAttachmentCount += 1;
                            if(mms.textOnly){
                                mms.textOnly = false;
                            }
                            pendingMms.put(mmsId, mms);
                            break;
                        }

                    }
                }
                cursor.close();
            }
        }
    }

    static class MmsContainer{
        String id = "";
        boolean textOnly = false;
        String text = "";
        String address = "";
        long date = -1;
        int msgBox = -1;
        int read = -1;
        int attachmentCount = 0;
        int attachmentSize = 0;
        ArrayList<String> handledParts = new ArrayList<>();
        ConcurrentHashMap<String, File> attachments = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> mimes = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, byte[]> finishingSequences = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> filenameMap = new ConcurrentHashMap<>();
        File encodedAttachments = null;
        int encodedAttachmentCount = 0;
        SmilObject smil = null;

        boolean isFilledForDecoding(){
            boolean noNulls = date > -1 && msgBox > -1 && read > -1;
            boolean attachmentCondition = ((!textOnly) == (attachments.size() > 0)) &&
                    ((attachments.size() > 0) == mimes.size() > 0);
            boolean hasAllAtmnts = attachmentCount == attachments.size();
            return attachmentCondition && hasAllAtmnts && noNulls &&
                    address.length() > 0 && smil != null;
        }

        boolean isFilledForEncoding(){
            return (encodedAttachmentCount == attachmentCount) && (mimes.size() == attachmentCount)
                    && smil != null && !address.equals("") && !id.equals("");
        }

        String getNewAttachmentCl(String attachmentKey){
            String fileExt = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimes.get(attachmentKey));
            return "media_" + attachmentKey + "." + fileExt;
        }

        void encodeAndSend(){
            //encode header - root info + text and address
            BtConnectionManager btcm = BtConnectionManager.getInstance();
            int textOnlyInt = 0;
            if(textOnly){ textOnlyInt = 1; }
            byte[] header = (PayloadIDs.MMS_HEADER + twoDigitNumber(id.length()) + id +
                                Integer.toString(textOnlyInt) +
                                twoDigitNumber(attachmentCount) + twoDigitNumber(address.length()) +
                                address + text + PayloadIDs.DELIMITER_STRING).getBytes();

            //encode SMIL
            Enumeration<String> filenames = filenameMap.keys();
            while(filenames.hasMoreElements()){
                String filename = filenames.nextElement();
                String key = filenameMap.get(filename);
                smil.remapMediaSource(filename, key);
            }
            byte[] smilPart = (PayloadIDs.MMS_PART_LAYOUT + smil.toJSON() + PayloadIDs.DELIMITER_STRING)
                                .getBytes();

            byte[] attachmentBuffer = new byte[1024]; //1KB buffer for parsing attachment

            btcm.write(header);
            btcm.write(smilPart);
            Log.d("SEND_MMS", "Sending: "+new String(header));
            Log.d("SEND_MMS", "Sending: "+new String(smilPart));

            Log.d("SEND_MMS", String.format("Total Attachment (as read): %d bytes\n" +
                    "Total after encoding (from file): %d bytes", this.attachmentSize,
                    encodedAttachments.length()));
            FileInputStream fis;
            try{
                fis = new FileInputStream(encodedAttachments);
            }catch(FileNotFoundException e){
                Log.e("ENCODE_MMS", "Could not open total attachment FIS!", e);
                return;
            }
            try{
                int bytesRead = fis.read(attachmentBuffer);
                while(bytesRead != -1){
                    if(bytesRead < 1024) {
                        //under 1KB, so copy into a new buffer to avoid re-sending stale bytes
                        //from previous iteration
                        byte[] remainingBytes = new byte[bytesRead];
                        System.arraycopy(attachmentBuffer, 0, remainingBytes, 0, bytesRead);
                        btcm.write(remainingBytes);
                        //Log.d("SEND_MMS", "Sending: "+ new String(remainingBytes));

                    }else{
                        btcm.write(attachmentBuffer);
                        //Log.d("SEND_MMS", "Sending: "+ new String(attachmentBuffer));
                    }

                    bytesRead = fis.read(attachmentBuffer);
                }

            }catch(IOException e){
                Log.e("ENCODE_MMS", "Could not read from total attachment file!", e);
            }

            //delete the raw and encoded attachment files
            encodedAttachments.delete();
            Enumeration<File> filesEnum = attachments.elements();
            while(filesEnum.hasMoreElements()){
                File file = filesEnum.nextElement();
                file.delete();
            }

        }

        File encodeAttachments(){
            File encodedFile;
            try{
                encodedFile = MmsIO.newMmsMediaFile(String.format("encoded_%d.atmnt",
                        System.currentTimeMillis()));
            }catch(IOException e){
                Log.e("ENCODE_ATTACHMENTS", "Could not create encoded attachment file!", e);
                return null;
            }
            Enumeration<String> keysEnum = attachments.keys();
            while(keysEnum.hasMoreElements()){
                String key = keysEnum.nextElement();
                File atmntFile = attachments.get(key);
                String mime = mimes.get(key);
                String mimeLen = twoDigitNumber(mime.length());

                BufferedInputStream fis;
                BufferedOutputStream fos;
                try{
                    fis = new BufferedInputStream(new FileInputStream(atmntFile));
                    fos = new BufferedOutputStream(new FileOutputStream(encodedFile));
                }catch(FileNotFoundException e){
                    Log.e("ENCODE_ATTACHMENTS",
                            "Could not find attachment file "+atmntFile.getAbsolutePath(), e);
                    return null;
                }
                try{
                    fos.write(PayloadIDs.MMS_PART_ATTACHMENT);
                    fos.write(key.getBytes());
                    fos.write(mimeLen.getBytes());
                    fos.write(mime.getBytes());
                    int nextByte = fis.read();
                    while(nextByte != -1){
                        fos.write(nextByte);
                        if(nextByte == PayloadIDs.DELIMITER_BYTE){
                            fos.write(PayloadIDs.MMS_PART_ATTACHMENT);
                            fos.write(key.getBytes());
                        }
                        nextByte = fis.read();
                    }

                    //once finished encoding, write "END{key}{delimiter}" to signify end of file
                    //statistically improbable that this byte segment would occur naturally
                    //also what else am I meant to do
                    fos.write(("END"+key+PayloadIDs.DELIMITER_STRING).getBytes());
                    fos.flush();
                    fos.close();
                }catch(IOException e){
                    Log.e("ENCODE_ATTACHMENTS", "Failed to read from "+atmntFile.getAbsolutePath(), e);
                    return null;
                }finally{
                    try{
                        fis.close();
                        fos.close();
                    }catch(IOException e){
                        Log.e("ENCODE_ATTACHMENT", "Could not close file streams!", e);
                    }
                }
            }
            return encodedFile;
        }
    }

    static class AttachmentBuilder {
        FileOutputStream fileOut = null;
        File lastFile = null;

        void openFileOut(File file){
            closeFileOut();
            try{
                fileOut = new FileOutputStream(file);
                lastFile = file;
            }catch(FileNotFoundException e){
                Log.e("ATMNT_BUILDER", "Could not open new FileOutputStream!", e);
            }
        }

        void closeFileOut(){
            if(fileOut != null){
                try {
                    fileOut.flush();
                    fileOut.close();
                    fileOut = null;
                    lastFile = null;
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

    static class SmilObject{
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

            JSONObject toJSONObject(){
                JSONObject self = new JSONObject();
                try{
                    self.put("type", type);
                    self.put("src", src);
                    self.put("region", region);
                    return self;

                }catch(JSONException e){
                    Log.e("SMIL_TOJSON", "Could not convert media to JSON!", e);
                    return null;
                }
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

            JSONObject toJSONObject(){
                JSONObject self = new JSONObject();
                try{
                    self.put("dur", duration);
                    JSONArray media = new JSONArray();
                    for(Media m: this.media){
                        media.put(m.toJSONObject());
                    }
                    self.put("media", media);
                    return self;

                }catch(JSONException e){
                    Log.e("SMIL_TOJSON", "Could not convert slide to JSON!", e);
                    return null;
                }
            }
        }

        ArrayList<String> regions = new ArrayList<>();
        ArrayList<Slide> slides = new ArrayList<>();
        int totalMedia = -1;

        SmilObject(){}
        SmilObject(ArrayList<String> regions, ArrayList<Slide> slides){
            this.regions = regions;
            this.slides = slides;
        }

        String toJSON(){
            JSONObject self = new JSONObject();
            try{
                self.put("regions", new JSONArray(regions));
                JSONArray slides = new JSONArray();
                for(Slide slide: this.slides){
                    slides.put(slide.toJSONObject());
                }
                self.put("pars", slides);
                return self.toString(0); //0 indent spaces

            }catch(JSONException e){
                Log.e("SMIL_TOJSON", "Could not convert SMIL to JSON!", e);
                return null;
            }
        }

        void parseJSON(String jsonString){
            totalMedia = 0;
            SmilObject newSmil;
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
                        if(!mediaType.equals("text")){
                            totalMedia += 1;
                        }
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
            totalMedia = 0;
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

        void parseXml(InputStream streamIn){
            totalMedia = 0;
            XmlPullParserFactory factory;
            XmlPullParser parser;
            try{
                factory = XmlPullParserFactory.newInstance();
                parser = factory.newPullParser();
                parser.setInput(streamIn, null);

                String currentDur = null;
                ArrayList<Media> currentElements = new ArrayList<>();
                int eventType = parser.getEventType();
                while(eventType != XmlPullParser.END_DOCUMENT){
                    switch(eventType){ //switch on tag name
                        case XmlPullParser.START_TAG: {
                            //start of xml tag
                            switch(parser.getName()){
                                case "region": {
                                    String id = parser.getAttributeValue(null, "id");
                                    regions.add(id);
                                    break;
                                }
                                case "par": {
                                    if(currentDur == null){
                                        currentDur = parser.getAttributeValue(null, "dur");
                                    }
                                    break;
                                }
                                case "img":
                                case "video":
                                case "audio":
                                case "text": {
                                    String source = parser.getAttributeValue(null, "src");
                                    String region = parser.getAttributeValue(null, "region");
                                    currentElements.add(new Media(parser.getName(), region, source));
                                    if(!parser.getName().equals("text")){
                                        totalMedia += 1;
                                    }
                                    break;
                                }
                            }
                            break;
                        }

                        case XmlPullParser.END_TAG: {
                            if(parser.getName().equals("par") && currentDur != null){
                                slides.add(new Slide(currentDur, currentElements));
                            }
                            break;
                        }
                    }
                    try{
                        eventType = parser.next();
                    }catch(IOException e){
                        Log.e("PARSE_XML", "Couldn't get next event type!", e);
                        return;
                    }
                }

            } catch(XmlPullParserException e){
                Log.e("PASRSE_XML", "Could not parse SMIL XML!", e);
                return;
            }
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

    //more memory-efficient than splitting the array at specified indexes
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

    public static String twoDigitNumber(int number){
        String str = Integer.toString(number);
        if(number < 10){
            str = "0" + str;
        }
        return str;
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
            Log.d("BT_IN", "Got payload of type "+dataType+"!");
            switch(dataType){
                case PayloadIDs.SMS_MESSAGE: { //Incoming SMS message (new)
                    String str = new String(split);
                    int dateLen = Integer.parseInt(str.substring(1, 3));
                    long date = Long.parseLong(str.substring(3, 3+dateLen));
                    int numLen = Integer.parseInt(str.substring(3+dateLen, 5+dateLen));
                    String number = str.substring(5+dateLen, 5+dateLen+numLen);
                    String content = str.substring(5+dateLen+numLen, str.length());
                    handleNewSMS(number, content, date);
                    break;
                }
                case PayloadIDs.SYNC_UPDATE: { //Incoming database content (syncing old SMS/MMS)
                    char updateType = (char)split[1];
                    Log.d("DB_UPDATE", "Update type: "+updateType);

                    switch(updateType){
                        case PayloadIDs.SMS_MESSAGE: {
                            // content://sms update
                            String str = new String(split);
                            int msgType = Integer.parseInt(str.substring(2, 3));
                            int msgRead = Integer.parseInt(str.substring(3, 4));
                            int dateLen = Integer.parseInt(str.substring(4, 6));
                            long date = Long.parseLong(str.substring(6, 6+dateLen));
                            int numLen = Integer.parseInt(str.substring(6+dateLen, 8+dateLen));
                            String number = str.substring(8+dateLen, 8+dateLen+numLen);
                            String content = str.substring(8+dateLen+numLen, str.length());
                            handleSmsDbUpdate(number, content, msgType, date, msgRead);
                            //update last sync timestamp
                            Appdata.put(getApplicationContext(), Appdata.Keys.LAST_SYNC,
                                    System.currentTimeMillis());
                            break;
                        }

                        case PayloadIDs.MMS_HEADER:{
                            // content://mms update
                            String str = new String(split);
                            int idLen = Integer.parseInt(str.substring(2, 4));
                            String mmsId = str.substring(4, 4+idLen);
                            int msgBox = Integer.parseInt(str.substring(4+idLen, 5+idLen));
                            int readInt = Integer.parseInt(str.substring(5+idLen, 6+idLen));
                            int atmntCount = Integer.parseInt(str.substring(6+idLen, 8+idLen));
                            int dateLen = Integer.parseInt(str.substring(8+idLen, 10+idLen));
                            long date = Long.parseLong(str.substring(10+idLen, 10+idLen+dateLen));
                            int addressLen = Integer.parseInt(str.substring(10+idLen+dateLen, 12+idLen+dateLen));
                            String address = str.substring(12+idLen+dateLen, 12+idLen+dateLen+addressLen);

                            handleMmsDbUpdate(mmsId, address, date, atmntCount, msgBox, readInt);
                            break;
                        }
                        case PayloadIDs.MMS_PART_LAYOUT: {
                            String str = new String(split);
                            int idLen = Integer.parseInt(str.substring(2, 4));
                            String hostId = str.substring(4, 4+idLen);

                            String smilJSON = str.substring(4+idLen, str.length());
                            SmilObject smil = new SmilObject();
                            smil.parseJSON(smilJSON);
                            String smilXml = smil.toXml();
                            Log.d("SYNC_MMS", "SMIL: "+smilXml);

                            ContentValues smilValues = new ContentValues();
                            smilValues.put("text", smilXml);
                            smilValues.put("ct", "application/smil");
                            smilValues.put("cl", "smil.xml");
                            smilValues.put("chset", 106);

                            String clientId = (String)Appdata.get(getApplicationContext(), hostId, null);
                            if(clientId != null) {
                                Uri partTableUri = Uri.parse("content://mms/" + clientId + "/part");
                                getContentResolver().insert(partTableUri, smilValues);
                            }else{
                                Log.e("MMS_SYNC", "Could not get client-side ID for host MMS "+hostId+"!");
                            }
                            break;
                        }

                        case PayloadIDs.MMS_UPDATE_TEXT: {
                            String str = new String(split);
                            int idLen = Integer.parseInt(str.substring(2, 4));
                            String hostId = str.substring(4, 4+idLen);
                            String text = str.substring(4+idLen, str.length());

                            String contentLocation = "text_00.txt";
                            ContentValues textValues = new ContentValues();
                            textValues.put("text", text);
                            textValues.put("ct", "text/plain");
                            textValues.put("cl", contentLocation);
                            textValues.put("name", contentLocation);
                            textValues.put("chset", 106);

                            String clientId = (String)Appdata.get(getApplicationContext(), hostId, null);
                            if(clientId != null) {
                                Uri partTableUri = Uri.parse("content://mms/" + clientId + "/part");
                                getContentResolver().insert(partTableUri, textValues);
                            }else{
                                Log.e("MMS_SYNC", "Could not get client-side ID for host MMS "+hostId+"!");
                            }
                            break;
                        }

                        case PayloadIDs.MMS_PART_ATTACHMENT: {
                            //composite key in form "{mmsId}{atmntKey}"
                            byte[] idLenBytes = new byte[2];
                            System.arraycopy(split, 2, idLenBytes, 0, 2);
                            int idLen = Integer.parseInt(new String(idLenBytes));
                            int compKeyLen = idLen+2;

                            byte[] compKeyBytes = new byte[compKeyLen];
                            System.arraycopy(split, 4, compKeyBytes, 0, compKeyLen);
                            String compositeKey = new String(compKeyBytes);

                            if(btClientThread.mmsSyncAb.fileOut == null){
                                try{
                                    File tempFile = MmsIO.newMmsMediaFile("temp_20260721.jpeg");
                                    btClientThread.mmsSyncAb.openFileOut(tempFile);
                                }catch(IOException e){
                                    Log.e("TEMP_FILE", "Could not create temp file", e);
                                }
                            }

                            if(btClientThread.mmsSyncAtmntTable.containsKey(compositeKey)){
                                //atmnt is already being decoded, get uri and write to stream
                                Uri partUri = btClientThread.mmsSyncAtmntTable.get(compositeKey);
                                String atmntKey = compositeKey.substring(idLen, compKeyLen);
                                byte[] endSeq = ("END"+atmntKey).getBytes();
                                if(isSubArray(split, endSeq, split.length-endSeq.length)){
                                    //this is the final segment in the attachment
                                    btClientThread.mmsSyncAb.write(split, 4+compKeyLen,
                                            split.length-(4+compKeyLen+endSeq.length));
                                    byte[] atmntBytes = MmsIO.readFromFile(
                                            btClientThread.mmsSyncAb.lastFile);
                                    if(atmntBytes != null){
                                        MmsIO.writeToUri(getApplicationContext(), partUri, atmntBytes);
                                    }else{
                                        Log.d("MMS_SYNC", "atmntBytes is null!");
                                    }
                                    btClientThread.mmsSyncAtmntTable.remove(compositeKey);
                                    btClientThread.mmsSyncAb.lastFile.delete();
                                    btClientThread.mmsSyncAb.closeFileOut();


                                }else{
                                    btClientThread.mmsSyncAb.write(split, 4+compKeyLen,
                                            split.length - (4+compKeyLen));
                                }

                            }else{
                                //this is a new attachment
                                String hostId = compositeKey.substring(0, idLen);
                                String atmntKey = compositeKey.substring(idLen, compKeyLen);
                                String clientId = (String)Appdata.get(getApplicationContext(), hostId, null);
                                if(clientId != null) {
                                    byte[] mimeLenBytes = new byte[2];
                                    System.arraycopy(split, 4+compKeyLen, mimeLenBytes, 0, 2);
                                    int mimeLen = Integer.parseInt(new String(mimeLenBytes));

                                    byte[] mimeBytes = new byte[mimeLen];
                                    System.arraycopy(split, 6+compKeyLen, mimeBytes, 0, mimeLen);
                                    String mime = new String(mimeBytes);
                                    String fileExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                                    String contentLocation = "media_" + atmntKey + "." + fileExt;
                                    Log.d("CL", "Attachment "+atmntKey+"'s cl: "+contentLocation);

                                    Uri partTableUri = Uri.parse("content://mms/" + clientId + "/part");
                                    ContentValues attachmentVals = new ContentValues();
                                    attachmentVals.put("ct", mime);
                                    attachmentVals.put("cl", contentLocation);
                                    attachmentVals.put("name", contentLocation);
                                    Uri partUri = getContentResolver().insert(partTableUri, attachmentVals);
                                    btClientThread.mmsSyncAtmntTable.put(compositeKey, partUri);
                                    btClientThread.mmsSyncAb.write(split, 6+compKeyLen+mimeLen,
                                            split.length-(6+compKeyLen+mimeLen));

                                }else{
                                    Log.e("MMS_SYNC", "Could not get client-side ID for host MMS "+hostId+"!");
                                }

                            }
                            break;
                        }
                    }
                    break;
                }

                case PayloadIDs.MMS_HEADER: { //Incoming MMS metadata
                    String str = new String(split);
                    int textOnly = Integer.parseInt(str.substring(1, 2));
                    int attachmentCount = Integer.parseInt(str.substring(2, 4));
                    int dateLen = Integer.parseInt(str.substring(4, 6));
                    long date = Long.parseLong(str.substring(6, 6+dateLen));
                    int numberLen = Integer.parseInt(str.substring(6+dateLen, 8+dateLen));
                    String number = str.substring(8+dateLen, 8+dateLen+numberLen);
                    String content = str.substring(8+dateLen+numberLen, str.length());

                    MmsContainer mmsObj = btClientThread.getLatestMms();
                    mmsObj.address = number;
                    mmsObj.text = content;
                    mmsObj.date = date;
                    mmsObj.textOnly = (textOnly == 1);
                    mmsObj.attachmentCount = attachmentCount;
                    btClientThread.setLatestMms(mmsObj);
                    break;
                }

                case PayloadIDs.MMS_PART_LAYOUT: { //Incoming MMS SMIL section
                    String str = new String(split);
                    String smilJson = str.substring(1, str.length());
                    MmsContainer mmsObj = btClientThread.getLatestMms();
                    SmilObject smil = new SmilObject();
                    smil.parseJSON(smilJson);
                    mmsObj.smil = smil;
                    btClientThread.setLatestMms(mmsObj);
                    break;
                }

                case PayloadIDs.MMS_PART_ATTACHMENT: { //Incoming bytes for MMS attachment part
                    byte[] keyBytes = new byte[2];
                    System.arraycopy(split, 1, keyBytes, 0, 2);
                    String attachmentKey = new String(keyBytes);

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
                        long fnDate = System.currentTimeMillis();

                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmSSSS");
                        String fileExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                        String filename = sdf.format(fnDate) + "_" + attachmentKey + "." + fileExt;
                        File attachmentFile = null;
                        try{
                            attachmentFile = MmsIO.newMmsMediaFile(filename);
                        }catch(IOException e){
                            Log.e("MMS_IN", "Could not create new attachment file!", e);
                        }
                        if(attachmentFile != null){
                            btClientThread.newMmsAb.openFileOut(attachmentFile);
                            btClientThread.newMmsAb.write(split,
                                    7 + mimeLen + finSeqLen, contentSize);
                            mmsObj.attachments.put(attachmentKey, attachmentFile);
                            mmsObj.mimes.put(attachmentKey, mime);
                            mmsObj.finishingSequences.put(attachmentKey, finishingSequence);
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
                            btClientThread.newMmsAb.write(split, 3, contentSize);
                            btClientThread.newMmsAb.closeFileOut();
                            btClientThread.setLatestMms(mmsObj);
                            Log.d("BT_IN", "Finished MMS attachment with ID " + attachmentKey);

                            if(mmsObj.isFilledForDecoding()){
                                //MMS has been successfully received
                                handleNewMMS(btClientThread.getLatestMms());
                                btClientThread.clearLatestMms();
                            }

                        }else{
                            //this split contains attachment content and typical encoding data
                            int contentSize = split.length - 3;
                            btClientThread.newMmsAb.write(split, 3, contentSize);
                        }
                    }
                    break;
                }

                case PayloadIDs.TIMESTAMP_UPDATE: { //sms/mms sent callback
                    char targetDb = (char)split[1];
                    String str = new String(split);
                    int idLen = Integer.parseInt(str.substring(2, 4));
                    String msgId = str.substring(4, 4+idLen);
                    long newDate = Long.parseLong(str.substring(4+idLen, str.length()));

                    ContentValues values = new ContentValues();
                    values.put("date", newDate);

                    if(targetDb == PayloadIDs.SMS_MESSAGE){
                        //update content://sms
                        values.put("type", 2);
                        String[] idArgs = {msgId};
                        getContentResolver().update(
                                Uri.parse("content://sms"),
                                values,
                                "_id = ?",
                                idArgs
                        );
                    }else{
                        //update content://mms
                        values.put("msg_box", 2);
                        String[] idArgs = {msgId};
                        getContentResolver().update(
                                Uri.parse("content://mms"),
                                values,
                                "_id = ?",
                                idArgs
                        );
                    }
                    break;
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
        updateBtDeviceText(null, -1);
    }

    byte[] encodeSMS(String id, String number, String content){
        String idLen = twoDigitNumber(id.length());
        String numLen = twoDigitNumber(number.length());
        return (PayloadIDs.SMS_MESSAGE + idLen + id + numLen + number + content +
                    PayloadIDs.DELIMITER_STRING).getBytes();
    }

    void handleNewSMS(String smsAuthor, String smsContent, long date){
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
        insertValues.put("date", date);
        insertValues.put("read", 0); //false
        insertValues.put("type", 1); //1 = inbox/received
        getContentResolver().insert(Uri.parse("content://sms/inbox"), insertValues);

        String contactName = getContactName(smsAuthor);
        String threadId = String.valueOf(getThreadId(smsAuthor));
        Notifications.sendMessageNotif(getApplicationContext(), contactName, smsContent, smsAuthor);
    }

    void handleNewMMS(MmsContainer mms){
        Log.i("MMS_IN", String.format("MMS Content:\n" +
                        "Text (if any): \"%s\"\n" +
                        "Address: %s\n" +
                        "SMIL?: %s\n" +
                        "Attachment Count: %d", mms.text, mms.address,
                (mms.smil != null), mms.attachmentCount));

        //get the contact's thread ID
        long threadID = getThreadId(mms.address);
        if(threadID == -1){
            //could not get thread ID
            Log.e("MMS_HANDLER", "Could not get thread_id for address " + mms.address);
            return;
        }

        //insert data into content://mms to get the MMS's ID
        ContentValues mmsValues = new ContentValues();
        mmsValues.put("date", mms.date);
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

        Uri partTableUri = Uri.parse("content://mms/" + mmsId + "/part");
        if(!mms.textOnly) {
            //edit the SMIL and save each attachment to content://mms/part
            Enumeration<String> keyEnum = mms.attachments.keys();
            while (keyEnum.hasMoreElements()) {
                String key = keyEnum.nextElement();
                String contentLocation = mms.getNewAttachmentCl(key);
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
                byte[] atmntBytes = MmsIO.readFromFile(atmntFile);
                if (atmntBytes == null) {
                    Log.e("MMS_HANDLER", "Could not read attachment from file!");
                    return;
                }
                MmsIO.writeToUri(this, partUri, atmntBytes);

                //update smil to replace attachment key with content location
                mms.smil.remapMediaSource(key, contentLocation);

                //delete media from SD card if user has requested so in settings
                //or if the file was saved to internal storage due to no SD card
                boolean isFileInternal;
                try{
                    isFileInternal = atmntFile.getCanonicalPath().startsWith(
                            internalStorage.getCanonicalPath());
                }catch(IOException e){
                    Log.e("MMS_HANDLER", "Could not check whether file is internal!", e);
                    isFileInternal = !Environment.getExternalStorageState()
                            .equals(Environment.MEDIA_MOUNTED); //fallback in case path check fails
                }
                boolean saveMedia = (boolean) Appdata.get(this, Appdata.Keys.SAVE_MEDIA, true);
                if(!saveMedia || isFileInternal){
                    boolean deleteSuccess = atmntFile.delete();
                    if(!deleteSuccess){
                        Log.e("MMS_HANDLER", "Could not delete "+atmntFile.getAbsolutePath()+"!");
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

            mms.smil.remapMediaSource("text", contentLocation);
        }

        //save smil with its sources remapped to match table cl fields
        String smilXml = mms.smil.toXml();
        Log.d("NEW_SMIL", "SMIL: "+smilXml);
        ContentValues smilValues = new ContentValues();
        smilValues.put("text", smilXml);
        smilValues.put("ct", "application/smil");
        smilValues.put("cl", "smil.xml");
        smilValues.put("chset", 106);
        getContentResolver().insert(partTableUri, smilValues);

        //send notification for new MMS
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
        Notifications.sendMessageNotif(getApplicationContext(), contactName,
                notifBody, mms.address);
    }

    void handleSmsDbUpdate(String number, String content, int msgType, long date, int read){
        Uri uri = Uri.parse("content://sms/");
        String[] columns = {"_id"};
        String[] selectionArgs = {number, content, String.valueOf(msgType),
                String.valueOf(date-10000), String.valueOf(date+10000)}; //10 second tolerance

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

    public void handleMmsDbUpdate(String hostId, String address, long date,
                                  int atmntCount, int msgBox, int read){
        boolean rootMatch = false;
        boolean addrMatch = false;
        boolean partMatch = false;

        Cursor rootCursor = getContentResolver().query(
                Uri.parse("content://mms"),
                new String[]{"_id"},
                "msg_box = ? AND date BETWEEN ? AND ?",
                new String[]{String.valueOf(msgBox), String.valueOf(date-10),
                        String.valueOf(date+10)}, //10 second tolerance for date, just in case
                null
        );

        if(rootCursor != null){
            if(rootCursor.moveToFirst()){
                rootMatch = true;
                do {
                    String matchId = rootCursor.getString(rootCursor.getColumnIndex("_id"));
                    Cursor addrMatchCursor = getContentResolver().query(
                            Uri.parse("content://mms/"+matchId+"/addr"),
                            new String[]{"address"},
                            "address = ?",
                            new String[]{address},
                            null
                    );
                    if(addrMatchCursor != null){
                        addrMatch = addrMatchCursor.moveToFirst();
                        addrMatchCursor.close();
                    }
                    Cursor partMatchCursor = getContentResolver().query(
                            Uri.parse("content://mms/"+matchId+"/part"),
                            new String[]{"_id"},
                            "",
                            null,
                            null
                    );
                    if(partMatchCursor != null){
                        partMatch = (partMatchCursor.getCount() == atmntCount);
                        partMatchCursor.close();
                    }
                } while(rootCursor.moveToNext() && !(rootMatch && addrMatch && partMatch));
            }
            rootCursor.close();
        }

        if(!(rootMatch && addrMatch && partMatch)){
            //message is not in client db, so add root and addr info and request part info
            long threadId = getThreadId(address);
            int m_type = 128; //m_type for outgoing messages
            if(msgBox == 1){
                m_type = 132; //m_type for incoming messages
            }

            ContentValues rootValues = new ContentValues();
            rootValues.put("date", date);
            rootValues.put("msg_box", msgBox);
            rootValues.put("m_type", m_type);
            rootValues.put("read", read);
            rootValues.put("thread_id", threadId);
            rootValues.put("ct_t", "application/vnd.wap.multipart.related");
            rootValues.put("sub", "Multimedia Message"); //for message previews
            rootValues.put("sub_cs", 106);
            Uri mmsUri = getContentResolver().insert(Uri.parse("content://mms"), rootValues);
            String clientId = mmsUri.getLastPathSegment();

            //insert address segment
            Uri addrUri = Uri.parse("content://mms/"+clientId+"/addr");
            int addrType = 151;
            if(msgBox == 1){
                addrType = 137;
            }

            ContentValues addrValues = new ContentValues();
            addrValues.put("address", address);
            addrValues.put("type", addrType);
            addrValues.put("charset", 106); //Default which is UTF-8
            getContentResolver().insert(addrUri, addrValues);

            //link host and client IDs in appdata so part records can be added later
            Appdata.put(getApplicationContext(), hostId, clientId);

            //request part records for this MMS
            byte[] fullUpdateReq = (PayloadIDs.SYNC_REQUEST +
                    (PayloadIDs.MMS_PART_ATTACHMENT + hostId + PayloadIDs.DELIMITER_STRING)).getBytes();
            BtConnectionManager.getInstance().write(fullUpdateReq);
        }
    }

    public static void sendSyncRequest(long fromDate){
        String syncInfoBt = PayloadIDs.SYNC_REQUEST + (PayloadIDs.TIMESTAMP_UPDATE +
                String.valueOf(fromDate) + PayloadIDs.DELIMITER_STRING);
        Log.d("SYNC_REQ", String.format("Sending %s...", syncInfoBt));
        BtConnectionManager.getInstance().write(syncInfoBt.getBytes());
    }
    public static void sendSyncRequest(int days){
        long fromDate = System.currentTimeMillis() - (86400000 * days);
        String syncInfoBt = PayloadIDs.SYNC_REQUEST + (PayloadIDs.TIMESTAMP_UPDATE +
                String.valueOf(fromDate) + PayloadIDs.DELIMITER_STRING);
        Log.d("SYNC_REQ", String.format("Sending %s...", syncInfoBt));
        BtConnectionManager.getInstance().write(syncInfoBt.getBytes());
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
        if(deviceName == null){
            connDeviceText.setText("No Device Connected!");
        }else {
            connDeviceText.setText(getString(R.string.connDeviceText, String.format(
                    "Connected to %s", deviceName)));
        }

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

        //save the internal storage directory
        internalStorage = getFilesDir();

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

        settingsBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                Intent intent = new Intent(getApplicationContext(), DebugActivity.class);
                startActivity(intent);
                return true;
            }
        });


        outboxThread = new HandlerThread("OutboxObservers");
        outboxThread.start();
        Handler handler = new Handler(outboxThread.getLooper());
        smsOutboxObserver = new SmsOutboxObserver(handler);
        mmsOutboxObserver = new MmsOutboxObserver(handler);
        getContentResolver().registerContentObserver(smsOutboxObserver.smsUri, true, smsOutboxObserver);
        getContentResolver().registerContentObserver(Uri.parse("content://mms-sms/conversations")
                , true, mmsOutboxObserver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(smsOutboxObserver);
        getContentResolver().unregisterContentObserver(mmsOutboxObserver);
        outboxThread.quit();
    }
}
