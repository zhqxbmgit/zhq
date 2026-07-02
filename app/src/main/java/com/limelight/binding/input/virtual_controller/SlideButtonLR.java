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
 *  - 快速点一下(不滑动)    -> 抬起时发一次原键(如 X)的完整点击。单击延迟不变。
 *  - 按住不放(超过长按窗口)  -> 补发原键按下并保持,松手才松,实现长按。
 *  - 按住 + 向左滑(超阈值) -> 按住"左滑键",抬起才松;原键全程不发。
 *  - 按住 + 向右滑(超阈值) -> 按住"右滑键",抬起才松;原键全程不发。
 *  - 按住 + 向上滑(超阈值) -> 按住"上滑键",抬起才松;原键全程不发。
 *
 * 设计:按下时"不"立即发原键。短按=抬手立即补发完整点击;长按=超过 LONG_PRESS_MS
 * 补发按下并保持;滑动=判定后原键不发(防误触)。代价:长按有约 LONG_PRESS_MS 启动延迟。
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
    private static final float SLIDE_LEFT_THRESHOLD_DP = 14f;   // 向左滑阈值
    private static final float SLIDE_RIGHT_THRESHOLD_DP = 8f;   // 向右滑阈值
    private static final float SLIDE_UP_THRESHOLD_DP = 4f;      // 向上滑阈值
    // 🎯 补发点击时,按下与松开之间的保持时长(毫秒)。
    //    游戏按帧轮询,太短会漏点(点几次才中一次)。20ms覆盖60Hz/120Hz轮询都够,
    //    且延迟感几乎没有、连点够快。某游戏仍漏点再调大(25、30)。
    private static final int TAP_HOLD_MS = 20;
    // 🎯 长按判定窗口(毫秒)。按住超过这个时间且没滑动,就进入"按住模式":
    //    补发原键按下并保持,直到松手才松开,实现长按。
    //    短于此时间就抬手=单击(单击延迟不变,抬手即发)。调小=长按更快触发但更易误判;调大=要按更久才算长按。
    private static final int LONG_PRESS_MS = 100;
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
    private boolean longPressActive = false;   // 是否已进入长按(原键已补发按下并保持中)
    private Runnable longPressRunnable = null;  // 长按延时任务

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
                longPressActive = false;
                // 按下先不发原键(X),仅视觉按下+震动;抬起时若全程没滑动才补发完整点击。
                // 这样滑动触发时,原键(X)从头到尾不会被发出,绝无误触。
                basePressed = true;
                slideVibrate();   // 按下:清脆震动(EFFECT_CLICK)
                // 启动长按判定:超过 LONG_PRESS_MS 仍未滑动、未抬手,则补发按下并保持(长按)
                longPressRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (basePressed && !slideTriggered && !longPressActive) {
                            longPressActive = true;
                            if (listener != null) listener.onBaseClick();   // 补发按下并保持(不补松开)
                        }
                    }
                };
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!slideTriggered) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    // 左右用各自阈值
                    float lrThreshold = (dx < 0) ? slideLeftThresholdPx : slideRightThresholdPx;
                    boolean lrOver = Math.abs(dx) >= lrThreshold;
                    // 上滑(dy<0)只在向上时判定
                    boolean upOver = (dy < 0) && (Math.abs(dy) >= slideUpThresholdPx);

                    // 🎯 左右优先策略:只要左右到达阈值,就判左右(不和上滑比例比较)。
                    //    只有左右都没到、纯粹向上时,才判上滑。
                    //    这样向右滑时即使带一点向上分量,也不会误判成上滑。
                    if (lrOver) {
                        slideTriggered = true;
                        if (longPressRunnable != null) {
                            removeCallbacks(longPressRunnable);
                            longPressRunnable = null;
                        }
                        basePressed = false;   // 原键(X)从未发出,只取消视觉按下态
                        slideDir = (dx < 0) ? -1 : 1;   // 左/右
                        slideVibrate();   // 滑动触发:清脆震动
                        if (listener != null) {
                            if (slideDir < 0) listener.onSlideLeft();
                            else listener.onSlideRight();
                        }
                        invalidate();
                    } else if (upOver) {
                        slideTriggered = true;
                        if (longPressRunnable != null) {
                            removeCallbacks(longPressRunnable);
                            longPressRunnable = null;
                        }
                        basePressed = false;
                        slideDir = 2;   // 上
                        slideVibrate();
                        if (listener != null) listener.onSlideUp();
                        invalidate();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // 抬手:取消还没触发的长按延时任务
                if (longPressRunnable != null) {
                    removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
                if (slideTriggered) {
                    // 滑动模式:松开滑动键
                    if (listener != null) {
                        if (slideDir == 2) listener.onSlideUpRelease();
                        else if (slideDir < 0) listener.onSlideLeftRelease();
                        else if (slideDir > 0) listener.onSlideRightRelease();
                    }
                    slideTriggered = false;
                    slideDir = 0;
                } else if (longPressActive) {
                    // 长按模式:按下已在 LONG_PRESS_MS 时补发,这里只松开
                    if (listener != null) listener.onBaseRelease();
                    longPressActive = false;
                } else {
                    // 短按(没滑动、没到长按时间):补发一次原键(X)完整点击。
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
