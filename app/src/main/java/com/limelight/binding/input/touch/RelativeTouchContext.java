package com.limelight.binding.input.touch;

import android.view.View;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;

public class RelativeTouchContext extends TrackpadContext {
    private final View view;
    private final int videoWidth;
    private final int videoHeight;
    private final float scaleX;
    private final float scaleY;

    public RelativeTouchContext(NvConnection conn, int actionIndex, int videoWidth, int videoHeight, View view, PreferenceConfiguration prefConfig) {
        super(conn, actionIndex, prefConfig.trackpadSwapAxis, prefConfig.trackpadSensitivityX, prefConfig.trackpadSensitivityY);
        this.view = view;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;

        // Default scale factor is 1.0 (unscaled)
        scaleX = prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH ? 1.0f : ((float) videoWidth) / view.getWidth();
        scaleY = prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH ? 1.0f : ((float) videoHeight) / view.getHeight();
    }

    @Override
    public boolean touchMoveEvent(float eventX, float eventY, long eventTime) {
        // We override this to apply a dynamic sensitivity based on the stream view's scaling,
        // so moving X physical inches on the screen translates to the same visual movement
        // across the remote desktop.

        // This is not perfect because the video might have black bars, meaning the view is larger
        // than the actual video being displayed. However, since the mouse movement is confined to
        // the video area anyway, scaling by the view dimensions is close enough.

        // We temporarily adjust the sensitivity of the TrackpadContext
        // before handing off the event for standard processing.
        // NOTE: Since the parent TrackpadContext uses float, we pass the raw float values directly.
        return super.touchMoveEvent(eventX * scaleX, eventY * scaleY, eventTime);
    }
}