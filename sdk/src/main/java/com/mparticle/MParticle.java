package com.mparticle;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import com.mparticle.Constants.MessageKey;
import com.mparticle.Constants.PrefKeys;
import com.mparticle.segmentation.SegmentListener;

import org.apache.http.conn.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.Thread.UncaughtExceptionHandler;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.SocketImplFactory;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/**
 * The primary access point to the mParticle SDK. In order to use this class, you must first call {@link #start(android.content.Context)}, which requires
 * configuration via {@link <a href="http://developer.android.com/guide/topics/resources/providing-resources.html">Android Resources</a>}. You can then retrieve a reference
 * to an instance of this class via {@link #getInstance()}
 * <p/>
 * It's recommended to keep configuration parameters in a single xml file located within your res/values folder. The full list of configuration options is as follows:
 * <p/>
 * <h4>Required parameters</h4>
 * <ul>
 * <li>mp_key - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - This is the key used to authenticate with the mParticle SDK server API</li>
 * <li>mp_secret - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - This is the secret used to authenticate with the mParticle SDK server API</li>
 * </ul>
 * <h4>Required for push notifications</h4>
 * <ul>
 * <li> mp_enablePush - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enable push registration, notifications, and analytics. <i>Default: false</i></li>
 * <li> mp_pushSenderId - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - {@link <a href="http://developer.android.com/google/gcm/gcm.html#senderid">GCM Sender ID</a>}</li>
 * </ul>
 * <h4>Required for licensing</h4>
 * <ul>
 * <li> mp_enableLicenseCheck - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - By enabling license check, MParticle will automatically validate that the app was downloaded and/or bought via Google Play, or if it was "pirated" or "side-loaded". <i>Default: false</i></li>
 * <li> mp_appLicenseKey - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - The {@link <a href="http://developer.android.com/google/play/licensing/adding-licensing.html#account-key">public key</a>} used by your app to verify the user's license with Google Play.</li>
 * </ul>
 * <h4>Optional</h4>
 * <ul>
 * <li>mp_enableAutoScreenTracking - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - Enable automatic screen view events. Note that *prior to ICS/API level 14*, this functionality requires instrumentation via an mParticle Activity implementation or manually. </li>
 * <li>mp_productionUploadInterval - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - The length of time in seconds to send batches of messages to mParticle. Setting this too low could have an adverse effect on the device battery. <i>Default: 600</i></li>
 * <li>mp_reportUncaughtExceptions - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - By enabling this, the MParticle SDK will automatically log and report any uncaught exceptions, including stack traces. <i>Default: false</i></li>
 * <li>mp_sessionTimeout - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - The length of time (in seconds) that a user session will remain valid while application has been paused and put into the background. <i>Default: 60</i></li>
 * <li>mp_enableDebugMode - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enabling this will provide additional logcat messages to debug your implementation and usage of mParticle <i>Default: false</i></li>
 * <li>mp_debugUploadInterval - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - The upload interval (see above) while in debug mode. <i>Default: 10</i></li>
 * <li>mp_enableSandboxMode - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enabling this will mark events as sandbox messages for debugging and isolation in the mParticle web application. <i>Default: false</i></li>
 * <li>mp_enableNetworkPerformanceMeasurement - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enabling this will allow the mParticle SDK to measure network requests made with Apache's HttpClient as well as UrlConnection. <i>Default: false</i></li>
 * </ul>
 */
public class MParticle {
    private static final String TAG = Constants.LOG_TAG;
    private static final HandlerThread sTimeoutHandlerThread = new HandlerThread("mParticleSessionTimeoutHandler",
            Process.THREAD_PRIORITY_BACKGROUND);
    private static final byte[] SALT = new byte[]{
            -46, 65, 30, -128, -103, -57, 74, 10, 51, 88, -95, -45, -43, -117, -36, 99, -11, 32, -64,
            89
    };
    static Bundle lastNotificationBundle;
    static Boolean appRunning;
    private static volatile MParticle instance;
    private static SharedPreferences sPreferences;
    final ConfigManager mConfigManager;
    AppStateManager mAppStateManager;
    /* package-private */ String mSessionID;
    /* package-private */ long mSessionStartTime = 0;
    private PushRegistrationListener registrationListener = new PushRegistrationListener() {

        @Override
        public void onRegistered(String regId) {
            mMessageManager.setPushRegistrationId(mSessionID, mSessionStartTime, System.currentTimeMillis(), regId, true);
        }

        @Override
        public void onCleared(String regId) {
            mMessageManager.setPushRegistrationId(mSessionID, mSessionStartTime, System.currentTimeMillis(), null, true);
        }
    };
    /* package-private */ long mLastEventTime = 0;
    /* package-private */ JSONArray mUserIdentities = new JSONArray();
    /* package-private */ JSONObject mUserAttributes = new JSONObject();
    /* package-private */ JSONObject mSessionAttributes;
    /* package-private */ MeasuredRequestManager measuredRequestManager;
    private MessageManager mMessageManager;
    private Handler mTimeoutHandler;
    private MParticleLocationListener mLocationListener;
    private ExceptionHandler mExHandler;
    private Context mAppContext;
    private String mApiKey;
    private Boolean mDebugMode = false;
    private EmbeddedKitManager embeddedKitManager;
    //private int mSessionTimeout = 30 * 60 * 1000;
    private int mEventCount = 0;
    private String mLaunchUri;
    private LicenseCheckerCallback clientLicensingCallback;


    /* package-private */MParticle(Context context, MessageManager messageManager, ConfigManager configManager, EmbeddedKitManager embeddedKitManager) {
        appRunning = true;
        mConfigManager = configManager;
        mAppContext = context.getApplicationContext();
        mApiKey = mConfigManager.getApiKey();
        mMessageManager = messageManager;
        mAppStateManager = new AppStateManager(mAppContext, embeddedKitManager);
        measuredRequestManager = new MeasuredRequestManager();
        mTimeoutHandler = new SessionTimeoutHandler(this, sTimeoutHandlerThread.getLooper());
        this.embeddedKitManager = embeddedKitManager;

        String userAttrs = sPreferences.getString(PrefKeys.USER_ATTRS + mApiKey, null);

        if (null != userAttrs) {
            try {
                mUserAttributes = new JSONObject(userAttrs);
            } catch (JSONException e) {
                // carry on without user attributes
            }
        }

        String userIds = sPreferences.getString(PrefKeys.USER_IDENTITIES + mApiKey, null);
        if (null != userIds) {
            try {
                mUserIdentities = new JSONArray(userIds);
            } catch (JSONException e) {
                // carry on without user identities
            }
            try {
                Boolean changeMade = false;
                for (int i = 0; i < mUserIdentities.length(); i++) {
                    JSONObject identity = mUserIdentities.getJSONObject(i);
                    if (!identity.has(MessageKey.IDENTITY_DATE_FIRST_SEEN)){
                        identity.put(MessageKey.IDENTITY_DATE_FIRST_SEEN, 0);
                        changeMade = true;
                    }
                    if (!identity.has(MessageKey.IDENTITY_FIRST_SEEN)){
                        identity.put(MessageKey.IDENTITY_FIRST_SEEN, true);
                        changeMade = true;
                    }
                }
                if (changeMade) {
                    sPreferences.edit().putString(PrefKeys.USER_IDENTITIES + mApiKey, mUserIdentities.toString()).commit();
                }
            }catch (JSONException jse){
                //swallow this
            }
        }




    }

    /**
     * Start the mParticle SDK and begin tracking a user session. This method must be called prior to {@link #getInstance()}.
     * This method requires that your API key and secret are contained in your XML configuration.
     *
     * @param context Required reference to a Context object
     */

    public static void start(Context context) {
        start(context, InstallType.AutoDetect);
    }

    /**
     * Start the mParticle SDK and begin tracking a user session.
     *
     * @param context     Required reference to a Context object
     * @param apiKey      The API key to use for authentication with mParticle
     * @param secret      The API secret to use for authentication with mParticle
     */

    public static void start(Context context, String apiKey, String secret) {
        start(context, apiKey, secret, false, InstallType.AutoDetect);
    }

    /**
     * Start the mParticle SDK and begin tracking a user session. This method must be called prior to {@link #getInstance()}.
     * This method requires that your API key and secret are contained in your XML configuration.
     *
     * The InstallType parameter is used to determine if this is a new install or an upgrade. In
     * the case where the mParticle SDK is being added to an existing app with existing users, this
     * parameter prevents mParticle from categorizing all users as new users.
     *
     * @param context     Required reference to a Context object
     * @param installType Specify whether this is a new install or an upgrade, or let mParticle detect
     *
     * @see com.mparticle.MParticle.InstallType
     */

    public static void start(Context context, InstallType installType) {
        if (context == null) {
            throw new IllegalArgumentException("mParticle failed to start: context is required.");
        }
        MParticle.getInstance(context, null, null, false, installType);
    }

    /**
     * Start the mParticle SDK and begin tracking a user session.
     *
     * The InstallType parameter is used to determine if this is a new install or an upgrade. In
     * the case where the mParticle SDK is being added to an existing app with existing users, this
     * parameter prevents mParticle from categorizing all users as new users.
     *
     * @param context     Required reference to a Context object
     * @param apiKey      The API key to use for authentication with mParticle
     * @param secret      The API secret to use for authentication with mParticle
     * @param sandboxMode Enable/disable sandbox mode
     * @param installType Specify whether this is a new install or an upgrade, or let mParticle detect
     *
     * @see com.mparticle.MParticle.InstallType
     */

    public static void start(final Context context, final String apiKey, final String secret, final Boolean sandboxMode, final InstallType installType) {
        if (context == null) {
            throw new IllegalArgumentException("mParticle failed to start: context is required.");
        }
        if (apiKey == null) {
            throw new IllegalArgumentException("mParticle failed to start: apiKey is required.");
        }
        if (secret == null) {
            throw new IllegalArgumentException("mParticle failed to start: secret is required.");
        }
        if (installType == null) {
            throw new IllegalArgumentException("mParticle failed to start: installType is required.");
        }
        MParticle.getInstance(context, apiKey, secret, sandboxMode, installType);
    }

    /**
     * Initialize or return a thread-safe instance of the mParticle SDK, specifying the API credentials to use. If this
     * or any other {@link #getInstance()} has already been called in the application's lifecycle, the
     * API credentials will be ignored and the current instance will be returned.
     *
     * @param context     the Activity that is creating the instance
     * @param apiKey      the API key for your account
     * @param secret      the API secret for your account
     * @param sandboxMode set the SDK in sandbox mode, xml configuration will override this value
     * @return An instance of the mParticle SDK configured with your API key
     *
     */
    private static MParticle getInstance(Context context, String apiKey, String secret, Boolean sandboxMode, InstallType installType) {
        if (instance == null) {
            synchronized (MParticle.class) {
                if (instance == null) {
                    if (PackageManager.PERMISSION_DENIED == context
                            .checkCallingOrSelfPermission(android.Manifest.permission.INTERNET)) {
                        throw new IllegalArgumentException("mParticle requires android.permission.INTERNET permission");
                    }

                    if (!sTimeoutHandlerThread.isAlive()) {
                        sTimeoutHandlerThread.start();
                    }

                    if (null == sPreferences) {
                        sPreferences = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
                    }

                    EmbeddedKitManager embeddedKitManager1 = new EmbeddedKitManager(context);
                    ConfigManager appConfigManager = new ConfigManager(context, apiKey, secret, sandboxMode, embeddedKitManager1);
                    Context appContext = context.getApplicationContext();

                    Boolean firstRun = sPreferences.getBoolean(PrefKeys.FIRSTRUN + appConfigManager.getApiKey(), true);
                    if (firstRun) {
                        sPreferences.edit().putBoolean(PrefKeys.FIRSTRUN + appConfigManager.getApiKey(), false).commit();
                    }

                    MessageManager messageManager = new MessageManager(appContext, appConfigManager);


                    instance = new MParticle(appContext, messageManager, appConfigManager, embeddedKitManager1);
                    messageManager.start(appContext, firstRun, installType);
                    instance.logStateTransition(Constants.StateTransitionType.STATE_TRANS_INIT);
                    if (appConfigManager.getLogUnhandledExceptions()) {
                        instance.enableUncaughtExceptionLogging();
                    }
            
                    if (context instanceof Activity) {
                        instance.mLaunchUri = ((Activity) context).getIntent().getDataString();
                        if (instance.mLaunchUri != null) {
                            Log.d(TAG, "launchuri: " + instance.mLaunchUri);
                        }
                    }

                    if (appConfigManager.isPushEnabled()) {
                        instance.enablePushNotifications(appConfigManager.getPushSenderId());
                    }
                    if (appConfigManager.isLicensingEnabled()) {
                        instance.performLicenseCheck();
                    }
                    if (appConfigManager.isNetworkPerformanceEnabled()) {
                        instance.beginMeasuringNetworkPerformance();
                    }
                }
            }
        }
        return instance;
    }

    /**
     * Retrieve an instance of the MParticle class. {@link #start(android.content.Context)} or {@link #start(android.content.Context, String, String)} must
     * be called prior to this or a {@code java.lang.IllegalStateException} will be thrown.
     *
     * @return An instance of the mParticle SDK configured with your API key
     */
    public static MParticle getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Failed to get MParticle instance, getInstance() called prior to start().");
        }
        return getInstance(null, null, null, false, null);
    }

    /* package-private */
    static Boolean setCheckedAttribute(JSONObject attributes, String key, Object value) {
        return setCheckedAttribute(attributes, key, value, false);
    }

    /* package-private */
    static Boolean setCheckedAttribute(JSONObject attributes, String key, Object value, Boolean caseInsensitive) {
        if (null == attributes || null == key) {
            return false;
        }
        try {
            if (Constants.LIMIT_ATTR_COUNT == attributes.length() && !attributes.has(key)) {
                Log.w(TAG, "Attribute count exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (null != value && value.toString().length() > Constants.LIMIT_ATTR_VALUE) {
                Log.w(TAG, "Attribute value length exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (key.length() > Constants.LIMIT_ATTR_NAME) {
                Log.w(TAG, "Attribute name length exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (value == null) {
                value = JSONObject.NULL;
            }
            if (caseInsensitive) {
                key = findCaseInsensitiveKey(attributes, key);
            }
            attributes.put(key, value);
        } catch (JSONException e) {
            Log.w(TAG, "JSON error processing attributes. Discarding attribute: " + key);
            return false;
        }
        return true;
    }

    static String findCaseInsensitiveKey(JSONObject jsonObject, String key) {
        Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String currentKey = keys.next();
            if (currentKey.equalsIgnoreCase(key)) {
                return currentKey;
            }
        }
        return key;
    }

    Boolean shouldProcessUrl(String url) {
        return mConfigManager.isNetworkPerformanceEnabled() &&
                measuredRequestManager.isUriAllowed(url);
    }

    void logStateTransition(String transitionType) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mMessageManager.logStateTransition(transitionType, mSessionID, mSessionStartTime, lastNotificationBundle);
            if (Constants.StateTransitionType.STATE_TRANS_BG.equals(transitionType)) {
                lastNotificationBundle = null;
            }
        }
    }

    /**
     * Track that an Activity has started. Should only be called within the onStart method of your Activities,
     * and is only necessary for pre-API level 14 devices. Not necessary to use if your Activity extends an mParticle
     * Activity implementation.
     *
     * @see com.mparticle.activity.MPActivity
     * @see com.mparticle.activity.MPListActivity
     */
    public void activityStarted(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mConfigManager.getSendOoEvents()) {
                ensureActiveSession();
                mAppStateManager.onActivityStarted(activity);
            }
        }
    }

    /**
     * Track that an Activity has stopped. Should only be called within the onStop method of your Activities,
     * and is only necessary for pre-API level 14 devices. Not necessary to use if your Activity extends an mParticle
     * Activity implementation.
     *
     * @see com.mparticle.activity.MPActivity
     * @see com.mparticle.activity.MPListActivity
     */
    public void activityStopped(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mConfigManager.getSendOoEvents()) {
                ensureActiveSession();
                mAppStateManager.onActivityStopped(activity);
            }
        }
    }

    /**
     * Explicitly begin tracking a new session. Usually not necessary unless {@link #endSession()} is also used.
     */
    public void beginSession() {
        if (mConfigManager.getSendOoEvents()) {
            endSession();
            newSession();
        }
    }

    /**
     * Explicitly terminate the current user's session.
     */
    public void endSession() {
        if (mConfigManager.getSendOoEvents()) {
            long sessionEndTime = System.currentTimeMillis();
            endSession(sessionEndTime);
        }
    }

    private void endSession(long sessionEndTime) {
        if (mDebugMode)
            debugLog("Ended session");

       // mMessageManager.stopSession(mSessionID, sessionEndTime, sessionEndTime - mSessionStartTime);
        mMessageManager.endSession(mSessionID, sessionEndTime, sessionEndTime - mSessionStartTime);
        // reset agent to unstarted state
        mSessionStartTime = 0;
        mSessionID = "";
    }

    private void ensureActiveSession() {
        //    checkSessionTimeout();
        mLastEventTime = System.currentTimeMillis();
        if (0 == mSessionStartTime) {
            newSession();
        }
    }

    /**
     * Check current session timeout and end the session if needed. Will not start a new session.
     */
    /* package-private */Boolean checkSessionTimeout() {
        long now = System.currentTimeMillis();
        if (0 != mSessionStartTime &&
                mAppStateManager.isBackgrounded() &&
                (mConfigManager.getSessionTimeout() < now - mLastEventTime)) {
            if (mDebugMode)
                debugLog("Session timed out");

            endSession(mLastEventTime);
            return true;
        }
        return false;
    }

    /**
     * Creates a new session and generates the start-session message.
     */
    private void newSession() {
        mLastEventTime = mSessionStartTime = System.currentTimeMillis();
        mSessionID = UUID.randomUUID().toString();
        mEventCount = 0;
        mSessionAttributes = new JSONObject();
        mMessageManager.startSession(mSessionID, mSessionStartTime, mLaunchUri);
        mTimeoutHandler.sendEmptyMessageDelayed(0, mConfigManager.getSessionTimeout());
        if (mDebugMode)
            debugLog("Started new session");
        // clear the launch URI so it isn't sent on future sessions
        mLaunchUri = null;
    }

    /**
     * Force upload all queued messages to the mParticle server.
     */
    public void upload() {
        mMessageManager.doUpload();
    }

    /**
     * Manually set the install referrer. This will replace any install referrer that was
     * automatically retrieved upon installation from Google Play.
     */
    public void setInstallReferrer(String referrer) {
        sPreferences.edit().putString(PrefKeys.INSTALL_REFERRER, referrer).commit();
        if (mDebugMode)
            debugLog("Set installReferrer: " + referrer);
    }

    /**
     * Logs an event
     *
     * @param eventName the name of the event to be tracked (required not null)
     * @param eventType the type of the event to be tracked
     */
    public void logEvent(String eventName, EventType eventType) {
        logEvent(eventName, eventType, null, 0, null);
    }

    /**
     * Logs an event
     *
     * @param eventName the name of the event to be tracked (required not null)
     * @param eventType the type of the event to be tracked
     * @param category  the Google Analytics category with which to associate this event
     */
    public void logEvent(String eventName, EventType eventType, String category) {
        logEvent(eventName, eventType, null, 0, category);
    }

    /**
     * Logs an event
     *
     * @param eventName   the name of the event to be tracked  (required not null)
     * @param eventType   the type of the event to be tracked
     * @param eventLength the duration of the event in milliseconds
     */
    public void logEvent(String eventName, EventType eventType, long eventLength) {
        logEvent(eventName, eventType, null, eventLength);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName the name of the event to be tracked  (required not null)
     * @param eventType the type of the event to be tracked
     * @param eventInfo a Map of data attributes
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventInfo) {
        logEvent(eventName, eventType, eventInfo, 0);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName the name of the event to be tracked  (required not null)
     * @param eventType the type of the event to be tracked
     * @param eventInfo a Map of data attributes
     * @param category  the Google Analytics category with which to associate this event
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventInfo, String category) {
        logEvent(eventName, eventType, eventInfo, 0, category);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName   the name of the event to be tracked  (required not null)
     * @param eventType   the type of the event to be tracked
     * @param eventInfo   a Map of data attributes to associate with the event
     * @param eventLength the duration of the event in milliseconds
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventInfo, long eventLength) {
        logEvent(eventName, eventType, eventInfo, eventLength, null);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName   the name of the event to be tracked  (required not null)
     * @param eventType   the type of the event to be tracked
     * @param eventInfo   a Map of data attributes to associate with the event
     * @param eventLength the duration of the event in milliseconds
     * @param category    the Google Analytics category with which to associate this event
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventInfo, long eventLength, String category) {
        if (null == eventName) {
            Log.w(TAG, "eventName is required for logEvent");
            return;
        }

        if (eventName.length() > Constants.LIMIT_NAME) {
            Log.w(TAG, "The event name was too long. Discarding event.");
            return;
        }
        ensureActiveSession();
        if (checkEventLimit()) {
            if (category != null) {
                if (eventInfo == null) {
                    eventInfo = new HashMap<String, String>();
                }
                eventInfo.put(Constants.MessageKey.EVENT_CATEGORY, category);
            }
            JSONObject eventDataJSON = enforceAttributeConstraints(eventInfo);
            if (mConfigManager.getSendOoEvents()) {
                mMessageManager.logEvent(mSessionID, mSessionStartTime, mLastEventTime, eventName, eventType, eventDataJSON, eventLength);
                if (mDebugMode) {
                    if (null == eventDataJSON) {
                        debugLog("Logged event: " + eventName);
                    } else {
                        debugLog("Logged event: " + eventName + " with data " + eventDataJSON);
                    }
                }

            }
            embeddedKitManager.logEvent(eventType, eventName, eventDataJSON);
        }
    }

    /**
     * Logs an increase in the lifetime value of a user. This will signify an increase
     * in the revenue assigned to this user for service providers that support revenue tracking.
     *
     * @param valueIncreased    The currency value by which to increase the current user's LTV (required)
     * @param eventName         An event name to be associated with this increase in LTV (optional)
     * @param contextInfo       An MPProduct or any set of data to associate with this increase in LTV (optional)
     */
    public void logLtvIncrease(BigDecimal valueIncreased, String eventName, Map<String, String> contextInfo){
        if (valueIncreased == null){
            throw new IllegalArgumentException("ValueIncreased must not be null.");
        }
        if (contextInfo == null){
            contextInfo = new HashMap<String, String>();
        }
        contextInfo.put("$Amount", valueIncreased.toPlainString());
        contextInfo.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_LTV);
        logEvent(eventName == null ? "Increase LTV" : eventName, EventType.Transaction, contextInfo);
    }


    /**
     * Log an E-Commerce related event associated to a product
     *
     * @see com.mparticle.MPProduct
     * @see com.mparticle.MPProduct.Event
     *
     * @param event
     * @param product
     */
    public void logProductEvent(final MPProduct.Event event, MPProduct product) {
        if (product == null) {
            throw new IllegalArgumentException("MPProduct is required.");
        }
        if (product.isEmpty()) {
            throw new IllegalArgumentException("MPProduct data was null, please check that the MPProduct was built properly.");
        }
        if (event == null){
            throw new IllegalArgumentException("MPProduct.EVENT is required.");
        }
        boolean purchaseEvent = false;
        switch (event) {
            case VIEW:
                product.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_ECOMMERCE_VIEW);
                break;
            case REMOVE_FROM_CART:
                product.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_ECOMMERCE_REMOVE_FROM_CART);
                break;
            case ADD_TO_CART:
                product.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_ECOMMERCE_ADD_TO_CART);
                break;
            case ADD_TO_WISHLIST:
                product.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_ECOMMERCE_ADD_TO_WISHLIST);
                break;
            case REMOVE_FROM_WISHLIST:
                product.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_ECOMMERCE_REMOVE_FROM_WISHLIST);
                break;
            case PURCHASE:
                purchaseEvent = true;
                product.put(Constants.MethodName.METHOD_NAME, Constants.MethodName.LOG_ECOMMERCE);
                break;
        }

        ensureActiveSession();
        if (checkEventLimit()) {
            JSONObject transactionJson = enforceAttributeConstraints(product);
            mMessageManager.logEvent(mSessionID, mSessionStartTime, mLastEventTime, event.toString(), EventType.Transaction, transactionJson, 0);
            if (mDebugMode) {
                debugLog("Logged product event with data: " + product.toString());
            }

        }
        if (purchaseEvent) {
            embeddedKitManager.logTransaction(product);
        }

    }

    /**
     * Logs an e-commerce transaction event
     *
     * @param product (required not null)
     * @see com.mparticle.MPProduct
     */
    public void logTransaction(MPProduct product) {
        logProductEvent(MPProduct.Event.PURCHASE, product);
    }

    void logScreen(String screenName, Map<String, String> eventData, Boolean started) {
        if (null == screenName) {
            Log.w(TAG, "screenName is required for logScreen");
            return;
        }
        if (screenName.length() > Constants.LIMIT_NAME) {
            Log.w(TAG, "The screen name was too long. Discarding event.");
            return;
        }
        ensureActiveSession();
        if (checkEventLimit()) {
            JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
            if (mConfigManager.getSendOoEvents()) {
                mMessageManager.logScreen(mSessionID, mSessionStartTime, mLastEventTime, screenName, eventDataJSON, started);
                if (mDebugMode) {
                    if (null == eventDataJSON) {
                        debugLog("Logged screen: " + screenName);
                    } else {
                        debugLog("Logged screen: " + screenName + " with data " + eventDataJSON);
                    }
                }
            }
            embeddedKitManager.logScreen(screenName, eventDataJSON);
        }
    }

    /**
     * Logs a screen view event
     *
     * @param screenName the name of the screen to be tracked
     */
    public void logScreen(String screenName) {
        logScreen(screenName, null);
    }

    /**
     * Logs a screen view event
     *
     * @param screenName the name of the screen to be tracked
     * @param eventData  a Map of data attributes to associate with this screen view
     */
    public void logScreen(String screenName, Map<String, String> eventData) {
        logScreen(screenName, eventData, true);
    }

    /**
     * Leave a breadcrumb to be included with error and exception logging, as well as
     * with regular session events.
     *
     * @param breadcrumb
     */
    public void leaveBreadcrumb(String breadcrumb) {
        if (mConfigManager.getSendOoEvents()) {
            if (null == breadcrumb) {
                Log.w(TAG, "breadcrumb is required for leaveBreadcrumb");
                return;
            }
            if (breadcrumb.length() > Constants.LIMIT_NAME) {
                Log.w(TAG, "The breadcrumb name was too long. Discarding event.");
                return;
            }
            ensureActiveSession();
            mMessageManager.logBreadcrumb(mSessionID, mSessionStartTime, mLastEventTime, breadcrumb);
            if (mDebugMode)
                debugLog("Logged breadcrumb: " + breadcrumb);

        }
    }

    /**
     * Logs an error event
     *
     * @param message the name of the error event to be tracked
     */
    public void logError(String message) {
        logError(message, null);
    }

    /**
     * Logs an error event
     *
     * @param message   the name of the error event to be tracked
     * @param eventData a Map of data attributes to associate with this error
     */
    public void logError(String message, Map<String, String> eventData) {
        if (mConfigManager.getSendOoEvents()) {
            if (null == message) {
                Log.w(TAG, "message is required for logErrorEvent");
                return;
            }
            ensureActiveSession();
            if (checkEventLimit()) {
                JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
                mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, message, null, eventDataJSON);
                if (mDebugMode)
                    debugLog(
                            "Logged error with message: " + (message == null ? "<none>" : message) +
                                    " with data: " + (eventDataJSON == null ? "<none>" : eventDataJSON.toString())
                    );
            }
        }
    }

    public void logNetworkPerformance(String url, long startTime, String method, long length, long bytesSent, long bytesReceived) {
        logNetworkPerformance(url, startTime, method, length, bytesSent, bytesReceived, null);
    }

    void logNetworkPerformance(String url, long startTime, String method, long length, long bytesSent, long bytesReceived, String requestString) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            if (checkEventLimit()) {
                mMessageManager.logNetworkPerformanceEvent(mSessionID, mSessionStartTime, startTime, method, url, length, bytesSent, bytesReceived, requestString);
            }
        }
    }

    private void initNetworkMonitoring() {

        try {
            SocketImpl socket = (SocketImpl) MPUtility.getAccessibleObject(MPUtility.getAccessibleField(Socket.class, SocketImpl.class), new Socket());
            SocketImplFactory factory = new MPSocketImplFactory(socket.getClass());
            Socket.setSocketImplFactory(factory);
        } catch (Error e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Error initiating network performance monitoring: " + e.getMessage());
            }
        } catch (Exception e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Exception initiating network performance monitoring: " + e.getMessage());
            }
        }
        try {
            SSLSocketFactory currentSocketFactory = org.apache.http.conn.ssl.SSLSocketFactory.getSocketFactory();
            javax.net.ssl.SSLSocketFactory innerFactory = (javax.net.ssl.SSLSocketFactory) MPUtility.getAccessibleField(org.apache.http.conn.ssl.SSLSocketFactory.class, javax.net.ssl.SSLSocketFactory.class).get(currentSocketFactory);
            MPSSLSocketFactory wrapperFactory = new MPSSLSocketFactory(innerFactory);
            MPUtility.getAccessibleField(org.apache.http.conn.ssl.SSLSocketFactory.class, javax.net.ssl.SSLSocketFactory.class).set(currentSocketFactory, wrapperFactory);
        } catch (Error e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Error initiating network performance monitoring: " + e.getMessage());
            }
        } catch (Exception e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Exception initiating network performance monitoring: " + e.getMessage());
            }
        }

        try {
            MPUrlStreamHandlerFactory factory = new MPUrlStreamHandlerFactory();

            try {
                factory.createURLStreamHandler("https");
                factory.createURLStreamHandler("http");
            }catch (IllegalArgumentException iae){
                throw iae;
            }catch (Exception e){

            }


            URL.setURLStreamHandlerFactory(factory);
        } catch (Error e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Error initiating network performance monitoring: " + e.getMessage());
            }
        } catch (Exception e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Exception initiating network performance monitoring: " + e.getMessage());
            }
        }

        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(new MPSSLSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory()));
        } catch (Error e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Error initiating network performance monitoring: " + e.getMessage());
            }
        } catch (Exception e) {
            if (getDebugMode()) {
                Log.d(Constants.LOG_TAG, "Exception initiating network performance monitoring: " + e.getMessage());
            }
        }
        measuredRequestManager.setEnabled(true);

    }

    /**
     * Begin measuring network performance. This method only needs to be called one time during the runtime of an application.
     *
     */
    public void beginMeasuringNetworkPerformance() {
        if (!measuredRequestManager.getEnabled()) {
            mConfigManager.setNetworkingEnabled(true);
            initNetworkMonitoring();
        }
    }


    /**
     * Stop measuring network performance.
     *
     */
    public void endMeasuringNetworkPerformance() {
        if (measuredRequestManager.getEnabled()) {
            measuredRequestManager.setEnabled(false);
            mConfigManager.setNetworkingEnabled(false);
            try {
                javax.net.ssl.SSLSocketFactory current = HttpsURLConnection.getDefaultSSLSocketFactory();
                if (current instanceof MPSSLSocketFactory) {
                    HttpsURLConnection.setDefaultSSLSocketFactory(((MPSSLSocketFactory) current).delegateFactory);
                }
            } catch (Exception e) {
                if (getDebugMode()) {
                    Log.d(Constants.LOG_TAG, "Error stopping network performance monitoring: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Exclude the given URL substring from network measurement tracking. This method may be called repeatedly to add
     * multiple excluded URLs.
     *
     * @param url
     * @see #resetNetworkPerformanceExclusionsAndFilters()
     */
    public void excludeUrlFromNetworkPerformanceMeasurement(String url) {
        measuredRequestManager.addExcludedUrl(url);
    }

    /**
     * Specify a filter for query strings that should be logged. Call this method repeatedly to specify
     * multiple query string filters. By default, query strings will be removed from all measured URLs.
     *
     * @param filter
     * @see #resetNetworkPerformanceExclusionsAndFilters()
     */
    public void addNetworkPerformanceQueryOnlyFilter(String filter) {
        measuredRequestManager.addQueryStringFilter(filter);
    }

    /**
     * Remove all previously excluded URLs and allowed query filters. After this, all URLs will be
     * measured, and all query strings will be redacted when logging measurements.
     *
     * @see #excludeUrlFromNetworkPerformanceMeasurement(String)
     * @see #addNetworkPerformanceQueryOnlyFilter(String)
     *
     */
    public void resetNetworkPerformanceExclusionsAndFilters() {
        measuredRequestManager.resetFilters();
    }

    /**
     * Logs an Exception
     *
     * @param exception an Exception
     */
    public void logException(Exception exception) {
        logException(exception, null, null);
    }

    /**
     * Logs an Exception
     *
     * @param exception an Exception
     * @param eventData a Map of data attributes
     */
    public void logException(Exception exception, Map<String, String> eventData) {
        logException(exception, eventData, null);
    }

    /**
     * Logs an Exception
     *
     * @param exception an Exception
     * @param eventData a Map of data attributes
     * @param message   the name of the error event to be tracked
     */
    public void logException(Exception exception, Map<String, String> eventData, String message) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            if (checkEventLimit()) {
                JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
                mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, message, exception, eventDataJSON);
                if (mDebugMode)
                    debugLog(
                            "Logged exception with message: " + (message == null ? "<none>" : message) +
                                    " with data: " + (eventDataJSON == null ? "<none>" : eventDataJSON.toString()) +
                                    " with exception: " + (exception == null ? "<none>" : exception.getMessage())
                    );
            }
        }
    }

    void logUnhandledError(Throwable t) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, t != null ? t.getMessage() : null, t, null, false);
            //we know that the app is about to crash and therefore exit
            logStateTransition(Constants.StateTransitionType.STATE_TRANS_EXIT);
            endSession(System.currentTimeMillis());
        }
    }

    /**
     * Enables location tracking given a provider and update frequency criteria. The provider must
     * be available and the correct permissions must have been requested within your application's manifest XML file.
     *
     * @param provider    the provider key
     * @param minTime     the minimum time (in milliseconds) to trigger an update
     * @param minDistance the minimum distance (in meters) to trigger an update
     */
    public void enableLocationTracking(String provider, long minTime, long minDistance) {
        if (mConfigManager.getSendOoEvents()) {
            try {
                LocationManager locationManager = (LocationManager) mAppContext.getSystemService(Context.LOCATION_SERVICE);
                if (!locationManager.isProviderEnabled(provider)) {
                    Log.w(TAG, "That requested location provider is not available");
                    return;
                }

                if (null == mLocationListener) {
                    mLocationListener = new MParticleLocationListener(this);
                } else {
                    // clear the location listener, so it can be added again
                    locationManager.removeUpdates(mLocationListener);
                }
                locationManager.requestLocationUpdates(provider, minTime, minDistance, mLocationListener);
            } catch (SecurityException e) {
                Log.w(TAG, "The app must require the appropriate permissions to track location using this provider");
            }
        }
    }

    /**
     * Disables any mParticle location tracking that had been started
     */
    public void disableLocationTracking() {
        if (null != mLocationListener) {
            LocationManager locationManager = (LocationManager) mAppContext.getSystemService(Context.LOCATION_SERVICE);
            locationManager.removeUpdates(mLocationListener);
            mLocationListener = null;
        }
    }

    /**
     * Set the current location of the active session.
     *
     * @param location
     */
    public void setLocation(Location location) {
        mMessageManager.setLocation(location);
        embeddedKitManager.setLocation(location);
    }

    /**
     * Set a single <i>session</i> attribute. The attribute will combined with any existing session attributes.
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void setSessionAttribute(String key, String value) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            if (mDebugMode) {
                debugLog("Set session attribute: " + key + "=" + value);
            }
            if (setCheckedAttribute(mSessionAttributes, key, value, true)) {
                mMessageManager.setSessionAttributes(mSessionID, mSessionAttributes);
            }
        }
    }

    /**
     * Set a single <i>user</i> attribute. The attribute will combined with any existing user attributes.
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void setUserAttribute(String key, String value) {
        if (mDebugMode)
            if (value != null) {
                debugLog("Set user attribute: " + key + " with value " + value);
            } else {
                debugLog("Set user attribute: " + key);
            }

        if (setCheckedAttribute(mUserAttributes, key, value)) {
            sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
            embeddedKitManager.setUserAttributes(mUserAttributes);
        }

    }

    /**
     * Remove a <i>user</i> attribute
     *
     * @param key the key of the attribute
     */
    public void removeUserAttribute(String key) {
        if (mDebugMode && key != null) {
            debugLog("Removing user attribute: " + key);
        }
        if (mUserAttributes.has(key) || mUserAttributes.has(findCaseInsensitiveKey(mUserAttributes, key))) {
            mUserAttributes.remove(key);
            sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
            embeddedKitManager.removeUserAttribute(key);
        }
    }

    /**
     * Set a single user tag, it will be combined with any existing tags.
     *
     * @param tag a tag assigned to a user
     */
    public void setUserTag(String tag) {
        setUserAttribute(tag, null);
    }

    /**
     * Remove a user tag.
     *
     * @param tag a tag that was previously added
     */
    public void removeUserTag(String tag) {
        removeUserAttribute(tag);
    }



    /**
     * Set the current user's identity
     *
     * @param id
     * @param identityType
     */

    public void setUserIdentity(String id, IdentityType identityType) {
        if (id != null && id.length() > 0) {
            if (mDebugMode)
                debugLog("Setting user identity: " + id);

            if (null != id && id.length() > Constants.LIMIT_ATTR_VALUE) {
                Log.w(TAG, "Id value length exceeds limit. Discarding id: " + id);
                return;
            }

            embeddedKitManager.setUserIdentity(id, identityType);

            try {
                int index = -1;
                for (int i = 0; i < mUserIdentities.length(); i++) {
                    if (mUserIdentities.getJSONObject(i).get(MessageKey.IDENTITY_NAME).equals(identityType.value)) {
                        index = i;
                        break;
                    }
                }

                JSONObject newObject = new JSONObject();
                newObject.put(MessageKey.IDENTITY_NAME, identityType.value);
                newObject.put(MessageKey.IDENTITY_VALUE, id);

                if (index >= 0) {
                    newObject.put(MessageKey.IDENTITY_DATE_FIRST_SEEN, mUserIdentities.getJSONObject(index).optLong(MessageKey.IDENTITY_DATE_FIRST_SEEN, System.currentTimeMillis()));
                    newObject.put(MessageKey.IDENTITY_FIRST_SEEN, false);
                    mUserIdentities.put(index, newObject);
                } else {
                    newObject.put(MessageKey.IDENTITY_DATE_FIRST_SEEN, System.currentTimeMillis());
                    newObject.put(MessageKey.IDENTITY_FIRST_SEEN, true);
                    mUserIdentities.put(newObject);
                }

            } catch (JSONException e) {
                Log.w(TAG, "Error setting identity: " + id);
                return;
            }

            sPreferences.edit().putString(PrefKeys.USER_IDENTITIES + mApiKey, mUserIdentities.toString()).commit();
        }
    }

    /**
     * Remove an identity matching this id
     * <p/>
     * Note: this will only remove the *first* matching id
     *
     * @param id the id to remove
     */
    public void removeUserIdentity(String id) {
        if (id != null && id.length() > 0 && mUserIdentities != null) {
            try {
                int indexToRemove = -1;
                for (int i = 0; i < mUserIdentities.length(); i++) {
                    if (mUserIdentities.getJSONObject(i).getString(MessageKey.IDENTITY_VALUE).equals(id)) {
                        indexToRemove = i;
                        break;
                    }
                }
                if (indexToRemove >= 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        KitKatHelper.remove(mUserIdentities, indexToRemove);
                    } else {
                        JSONArray newIdentities = new JSONArray();
                        for (int i = 0; i < mUserIdentities.length(); i++) {
                            if (i != indexToRemove) {
                                newIdentities.put(mUserIdentities.get(i));
                            }
                        }
                    }
                    sPreferences.edit().putString(PrefKeys.USER_IDENTITIES + mApiKey, mUserIdentities.toString()).commit();

                }
            } catch (JSONException jse) {
                Log.w(TAG, "Error removing identity: " + id);
            }
        }
    }

    /* package-private */void clearUserAttributes() {
        mUserAttributes = new JSONObject();
        sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
    }

    /**
     * Get the current opt-out status for the application.
     *
     * @return the opt-out status
     */
    public Boolean getOptOut() {
        return mConfigManager.getOptedOut();
    }

    /**
     * Control the opt-in/opt-out status for the application.
     *
     * @param optOutStatus set to <code>true</code> to opt out of event tracking
     */
    public void setOptOut(Boolean optOutStatus) {
        if (optOutStatus != mConfigManager.getOptedOut()) {
            if (!optOutStatus) {
                ensureActiveSession();
            }
            mMessageManager.optOut(mSessionID, mSessionStartTime, System.currentTimeMillis(), optOutStatus);
            if (optOutStatus && mSessionStartTime > 0) {
                endSession();
            }

            mConfigManager.setOptOut(optOutStatus);

            if (mDebugMode)
                debugLog("Set opt-out: " + optOutStatus);
        }
    }

    /**
     * Turn on or off debug mode for mParticle. In debug mode, the mParticle SDK will output
     * informational messages to LogCat. This should never be enabled
     * in a production application.
     *
     * @param debugMode
     */
    public void setDebugMode(Boolean debugMode) {
        mConfigManager.setDebug(debugMode);
    }


    /**
     * Get the current debug mode status
     *
     * @return If debug mode is enabled or disabled
     */
    public Boolean getDebugMode() {
        return mConfigManager.isDebug();
    }

    /**
     * Turn on or off sandbox mode for mParticle. In sandbox mode, events will be fired immediately
     * and will be highlighted as sandbox events in the mParticle web console. This should never be enabled
     * in a production application.
     *
     * @param sandboxMode
     */
    public void setSandboxMode(Boolean sandboxMode) {
        mConfigManager.setSandboxMode(sandboxMode);
    }

    /**
     * Get the current sandbox mode status
     *
     * @return If sandbox mode is enabled or disabled
     */
    public Boolean getSandboxMode() {
        return mConfigManager.getSandboxMode();
    }


    /**
     * Set the upload interval period to control how frequently uploads occur.
     *
     * @param uploadInterval the number of seconds between uploads
     */
    public void setUploadInterval(int uploadInterval) {
        mConfigManager.setUploadInterval(uploadInterval);
    }

    /**
     * Set the upload interval period to control how frequently uploads occur when in debug mode.
     *
     * @param uploadInterval the number of seconds between uploads
     */
    public void setDebugUploadInterval(int uploadInterval) {
        mConfigManager.setDebugUploadInterval(uploadInterval);
    }

    /**
     * Enable mParticle exception handling to automatically log events on uncaught exceptions
     */
    public void enableUncaughtExceptionLogging() {
        if (null == mExHandler) {
            UncaughtExceptionHandler currentUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (!(currentUncaughtExceptionHandler instanceof ExceptionHandler)) {
                mExHandler = new ExceptionHandler(mMessageManager, currentUncaughtExceptionHandler);
                Thread.setDefaultUncaughtExceptionHandler(mExHandler);
            }
        }
    }

    /**
     * Disables mParticle exception handling and restores the original UncaughtExceptionHandler
     */
    public void disableUncaughtExceptionLogging() {
        if (null != mExHandler) {
            UncaughtExceptionHandler currentUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (currentUncaughtExceptionHandler instanceof ExceptionHandler) {
                Thread.setDefaultUncaughtExceptionHandler(mExHandler.getOriginalExceptionHandler());
                mExHandler = null;
            }
        }
    }

    /**
     * Register the application for GCM notifications
     *
     * @param senderId the SENDER_ID for the application
     */
    public void enablePushNotifications(String senderId) {
        if (MPUtility.checkPermission(mAppContext, "com.google.android.c2dm.permission.RECEIVE")) {
            mConfigManager.setPushSenderId(senderId);
            PushRegistrationHelper.enablePushNotifications(mAppContext, senderId);
        } else {
            Log.e(Constants.LOG_TAG, "Attempted to enable push notifications without required permission: " + "\"com.google.android.c2dm.permission.RECEIVE\"");
        }
    }

    /**
     * Unregister the application for GCM notifications
     */
    public void disablePushNotifications() {
        PushRegistrationHelper.disablePushNotifications(mAppContext);
    }

    /**
     * Enable the default notification sound for push notifications. This is a user preference that will be persisted across
     * application sessions.
     *
     * @param enabled
     */
    public void setNotificationSoundEnabled(Boolean enabled) {
        mConfigManager.setPushSoundEnabled(enabled);
    }

    /**
     * Enable the default notification vibration for push notifications. This is a user preference that will be persisted across
     * application sessions.
     *
     * @param enabled
     */
    public void setNotificationVibrationEnabled(Boolean enabled) {
        mConfigManager.setPushVibrationEnabled(enabled);
    }

    void clearPushNotificationId() {
        PushRegistrationHelper.clearPushRegistrationId(mAppContext, registrationListener);
    }

    void setPushRegistrationId(String registrationId) {
        PushRegistrationHelper.storeRegistrationId(mAppContext, registrationId, registrationListener);
    }

    /**
     * This method checks the event count is below the limit and increments the event count. A
     * warning is logged if the limit has been reached.
     *
     * @return true if event count is below limit
     */
    private Boolean checkEventLimit() {
        if (mEventCount < Constants.EVENT_LIMIT) {
            mEventCount++;
            return true;
        } else {
            Log.w(TAG, "The event limit has been exceeded for this session.");
            return false;
        }
    }

    /**
     * This method makes sure the constraints on event attributes are enforced. A JSONObject version
     * of the attributes is return with data that exceeds the limits removed. NOTE: Non-string
     * attributes are not converted to strings, currently.
     *
     * @param attributes the user-provided JSONObject
     * @return a cleansed copy of the JSONObject
     */
    /* package-private */JSONObject enforceAttributeConstraints(Map<String, String> attributes) {
        if (null == attributes) {
            return null;
        }
        JSONObject checkedAttributes = new JSONObject();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            setCheckedAttribute(checkedAttributes, key, value);
        }
        return checkedAttributes;
    }

    void performLicenseCheck() {
        String deviceId = Settings.Secure.getString(mAppContext.getContentResolver(), Settings.Secure.ANDROID_ID);

        MPLicenseCheckerCallback licenseCheckerCallback = new MPLicenseCheckerCallback();

        LicenseChecker checker = new LicenseChecker(
                mAppContext, new ServerManagedPolicy(mAppContext,
                new AESObfuscator(SALT, mAppContext.getPackageName(), deviceId)),
                mConfigManager.getLicenseKey()
        );
        checker.checkAccess(licenseCheckerCallback);
    }

    /**
     * Performs a license check to ensure that the application
     * was downloaded and/or purchased from Google Play and not "pirated" or "side-loaded".
     * <p/>
     * Optionally use the licensingCallback to allow or disallow access to features of your application.
     *
     * @param encodedPublicKey  GBase64-encoded RSA public key of your application
     * @param policy            <b>Optional</b> {@link Policy}, will default to {@link ServerManagedPolicy}
     * @param licensingCallback <b>Optional</b> {@link LicenseCheckerCallback} callback for licensing checking
     */
    private void performLicenseCheck(String encodedPublicKey, Policy policy, LicenseCheckerCallback licensingCallback) {
        if (encodedPublicKey == null || encodedPublicKey.length() == 0) {
            throw new IllegalArgumentException("LicenseKey null or invalid.");
        }

        if (licensingCallback == null) {
            Log.w(TAG, "No licensing callback specified, using MParticle default.");
        }

        clientLicensingCallback = licensingCallback;

        if (policy == null) {
            Log.w(TAG, "No policy specified, using default ServerManagedPolicy");
            String deviceId = Settings.Secure.getString(mAppContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            policy = new ServerManagedPolicy(mAppContext,
                    new AESObfuscator(SALT, mAppContext.getPackageName(), deviceId));
        }

        MPLicenseCheckerCallback licenseCheckerCallback = new MPLicenseCheckerCallback();
        LicenseChecker checker = new LicenseChecker(
                mAppContext, policy, encodedPublicKey);
        checker.checkAccess(licenseCheckerCallback);
    }

    private void debugLog(String message) {
        if (null != mSessionID) {
            Log.d(TAG, mApiKey + ": " + mSessionID + ": " + message);
        } else {
            Log.d(TAG, mApiKey + ": " + message);
        }
    }

    /**
     * Retrieves the current setting of automatic screen tracking.
     *
     * @return The current setting of automatic screen tracking.
     */

    public Boolean isAutoTrackingEnabled() {
        return mConfigManager.isAutoTrackingEnabled();
    }

    /**
     * Retrieves the current session timeout setting in seconds
     *
     * @return The current session timeout setting in seconds
     */
    public int getSessionTimeout() {
        return mConfigManager.getSessionTimeout() / 1000;
    }

    /**
     * Set the user session timeout interval.
     * <p/>
     * A session has ended once the application has been in the background for more than this timeout
     *
     * @param sessionTimeout Session timeout in seconds
     */
    public void setSessionTimeout(int sessionTimeout) {
        mConfigManager.setSessionTimeout(sessionTimeout);
    }

    /* package private */ void logNotification(Bundle notificationBundle, String appState) {
        lastNotificationBundle = notificationBundle;
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mMessageManager.logNotification(mSessionID, mSessionStartTime, lastNotificationBundle, appState);
        }
    }

    /**
     * Set the resource ID of the icon to be shown in the notification bar when a notification is received.
     * <p/>
     * By default, the app launcher icon will be shown.
     *
     * @param resId the resource id of a drawable
     */
    public void setPushNotificationIcon(int resId) {
        mConfigManager.setPushNotificationIcon(resId);
    }

    /**
     * Set the resource ID of the title to be shown in the notification bar when a notification is received
     * <p/>
     * By default, the title of the application will be shown.
     *
     * @param resId the resource id of a string
     */
    public void setPushNotificationTitle(int resId) {
        mConfigManager.setPushNotificationTitle(resId);
    }

    public void getUserSegments(long timeout, String endpointId, SegmentListener listener){
        if (mMessageManager != null && mMessageManager.mUploadHandler != null){
            mMessageManager.mUploadHandler.fetchSegments(timeout, endpointId, listener);
        }
    }

    /**
     * Event type to use when logging events.
     *
     * @see #logEvent(String, com.mparticle.MParticle.EventType)
     */

    public enum EventType {
        Unknown, Navigation, Location, Search, Transaction, UserContent, UserPreference, Social, Other;

        public String toString() {
            return name();
        }
    }

    /**
     * To be used when initializing MParticle
     *
     * @see #start(android.content.Context, com.mparticle.MParticle.InstallType)
     */

    public enum InstallType {
        /**
         * This is the default value. Using this value will rely on the mParticle SDK to differentiate a new install vs. an upgrade
         */
        AutoDetect,
        /**
         * In the case where your app has never seen this user before.
         */
        KnownInstall,
        /**
         * In the case where you app has seen this user before
         */
        KnownUpgrade;

        public String toString() {
            return name();
        }
    }

    /**
     * Identity type to use when setting the user identity.
     *
     * @see #setUserIdentity(String, com.mparticle.MParticle.IdentityType)
     */

    public enum IdentityType {
        Other(0),
        CustomerId(1),
        Facebook(2),
        Twitter(3),
        Google(4),
        Microsoft(5),
        Yahoo(6),
        Email(7),
        Alias(8);

        private final int value;

        private IdentityType(int value) {
            this.value = value;
        }

        public static IdentityType parseInt(int val) {
            switch (val) {
                case 1:
                    return CustomerId;
                case 2:
                    return Facebook;
                case 3:
                    return Twitter;
                case 4:
                    return Google;
                case 5:
                    return Microsoft;
                case 6:
                    return Yahoo;
                case 7:
                    return Email;
                default:
                    return Other;

            }
        }

        public int getValue() {
            return value;
        }

    }

    public interface Push {
        public static final String BROADCAST_NOTIFICATION_RECEIVED = "com.mparticle.push.NOTIFICATION_RECEIVED";
        public static final String BROADCAST_NOTIFICATION_TAPPED = "com.mparticle.push.NOTIFICATION_TAPPED";
        public static final String PUSH_ALERT_EXTRA = "com.mparticle.push.alert";
    }

    public interface UserAttributes {
        //A special attribute string to specify the mobile number of the consumer's device
        public static final String MOBILE_NUMBER = "$Mobile";
        //A special attribute string to specify the consumer's gender.
        public static final String GENDER = "$Gender";
        //A special attribute string to specify the consumer's age.
        public static final String AGE = "$Age";
        //A special attribute string to specify the consumer's country.
        public static final String COUNTRY = "$Country";
        //A special attribute string to specify the consumer's zip code.
        public static final String ZIPCODE = "$Zip";
        //A special attribute string to specify the consumer's city.
        public static final String CITY = "$City";
        //A special attribute string to specify the consumer's state or region.
        public static final String STATE = "$State";
        //A special attribute string to specify the consumer's street address and apartment number.
        public static final String ADDRESS = "$Address";
        //A special attribute string to specify the consumer's first name.
        public static final String FIRSTNAME = "$FirstName";
        //A special attribute string to specify the consumer's last name.
        public static final String LASTNAME = "$LastName";
    }

    private static final class SessionTimeoutHandler extends Handler {
        private final MParticle mParticle;

        public SessionTimeoutHandler(MParticle mParticle, Looper looper) {
            super(looper);
            this.mParticle = mParticle;
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (!mParticle.checkSessionTimeout()) {
                sendEmptyMessageDelayed(0, mParticle.getSessionTimeout() * 1000);
            }
        }
    }

    private static final class MParticleLocationListener implements LocationListener {
        private final MParticle mParticle;

        public MParticleLocationListener(MParticle mParticle) {
            this.mParticle = mParticle;
        }

        @Override
        public void onLocationChanged(Location location) {
            mParticle.setLocation(location);
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

    }

    private class MPLicenseCheckerCallback implements LicenseCheckerCallback {
        public void allow(int policyReason) {
            if (policyReason == Policy.LICENSED) {
                sPreferences.edit().putBoolean(PrefKeys.PIRATED, false).commit();
            }
            if (clientLicensingCallback != null) {
                clientLicensingCallback.allow(policyReason);
            }
        }

        public void dontAllow(int policyReason) {
            if (policyReason == ServerManagedPolicy.NOT_LICENSED) {
                sPreferences.edit().putBoolean(PrefKeys.PIRATED, true).commit();
            }
            if (clientLicensingCallback != null) {
                clientLicensingCallback.dontAllow(policyReason);
            }
        }

        public void applicationError(int errorCode) {
            if (errorCode == LicenseCheckerCallback.ERROR_MISSING_PERMISSION) {
                Log.e(TAG, "License checking enabled but app is missing permission: \"com.android.vending.CHECK_LICENSE\"");
            }
            if (clientLicensingCallback != null) {
                clientLicensingCallback.applicationError(errorCode);
            }
        }
    }
}
