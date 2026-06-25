/**
 * Created by Karim Mreisi.
 *
 * Modified Features:
 * - Smart Haptic Feedback: Analog sticks can pass -1 to bypass continuous vibration.
 * - Smart Release Mute: Digitial buttons and triggers now only vibrate on press, not on release,
 * by tracking the previous input state.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

public class VirtualController {
    public static class ControllerInputContext {
        public int inputMap = 0;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;

    ControllerMode currentMode = ControllerMode.Active;
    ControllerInputContext inputContext = new ControllerInputContext();

    // 【新增】：用于记录上一次的按键状态，智能拦截松开时的震动
    private int lastInputMap = 0;
    private byte lastLeftTrigger = 0x00;
    private byte lastRightTrigger = 0x00;

    private Button buttonConfigure = null;

    private List<VirtualControllerElement> elements = new ArrayList<>();

    private Vibrator vibrator;

    private final VibrationEffect defaultVibrationEffect;

    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultVibrationEffect = VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            defaultVibrationEffect = null;
        }

        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleConfigMode();
            }
        });

    }

    // 🎯 循环切换配置模式(Active→禁用/启用→移动→缩放→保存退出)。
    //    原本由悬浮齿轮触发,现在抽成public方法,可由游戏菜单调用。
    public void cycleConfigMode() {
        String message;

        if (currentMode == ControllerMode.Active) {
            currentMode = ControllerMode.DisableEnableButtons;
            showElements();
            message = context.getString(R.string.configuration_mode_disable_enable_buttons);
        } else if (currentMode == ControllerMode.DisableEnableButtons){
            currentMode = ControllerMode.MoveButtons;
            showEnabledElements();
            message = context.getString(R.string.configuration_mode_move_buttons);
        } else if (currentMode == ControllerMode.MoveButtons) {
            currentMode = ControllerMode.ResizeButtons;
            message = context.getString(R.string.configuration_mode_resize_buttons);
        } else {
            currentMode = ControllerMode.Active;
            VirtualControllerConfigurationLoader.saveProfile(VirtualController.this, context);
            message = context.getString(R.string.configuration_mode_exiting);
        }

        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();

        if (buttonConfigure != null) buttonConfigure.invalidate();

        for (VirtualControllerElement element : elements) {
            element.invalidate();
        }
    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.GONE);
        }

        buttonConfigure.setVisibility(View.GONE);
    }

    public void show() {
        showEnabledElements();

        buttonConfigure.setVisibility(View.VISIBLE);
    }

    public int switchShowHide() {
        if (buttonConfigure.getVisibility() == View.VISIBLE) {
            hide();
            return 0;
        } else {
            show();
            return 1;
        }
    }

    public void showElements(){
        for(VirtualControllerElement element : elements){
            element.setVisibility(View.VISIBLE);
        }
    }

    public void showEnabledElements(){
        for(VirtualControllerElement element: elements){
            element.setVisibility( element.enabled ? View.VISIBLE : View.GONE );
        }
    }

    public void removeElements() {
        for (VirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
    }

    public void setOpacity(int opacity) {
        for (VirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }


    public void addElement(VirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        frame_layout.addView(element, layoutParams);
    }

    public List<VirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();

        // 🎯 不再显示悬浮配置齿轮,避免盲操作误触。
        //    "编辑虚拟按键布局"改由游戏菜单(返回菜单→高级)触发 cycleConfigMode()。
        //    buttonConfigure 对象仍保留(cycleConfigMode内部会invalidate它),只是不加到屏幕上。
        // int buttonSize = (int)(screen.heightPixels*0.02f);
        // FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        // params.leftMargin = (screen.widthPixels - buttonSize) / 2;
        // params.topMargin = 5;
        // frame_layout.addView(buttonConfigure, params);

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context);
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    public void sendControllerInputContext(long vibrationDuration, int vibrationAmplitude) {
        // Cancel retransmissions of prior gamepad inputs
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();

        // ========================================================
        // 【核心新增逻辑】：智能对比当前状态和上一次状态，判断是“按下”还是“松开”
        // ========================================================
        boolean isPress = false;

        // 1. 判断普通按键或方向键：通过位运算，如果新状态有，而老状态没有，说明按下了新键
        if ((inputContext.inputMap & ~lastInputMap) != 0) {
            isPress = true;
        }
        // 2. 判断左/右扳机键是否被按下 (转为无符号数比较防越界)
        if ((inputContext.leftTrigger & 0xFF) > (lastLeftTrigger & 0xFF)) {
            isPress = true;
        }
        if ((inputContext.rightTrigger & 0xFF) > (lastRightTrigger & 0xFF)) {
            isPress = true;
        }

        // 更新历史状态，供下一次比对使用
        lastInputMap = inputContext.inputMap;
        lastLeftTrigger = inputContext.leftTrigger;
        lastRightTrigger = inputContext.rightTrigger;

        // 【静音绝杀】：如果是“松开”操作，并且使用的是默认发包震动(0)，则强制拦截震动设为(-1)
        if (!isPress && vibrationDuration == 0) {
            vibrationDuration = -1;
        }
        // ========================================================

        // 兼容摇杆的防狂震：只有当震动时长 >= 0 时才真正调用物理马达
        if (vibrationDuration >= 0 && frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (vibrationDuration == 0) {
                    effect = defaultVibrationEffect;
                } else {
                    effect = VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude);
                }
                vibrator.vibrate(effect);
            } else {
                if (vibrationDuration == 0) {
                    vibrationDuration = 10;
                }
                vibrator.vibrate(vibrationDuration);
            }
        }

        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }

    public void sendControllerInputContext() {
        sendControllerInputContext(0, 0);
    }
}