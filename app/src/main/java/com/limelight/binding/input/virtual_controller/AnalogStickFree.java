/**
 * Created by Karim Mreisi.
 *
 * Modified Features:
 * - 🎯 Blind Operation Mode: Decoupled visual constraints from data output.
 * - 📏 Extended Travel: Added TRAVEL_MULTIPLIER for high-precision, long-travel analog data output.
 * - 🐛 Ghost Touch Fixed: Added ACTION_CANCEL to prevent touch deadlocks during rapid swipes.
 * - ⚡ Instant Teleport Override: Removed !bIsFingerOnScreen lock, forcing the stick to INSTANTLY snap to any new tap.
 * - 📐 Vector Math Rewrite: Completely removed the broken trigonometry (atan/sin/cos).
 * - ⚡ Zero delay single click & Single precise haptic feedback.
 */
package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

public class AnalogStickFree extends VirtualControllerElement {

    public static final int SIZE_RADIUS_COMPLETE = 90;
    public static final int SIZE_RADIUS_ANALOG_STICK = 90;

    public interface AnalogStickListener {
        void onMovement(float x, float y);
        void onClick();
        void onDoubleClick(); // 保留接口声明以防报错
        void onRevoke();
    }

    private float radius_complete = 0;
    private float radius_analog_stick = 0;

    private boolean bIsFingerOnScreen = false;
    private float position_stick_x = 0;
    private float position_stick_y = 0;

    private final Paint paint = new Paint();

    private List<AnalogStickListener> listeners = new ArrayList<>();

    private int touchID;
    private float touchStartX;
    private float touchStartY;

    protected String strStickSide = "L";

    // =========================================================
    // 🚀 【新增】：盲操专属行程放大倍数
    // 3.0f 代表你需要滑动的物理距离是摇杆原本半径的 3 倍，才能达到 100% 满轴。
    // 觉得太灵敏就调大 (如 4.0f)，觉得推到底太累就调小 (如 2.0f)
    // =========================================================
    private final float TRAVEL_MULTIPLIER = 4.0f;

    private int bgCircleColor = 0x2BF5F5F9;
    private int strokeCircleColor = 0xFF8F8F8F;

    public AnalogStickFree(VirtualController controller, Context context, int elementId) {
        super(controller, context, elementId);
        position_stick_x = getWidth() / 2.0f;
        position_stick_y = getHeight() / 2.0f;
    }

    public void addAnalogStickListener(AnalogStickListener listener) {
        listeners.add(listener);
    }

    private void notifyOnMovement(float x, float y) {
        for (AnalogStickListener listener : listeners) {
            listener.onMovement(x, y);
        }
    }

    private void notifyOnClick() {
        for (AnalogStickListener listener : listeners) {
            listener.onClick();
        }
    }

    private void notifyOnRevoke() {
        for (AnalogStickListener listener : listeners) {
            listener.onRevoke();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        radius_complete = getPercent(getCorrectWidth() / 2, 100) - 2 * getDefaultStrokeWidth();
        radius_analog_stick = getPercent(getCorrectWidth() / 2, 20);
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        boolean bEditMove = virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons;
        boolean bEditResize = virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons;
        boolean bEditEnable = virtualController.getControllerMode() == VirtualController.ControllerMode.DisableEnableButtons;
        boolean editMode = bEditMove || bEditResize || bEditEnable;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getDefaultStrokeWidth());
        canvas.drawColor(Color.TRANSPARENT);

        if (bIsFingerOnScreen) {
            // 自由摇杆：手指点在哪里，底座大圈就在哪里画出来
            paint.setColor(strokeCircleColor);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(touchStartX, touchStartY, radius_complete, paint);

            // 画内圈小圆点
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bgCircleColor);
            canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paint);
        } else {
            // 没触摸时：底座大圈和小圈乖乖呆在整个控件的正中心
            paint.setColor(strokeCircleColor);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, radius_complete, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bgCircleColor);
            canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, radius_analog_stick, paint);
        }

        if (editMode) {
            canvas.drawColor(getDefaultColor());
            paint.setColor(Color.WHITE);
            int w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(Math.min(w, h) / 2);
            canvas.drawText(strStickSide, w / 2, h / 2, paint);
        }
    }

    public void setBgOpacity() {
        int hexOpacity = PreferenceConfiguration.readPreferences(getContext()).enableNewAnalogStickOpacity * 255 / 100;
        this.bgCircleColor = (hexOpacity << 24) | (bgCircleColor & 0x00FFFFFF);
        this.strokeCircleColor = (hexOpacity << 24) | (pressedColor & 0x00FFFFFF);
        invalidate();
    }

    @Override
    public void setOpacity(int opacity) {
        super.setOpacity(opacity);
        setBgOpacity();
    }

    // =========================================================
    // 🚀 盲操究极版：抛弃一切视觉包袱，纯粹的长行程数据发射器
    // =========================================================
    private void calculateMovement(float currentX, float currentY) {
        float dx = currentX - touchStartX;
        float dy = currentY - touchStartY;

        // 1. 算出手指滑动的真实物理距离
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance == 0) {
            notifyOnMovement(0, 0);
            return;
        }

        // 2. 【核心算法】：算出拉长后的满轴行程
        float original_radius = radius_complete - radius_analog_stick;
        float required_travel = original_radius * TRAVEL_MULTIPLIER;

        // 3. 计算手指滑动进度 (0.0 到 1.0)
        float output_ratio = distance / required_travel;
        if (output_ratio > 1.0f) {
            output_ratio = 1.0f; // 封顶 100%
        }

        // 4. 算出最终要发给 Steam 的 X/Y 轴数据 (-1.0 到 1.0)
        float out_x = (dx / distance) * output_ratio;
        float out_y = (dy / distance) * output_ratio;

        // 直接极速发包！
        notifyOnMovement(out_x, out_y);

        // 视觉处理：因为你根本不看屏幕，这里直接让内圈小红点死死跟着你的物理手指走，
        // 不做任何多余的限制运算，既能防止原版 UI 报错，又节省了手机 CPU 算力。
        position_stick_x = currentX;
        position_stick_y = currentY;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 【核心修复：暴君级强行瞬移】
                // 彻底移除了 if (!bIsFingerOnScreen) 的保护锁。
                // 现在只要你的手速再快、上一次滑动因为手势冲突没清理干净，
                // 只要指尖落下来，立刻无条件抹除一切旧状态，强制把中心瞬移到这根手指上！
                touchID = event.getPointerId(actionIndex);
                touchStartX = event.getX(actionIndex);
                touchStartY = event.getY(actionIndex);

                // 按下第一瞬间，立即触发单次震动
                if (!bIsFingerOnScreen && PreferenceConfiguration.readPreferences(getContext()).enableKeyboardVibrate) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS);
                }

                bIsFingerOnScreen = true;

                notifyOnClick();
                setPressed(true);
                calculateMovement(touchStartX, touchStartY);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (touchID == event.getPointerId(i)) {
                        calculateMovement(event.getX(i), event.getY(i));
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                // 【核心修复：彻底消灭幽灵触控】
                // 必须监听 ACTION_CANCEL。当快速滑动触发边缘系统手势时，系统会发 CANCEL
                // 如果不监听它，摇杆就会假死，导致下一次点击被拒绝瞬移。
                if (action == MotionEvent.ACTION_CANCEL || touchID == event.getPointerId(actionIndex)) {
                    setPressed(false);
                    bIsFingerOnScreen = false;
                }
                break;
            }
        }

        if (!isPressed()) {
            notifyOnRevoke();
            notifyOnMovement(0, 0);

            // 自由摇杆松开时，让内圈圆点相对居中，准备下一次顺滑召唤
            position_stick_x = getWidth() / 2.0f;
            position_stick_y = getHeight() / 2.0f;
        }

        invalidate();
        return true;
    }
}