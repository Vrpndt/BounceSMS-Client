package com.vrpndt.bouncesmsclient;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Enumeration;

import static com.vrpndt.bouncesmsclient.MainActivity.twoDigitNumber;
import com.vrpndt.bouncesmsclient.MainActivity.SmilObject;
import com.vrpndt.bouncesmsclient.util.MmsIO;
import com.vrpndt.bouncesmsclient.util.Appdata;

public class DebugActivity extends AppCompatActivity {

    private Button loadMmsBtn;
    private EditText mmsIdIn;
    private TextView mmsIdOut;
    private TextView mmsClOut;
    private TextView mmsMsgboxOut;
    private TextView mmsAddressOut;
    private TextView mmsDateOut;
    private TextView mmsTextOut;
    private TextView mmsAtmntCountOut;
    private ImageView mmsAtmntOut;
    private TextView mmsSmilOut;

    private EditText delSmsIdIn;
    private EditText delMmsIdIn;
    private Button deleteSmsBtn;
    private Button deleteMmsBtn;



    MainActivity.MmsContainer getMmsById(Context context, String mmsId){
        MainActivity.MmsContainer mms = new MainActivity.MmsContainer();
        Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://mms/"+mmsId),
                new String[]{"date", "msg_box"},
                "",
                null,
                null
        );
        if(cursor != null){
            if(cursor.moveToFirst()){
                long date = cursor.getLong(cursor.getColumnIndex("date"));
                int msg_box = cursor.getInt(cursor.getColumnIndex("msg_box"));
                mms.date = date;
                mms.msgBox = msg_box;
            }
        }

        cursor = context.getContentResolver().query(
                Uri.parse("content://mms/"+mmsId+"/addr"),
                new String[]{"address"},
                "",
                null,
                null
        );
        if(cursor != null){
            if(cursor.moveToFirst()){
                mms.address = cursor.getString(cursor.getColumnIndex("address"));
            }
        }

        cursor = context.getContentResolver().query(
                Uri.parse("content://mms/"+mmsId+"/part"),
                new String[]{"_id", "text", "ct", "cl"},
                "",
                null,
                null
        );
        if(cursor != null){
            if(cursor.moveToFirst()){
                do{
                    String contentType = cursor.getString(cursor.getColumnIndex("ct"));
                    switch(contentType){
                        case "text/plain": {
                            //text content
                            String text = cursor.getString(cursor.getColumnIndex("text"));
                            mms.text = text;
                            break;
                        }
                        case "application/smil": {
                            //smil xml
                            String smilXml = cursor.getString(cursor.getColumnIndex("text"));
                            InputStream smilStream = new ByteArrayInputStream(smilXml.getBytes());
                            MainActivity.SmilObject smil = new SmilObject();
                            smil.parseXml(smilStream);
                            mms.smil = smil;
                            break;
                        }
                        default: {
                            //attachment content
                            String partId = cursor.getString(cursor.getColumnIndex("_id"));
                            String filename = cursor.getString(cursor.getColumnIndex("cl"));
                            String fileExt = MimeTypeMap.getSingleton()
                                    .getExtensionFromMimeType(contentType);
                            Uri partUri = Uri.parse("content://mms/part/"+partId);

                            //save attachment to sd card and store in hashmap as File
                            byte[] attachmentBytes = MmsIO.readFromUri(
                                    getApplicationContext(), partUri);
                            File mediaFile = null;
                            try{
                                mediaFile = MmsIO.newMmsMediaFile(String.format("outgoing.%d.%s",
                                        System.currentTimeMillis(), fileExt));
                            }catch(IOException e){
                                Log.e("ON_MMS_PART", "Could not create new media file!", e);
                            }
                            if(attachmentBytes != null && mediaFile != null){
                                MmsIO.writeToFile(attachmentBytes, mediaFile);
                                String attachmentKey = twoDigitNumber(mms.attachmentCount);
                                mms.attachments.put(attachmentKey, mediaFile);
                                mms.mimes.put(attachmentKey, contentType);
                                mms.filenameMap.put(filename, attachmentKey);
                                mms.attachmentCount += 1;
                                if(mms.textOnly){
                                    mms.textOnly = false;
                                }
                            }
                            break;
                        }

                    }
                } while(cursor.moveToNext());
            }
        }
        return mms;
    }

    void displayMmsDebug(MainActivity.MmsContainer mms, String mmsId){
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy @ HH:mm");
        mmsIdOut.setText("ID: "+mmsId);
        mmsMsgboxOut.setText("Message Box: "+Integer.toString(mms.msgBox));
        mmsAddressOut.setText("Address: "+mms.address);
        mmsDateOut.setText("Date: "+sdf.format(mms.date*1000));
        mmsTextOut.setText("Text: "+mms.text);
        mmsAtmntCountOut.setText("Attachment Count: "+Integer.toString(mms.attachmentCount));
        mmsSmilOut.setText("SMIL:\n"+mms.smil.toXml());

        if(mms.attachmentCount > 0){
            mmsClOut.setText("Filename Example: "+mms.filenameMap.keys().nextElement());
            File imgFile = mms.attachments.get("00");
            byte[] imgBytes = MmsIO.readFromFile(imgFile);
            Bitmap image = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
            mmsAtmntOut.setImageBitmap(image);

        }else{
            mmsClOut.setText("Filename Example: N/A");
        }
        //delete temp files from before
        Collection<File> atmntEnum = mms.attachments.values();
        for(File file: atmntEnum){
            file.delete();
        }
        mms.attachments.clear();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        loadMmsBtn = (Button) findViewById(R.id.loadMmsBtn);
        mmsIdIn = (EditText) findViewById(R.id.mmsIdIn);
        mmsIdOut = (TextView) findViewById(R.id.mmsIdOut);
        mmsClOut = (TextView) findViewById(R.id.mmsClOut);
        mmsMsgboxOut = (TextView) findViewById(R.id.mmsMsgboxOut);
        mmsAddressOut = (TextView) findViewById(R.id.mmsAddressOut);
        mmsDateOut = (TextView) findViewById(R.id.mmsDateOut);
        mmsTextOut = (TextView) findViewById(R.id.mmsTextOut);
        mmsAtmntCountOut = (TextView) findViewById(R.id.mmsAtmntCountOut);
        mmsAtmntOut = (ImageView) findViewById(R.id.mmsAtmntOut);
        mmsSmilOut = (TextView) findViewById(R.id.mmsSmilOut);

        delSmsIdIn = (EditText) findViewById(R.id.delSmsIdIn);
        delMmsIdIn = (EditText) findViewById(R.id.delMmsIdIn);
        deleteSmsBtn = (Button) findViewById(R.id.deleteSmsBtn);
        deleteMmsBtn = (Button) findViewById(R.id.deleteMmsBtn);

        loadMmsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String mmsId = mmsIdIn.getText().toString();
                if(mmsId.equals("")){
                    //load latest
                    Cursor cursor = getApplicationContext().getContentResolver().query(
                            Uri.parse("content://mms"),
                            new String[]{"_id"},
                            "",
                            null,
                            "_id DESC"
                    );
                    if(cursor != null){
                        if(cursor.moveToFirst()){
                            mmsId = cursor.getString(cursor.getColumnIndex("_id"));
                        }
                    }
                    MainActivity.MmsContainer mms = getMmsById(getApplicationContext(), mmsId);
                    displayMmsDebug(mms, mmsId);

                }else{
                    MainActivity.MmsContainer mms = getMmsById(getApplicationContext(), mmsId);
                    displayMmsDebug(mms, mmsId);
                }
            }
        });

        deleteSmsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String smsId = delSmsIdIn.getText().toString();
                if(smsId.equals("")){
                    Cursor cursor = getContentResolver().query(
                            Uri.parse("content://sms"),
                            new String[]{"_id"},
                            "",
                            null,
                            "_id DESC"
                    );
                    if(cursor != null){
                        if(cursor.moveToFirst()){
                            smsId = cursor.getString(cursor.getColumnIndex("_id"));
                        }
                        cursor.close();
                    }

                }

                if(!smsId.equals("")){
                    Uri smsUri = Uri.parse("content://sms/" + smsId);
                    getContentResolver().delete(smsUri, "", null);
                }
            }
        });

        deleteMmsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String mmsId = delMmsIdIn.getText().toString();
                if(mmsId.equals("")){
                    Cursor cursor = getContentResolver().query(
                            Uri.parse("content://mms"),
                            new String[]{"_id"},
                            "",
                            null,
                            "_id DESC"
                    );
                    if(cursor != null){
                        if(cursor.moveToFirst()){
                            mmsId = cursor.getString(cursor.getColumnIndex("_id"));
                        }
                        cursor.close();
                    }

                }

                if(!mmsId.equals("")){
                    Uri mmsRootUri = Uri.parse("content://mms/" + mmsId);
                    getContentResolver().delete(mmsRootUri, "", null);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        finish();
    }
}
