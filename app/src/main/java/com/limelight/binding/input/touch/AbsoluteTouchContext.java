package com.limelight.binding.input.touch;

import android.view.View;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

public class AbsoluteTouchContext implements TouchContext {
    private boolean cancelled;
    private final NvConnection conn;
    private final int actionIndex;
    private final View view;
    private final boolean swapButtons;

    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view, boolean swapButtons) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.view = view;
        this.swapButtons = swapButtons;
    }

    @Override
    public int getActionIndex() {
        return actionIndex;
    }

    @Override
    public boolean touchDownEvent(float eventX, float eventY, long eventTime, boolean isNewFinger) {
        cancelled = false;
        if (actionIndex == 0) {
            conn.sendMousePosition((short) eventX, (short) eventY, (short) view.getWidth(), (short) view.getHeight());
            conn.sendMouseButtonDown(swapButtons ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT);
        }
        else if (actionIndex == 1) {
            conn.sendMouseButtonDown(swapButtons ? MouseButtonPacket.BUTTON_LEFT : MouseButtonPacket.BUTTON_RIGHT);
        }
        else if (actionIndex == 2) {
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
        }
        return true;
    }

    @Override
    public void touchUpEvent(float eventX, float eventY, long eventTime) {
        if (cancelled) return;

        if (actionIndex == 0) {
            conn.sendMouseButtonUp(swapButtons ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT);
        }
        else if (actionIndex == 1) {
            conn.sendMouseButtonUp(swapButtons ? MouseButtonPacket.BUTTON_LEFT : MouseButtonPacket.BUTTON_RIGHT);
        }
        else if (actionIndex == 2) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
        }
    }

    @Override
    public boolean touchMoveEvent(float eventX, float eventY, long eventTime) {
        if (cancelled) return true;

        if (actionIndex == 0) {
            conn.sendMousePosition((short) eventX, (short) eventY, (short) view.getWidth(), (short) view.getHeight());
        }
        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;
        if (actionIndex == 0) {
            conn.sendMouseButtonUp(swapButtons ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT);
        }
        else if (actionIndex == 1) {
            conn.sendMouseButtonUp(swapButtons ? MouseButtonPacket.BUTTON_LEFT : MouseButtonPacket.BUTTON_RIGHT);
        }
        else if (actionIndex == 2) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {}
}