package com.limelight.binding.input.touch;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

import android.content.Context;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibrationEffect;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TrackpadContext implements TouchContext {

    private float lastTouchX = 0, lastTouchY = 0, originalTouchX = 0, originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled, confirmedMove, isDragging;
    private long lastTapUpTime = 0;

    private final NvConnection conn;
    private final int actionIndex;
    private boolean swapAxis = false;
    private float finalMultiplierX, finalMultiplierY;
    private final Vibrator vibrator;   // 点击振动器,可为null(无振动)

    private static final float LINEAR_SPEED_MULTIPLIER = 7.0f;
    private static final int TAP_DURATION_MAX = 300;
    private static final int DOUBLE_TAP_INTERVAL = 130;
    private static final float TAP_MOVEMENT_THRESHOLD = 8f;
    // 🎯 单击按住时长(毫秒):按下后保持这么久再抬起,确保游戏按帧轮询能采到"按下"状态。
    //    游戏漏点就调大(80、100);桌面/连点偏慢可调小(40)。
    private static final int TAP_HOLD_MS = 25;
    // 🎯 点击振动时长(毫秒):盲操作时确认"点到了"。0=关闭振动。
    //    注:Android10+用系统清脆点击效果(此时长不生效);Android9及以下才用这个时长。
    private static final int CLICK_VIBRATE_MS = 20;

    private static final int TICK_RATE_MS = 4;
    private static final double DT = TICK_RATE_MS / 1000.0;

    // 🎯 时间常数：控制整体跟随快慢 (推荐 0.02 ~ 0.03)
    private static final double SMOOTHING_TIME_CONSTANT = 0.04;
    private static final double MAX_VELOCITY = 15000.0;
    private static final double MAX_ACCELERATION = 80000.0;
    private static final double GLIDE_DECELERATION = 120000.0;
    private static final double POS_THRESHOLD = 0.5;
    private static final double VEL_THRESHOLD = 2.0;

    private static final ScheduledExecutorService SHARED_TICKER_SERVICE = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> tickerFuture = null;
    private final Object stateLock = new Object();

    private volatile boolean isTouching = false;
    private boolean gliding = false;

    private float startAbsX = 0, startAbsY = 0;
    private double targetAccumX = 0, targetAccumY = 0;
    private double currentPosX = 0, currentPosY = 0;
    private double currentVelX = 0, currentVelY = 0;
    private double lastSentX = 0, lastSentY = 0;
    private double carryOverX = 0, carryOverY = 0;

    // 原构造函数(无振动):保持兼容,vibrator为null
    public TrackpadContext(NvConnection conn, int actionIndex, boolean swapAxis, int sensitivityX, int sensitivityY) {
        this(conn, actionIndex, swapAxis, sensitivityX, sensitivityY, null);
    }

    // 🎯 新构造函数(带振动):传入Context以获取振动器。game.java用这个就有点击振动。
    public TrackpadContext(NvConnection conn, int actionIndex, boolean swapAxis, int sensitivityX, int sensitivityY, Context context) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.swapAxis = swapAxis;
        float baseSensX = (sensitivityX == 0) ? 1.0f : sensitivityX / 100f;
        float baseSensY = (sensitivityY == 0) ? 1.0f : sensitivityY / 100f;
        this.finalMultiplierX = baseSensX * LINEAR_SPEED_MULTIPLIER;
        this.finalMultiplierY = baseSensY * LINEAR_SPEED_MULTIPLIER;
        // 获取振动器(用ApplicationContext避免持有Activity引用导致内存泄漏)
        Vibrator v = null;
        if (context != null) {
            try {
                v = (Vibrator) context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
            } catch (Exception e) {
                v = null;
            }
        }
        this.vibrator = v;
    }

    @Override public int getActionIndex() { return actionIndex; }

    private boolean isWithinTapBounds(float touchX, float touchY) {
        return Math.abs(touchX - originalTouchX) <= TAP_MOVEMENT_THRESHOLD && Math.abs(touchY - originalTouchY) <= TAP_MOVEMENT_THRESHOLD;
    }

    // 🎯 触发一次短促振动(点击反馈)。vibrator为null或时长为0时静默跳过。
    //    Android10+用系统预定义的清脆"咔哒"效果(厂商调校,最接近真实鼠标);老版本降级。
    private void clickVibrate() {
        if (vibrator == null || CLICK_VIBRATE_MS <= 0) return;
        try {
            if (!vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ : 系统级轻微"嗒"反馈(EFFECT_TICK比EFFECT_CLICK更轻,
                //               用于和虚拟按键的清脆CLICK区分开,盲操作可辨别)
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8/9 : 自定义短促微振(时长用常量,幅度偏低更清脆)
                vibrator.vibrate(VibrationEffect.createOneShot(CLICK_VIBRATE_MS, 120));
            } else {
                // 老设备降级兼容
                vibrator.vibrate(CLICK_VIBRATE_MS);
            }
        } catch (Exception e) {
            // 振动失败不影响点击功能,静默忽略
        }
    }

    @Override
    public boolean touchDownEvent(float eventX, float eventY, long eventTime, boolean isNewFinger) {
        if (isNewFinger) {
            originalTouchX = lastTouchX = eventX;
            originalTouchY = lastTouchY = eventY;
            originalTouchTime = eventTime;
            cancelled = false; confirmedMove = false;
            startAbsX = eventX; startAbsY = eventY;
            isTouching = true; gliding = false;

            synchronized (stateLock) {
                targetAccumX = carryOverX; targetAccumY = carryOverY;
                currentPosX = targetAccumX; currentPosY = targetAccumY;
                currentVelX = 0; currentVelY = 0;
                lastSentX = 0; lastSentY = 0;
            }
            if (eventTime - lastTapUpTime <= DOUBLE_TAP_INTERVAL) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT); isDragging = true;
            } else { isDragging = false; }
            startSmoothingTicker();
        }
        return true;
    }

    @Override
    public void touchUpEvent(float eventX, float eventY, long eventTime) {
        if (cancelled) return;
        isTouching = false;
        synchronized (stateLock) { gliding = true; } // 进入纯惯性滑行

        if (isDragging) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT); isDragging = false; lastTapUpTime = eventTime;
        } else if (!confirmedMove) {
            if (eventTime - originalTouchTime <= TAP_DURATION_MAX && isWithinTapBounds(eventX, eventY)) {
                // 🎯 按下后延迟抬起:用ticker线程定时发Up,中间保持TAP_HOLD_MS的"按下"状态。
                //    不能用Thread.sleep(会卡线程),用调度器延迟发送最干净。
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                clickVibrate();   // 单击:发出时振动
                SHARED_TICKER_SERVICE.schedule(
                        () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT),
                        TAP_HOLD_MS, TimeUnit.MILLISECONDS);
                lastTapUpTime = eventTime;
            }
        }
        confirmedMove = false;
    }

    @Override
    public boolean touchMoveEvent(float eventX, float eventY, long eventTime) {
        if (cancelled) return true;
        if (eventX != lastTouchX || eventY != lastTouchY) {
            if (!isDragging && !confirmedMove && !isWithinTapBounds(eventX, eventY)) confirmedMove = true;

            float totalDx = eventX - startAbsX, totalDy = eventY - startAbsY;
            float mappedDx = swapAxis ? totalDy : totalDx;
            float mappedDy = swapAxis ? totalDx : totalDy;

            synchronized (stateLock) {
                targetAccumX = carryOverX + mappedDx * finalMultiplierX;
                targetAccumY = carryOverY + mappedDy * finalMultiplierY;
            }
        }
        lastTouchX = eventX; lastTouchY = eventY;
        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true; isTouching = false; gliding = false; stopSmoothingTicker();
        synchronized (stateLock) {
            targetAccumX = 0; targetAccumY = 0; currentPosX = 0; currentPosY = 0;
            currentVelX = 0; currentVelY = 0; lastSentX = 0; lastSentY = 0; carryOverX = 0; carryOverY = 0;
        }
        if (isDragging) { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT); isDragging = false; }
        confirmedMove = false;
    }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setPointerCount(int pointerCount) {}

    private void startSmoothingTicker() {
        if (tickerFuture != null) { tickerFuture.cancel(false); tickerFuture = null; }
        tickerFuture = SHARED_TICKER_SERVICE.scheduleAtFixedRate(this::tick, 0, TICK_RATE_MS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        short sendDeltaX = 0, sendDeltaY = 0;
        boolean shouldStop = false;

        synchronized (stateLock) {
            if (gliding) {
                // 🌟 纯惯性滑行：松手后的丝滑减速
                double curSpeed = Math.sqrt(currentVelX * currentVelX + currentVelY * currentVelY);
                double decel = GLIDE_DECELERATION * DT;
                if (curSpeed <= decel + VEL_THRESHOLD) {
                    currentVelX = 0; currentVelY = 0;
                    if (!isTouching) shouldStop = true;
                } else {
                    double scale = (curSpeed - decel) / curSpeed;
                    currentVelX *= scale; currentVelY *= scale;
                    currentPosX += currentVelX * DT; currentPosY += currentVelY * DT;
                }
            } else {
                double distX = targetAccumX - currentPosX;
                double distY = targetAccumY - currentPosY;
                double distLen = Math.sqrt(distX * distX + distY * distY);
                double currentSpeed = Math.sqrt(currentVelX * currentVelX + currentVelY * currentVelY);

                if (distLen < POS_THRESHOLD && currentSpeed < VEL_THRESHOLD) {
                    currentPosX = targetAccumX; currentPosY = targetAccumY;
                    currentVelX = 0; currentVelY = 0;
                    if (!isTouching) shouldStop = true;
                } else {
                    // 🛡️ 核心重构：二阶强阻尼弹簧模型 (彻底消灭弹簧、反弹、挂钩)

                    // 防反弹灵魂参数：阻尼比 (Damping Ratio)
                    // = 1.0 是临界阻尼(最快且无震荡)
                    // > 1.0 是过阻尼(绝对不反弹，稍微慢一点点)
                    // 设为 1.3 可彻底锁死任何反弹和弹簧效应，且不影响跟手
                    double dampingRatio = 1.0;

                    // 自然频率 (由时间常数决定)
                    double omega = 1.0 / SMOOTHING_TIME_CONSTANT;

                    // 弹簧-阻尼加速度公式： a = (dist * w^2) - (vel * 2 * zeta * w)
                    // 第一项(弹簧)拉向目标，第二项(阻尼)抵抗当前速度(防冲过头/防挂钩)
                    double accX = (distX * omega * omega) - (currentVelX * 2.0 * dampingRatio * omega);
                    double accY = (distY * omega * omega) - (currentVelY * 2.0 * dampingRatio * omega);

                    // 限制最大加速度 (防起步抽搐)
                    double accLen = Math.sqrt(accX * accX + accY * accY);
                    if (accLen > MAX_ACCELERATION) {
                        double scale = MAX_ACCELERATION / accLen;
                        accX *= scale; accY *= scale;
                    }

                    // 更新速度
                    currentVelX += accX * DT;
                    currentVelY += accY * DT;

                    // 限制最大速度 (防失控)
                    double velLen = Math.sqrt(currentVelX * currentVelX + currentVelY * currentVelY);
                    if (velLen > MAX_VELOCITY) {
                        double scale = MAX_VELOCITY / velLen;
                        currentVelX *= scale; currentVelY *= scale;
                    }

                    // 更新位置
                    currentPosX += currentVelX * DT;
                    currentPosY += currentVelY * DT;
                }
            }

            double moveX = currentPosX - lastSentX, moveY = currentPosY - lastSentY;
            sendDeltaX = (short) Math.round(moveX); sendDeltaY = (short) Math.round(moveY);
            if (sendDeltaX != 0 || sendDeltaY != 0) { lastSentX += sendDeltaX; lastSentY += sendDeltaY; }

            if (shouldStop) {
                carryOverX = currentPosX - lastSentX;
                carryOverY = currentPosY - lastSentY;
            }
        }

        if (sendDeltaX != 0 || sendDeltaY != 0) conn.sendMouseMove(sendDeltaX, sendDeltaY);
        if (shouldStop) stopSmoothingTicker();
    }

    private void stopSmoothingTicker() {
        if (tickerFuture != null) { tickerFuture.cancel(false); tickerFuture = null; }
    }
}
