package com.limelight.binding.input.touch;

public interface TouchContext {
    int getActionIndex();

    // 🔥 全部升级为 float
    boolean touchDownEvent(float eventX, float eventY, long eventTime, boolean isNewFinger);
    void touchUpEvent(float eventX, float eventY, long eventTime);
    boolean touchMoveEvent(float eventX, float eventY, long eventTime);

    void cancelTouch();
    boolean isCancelled();
    void setPointerCount(int pointerCount);
}