package com.vrpndt.bouncesmsclient.util;

public final class PayloadIDs{
    public static final char SMS_MESSAGE = 'S';
    public static final char SYNC_REQUEST = 'I';
    public static final char SYNC_UPDATE = 'U';
    public static final char MMS_HEADER = 'M';
    public static final char MMS_PART_ATTACHMENT = 'A';
    public static final char MMS_PART_LAYOUT = 'L';
    public static final char MMS_UPDATE_TEXT = 'N';
    public static final char TIMESTAMP_UPDATE = 'T';
    public static final String DELIMITER_STRING = String.valueOf((char)0x1e);
    public static final byte DELIMITER_BYTE = (byte)0x1e;
}
