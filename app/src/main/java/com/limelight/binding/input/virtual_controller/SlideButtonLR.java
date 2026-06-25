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
 * SlideButtonLR:横向"按住+滑动"按键(左右版)。盲操作友好。
 *
 * 行为:
 *  - 点一下(不滑动)       -> 抬起时发一次原键(如 X)的完整点击。
 *  - 按住 + 向左滑(超阈值) -> 按住"左滑键",抬起才松;原键全程不发。
 *  - 按住 + 向右滑(超阈值) -> 按住"右滑键",抬起才松;原键全程不发。
 *  - 按住 + 向上滑(超阈值) -> 按住"上滑键",抬起才松;原键全程不发。
 *
 * 设计:按下时"不"立即发原键,等抬起时若全程没滑动,才补发一次原键的完整点击。
 * 这样滑动触发时,原键(X)从头到尾都不会被发出,绝无误触。
 * 代价:原键点击在抬起时才生效(有延迟),且无法按住原键。
 */
public class SlideButtonLR extends VirtualControllerElement {

    public interface SlideButtonLRListener {
        void onBaseClick();         // 原键按下(如X)
        void onBaseRelease();       // 原键松开
        void onSlideLeft();         // 左滑键按下
        void onSlideLeftRelease();  // 左滑键松开
        void onSlideRight();        // 右滑键按下
        void onSlideRightRelease(); // 右滑键松开
        void onSlideUp();           // 上滑键按下
        void onSlideUpRelease();    // 上滑键松开
    }

    // 滑动触发阈值(dp)。手指左/右移动超过对应阈值才算"滑动"。
    // 调大=该方向更不易误触发(原键更稳);调小=更灵敏。左右可分别设。
    private static final float SLIDE_LEFT_THRESHOLD_DP = 12f;   // 向左滑阈值
    private static final float SLIDE_RIGHT_THRESHOLD_DP = 3f;  // 向右滑阈值
    private static final float SLIDE_UP_THRESHOLD_DP = 1f;     // 向上滑阈值
    // 🎯 补发点击时,按下与松开之间的保持时长(毫秒)。
    //    游戏按帧轮询,太短会漏点(点几次才中一次)。20ms覆盖60Hz/120Hz轮询都够,
    //    且延迟感几乎没有、连点够快。某游戏仍漏点再调大(25、30)。
    private static final int TAP_HOLD_MS = 20;
    // 清脆震动时长(ms,仅Android9及以下用;Android10+用系统清脆效果)。0=关闭。
    private static final int SLIDE_VIBRATE_MS = 20;

    private final Vibrator vibrator;

    private SlideButtonLRListener listener = null;
    private String text = "";
    private int icon = -1;

    private final float slideLeftThresholdPx;
    private final float slideRightThresholdPx;
    private final float slideUpThresholdPx;

    private float downX = 0;
    private float downY = 0;
    private boolean basePressed = false;      // 原键(X)是否正按着
    private boolean slideTriggered = false;   // 本次是否已判定为滑动
    private int slideDir = 0;                  // 当前滑动方向 -1=左 +1=右 2=上 0=无

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    public SlideButtonLR(VirtualController controller, int elementId, int layer, Context context) {
        super(controller, context, elementId);
        float density = context.getResources().getDisplayMetrics().density;
        this.slideLeftThresholdPx = SLIDE_LEFT_THRESHOLD_DP * density;
        this.slideRightThresholdPx = SLIDE_RIGHT_THRESHOLD_DP * density;
        this.slideUpThresholdPx = SLIDE_UP_THRESHOLD_DP * density;
        Vibrator v = null;
        try {
            v = (Vibrator) context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            v = null;
        }
        this.vibrator = v;
    }

    public void setSlideButtonLRListener(SlideButtonLRListener l) { this.listener = l; }
    public void setText(String t) { this.text = t; invalidate(); }
    public void setIcon(int id) { this.icon = id; invalidate(); }

    // 清脆震动:Android10+用系统预定义EFFECT_CLICK(厂商调校最清脆),老版本降级。
    // 仅在用户开启了震动设置时触发,与全局开关一致。
    private void slideVibrate() {
        if (vibrator == null || SLIDE_VIBRATE_MS <= 0) return;
        if (!PreferenceConfiguration.readPreferences(getContext()).enableKeyboardVibrate) return;
        try {
            if (!vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(SLIDE_VIBRATE_MS, 120));
            } else {
                vibrator.vibrate(SLIDE_VIBRATE_MS);
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
                downX = event.getX();
                downY = event.getY();
                slideTriggered = false;
                slideDir = 0;
                // 按下先不发原键(X),仅视觉按下+震动;抬起时若全程没滑动才补发完整点击。
                // 这样滑动触发时,原键(X)从头到尾不会被发出,绝无误触。
                basePressed = true;
                slideVibrate();   // 按下:清脆震动(EFFECT_CLICK)
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!slideTriggered) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    // 上滑(dy<0)只在向上时判定;左右用各自阈值。
                    boolean upOver = (dy < 0) && (Math.abs(dy) >= slideUpThresholdPx);
                    float lrThreshold = (dx < 0) ? slideLeftThresholdPx : slideRightThresholdPx;
                    boolean lrOver = Math.abs(dx) >= lrThreshold;

                    // 同时超过时,取位移更大的方向(以各自阈值归一化比较,谁先到位算谁)
                    boolean pickUp = false;
                    if (upOver && lrOver) {
                        float upRatio = Math.abs(dy) / slideUpThresholdPx;
                        float lrRatio = Math.abs(dx) / lrThreshold;
                        pickUp = upRatio >= lrRatio;
                    } else if (upOver) {
                        pickUp = true;
                    }

                    if (upOver || lrOver) {
                        slideTriggered = true;
                        // 原键(X)从未发出,只需取消视觉按下态
                        basePressed = false;
                        slideVibrate();   // 滑动触发:清脆震动
                        if (pickUp) {
                            slideDir = 2;   // 上
                            if (listener != null) listener.onSlideUp();
                        } else {
                            slideDir = (dx < 0) ? -1 : 1;   // 左/右
                            if (listener != null) {
                                if (slideDir < 0) listener.onSlideLeft();
                                else listener.onSlideRight();
                            }
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
                        if (slideDir == 2) listener.onSlideUpRelease();
                        else if (slideDir < 0) listener.onSlideLeftRelease();
                        else if (slideDir > 0) listener.onSlideRightRelease();
                    }
                    slideTriggered = false;
                    slideDir = 0;
                } else {
                    // 没滑动:补发一次原键(X)完整点击。
                    // 关键:按下与松开之间延迟 TAP_HOLD_MS,保证游戏按帧轮询能采到"按下"状态,
                    //       否则瞬间Down+Up常被游戏漏掉(表现为"点几次才中一次")。
                    if (action == MotionEvent.ACTION_UP && listener != null) {
                        final SlideButtonLRListener l = listener;
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
