/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.transaction;

import com.android.mms.MmsConfig;
import com.android.mms.ui.MessageUtils;
import com.android.mms.util.DownloadManager;
import com.android.mms.util.Recycler;
import com.google.android.framework.mms.ContentType;
import com.google.android.framework.mms.MmsException;
import com.google.android.framework.mms.pdu.AcknowledgeInd;
import com.google.android.framework.mms.pdu.CharacterSets;
import com.google.android.framework.mms.pdu.PduBody;
import com.google.android.framework.mms.pdu.PduComposer;
import com.google.android.framework.mms.pdu.PduHeaders;
import com.google.android.framework.mms.pdu.PduParser;
import com.google.android.framework.mms.pdu.PduPart;
import com.google.android.framework.mms.pdu.PduPersister;
import com.google.android.framework.mms.pdu.RetrieveConf;
import com.google.android.framework.mms.pdu.EncodedStringValue;
import android.database.sqlite.SqliteWrapper;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Mms.Inbox;
import android.util.Config;
import android.util.Log;

import java.io.IOException;

/**
 * The RetrieveTransaction is responsible for retrieving multimedia
 * messages (M-Retrieve.conf) from the MMSC server.  It:
 *
 * <ul>
 * <li>Sends a GET request to the MMSC server.
 * <li>Retrieves the binary M-Retrieve.conf data and parses it.
 * <li>Persists the retrieve multimedia message.
 * <li>Determines whether an acknowledgement is required.
 * <li>Creates appropriate M-Acknowledge.ind and sends it to MMSC server.
 * <li>Notifies the TransactionService about succesful completion.
 * </ul>
 */
public class RetrieveTransaction extends Transaction implements Runnable {
    private static final String TAG = "RetrieveTransaction";
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG ? Config.LOGD : Config.LOGV;

    private final Uri mUri;
    private final String mContentLocation;
    private boolean mLocked;

    static final String[] PROJECTION = new String[] {
        Mms.CONTENT_LOCATION,
        Mms.LOCKED
    };

    // The indexes of the columns which must be consistent with above PROJECTION.
    static final int COLUMN_CONTENT_LOCATION      = 0;
    static final int COLUMN_LOCKED                = 1;

    public RetrieveTransaction(Context context, int serviceId,
            TransactionSettings connectionSettings, String uri)
            throws MmsException {
        super(context, serviceId, connectionSettings);

        if (uri.startsWith("content://")) {
            mUri = Uri.parse(uri); // The Uri of the M-Notification.ind
            mId = mContentLocation = getContentLocation(context, mUri);
            if (LOCAL_LOGV) {
                Log.v(TAG, "X-Mms-Content-Location: " + mContentLocation);
            }
        } else {
            throw new IllegalArgumentException(
                    "Initializing from X-Mms-Content-Location is abandoned!");
        }

        // Attach the transaction to the instance of RetryScheduler.
        attach(RetryScheduler.getInstance(context));
    }

    private String getContentLocation(Context context, Uri uri)
            throws MmsException {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                            uri, PROJECTION, null, null, null);
        mLocked = false;

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    // Get the locked flag from the M-Notification.ind so it can be transferred
                    // to the real message after the download.
                    mLocked = cursor.getInt(COLUMN_LOCKED) == 1;
                    return cursor.getString(COLUMN_CONTENT_LOCATION);
                }
            } finally {
                cursor.close();
            }
        }

        throw new MmsException("Cannot get X-Mms-Content-Location from: " + uri);
    }

    /*
     * (non-Javadoc)
     * @see com.android.mms.transaction.Transaction#process()
     */
    @Override
    public void process() {
        new Thread(this).start();
    }

    private static int toUnicodeSoftBankGlyph(String glyphStr) throws java.io.UnsupportedEncodingException {
        byte[] byteArray = glyphStr.getBytes("Windows-31J");
        if (byteArray.length < 2) return -1;
        int firstByte = byteArray[0] & 0xFF;
        int secondByte = byteArray[1] & 0xFF;
        int base;
        if (firstByte == 0xF7) {
            if (secondByte < 0xA0) {
                base = 0xE100;
            } else {
                base = 0xE200;
            }
        } else if (firstByte == 0xF9) {
            if (secondByte < 0xA0) {
                base = 0xE000;
            } else {
                base = 0xE300;
            }
        } else if (firstByte == 0xFB) {
            if (secondByte < 0xA0) {
                base = 0xE400;
            } else {
                base = 0xE500;
            }
        } else {
            return -1;
        }

        int uniNum;
        if (secondByte < 0x80) {
            uniNum = base + (secondByte - 0x40);
        } else if (secondByte > 0xA0) {
            uniNum = base + (secondByte - 0xA0);
        } else {
            uniNum = base + (secondByte - 0x41);
        }
        return uniNum;
    }

    public void run() {
        try {
            // Change the downloading state of the M-Notification.ind.
            DownloadManager.getInstance().markState(
                    mUri, DownloadManager.STATE_DOWNLOADING);

            // Send GET request to MMSC and retrieve the response data.
            byte[] resp = getPdu(mContentLocation);

            // Parse M-Retrieve.conf
            RetrieveConf retrieveConf = (RetrieveConf) new PduParser(resp).parse();
            if (null == retrieveConf) {
                throw new MmsException("Invalid M-Retrieve.conf PDU.");
            }

            Uri msgUri = null;
            if (isDuplicateMessage(mContext, retrieveConf)) {
                // Mark this transaction as failed to prevent duplicate
                // notification to user.
                mTransactionState.setState(TransactionState.FAILED);
                mTransactionState.setContentUri(mUri);
            } else {
                // Store M-Retrieve.conf into Inbox
                PduPersister persister = PduPersister.getPduPersister(mContext);
                PduBody body = retrieveConf.getBody();
                if (body != null) {
                        int partsNum = body.getPartsNum();
                        for (int i = 0; i < partsNum; i++) {
                            PduPart part = body.getPart(i);
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "Content-Type: " + new String(part.getContentType()) + "; Charset: " + part.getCharset());
                            }
                            if (part.getCharset() == CharacterSets.SHIFT_JIS ||
                                (part.getCharset() == 0 && ContentType.TEXT_HTML.equals(new String(part.getContentType())))) {
                                 String text;
                                 StringBuilder sb = new StringBuilder();
                                 text = new String(part.getData(),
                                     CharacterSets.getMimeName(CharacterSets.SHIFT_JIS));
                                 for (String s : text.split("")) {
                                     int cp = toUnicodeSoftBankGlyph(s);
                                     if (cp > 0) sb.appendCodePoint(cp);
                                     else sb.append(s);
                                 }
                                 part.setData(sb.toString().getBytes());
                                 part.setCharset(CharacterSets.UTF_8);
                            } else if (part.getCharset() == 39) {
                                 String text = new String(part.getData(), "iso-2022-jp");
                                 part.setData(text.getBytes());
                                 part.setCharset(CharacterSets.UTF_8);
                            } else if (ContentType.TEXT_HTML.equals(new String(part.getContentType())) &&
                                     part.getCharset() != CharacterSets.UTF_8) {
                                 String text = new String(part.getData(),
                                     CharacterSets.getMimeName(part.getCharset()));
                                 part.setData(text.getBytes());
                                 part.setCharset(CharacterSets.UTF_8);
                            }
                        }
                }

                msgUri = persister.persist(retrieveConf, Inbox.CONTENT_URI);

                // The M-Retrieve.conf has been successfully downloaded.
                mTransactionState.setState(TransactionState.SUCCESS);
                mTransactionState.setContentUri(msgUri);
                // Remember the location the message was downloaded from.
                // Since it's not critical, it won't fail the transaction.
                // Copy over the locked flag from the M-Notification.ind in case
                // the user locked the message before activating the download.
                updateContentLocation(mContext, msgUri, mContentLocation, mLocked);
            }

            // Delete the corresponding M-Notification.ind.
            SqliteWrapper.delete(mContext, mContext.getContentResolver(),
                                 mUri, null, null);

            if (msgUri != null) {
                // Have to delete messages over limit *after* the delete above. Otherwise,
                // it would be counted as part of the total.
                Recycler.getMmsRecycler().deleteOldMessagesInSameThreadAsMessage(mContext, msgUri);
            }

            // Send ACK to the Proxy-Relay to indicate we have fetched the
            // MM successfully.
            // Don't mark the transaction as failed if we failed to send it.
            sendAcknowledgeInd(retrieveConf);
        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
        } finally {
            if (mTransactionState.getState() != TransactionState.SUCCESS) {
                mTransactionState.setState(TransactionState.FAILED);
                mTransactionState.setContentUri(mUri);
                Log.e(TAG, "Retrieval failed.");
            }
            notifyObservers();
        }
    }

    private static boolean isDuplicateMessage(Context context, RetrieveConf rc) {
        byte[] rawMessageId = rc.getMessageId();
        if (rawMessageId != null) {
            String messageId = new String(rawMessageId);
            String selection = "(" + Mms.MESSAGE_ID + " = ? AND "
                                   + Mms.MESSAGE_TYPE + " = ?)";
            String[] selectionArgs = new String[] { messageId,
                    String.valueOf(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) };
            Cursor cursor = SqliteWrapper.query(
                    context, context.getContentResolver(),
                    Mms.CONTENT_URI, new String[] { Mms._ID },
                    selection, selectionArgs, null);
            if (cursor != null) {
                try {
                    if (cursor.getCount() > 0) {
                        // We already received the same message before.
                        return true;
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        return false;
    }

    private void sendAcknowledgeInd(RetrieveConf rc) throws MmsException, IOException {
        // Send M-Acknowledge.ind to MMSC if required.
        // If the Transaction-ID isn't set in the M-Retrieve.conf, it means
        // the MMS proxy-relay doesn't require an ACK.
        byte[] tranId = rc.getTransactionId();
        if (tranId != null) {
            // Create M-Acknowledge.ind
            AcknowledgeInd acknowledgeInd = new AcknowledgeInd(
                    PduHeaders.CURRENT_MMS_VERSION, tranId);

            // insert the 'from' address per spec
            String lineNumber = MessageUtils.getLocalNumber();
            acknowledgeInd.setFrom(new EncodedStringValue(lineNumber));

            // Pack M-Acknowledge.ind and send it
            if(MmsConfig.getNotifyWapMMSC()) {
                sendPdu(new PduComposer(mContext, acknowledgeInd).make(), mContentLocation);
            } else {
                sendPdu(new PduComposer(mContext, acknowledgeInd).make());
            }
        }
    }

    private static void updateContentLocation(Context context, Uri uri,
                                              String contentLocation,
                                              boolean locked) {
        ContentValues values = new ContentValues(2);
        values.put(Mms.CONTENT_LOCATION, contentLocation);
        values.put(Mms.LOCKED, locked);     // preserve the state of the M-Notification.ind lock.
        SqliteWrapper.update(context, context.getContentResolver(),
                             uri, values, null, null);
    }

    @Override
    public int getType() {
        return RETRIEVE_TRANSACTION;
    }
}
