package com.mparticle;

import static org.mockito.Mockito.*;

import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;

import com.mparticle.Constants.PrefKeys;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.AndroidTestCase;

public class OptOutTests extends AndroidTestCase {

    private MessageManager mMockMessageManager;
    private SharedPreferences mPrefs;
    private MParticleAPI mMParticleAPI;

    @Override
    protected void setUp() throws Exception {
      super.setUp();
      mMockMessageManager = mock(MessageManager.class);
      mPrefs = getContext().getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
      mPrefs.edit().remove(PrefKeys.OPTOUT+"TestAppKey").commit();
      mMParticleAPI = new MParticleAPI(getContext(), "TestAppKey", mMockMessageManager);
    }

    @Override
    protected void tearDown() throws Exception {
      super.tearDown();
      mPrefs.edit().remove(PrefKeys.OPTOUT+"TestAppKey").commit();
    }

    public void testOptedOutMessages() throws JSONException {
        HashMap<String, String> eventData= new HashMap<String, String>();
        eventData.put("testKey1", "testValue1");
        mMParticleAPI.setOptOut(true);
        mMParticleAPI.start();
        mMParticleAPI.setSessionProperty("testKey1", "testValue1");
        mMParticleAPI.setUserProperty("testKey1", "testValue1");
        mMParticleAPI.logEvent("event1");
        mMParticleAPI.logEvent("event2", eventData);
        mMParticleAPI.newSession();
        mMParticleAPI.logScreenView("view1");
        mMParticleAPI.logScreenView("view2", eventData);
        mMParticleAPI.logErrorEvent("error1");
        mMParticleAPI.logErrorEvent("error2");
        mMParticleAPI.logErrorEvent("error3", null, new Exception("exception1"));
        mMParticleAPI.stop();
        mMParticleAPI.endSession();
        verify(mMockMessageManager, times(1)).optOut(anyString(), anyLong(), anyLong(), eq(true));
        verify(mMockMessageManager, never()).startSession(anyString(), anyLong());
        verify(mMockMessageManager, never()).setSessionAttributes(anyString(), any(JSONObject.class));
        verify(mMockMessageManager, never()).logScreenView(anyString(), anyLong(), anyLong(), anyString(), any(JSONObject.class));
        verify(mMockMessageManager, never()).logCustomEvent(anyString(), anyLong(), anyLong(), anyString(), any(JSONObject.class));
        verify(mMockMessageManager, never()).logErrorEvent(anyString(), anyLong(), anyLong(), anyString(), any(JSONObject.class), any(Throwable.class));
        verify(mMockMessageManager, never()).stopSession(anyString(), anyLong(), anyLong());
        verify(mMockMessageManager, never()).endSession(anyString(), anyLong(), anyLong());
    }

    public void testOptedOutActiveSession() throws JSONException {
        JSONObject eventData=new JSONObject();
        eventData.put("testKey1", "testValue1");
        mMParticleAPI.start();
        mMParticleAPI.setSessionProperty("testKey1", "testValue1");
        mMParticleAPI.logEvent("event1");
        mMParticleAPI.setOptOut(true);
        mMParticleAPI.logEvent("event2");
        mMParticleAPI.stop();
        mMParticleAPI.endSession();
        mMParticleAPI.start();
        mMParticleAPI.setSessionProperty("testKey1", "testValue1");
        mMParticleAPI.logEvent("event1");
        mMParticleAPI.stop();
        mMParticleAPI.endSession();

        verify(mMockMessageManager, times(1)).optOut(anyString(), anyLong(), anyLong(), eq(true));
        verify(mMockMessageManager, times(1)).startSession(anyString(), anyLong());
        verify(mMockMessageManager, times(1)).logCustomEvent(anyString(), anyLong(), anyLong(), anyString(), any(JSONObject.class));
        verify(mMockMessageManager, times(1)).stopSession(anyString(), anyLong(), anyLong());
        verify(mMockMessageManager, times(1)).endSession(anyString(), anyLong(), anyLong());
    }

    public void testOptOutAndIn() throws JSONException {
        mMParticleAPI.start();
        mMParticleAPI.setSessionProperty("testKey1", "testValue1");
        mMParticleAPI.setUserProperty("testKey1", "testValue1");
        mMParticleAPI.logEvent("event1");
        mMParticleAPI.setOptOut(true);
        mMParticleAPI.setSessionProperty("testKey2", "testValue1");
        mMParticleAPI.setUserProperty("testKey2", "testValue1");
        mMParticleAPI.logEvent("event2");
        mMParticleAPI.endSession();
        mMParticleAPI.start();
        mMParticleAPI.setSessionProperty("testKey3", "testValue2");
        mMParticleAPI.setUserProperty("testKey3", "testValue2");
        mMParticleAPI.logEvent("event3");
        mMParticleAPI.setOptOut(false);
        mMParticleAPI.setSessionProperty("testKey4", "testValue2");
        mMParticleAPI.setUserProperty("testKey4", "testValue2");
        mMParticleAPI.logEvent("event4");
        mMParticleAPI.endSession();

        verify(mMockMessageManager, times(2)).optOut(anyString(), anyLong(), anyLong(), anyBoolean());
        verify(mMockMessageManager, times(1)).optOut(anyString(), anyLong(), anyLong(), eq(true));
        verify(mMockMessageManager, times(1)).optOut(anyString(), anyLong(), anyLong(), eq(false));
        verify(mMockMessageManager, times(2)).startSession(anyString(), anyLong());
        verify(mMockMessageManager, times(2)).logCustomEvent(anyString(), anyLong(), anyLong(), anyString(), any(JSONObject.class));
        verify(mMockMessageManager, times(1)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event1"), any(JSONObject.class));
        verify(mMockMessageManager, times(0)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event2"), any(JSONObject.class));
        verify(mMockMessageManager, times(0)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event3"), any(JSONObject.class));
        verify(mMockMessageManager, times(1)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event4"), any(JSONObject.class));
        verify(mMockMessageManager, times(2)).setSessionAttributes(anyString(), any(JSONObject.class));
        verify(mMockMessageManager, times(2)).stopSession(anyString(), anyLong(), anyLong());
        verify(mMockMessageManager, times(2)).endSession(anyString(), anyLong(), anyLong());
    }

    public void testMultipleCallsToOptOut() throws JSONException {
        mMParticleAPI.setOptOut(false);
        mMParticleAPI.start();
        mMParticleAPI.setOptOut(true);
        mMParticleAPI.logEvent("event1");
        mMParticleAPI.setOptOut(true);
        mMParticleAPI.logEvent("event2");
        mMParticleAPI.setOptOut(false);
        mMParticleAPI.logEvent("event3");
        mMParticleAPI.setOptOut(false);
        mMParticleAPI.logEvent("event4");
        mMParticleAPI.endSession();

        verify(mMockMessageManager, times(2)).optOut(anyString(), anyLong(), anyLong(), anyBoolean());
        verify(mMockMessageManager, times(1)).optOut(anyString(), anyLong(), anyLong(), eq(true));
        verify(mMockMessageManager, times(1)).optOut(anyString(), anyLong(), anyLong(), eq(false));
        verify(mMockMessageManager, times(2)).startSession(anyString(), anyLong());
        verify(mMockMessageManager, times(2)).logCustomEvent(anyString(), anyLong(), anyLong(), anyString(), any(JSONObject.class));
        verify(mMockMessageManager, times(0)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event1"), any(JSONObject.class));
        verify(mMockMessageManager, times(0)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event2"), any(JSONObject.class));
        verify(mMockMessageManager, times(1)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event3"), any(JSONObject.class));
        verify(mMockMessageManager, times(1)).logCustomEvent(anyString(), anyLong(), anyLong(), eq("event4"), any(JSONObject.class));
        verify(mMockMessageManager, times(2)).endSession(anyString(), anyLong(), anyLong());
    }

}