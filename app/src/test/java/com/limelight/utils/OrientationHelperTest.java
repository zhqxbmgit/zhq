package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class OrientationHelperTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
    }

    @Test
    public void portraitMapsToFixedPortrait() {
        assertEquals(
                PreferenceConfiguration.AppOrientation.PORTRAIT,
                PreferenceConfiguration.parseAppOrientation("portrait"));
        assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                OrientationHelper.toRequestedOrientation(
                        PreferenceConfiguration.AppOrientation.PORTRAIT));
    }

    @Test
    public void landscapeMapsToFixedLandscape() {
        assertEquals(
                PreferenceConfiguration.AppOrientation.LANDSCAPE,
                PreferenceConfiguration.parseAppOrientation("landscape"));
        assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                OrientationHelper.toRequestedOrientation(
                        PreferenceConfiguration.AppOrientation.LANDSCAPE));
    }

    @Test
    public void unknownFallsBackToPortrait() {
        assertEquals(
                PreferenceConfiguration.AppOrientation.PORTRAIT,
                PreferenceConfiguration.parseAppOrientation("automatic"));
    }

    @Test
    public void nullFallsBackToPortrait() {
        assertEquals(
                PreferenceConfiguration.AppOrientation.PORTRAIT,
                PreferenceConfiguration.parseAppOrientation(null));
    }

    @Test
    public void missingPreferenceFallsBackToPortrait() {
        assertEquals(
                PreferenceConfiguration.AppOrientation.PORTRAIT,
                PreferenceConfiguration.getAppOrientation(context));
    }

    @Test
    public void corruptPreferenceTypeFallsBackToPortrait() {
        preferences.edit()
                .putBoolean(PreferenceConfiguration.APP_ORIENTATION_PREF_STRING, true)
                .commit();

        assertEquals(
                PreferenceConfiguration.AppOrientation.PORTRAIT,
                PreferenceConfiguration.getAppOrientation(context));
    }

    @Test
    public void helperOnlyReturnsFixedOrientations() {
        int portrait = OrientationHelper.toRequestedOrientation(
                PreferenceConfiguration.AppOrientation.PORTRAIT);
        int landscape = OrientationHelper.toRequestedOrientation(
                PreferenceConfiguration.AppOrientation.LANDSCAPE);

        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, portrait);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, landscape);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, portrait);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, landscape);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_USER, portrait);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_USER, landscape);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, portrait);
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, landscape);
    }
}
