package com.mparticle.internal.database.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Looper;

import com.mparticle.UserAttributeListener;
import com.mparticle.identity.IdentityStateListener;
import com.mparticle.identity.MParticleUser;
import com.mparticle.internal.ConfigManager;
import com.mparticle.internal.Constants;
import com.mparticle.internal.DatabaseTables;
import com.mparticle.internal.DeviceAttributes;
import com.mparticle.internal.JsonReportingMessage;
import com.mparticle.internal.Logger;
import com.mparticle.internal.MPMessage;
import com.mparticle.internal.MPUtility;
import com.mparticle.internal.MessageBatch;
import com.mparticle.internal.MessageManager;
import com.mparticle.internal.MessageManagerCallbacks;
import com.mparticle.internal.Session;
import com.mparticle.internal.database.services.mp.BreadcrumbService;
import com.mparticle.internal.database.services.mp.GcmMessageService;
import com.mparticle.internal.database.services.mp.MessageService;
import com.mparticle.internal.database.services.mp.ReportingService;
import com.mparticle.internal.database.services.mp.SessionService;
import com.mparticle.internal.database.services.mp.UploadService;
import com.mparticle.internal.database.services.mp.UserAttributesService;
import com.mparticle.internal.dto.AttributionChangeDTO;
import com.mparticle.internal.dto.GcmMessageDTO;
import com.mparticle.internal.dto.ReadyUpload;
import com.mparticle.internal.dto.UserAttributeRemoval;
import com.mparticle.internal.dto.UserAttributeResponse;
import com.mparticle.messaging.AbstractCloudMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class MParticleDBManager extends BaseDBManager {
    private SharedPreferences mPreferences;

    public MParticleDBManager(Context context, DatabaseTables databaseTables) {
        super(context, databaseTables);
        mPreferences = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
        ConfigManager.setMpIdChangeListener(new MpIdChangeListener());
    }

    private long getMpid() {
        return ConfigManager.getMpid(mContext);
    }

    public boolean isAvailable() {
        return getMParticleDatabase() != null;
    }

    /**
     *
     *
     * Breadcumb Service Methods
     *
     *
     */


    public void insertBreadcrumb(MPMessage message, String apiKey) throws JSONException {
        BreadcrumbService.insertBreadcrumb(getMParticleDatabase(), message, apiKey, getMpid());
    }

    public void appendBreadcrumbs(MPMessage message) throws JSONException {
        JSONArray breadcrumbs = BreadcrumbService.getBreadcrumbs(getMParticleDatabase(), getMpid());
        if (!MPUtility.isEmpty(breadcrumbs)) {
            message.put(Constants.MessageType.BREADCRUMB, breadcrumbs);
        }
    }


    /**
     *
     *
     * GCM Message Methods
     *
     *
     */

    public void insertGcmMessage(AbstractCloudMessage message, String appState) throws JSONException {
        GcmMessageService.insertGcmMessage(getMParticleDatabase(), message, appState, getMpid());
    }

    public int updateGcmBehavior(int newBehavior, long timestamp, String contentId) {
        return GcmMessageService.updateGcmBehavior(getMParticleDatabase(), newBehavior, timestamp, contentId);
    }

    public String getPayload() {
        return GcmMessageService.getPayload(getMParticleDatabase(), getMpid());
    }


    public List<GcmMessageDTO> logInfluenceOpenGcmMessages(MessageManager.InfluenceOpenMessage message) {
        return GcmMessageService.logInfluenceOpenGcmMessages(getMParticleDatabase(), message, getMpid());
    }


    public void deleteExpiredGcmMessages() {
        GcmMessageService.deleteExpiredGcmMessages(getMParticleDatabase(), getMpid());
    }

    public JSONObject getGcmHistory() throws JSONException {
        List<GcmMessageService.GcmHistory> gcmHistories = GcmMessageService.getGcmHistory(getMParticleDatabase(), getMpid());
        JSONObject historyObject = new JSONObject();
        for (GcmMessageService.GcmHistory gcmHistory : gcmHistories) {
            JSONObject campaignObject = historyObject.optJSONObject(gcmHistory.getCampaignIdString());
            //only append the latest pushes
            if (campaignObject == null) {
                campaignObject = new JSONObject();
                campaignObject.put(Constants.MessageKey.PUSH_CONTENT_ID, gcmHistory.getContentId());
                campaignObject.put(Constants.MessageKey.PUSH_CAMPAIGN_HISTORY_TIMESTAMP, gcmHistory.getDisplayDate());
                historyObject.put(gcmHistory.getCampaignIdString(), campaignObject);
            }
        }
        return historyObject;
    }

    public void clearOldProviderGcm() {
        GcmMessageService.clearOldProviderGcm(getMParticleDatabase(), getMpid());
    }


    public int getCurrentBehaviors(String contentId) {
        return GcmMessageService.getCurrentBehaviors(getMParticleDatabase(), contentId, getMpid());
    }



    /**
     *
     *
     * Message Service Methods
     *
     *
     */

    public void cleanupMessages(long mpId) {
        MessageService.cleanupMessages(getMParticleDatabase(), mpId);
    }

    public void insertMessage(String apiKey, MPMessage message) throws JSONException {
        MessageService.insertMessage(getMParticleDatabase(), apiKey, message, message.getMpId());
    }

    /**
     *
     *
     * Prepare Messages for Upload
     *
     *
     */

    public void createSessionHistoryUploadMessage(ConfigManager configManager, DeviceAttributes deviceAttributes, String currentSessionId, long mpId) throws JSONException {
        SQLiteDatabase db = getMParticleDatabase();
        db.beginTransaction();
        List<MessageService.ReadyMessage> readyMessages = MessageService.getSessionHistory(db, currentSessionId, mpId);
        if (readyMessages.size() <= 0) {
            db.setTransactionSuccessful();
            return;
        }

        HashMap<String, MessageBatch> uploadMessagesBySession = new HashMap<String, MessageBatch>(2);
        int highestUploadedMessageId = 0;
        for (MessageService.ReadyMessage readyMessage : readyMessages) {
            MessageBatch uploadMessage = uploadMessagesBySession.get(readyMessage.getSessionId());
            if (uploadMessage == null) {
                uploadMessage = createUploadMessage(configManager, true, mpId);
                uploadMessagesBySession.put(readyMessage.getSessionId(), uploadMessage);
            }
            int messageLength = readyMessage.getMessage().length();
            JSONObject msgObject = new JSONObject(readyMessage.getMessage());
            if (messageLength + uploadMessage.getMessageLengthBytes() > Constants.LIMIT_MAX_UPLOAD_SIZE) {
                break;
            }
            uploadMessage.addSessionHistoryMessage(msgObject);
            uploadMessage.incrementMessageLengthBytes(messageLength);
            highestUploadedMessageId = readyMessage.getMessageId();
        }
        MessageService.deleteMessages(db, highestUploadedMessageId, mpId);
        List<JSONObject> deviceInfos = SessionService.processSessions(db, uploadMessagesBySession, mpId);
        for (JSONObject deviceInfo: deviceInfos) {
            deviceAttributes.updateDeviceInfo(mContext ,deviceInfo);
        }

        for (Map.Entry<String, MessageBatch> session : uploadMessagesBySession.entrySet()) {
            MessageBatch uploadMessage = session.getValue();
            if (uploadMessage != null) {
                String sessionId = session.getKey();
                //for upgrade scenarios, there may be no device or app info associated with the session, so create it now.
                if (uploadMessage.getAppInfo() == null) {
                    uploadMessage.setAppInfo(deviceAttributes.getAppInfo(mContext));
                }
                if (uploadMessage.getDeviceInfo() == null || sessionId.equals(currentSessionId)) {
                    uploadMessage.setDeviceInfo(deviceAttributes.getDeviceInfo(mContext));
                }
                JSONArray messages = uploadMessage.getSessionHistoryMessages();
                JSONArray identities = findIdentityState(configManager, messages);
                uploadMessage.setIdentities(identities);
                JSONObject userAttributes = findUserAttributeState(messages, mpId);
                uploadMessage.setUserAttributes(userAttributes);
                UploadService.insertUpload(db, uploadMessage, configManager.getApiKey());
                //if this was to process session history, or
                //if we're never going to process history AND
                //this batch contains a previous session, then delete the session
                SessionService.deleteSessions(db, currentSessionId, mpId);
            }
        }
    }

    public void createMessagesForUploadMessage(ConfigManager configManager, DeviceAttributes deviceAttributes, String currentSessionId, boolean sessionHistoryEnabled, long mpId) throws JSONException {
        SQLiteDatabase db = getMParticleDatabase();
        db.beginTransaction();
        List<MessageService.ReadyMessage> readyMessages = MessageService.getMessagesForUpload(db, mpId);
        if (readyMessages.size() <= 0) {
            db.setTransactionSuccessful();
            return;
        }
        HashMap<String, MessageBatch> uploadMessagesBySession = new HashMap<String, MessageBatch>(2);
        int highestUploadedMessageId = 0;
        for (MessageService.ReadyMessage readyMessage: readyMessages) {
            MessageBatch uploadMessage = uploadMessagesBySession.get(readyMessage.getSessionId());
            if (uploadMessage == null) {
                uploadMessage = createUploadMessage(configManager, false, mpId);
                uploadMessagesBySession.put(readyMessage.getSessionId(), uploadMessage);
            }
            int messageLength = readyMessage.getMessage().length();
            JSONObject msgObject = new JSONObject(readyMessage.getMessage());
            if (messageLength + uploadMessage.getMessageLengthBytes() > Constants.LIMIT_MAX_UPLOAD_SIZE) {
                break;
            }
            uploadMessage.addMessage(msgObject);
            uploadMessage.incrementMessageLengthBytes(messageLength);
            highestUploadedMessageId = readyMessage.getMessageId();
        }
        if (sessionHistoryEnabled) {
            //else mark the messages as uploaded, so next time around it'll be included in session history
            MessageService.markMessagesAsUploaded(db, highestUploadedMessageId, mpId);
        } else {
            //if this is a session-less message, or if session history is disabled, just delete it
            MessageService.deleteMessages(db, highestUploadedMessageId, mpId);
        }

        List<ReportingService.ReportingMessage> reportingMessages = ReportingService.getReportingMessagesForUpload(db, mpId);
        for (ReportingService.ReportingMessage reportingMessage: reportingMessages) {
            MessageBatch batch = uploadMessagesBySession.get(reportingMessage.getSessionId());
            if (batch == null) {
                //if there's no matching session id then just use the first batch object
                batch = uploadMessagesBySession.values().iterator().next();
            }
            if (batch != null) {
                batch.addReportingMessage(reportingMessage.getMsgObject());
            }
            ReportingService.deleteReportingMessage(db, reportingMessage.getReportingMessageId());
        }
        List<JSONObject> deviceInfos = SessionService.processSessions(db, uploadMessagesBySession, mpId);
        for (JSONObject deviceInfo: deviceInfos) {
            deviceAttributes.updateDeviceInfo(mContext, deviceInfo);
        }


        for (Map.Entry<String, MessageBatch> session : uploadMessagesBySession.entrySet()) {
            MessageBatch uploadMessage = session.getValue();
            if (uploadMessage != null) {
                String sessionId = session.getKey();
                //for upgrade scenarios, there may be no device or app info associated with the session, so create it now.
                if (uploadMessage.getAppInfo() == null) {
                    uploadMessage.setAppInfo(deviceAttributes.getAppInfo(mContext));
                }
                if (uploadMessage.getDeviceInfo() == null || sessionId.equals(currentSessionId)) {
                    uploadMessage.setDeviceInfo(deviceAttributes.getDeviceInfo(mContext));
                }
                JSONArray messages = uploadMessage.getMessages();
                JSONArray identities = findIdentityState(configManager, messages);
                uploadMessage.setIdentities(identities);
                JSONObject userAttributes = findUserAttributeState(messages, mpId);
                uploadMessage.setUserAttributes(userAttributes);
                UploadService.insertUpload(getMParticleDatabase(), uploadMessage, configManager.getApiKey());
                //if this was to process session history, or
                //if we're never going to process history AND
                //this batch contains a previous session, then delete the session
                if (!sessionHistoryEnabled && !sessionId.equals(currentSessionId)) {
                    SessionService.deleteSessions(db, sessionId, mpId);
                }
            }
        }

    }

    public void deleteMessagesAndSessions(String currentSessionId, long mpId) {
        SQLiteDatabase db = getMParticleDatabase();
        db.beginTransaction();
        MessageService.deleteOldMessages(db, currentSessionId, mpId);
        SessionService.deleteSessions(db, currentSessionId, mpId);
        db.endTransaction();
    }

    /**
     * Look for the last UAC message to find the end-state of user attributes
     */
    private JSONObject findUserAttributeState(JSONArray messages, long mpId) {
        JSONObject userAttributes = null;
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                try {
                    if (messages.getJSONObject(i).get(Constants.MessageKey.TYPE).equals(Constants.MessageType.USER_ATTRIBUTE_CHANGE)) {
                        userAttributes = messages.getJSONObject(i).getJSONObject(Constants.MessageKey.USER_ATTRIBUTES);
                        messages.getJSONObject(i).remove(Constants.MessageKey.USER_ATTRIBUTES);
                    }
                }catch (JSONException jse) {

                }catch (NullPointerException npe) {

                }
            }
        }
        if (userAttributes == null) {
            return getAllUserAttributes(mpId);
        } else {
            return userAttributes;
        }
    }

    /**
     * Look for the last UIC message to find the end-state of user identities
     */
    private JSONArray findIdentityState(ConfigManager configManager, JSONArray messages) {
        JSONArray identities = null;
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                try {
                    if (messages.getJSONObject(i).get(Constants.MessageKey.TYPE).equals(Constants.MessageType.USER_IDENTITY_CHANGE)) {
                        identities = messages.getJSONObject(i).getJSONArray(Constants.MessageKey.USER_IDENTITIES);
                        messages.getJSONObject(i).remove(Constants.MessageKey.USER_IDENTITIES);
                    }
                }catch (JSONException jse) {

                }catch (NullPointerException npe) {

                }
            }
        }
        if (identities == null) {
            return configManager.getUserIdentityJson();
        } else {
            return identities;
        }
    }

    /**
     * Method that is responsible for building an upload message to be sent over the wire.
    **/
    private MessageBatch createUploadMessage(ConfigManager configManager, boolean history, long mpId) throws JSONException {
        MessageBatch batchMessage = MessageBatch.create(
                history,
                configManager,
                mPreferences,
                configManager.getCookies(mpId));
        addGCMHistory(batchMessage);
        return batchMessage;
    }

    /**
     * If the customer is using our GCM solution, query and append all of the history used for attribution.
     *
     */
    void addGCMHistory(MessageBatch uploadMessage) {
        try {
            deleteExpiredGcmMessages();
            JSONObject historyObject = getGcmHistory();
            if (historyObject != null) {
                uploadMessage.put(Constants.MessageKey.PUSH_CAMPAIGN_HISTORY, historyObject);
            }
        } catch (Exception e) {
            Logger.warning(e, "Error while building GCM campaign history");
        }
    }


    /**
     *
     *
     * Session Service Methods
     *
     *
     */


    public void updateSessionEndTime(String sessionId, long endTime, long sessionLength) {
        SessionService.updateSessionEndTime(getMParticleDatabase(), sessionId, endTime, sessionLength);
    }

    public void updateSessionAttributes(String sessionId, String attributes) {
        SessionService.updateSessionAttributes(getMParticleDatabase(), sessionId, attributes);
    }

    public MPMessage getSessionForSessionEndMessage(String sessionId, Location location, long mpId) throws JSONException {
        Cursor selectCursor = null;
        try {
            selectCursor = SessionService.getSessionForSessionEndMessage(getMParticleDatabase(), sessionId);
            MPMessage endMessage = null;
            if (selectCursor.moveToFirst()) {
                long start = selectCursor.getLong(0);
                long end = selectCursor.getLong(1);
                long foregroundLength = selectCursor.getLong(2);
                String attributes = selectCursor.getString(3);
                JSONObject sessionAttributes = null;
                if (null != attributes) {
                    sessionAttributes = new JSONObject(attributes);
                }

                // create a session-end message
                endMessage = createMessageSessionEnd(sessionId, start, end, foregroundLength,
                        sessionAttributes, location, mpId);
                endMessage.put(Constants.MessageKey.ID, UUID.randomUUID().toString());
            }
            return endMessage;
        }
        finally {
            if (selectCursor != null && !selectCursor.isClosed()) {
                selectCursor.close();
            }
        }
    }

    private MPMessage createMessageSessionEnd(String sessionId, long start, long end, long foregroundLength, JSONObject sessionAttributes, Location location, long mpId) throws JSONException{
        int eventCounter = mPreferences.getInt(Constants.PrefKeys.EVENT_COUNTER, 0);
        resetEventCounter();
        Session session = new Session();
        session.mSessionID = sessionId;
        session.mSessionStartTime = start;
        MPMessage message = new MPMessage.Builder(Constants.MessageType.SESSION_END, session, location, mpId)
                .timestamp(end)
                .attributes(sessionAttributes)
                .build();
        message.put(Constants.MessageKey.EVENT_COUNTER, eventCounter);
        message.put(Constants.MessageKey.SESSION_LENGTH, foregroundLength);
        message.put(Constants.MessageKey.SESSION_LENGTH_TOTAL, (end - start));
        message.put(Constants.MessageKey.STATE_INFO_KEY, MessageManager.getStateInfo());
        return message;
    }

    private void resetEventCounter(){
        mPreferences.edit().putInt(Constants.PrefKeys.EVENT_COUNTER, 0).apply();
    }


    public List<String> getOrphanSessionIds(String apiKey) {
        return SessionService.getOrphanSessionIds(getMParticleDatabase(), apiKey, getMpid());
    }


    public void insertSession(MPMessage message, String apiKey, JSONObject appInfo, JSONObject deviceInfo) throws JSONException {
        String appInfoString = appInfo.toString();
        String deviceInfoString = deviceInfo.toString();
        SessionService.insertSession(getMParticleDatabase(), message, apiKey, appInfoString, deviceInfoString, getMpid());
    }


    /**
     *
     *
     * Reporting Service Methods
     *
     *
     */

    public void insertReportingMessages(List<JsonReportingMessage> reportingMessages) {
        SQLiteDatabase db = getMParticleDatabase();
        try {
            db.beginTransaction();
            for (int i = 0; i < reportingMessages.size(); i++) {
                ReportingService.insertReportingMessage(db, reportingMessages.get(i), getMpid());
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Logger.verbose("Error inserting reporting message: " + e.toString());
        } finally {
            db.endTransaction();
        }
    }



    /**
     *
     *
     * Upload Service Methods
     *
     *
     */


    public void cleanupUploadMessages() {
        UploadService.cleanupUploadMessages(getMParticleDatabase());
    }

    public List<ReadyUpload> getReadyUploads() {
        return UploadService.getReadyUploads(getMParticleDatabase());
    }

    public int deleteUpload(int id) {
        return UploadService.deleteUpload(getMParticleDatabase(), id);
    }



    /**
     *
     *
     * UserAttribute Service Methods
     *
     *
     */

    public TreeMap<String, String> getUserAttributeSingles(long mpId) {
        if (getMParticleDatabase() != null) {
            return UserAttributesService.getUserAttributesSingles(getMParticleDatabase(), mpId);
        }
        return null;
    }

    public TreeMap<String, List<String>> getUserAttributeLists(long mpId) {
        if (getMParticleDatabase() != null) {
            return UserAttributesService.getUserAttributesLists(getMParticleDatabase(), mpId);
        }
        return null;
    }


    public JSONObject getAllUserAttributes(long mpId)  {
        Map<String, Object> attributes = getAllUserAttributes(null, mpId);
        JSONObject jsonAttributes = new JSONObject();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Object value = entry.getValue();
            if (entry.getValue() instanceof List) {
                List<String> attributeList = (List<String>)value;
                JSONArray jsonArray = new JSONArray();
                for (String attribute : attributeList) {
                    jsonArray.put(attribute);
                }
                try {
                    jsonAttributes.put(entry.getKey(), jsonArray);
                } catch (JSONException e) {

                }
            }else {
                try {
                    Object entryValue = entry.getValue();
                    if (entryValue == null) {
                        entryValue = JSONObject.NULL;
                    }
                    jsonAttributes.put(entry.getKey(), entryValue);
                } catch (JSONException e) {

                }
            }
        }
        return jsonAttributes;
    }

    public Map<String, Object> getAllUserAttributes(final UserAttributeListener listener, final long mpId) {
        Map<String, Object> allUserAttributes = new HashMap<String, Object>();
        if (listener == null || Looper.getMainLooper() != Looper.myLooper()) {
            Map<String, String> userAttributes = getUserAttributeSingles(mpId);
            Map<String, List<String>> userAttributeLists = getUserAttributeLists(mpId);
            if (listener != null) {
                listener.onUserAttributesReceived(userAttributes, userAttributeLists);
            }
            if (userAttributes != null) {
                allUserAttributes.putAll(userAttributes);
            }
            if (userAttributeLists != null) {
                allUserAttributes.putAll(userAttributeLists);
            }
            return allUserAttributes;
        }else {
            new AsyncTask<Void, Void, UserAttributeResponse>() {
                @Override
                protected UserAttributeResponse doInBackground(Void... params) {
                    return getUserAttributes(mpId);
                }

                @Override
                protected void onPostExecute(UserAttributeResponse attributes) {
                    if (listener != null) {
                        listener.onUserAttributesReceived(attributes.attributeSingles, attributes.attributeLists);
                    }
                }
            }.execute();
            return null;
        }
    }

    public Map<String, String> getUserAttributes(final UserAttributeListener listener, final long mpId) {
        if (listener == null || Looper.getMainLooper() != Looper.myLooper()) {
            Map<String, String> userAttributes = getUserAttributeSingles(mpId);

            if (listener != null) {
                Map<String, List<String>> userAttributeLists = getUserAttributeLists(mpId);
                listener.onUserAttributesReceived(userAttributes, userAttributeLists);
            }
            return userAttributes;
        }else {
            new AsyncTask<Void, Void, UserAttributeResponse>() {
                @Override
                protected UserAttributeResponse doInBackground(Void... params) {
                    return getUserAttributes(mpId);
                }

                @Override
                protected void onPostExecute(UserAttributeResponse attributes) {
                    if (listener != null) {
                        listener.onUserAttributesReceived(attributes.attributeSingles, attributes.attributeLists);
                    }
                }
            }.execute();
            return null;
        }
    }


    public List<AttributionChangeDTO> setUserAttribute(UserAttributeResponse userAttribute) {
        List<AttributionChangeDTO> attributionChangeDTOs = new ArrayList<AttributionChangeDTO>();
        if (getMParticleDatabase() == null){
            return attributionChangeDTOs;
        }
        Map<String, Object> currentValues = getAllUserAttributes(null, userAttribute.mpId);
        SQLiteDatabase db = getMParticleDatabase();
        try {
            db.beginTransaction();
            long time = System.currentTimeMillis();
            if (userAttribute.attributeLists != null) {
                for (Map.Entry<String, List<String>> entry : userAttribute.attributeLists.entrySet()) {
                    String key = entry.getKey();
                    List<String> attributeValues = entry.getValue();
                    Object oldValue = currentValues.get(key);
                    if (oldValue != null && oldValue instanceof List && ((List) oldValue).containsAll(attributeValues)) {
                        continue;
                    }
                    int deleted = UserAttributesService.deleteAttributes(db, key, userAttribute.mpId);
                    boolean isNewAttribute = deleted == 0;
                    for (String attributeValue : attributeValues) {
                        UserAttributesService.insertAttribute(db, key, attributeValue, time, true, userAttribute.mpId);
                    }
                    attributionChangeDTOs.add(new AttributionChangeDTO(key, attributeValues, oldValue, false, isNewAttribute, userAttribute.time, userAttribute.mpId));
                }
            }
            if (userAttribute.attributeSingles != null) {
                for (Map.Entry<String, String> entry : userAttribute.attributeSingles.entrySet()) {
                    String key = entry.getKey();
                    String attributeValue = entry.getValue();
                    Object oldValue = currentValues.get(key);
                    if (oldValue != null && oldValue instanceof String && ((String) oldValue).equalsIgnoreCase(attributeValue)) {
                        continue;
                    }
                    int deleted = UserAttributesService.deleteAttributes(db, key, userAttribute.mpId);
                    boolean isNewAttribute = deleted == 0;
                    UserAttributesService.insertAttribute(db, key, attributeValue, time, false, getMpid());
                    attributionChangeDTOs.add(new AttributionChangeDTO(key, attributeValue, oldValue, false, isNewAttribute, userAttribute.time, userAttribute.mpId));
                }
            }
            db.setTransactionSuccessful();
        }catch (Exception e){
            Logger.error(e, "Error while adding user attributes: ", e.toString());
        } finally {
            db.endTransaction();
        }
        return attributionChangeDTOs;
    }


    public void removeUserAttribute(UserAttributeRemoval container, MessageManagerCallbacks callbacks) {
        Map<String, Object> currentValues = getAllUserAttributes(null, container.mpId);
        SQLiteDatabase db = getMParticleDatabase();
        try {
            db.beginTransaction();
            int deleted = UserAttributesService.deleteAttributes(db, container.key, container.mpId);
            if (callbacks != null && deleted > 0) {
                callbacks.attributeRemoved(container.key);
                callbacks.logUserAttributeChangeMessage(container.key, null, currentValues.get(container.key), true, false, container.time, container.mpId);
            }
            db.setTransactionSuccessful();
        }catch (Exception e) {

        } finally {
            db.endTransaction();
        }
    }

    private UserAttributeResponse getUserAttributes(long mpId) {
        UserAttributeResponse response = new UserAttributeResponse();
        response.attributeSingles = getUserAttributeSingles(mpId);
        response.attributeLists = getUserAttributeLists(mpId);
        return response;
    }


    /**
     *
     *
     *
     * MParticleUser Service Methods
     *
     *
     *
     */

    static Set<IdentityStateListener> idStateListeners = new HashSet<IdentityStateListener>();

    public MParticleUser getCurrentUser() {
        return getUser(ConfigManager.getMpid(mContext));
    }

    public MParticleUser getUser(long mpId) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    public void storeUser(MParticleUser user) {
        if (user == null) {
            return;
        }
        if (user.getId() == ConfigManager.getMpid(mContext)) {
            triggerUserChangedCallbacks(user);
        }
        throw new UnsupportedOperationException("Storing user in service not yet implemented");
    }

    public void setIdentityStateListener(IdentityStateListener listener) {
        if (listener != null) {
            idStateListeners.add(listener);
        }
    }

    /**
    *   IdentityStateListener callbacks are triggered on the following conditions:
    *       - MPID changes, if MParticleUser exists with that mpId
    *       - MParticleUser is stored, which matches mpId
    **/
    private void triggerUserChangedCallbacks(MParticleUser user) {
        idStateListeners.removeAll(Collections.singleton(null));
        for (IdentityStateListener identityStateListener: idStateListeners) {
            identityStateListener.onUserIdentified(user);
        }
    }

    public class MpIdChangeListener {
        private MpIdChangeListener(){}

        public void onMpIdChanged(long mpId) {
            triggerUserChangedCallbacks(getUser(mpId));
        }
    }
}
