package com.vrpndt.bouncesmsclient.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.vrpndt.bouncesmsclient.MainActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MmsIO{
    public static File newMmsMediaFile(String filename) throws IOException {
        String sdState = Environment.getExternalStorageState();
        if(sdState.equals(Environment.MEDIA_MOUNTED)){
            //SD Card is mounted and can be read/written to
            File sdRoot = Environment.getExternalStorageDirectory();
            File mediaDir = new File(sdRoot, "mms-media");
            if(!mediaDir.exists()){
                mediaDir.mkdirs();
            }
            return new File(mediaDir, filename);

        }else{
            Log.i("NEW_MMS_FILE", "Could not access SD card! Saving to internal storage...");
            return new File(MainActivity.internalStorage, filename);
        }
    }

    public static void writeToFile(byte[] data, File file){
        FileOutputStream fos = null;
        try{
            fos = new FileOutputStream(file);
        }catch(FileNotFoundException e){
            Log.e("WRITE_FILE", "Could not open FileOutputStream to file at " +
                    file.getAbsolutePath(), e);
        }

        if(fos != null){
            try{
                fos.write(data);
                fos.flush();
                fos.close();

            }catch(IOException e){
                Log.e("WRITE_FILE", "Could not write to file at" + file.getAbsolutePath(), e);

            }finally{
                try{
                    fos.close();
                }catch(IOException e){
                    Log.e("WRITE_FILE", "Could not close File Output Stream!", e);
                }
            }
        }
    }

    public static byte[] readFromFile(File file){
        FileInputStream attachmentIs;
        try{
            attachmentIs = new FileInputStream(file);
        }catch(FileNotFoundException e){
            Log.e("READ_FILE", "Could not open Input Stream to File at " +
                    file.getAbsolutePath(), e);
            return null;
        }

        byte[] atmntBytes = new byte[(int)file.length()];
        try{
            attachmentIs.read(atmntBytes);
            attachmentIs.close();
        }catch(IOException e){
            Log.e("READ_FILE", "Could not read from Input Stream to File at " +
                    file.getAbsolutePath(), e);
            return null;
        }finally{
            try{
                attachmentIs.close();
            }catch(IOException e){
                Log.e("READ_FILE", "Could not close FileInputStream!", e);
            }
        }
        return atmntBytes;
    }

    public static byte[] readFromUri(Context context, Uri uri){
        InputStream is = null;
        try{
            is = context.getContentResolver().openInputStream(uri);
        }catch(FileNotFoundException e){
            Log.e("READ_URI", "Could not open Input Stream to URI " + uri.toString(), e);
        }

        if(is != null){
            byte[] streamBuffer = new byte[1024*350]; //350KB buffer
            try{
                int readBytes = is.read(streamBuffer);
                is.close();
                byte[] readContents = new byte[readBytes];
                System.arraycopy(streamBuffer, 0, readContents, 0, readBytes);
                return readContents;
            }catch(IOException e){
                Log.e("READ_URI", "Cold not read from URI " + uri.toString(), e);
            }finally{
                try{
                    is.close();
                }catch(IOException e){
                    Log.e("READ_URI", "Could not close Input Stream!", e);
                }
            }
        }
        return null;
    }

    public static void writeToUri(Context context, Uri uri, byte[] data){
        OutputStream partOs;
        try{
            partOs = context.getContentResolver().openOutputStream(uri);
        }catch(FileNotFoundException e){
            Log.e("WRITE_URI", "Could not open Output Stream to URI " + uri.toString(), e);
            return;
        }

        try{
            if(partOs != null){
                partOs.write(data);
                partOs.flush();
                partOs.close();
            }
        }catch(IOException e){
            Log.e("WRITE_URI", "Could not write to OutputStream at URI " + uri.toString(), e);
            return;
        }finally{
            if (partOs != null){
                try{
                    partOs.close();
                }catch(IOException e){
                    Log.e("WRITE_URI", "Could not close OutputStream!", e);
                }
            }
        }
    }

    public static boolean readAndEncodeAtmnt(Context context, Uri uri,
                                      String atmntKey, String mime, File outputFile){
        BufferedInputStream is = null;
        BufferedOutputStream fos = null;
        try{
            is = new BufferedInputStream(context.getContentResolver().openInputStream(uri));
            fos = new BufferedOutputStream(new FileOutputStream(outputFile));
        }catch(FileNotFoundException e){
            Log.e("READ_URI", "Could not open Input Stream or File Output Stream!", e);
        }

        if(is != null && fos != null){
            try{
                String mimeLen = MainActivity.twoDigitNumber(mime.length());
                fos.write(PayloadIDs.MMS_PART_ATTACHMENT);
                fos.write(atmntKey.getBytes());
                fos.write(mimeLen.getBytes());
                fos.write(mime.getBytes());
                int nextByte = is.read();
                while(nextByte != -1) {
                    fos.write(nextByte);
                    if (nextByte == PayloadIDs.DELIMITER_BYTE) {
                        fos.write(PayloadIDs.MMS_PART_ATTACHMENT);
                        fos.write(atmntKey.getBytes());
                    }
                    nextByte = is.read();
                }
                //once finished encoding, write "END{key}{delimiter}" to signify end of file
                //statistically improbable that this byte segment would occur naturally
                //also what else am I meant to do
                fos.write(("END"+atmntKey+PayloadIDs.DELIMITER_STRING).getBytes());
                fos.flush();
                fos.close();
                return true;
            }catch(IOException e){
                Log.e("READ_URI", "Could not read from URI or write to File!", e);
            }finally{
                try{
                    is.close();
                    fos.close();
                }catch(IOException e){
                    Log.e("READ_URI", "Could not close Input Stream or File Output Stream!", e);
                }
            }
        }
        return false;
    }
}
