package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.view.MotionEvent;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * SlideButton:在普通按键基础上增加"按住+滑动"功能。盲操作友好。
 *
 * 行为:
 *  - 点一下(不滑动)       -> 抬起时发一次原键(如 B)的完整点击。
 *  - 按住 + 向上滑(超阈值) -> 按住"上滑键",抬起才松;原键全程不发。
 *  - 按住 + 向下滑(超阈值) -> 按住"下滑键",抬起才松;原键全程不发。
 *
 * 设计:按下时"不"立即发原键,等抬起时若全程没滑动,才补发一次原键的完整点击。
 * 这样滑动触发时,原键(B)从头到尾都不会被发出,绝无误触。
 * 代价:原键点击在抬起时才生效(有延迟),且无法按住原键。
 */
public class SlideButton extends VirtualControllerElement {

    public interface SlideButtonListener {
        void onBaseClick();        // 原键按下(如B)
        void onBaseRelease();      // 原键松开
        void onSlideUp();          // 上滑键按下
        void onSlideUpRelease();   // 上滑键松开
        void onSlideDown();        // 下滑键按下
        void onSlideDownRelease(); // 下滑键松开
    }

    // 滑动触发阈值(dp)。手指上/下移动超过对应阈值才算"滑动"。
    // 调大=该方向更不易误触发(原键更稳);调小=更灵敏。上下可分别设。
    private static final float SLIDE_UP_THRESHOLD_DP = 1f;    // 向上滑阈值
    private static final float SLIDE_DOWN_THRESHOLD_DP = 4f;  // 向下滑阈值
    // 🎯 补发点击时,按下与松开之间的保持时长(毫秒)。
    //    游戏按帧轮询,太短会漏点(点几次才中一次)。20ms覆盖60Hz/120Hz轮询都够,
    //    且延迟感几乎没有、连点够快。某游戏仍漏点再调大(25、30)。
    private static final int TAP_HOLD_MS = 20;
    // 清脆震动时长(ms,仅Android9及以下用;Android10+用系统清脆效果)。0=关闭。
    private static final int SLIDE_VIBRATE_MS = 20;

    private final Vibrator vibrator;

    private SlideButtonListener listener = null;
    private String text = "";
    private int icon = -1;

    private final float slideUpThresholdPx;
    private final float slideDownThresholdPx;

    private float downY = 0;
    private boolean basePressed = false;      // 原键(B)是否正按着
    private boolean slideTriggered = false;   // 本次是否已判定为滑动
    private int slideDir = 0;                  // 当前滑动方向 -1=上 +1=下 0=无

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    public SlideButton(VirtualController controller, int elementId, int layer, Context context) {
        super(controller, context, elementId);
        float density = context.getResources().getDisplayMetrics().density;
        this.slideUpThresholdPx = SLIDE_UP_THRESHOLD_DP * density;
        this.slideDownThresholdPx = SLIDE_DOWN_THRESHOLD_DP * density;
        Vibrator v = null;
        try {
            v = (Vibrator) context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            v = null;
        }
        this.vibrator = v;
    }

    public void setSlideButtonListener(SlideButtonListener l) { this.listener = l; }
    public void setText(String t) { this.text = t; invalidate(); }
    public void setIcon(int id) { this.icon = id; invalidate(); }

    // 震动:短而强的一下 createOneShot(10ms, 255强度)。不依赖系统预定义效果,
    // 在普通马达上也能稳定输出。仅在用户开启震动设置时触发。
    private void slideVibrate() {
        if (vibrator == null || SLIDE_VIBRATE_MS <= 0) return;
        if (!PreferenceConfiguration.readPreferences(getContext()).enableKeyboardVibrate) return;
        try {
            if (!vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10, 255));
            } else {
                vibrator.vibrate(10);
            }
        } catch (Exception e) { }
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        canvas.drawColor(Color.TRANSPARENT);
        paint.setTextSize(getPercent(getWidth(), 25));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeWidth(getDefaultStrokeWidth());

        int currentColor = (basePressed || slideTriggered) ? pressedColor : getDefaultColor();
        rect.left = rect.top = paint.getStrokeWidth();
        rect.right = getWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;

        paint.setColor(currentColor);
        paint.setStyle(Paint.Style.FILL);
        // 跟随"普通按钮为方形按钮"设置:勾选画方块,否则画圆,与其他按键统一
        if (PreferenceConfiguration.readPreferences(getContext()).enableKeyboardSquare) {
            canvas.drawRect(rect, paint);
        } else {
            canvas.drawOval(rect, paint);
        }

        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(getDefaultStrokeWidth() / 2);
        paint.setColor(Color.WHITE);
        canvas.drawText(text, getPercent(getWidth(), 50), getPercent(getHeight(), 63), paint);
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                downY = event.getY();
                slideTriggered = false;
                slideDir = 0;
                // 按下先不发原键(B),仅视觉按下+震动;抬起时若全程没滑动才补发完整点击。
                // 这样滑动触发时,原键(B)从头到尾不会被发出,绝无误触。
                basePressed = true;
                slideVibrate();   // 按下:短强震
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!slideTriggered) {
                    float dy = event.getY() - downY;
                    // 向上滑(dy<0)用上滑阈值,向下滑(dy>0)用下滑阈值
                    float threshold = (dy < 0) ? slideUpThresholdPx : slideDownThresholdPx;
                    if (Math.abs(dy) >= threshold) {
                        slideTriggered = true;
                        // 原键(B)从未发出,只需取消视觉按下态
                        basePressed = false;
                        // 按住对应方向的滑动键(屏幕Y向上为负)
                        slideDir = (dy < 0) ? -1 : 1;
                        slideVibrate();   // 上下滑触发:短强震
                        if (listener != null) {
                            if (slideDir < 0) listener.onSlideUp();
                            else listener.onSlideDown();
                        }
                        invalidate();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (slideTriggered) {
                    // 滑动模式:松开滑动键
                    if (listener != null) {
                        if (slideDir < 0) listener.onSlideUpRelease();
                        else if (slideDir > 0) listener.onSlideDownRelease();
                    }
                    slideTriggered = false;
                    slideDir = 0;
                } else {
                    // 没滑动:补发一次原键(B)完整点击。
                    // 关键:按下与松开之间延迟 TAP_HOLD_MS,保证游戏按帧轮询能采到"按下"状态,
                    //       否则瞬间Down+Up常被游戏漏掉(表现为"点几次才中一次")。
                    if (action == MotionEvent.ACTION_UP && listener != null) {
                        final SlideButtonListener l = listener;
                        l.onBaseClick();
                        postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                l.onBaseRelease();
                            }
                        }, TAP_HOLD_MS);
                    }
                }
                basePressed = false;
                invalidate();
                return true;
            }
        }
        return true;
    }
}
