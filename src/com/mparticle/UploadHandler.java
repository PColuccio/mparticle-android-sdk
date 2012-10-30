package com.mparticle;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.http.HttpHost;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.http.AndroidHttpClient;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.mparticle.Constants.MessageKey;
import com.mparticle.Constants.MessageType;
import com.mparticle.Constants.UploadStatus;
import com.mparticle.MessageDatabase.MessageTable;
import com.mparticle.MessageDatabase.UploadTable;

/* package-private */ final class UploadHandler extends Handler {

    private static final String TAG = "mParticleAPI";

    private MessageDatabase mDB;
    private Context mContext;
    private String mApiKey;
    private String mSecret;

    private AndroidHttpClient mHttpClient;
    private HttpContext mHttpContext;
    private CookieStore mCookieStore;
    private JSONObject mAppInfo;
    private JSONObject mDeviceInfo;

    public static final int PREPARE_BATCHES = 0;
    public static final int PROCESS_BATCHES = 1;
    public static final int PROCESS_RESULTS = 2;
    public static final int CLEANUP = 3;
    public static final int FETCH_CONFIG = 4;

    public static final String HEADER_APPKEY = "x-mp-appkey";
    public static final String HEADER_SIGNATURE = "x-mp-signature";

    public static int BATCH_SIZE = 10;
    public static String SERVICE_SCHEME = "http";
    public static String SERVICE_HOST = "api.dev.mparticle.com";
    public static String SERVICE_VERSION = "v1";

    private static String mUploadMode = "batch";

    public UploadHandler(Context context, Looper looper, String apiKey, String secret) {
        super(looper);
        mContext = context;
        mApiKey = apiKey;
        mSecret = secret;

        mDB = new MessageDatabase(mContext);
        mHttpClient = AndroidHttpClient.newInstance("mParticleSDK", mContext);
        mHttpContext  = new BasicHttpContext();
        mCookieStore = new PersistentCookieStore(context);
        mHttpContext.setAttribute(ClientContext.COOKIE_STORE, mCookieStore);

        mAppInfo = MParticleAPI.collectAppInfo(context);
        mDeviceInfo = MParticleAPI.collectDeviceInfo(context);
    }

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);
        switch (msg.what) {
        case PREPARE_BATCHES:
            // create uploads records for batches of messages and mark the messages as processed
            prepareBatches();
            // TODO: restore this break - for development only
            // break;
        case PROCESS_BATCHES:
            // post upload messages to server and mark the uploads as processed. store response commands to be processed.
            processBatches();
            break;
        case PROCESS_RESULTS:
            // read responses with upload messages
            // post response to server
            // handle cookies
            // update response process status
            // TODO: processResults();
            break;
        case CLEANUP:
            // delete completed uploads, messages, and sessions
            // TODO: cleanupDatabase();
            break;
        case FETCH_CONFIG:
            // get the application configuration and process it
            fetchConfig();
            break;
        }
    }

    void prepareBatches() {
        try {
            // select messages ready to upload (limited number)
            SQLiteDatabase db = mDB.getWritableDatabase();
            String[] selectionArgs = new String[]{Integer.toString(UploadStatus.BATCH_READY)};
            String selection;
            if (("batch").equals(mUploadMode)) {
                selection = MessageTable.UPLOAD_STATUS + "=?";
            } else {
                selection = MessageTable.UPLOAD_STATUS + "<=?";
            }
            String[] selectionColumns = new String[]{MessageTable.UUID, MessageTable.MESSAGE, MessageTable.UPLOAD_STATUS, MessageTable.MESSAGE_TIME, "_id"};
            Cursor readyMessagesCursor = db.query("messages", selectionColumns, selection, selectionArgs, null, null, MessageTable.MESSAGE_TIME+" , _id");
            if (readyMessagesCursor.getCount()>0) {
                JSONArray messagesArray = new JSONArray();
                int lastReadyMessage = 0;
                while (readyMessagesCursor.moveToNext()) {
                    // NOTE: this could be simpler if we ignore PENDING status on start-session message
                    if (!readyMessagesCursor.isLast() || UploadStatus.PENDING!=readyMessagesCursor.getInt(2)) {
                        JSONObject msgObject = new JSONObject(readyMessagesCursor.getString(1));
                        messagesArray.put(msgObject);
                        lastReadyMessage = readyMessagesCursor.getInt(4);
                    }
                    // TODO: decide how to deal with batch sizes and "batch" upload mode
                    // if( messagesArray.length()>=BATCH_SIZE || (readyMessagesCursor.isLast() && messagesArray.length()>0)) {
                    if (readyMessagesCursor.isLast() && messagesArray.length()>0) {
                        // create upload message
                        JSONObject uploadMessage = createUploadMessage(messagesArray);
                        // store in uploads table
                        dbInsertUpload(db, uploadMessage);
                        // delete processed messages
                        dbDeleteProcessedMessages(db, lastReadyMessage);
                        messagesArray = new JSONArray();
                    }
                }
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Error preparing batch upload in mParticle DB", e);
        } catch (JSONException e) {
            Log.e(TAG, "Error with JSON object", e);
        } finally {
            mDB.close();
        }
    }

    private JSONObject createUploadMessage(JSONArray messagesArray) throws JSONException {
        JSONObject uploadMessage= new JSONObject();
        uploadMessage.put(MessageKey.TYPE, MessageType.REQUEST_HEADER);
        uploadMessage.put(MessageKey.ID, UUID.randomUUID().toString());
        uploadMessage.put(MessageKey.TIMESTAMP, System.currentTimeMillis());

        uploadMessage.put(MessageKey.APPLICATION_KEY, mApiKey);
        uploadMessage.put(MessageKey.MPARTICLE_VERSION, MParticleAPI.VERSION);

        uploadMessage.put(MessageKey.APP_INFO, mAppInfo);
        uploadMessage.put(MessageKey.DEVICE_INFO, mDeviceInfo);

        uploadMessage.put(MessageKey.MESSAGES, messagesArray);

        return uploadMessage;
    }

    void processBatches() {
        try {
            // read batches ready to upload
            SQLiteDatabase db = mDB.getWritableDatabase();
            String[] selectionArgs = new String[]{Integer.toString(UploadStatus.PROCESSED)};
            String[] selectionColumns = new String[]{ "_id", UploadTable.MESSAGE };
            Cursor readyUploadsCursor = db.query("uploads", selectionColumns, UploadTable.UPLOAD_STATUS+"!=?", selectionArgs, null, null, UploadTable.MESSAGE_TIME+" , _id");
            if (readyUploadsCursor.getCount()>0) {
                while (readyUploadsCursor.moveToNext()) {
                    int uploadId=  readyUploadsCursor.getInt(0);
                    String message = readyUploadsCursor.getString(1);
                    // post message to mParticle service
                    HttpPost httpPost = new HttpPost(makeServiceUri("events"));
                    httpPost.setHeader("Content-type", "application/json");
                    httpPost.setEntity(new ByteArrayEntity(message.getBytes()));
                    httpPost.setHeader(HEADER_SIGNATURE, hmacSha256Encode(mSecret, message));

                    try {
                        String response = mHttpClient.execute(httpPost, new BasicResponseHandler(), mHttpContext);
                        JSONObject responseJSON = new JSONObject(response);
                        Log.d(TAG, "Got 2xx response with JSON:" + responseJSON.toString());
                        // store responses in DB
                        if (responseJSON.has(MessageKey.MESSAGES)) {
                            Log.d(TAG, "Response has messages to be processed");
                            // TODO: store and process command messages
                        }
                        dbDeleteUpload(db, uploadId);

                    } catch (Exception e) {
                        // TODO: process failures differently
                        Log.d(TAG, "Request failed:" + e.getMessage());
                        dbUpdateUploadStatus(db, uploadId, UploadStatus.READY);
                    }
                }
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Error preparing batch upload in mParticle DB", e);
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Error signing message", e);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error signing message", e);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error constructing service URI", e);
        } finally {
            mDB.close();
        }
    }

    void fetchConfig() {
        try {
            HttpGet httpGet = new HttpGet(makeServiceUri("config"));
            String response = mHttpClient.execute(httpGet, new BasicResponseHandler(), mHttpContext);
            JSONObject responseJSON = new JSONObject(response);
            if (responseJSON.has(MessageKey.SESSION_UPLOAD)) {
                String sessionUploadMode = responseJSON.getString(MessageKey.SESSION_UPLOAD);
                if ("batch".equalsIgnoreCase(sessionUploadMode)) {
                    mUploadMode = "batch";
                } else {
                    mUploadMode = "stream";
                }
            }
        } catch (HttpResponseException e) {
            Log.w(TAG, "Request failed:" + e.getMessage());
        } catch (IOException e) {
            Log.w(TAG, "Request failed:" + e.getMessage());
        } catch (JSONException e) {
            Log.w(TAG, "Failed to process response message JSON");
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error constructing service URI", e);
        }
    }

    private URI makeServiceUri(String method) throws URISyntaxException {
        return new URI(SERVICE_SCHEME, SERVICE_HOST, "/"+SERVICE_VERSION+"/"+mApiKey+"/"+method, null);
    }

    private void dbInsertUpload(SQLiteDatabase db, JSONObject message) throws JSONException {
        ContentValues contentValues = new ContentValues();
        contentValues.put(UploadTable.UPLOAD_ID, message.getString(MessageKey.ID));
        contentValues.put(UploadTable.MESSAGE_TIME, message.getLong(MessageKey.TIMESTAMP));
        contentValues.put(UploadTable.MESSAGE, message.toString());
        contentValues.put(UploadTable.UPLOAD_STATUS, UploadStatus.PENDING);
        db.insert("uploads", null, contentValues);
    }

    private void dbDeleteProcessedMessages(SQLiteDatabase db, int lastReadyMessage) {
        String[] whereArgs = { Long.toString(lastReadyMessage) };
        db.delete("messages", "_id<=?", whereArgs);
    }

    private void dbUpdateUploadStatus(SQLiteDatabase db, int uploadId, int status) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(UploadTable.UPLOAD_STATUS, status);
        String[] whereArgs = { Long.toString(uploadId) };
        db.update("uploads", contentValues, "_id=?", whereArgs);
    }

    private void dbDeleteUpload(SQLiteDatabase db, int uploadId) {
        String[] whereArgs = { Long.toString(uploadId) };
        db.delete("uploads", "_id=?", whereArgs);
    }

    /* Possibly for development only */
    public void setConnectionProxy(String host, int port) {
        HttpHost proxy = new HttpHost(host, port);
        mHttpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
    }

    // From Stack Overflow: http://stackoverflow.com/questions/7124735/hmac-sha256-algorithm-for-signature-calculation
     private static String hmacSha256Encode(String key, String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(key.getBytes(), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        return asHex(sha256_HMAC.doFinal(data.getBytes()));
     }

     // From Stack Overflow: http://stackoverflow.com/questions/923863/converting-a-string-to-hexadecimal-in-java
     private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
     private static String asHex(byte[] buf) {
         char[] chars = new char[2 * buf.length];
         for (int i = 0; i < buf.length; ++i)
         {
             chars[2 * i] = HEX_CHARS[(buf[i] & 0xF0) >>> 4];
             chars[2 * i + 1] = HEX_CHARS[buf[i] & 0x0F];
         }
         return new String(chars);
     }
}
