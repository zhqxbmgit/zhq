package com.limelight.utils;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import com.limelight.preferences.PreferenceConfiguration;

public final class OrientationHelper {
    private OrientationHelper() {
    }

    public static void apply(Activity activity) {
        apply(activity, PreferenceConfiguration.getAppOrientation(activity));
    }

    public static void apply(Activity activity, Object preferenceValue) {
        String value = preferenceValue != null ? preferenceValue.toString() : null;
        apply(activity, PreferenceConfiguration.parseAppOrientation(value));
    }

    public static int toRequestedOrientation(
            PreferenceConfiguration.AppOrientation orientation) {
        if (orientation == PreferenceConfiguration.AppOrientation.LANDSCAPE) {
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }

        return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    private static void apply(
            Activity activity,
            PreferenceConfiguration.AppOrientation orientation) {
        int targetOrientation = toRequestedOrientation(orientation);
        if (activity.getRequestedOrientation() != targetOrientation) {
            activity.setRequestedOrientation(targetOrientation);
        }
    }
}
