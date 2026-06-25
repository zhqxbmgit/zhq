/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

import com.limelight.R;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

public class VirtualControllerConfigurationLoader {
    public static final String OSC_PREFERENCE = "OSC";

    private static int getPercent(
            int percent,
            int total) {
        return (int) (((float) total / (float) 100) * (float) percent);
    }

    // The default controls are specified using a grid of 128*72 cells at 16:9
    private static int screenScale(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }

    private static DigitalPad createDigitalPad(
            final VirtualController controller,
            final Context context) {

        DigitalPad digitalPad = new DigitalPad(controller, context);
        digitalPad.addDigitalPadListener(new DigitalPad.DigitalPadListener() {
            @Override
            public void onDirectionChange(int direction) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();

                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    inputContext.inputMap |= ControllerPacket.LEFT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.LEFT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    inputContext.inputMap |= ControllerPacket.RIGHT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.RIGHT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    inputContext.inputMap |= ControllerPacket.UP_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.UP_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    inputContext.inputMap |= ControllerPacket.DOWN_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.DOWN_FLAG;
                }

                controller.sendControllerInputContext(10, 0x22);
            }
        });

        return digitalPad;
    }

    private static DigitalButton createDigitalButton(
            final int elementId,
            final int keyShort,
            final int keyLong,
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        DigitalButton button = new DigitalButton(controller, elementId, layer, context);
        button.setText(text);
        button.setIcon(icon);
        button.setIconPress(iconPress);
        button.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override
            public void onClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyShort;

                controller.sendControllerInputContext();
            }

            @Override
            public void onLongClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyLong;

                controller.sendControllerInputContext();
            }

            @Override
            public void onRelease() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~keyShort;
                inputContext.inputMap &= ~keyLong;

                controller.sendControllerInputContext();
            }
        });

        return button;
    }

    // ========================================================================
    // 🎯 SlideButton:按住+滑动三合一按键
    //   - 点一下(不滑)        → 发 baseFlag(原键,如B)
    //   - 按住向上滑(超阈值)  → 按住 slideUpFlag,抬起才松,不发原键
    //   - 按住向下滑(超阈值)  → 按住 slideDownFlag,抬起才松,不发原键
    // ========================================================================
    private static SlideButton createSlideButton(
            final int elementId,
            final int baseFlag,
            final int slideUpFlag,
            final int slideDownFlag,
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        SlideButton button = new SlideButton(controller, elementId, layer, context);
        button.setText(text);
        button.setIcon(icon);

        button.setSlideButtonListener(new SlideButton.SlideButtonListener() {
            @Override
            public void onBaseClick() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= baseFlag;
                controller.sendControllerInputContext(-1, 0); // 屏蔽VC震动,按下用SlideButton自己的HEAVY_CLICK
            }
            @Override
            public void onBaseRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~baseFlag;
                controller.sendControllerInputContext(-1, 0);
            }
            @Override
            public void onSlideUp() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= slideUpFlag;
                controller.sendControllerInputContext(-1, 0); // 屏蔽VC震动,上滑用SlideButton清脆震动
            }
            @Override
            public void onSlideUpRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~slideUpFlag;
                controller.sendControllerInputContext(-1, 0);
            }
            @Override
            public void onSlideDown() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= slideDownFlag;
                controller.sendControllerInputContext(-1, 0); // 屏蔽VC震动,下滑用SlideButton清脆震动
            }
            @Override
            public void onSlideDownRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~slideDownFlag;
                controller.sendControllerInputContext(-1, 0);
            }
        });

        return button;
    }

    // 🎯 横向滑动按键(左右+上版):点一下=baseFlag;左滑=slideLeftFlag;右滑=slideRightFlag;上滑=slideUpFlag
    private static SlideButtonLR createSlideButtonLR(
            final int elementId,
            final int baseFlag,
            final int slideLeftFlag,
            final int slideRightFlag,
            final int slideUpFlag,
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        SlideButtonLR button = new SlideButtonLR(controller, elementId, layer, context);
        button.setText(text);
        button.setIcon(icon);

        button.setSlideButtonLRListener(new SlideButtonLR.SlideButtonLRListener() {
            @Override
            public void onBaseClick() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= baseFlag;
                controller.sendControllerInputContext(-1, 0); // 屏蔽VC震动,按下用SlideButtonLR的EFFECT_CLICK // 按下:无参发包,震动由VirtualController触发
            }
            @Override
            public void onBaseRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~baseFlag;
                controller.sendControllerInputContext(-1, 0); // 松开:无参(松开本就被智能静音,不会震)
            }
            @Override
            public void onSlideLeft() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= slideLeftFlag;
                controller.sendControllerInputContext(-1, 0); // 传-1屏蔽VirtualController震动,震动由SlideButtonLR自己发清脆版
            }
            @Override
            public void onSlideLeftRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~slideLeftFlag;
                controller.sendControllerInputContext(-1, 0); // 传-1屏蔽VirtualController震动,震动由SlideButtonLR自己发清脆版
            }
            @Override
            public void onSlideRight() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= slideRightFlag;
                controller.sendControllerInputContext(-1, 0); // 传-1屏蔽VirtualController震动,震动由SlideButtonLR自己发清脆版
            }
            @Override
            public void onSlideRightRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~slideRightFlag;
                controller.sendControllerInputContext(-1, 0); // 传-1屏蔽VirtualController震动,震动由SlideButtonLR自己发清脆版
            }
            @Override
            public void onSlideUp() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap |= slideUpFlag;
                controller.sendControllerInputContext(-1, 0); // 屏蔽VC震动,震动由SlideButtonLR自己发清脆版
            }
            @Override
            public void onSlideUpRelease() {
                VirtualController.ControllerInputContext ctx = controller.getControllerInputContext();
                ctx.inputMap &= ~slideUpFlag;
                controller.sendControllerInputContext(-1, 0);
            }
        });

        return button;
    }

    private static DigitalButton createLeftTrigger(
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        LeftTrigger button = new LeftTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        button.setIconPress(iconPress);
        return button;
    }

    private static DigitalButton createRightTrigger(
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        RightTrigger button = new RightTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        button.setIconPress(iconPress);
        return button;
    }

    private static AnalogStick createLeftStick(
            final VirtualController controller,
            final Context context) {
        return new LeftAnalogStick(controller, context);
    }

    private static AnalogStick createRightStick(
            final VirtualController controller,
            final Context context) {
        return new RightAnalogStick(controller, context);
    }

    private static AnalogStickFree createLeftStick2(
            final VirtualController controller,
            final Context context) {
        return new LeftAnalogStickFree(controller, context);
    }

    private static AnalogStickFree createRightStick2(
            final VirtualController controller,
            final Context context) {
        return new RightAnalogStickFree(controller, context);
    }


    private static final int TRIGGER_L_BASE_X = 1;
    private static final int TRIGGER_R_BASE_X = 92;
    private static final int TRIGGER_DISTANCE = 23;
    private static final int TRIGGER_BASE_Y = 31;
    private static final int TRIGGER_WIDTH = 12;
    private static final int TRIGGER_HEIGHT = 9;

    // Face buttons are defined based on the Y button (button number 9)
    private static final int BUTTON_BASE_X = 106;
    private static final int BUTTON_BASE_Y = 1;
    private static final int BUTTON_SIZE = 10;

    private static final int DPAD_BASE_X = 4;
    private static final int DPAD_BASE_Y = 41;
    private static final int DPAD_SIZE = 30;

    private static final int ANALOG_L_BASE_X = 6;
    private static final int ANALOG_L_BASE_Y = 4;
    private static final int ANALOG_R_BASE_X = 98;
    private static final int ANALOG_R_BASE_Y = 42;
    private static final int ANALOG_SIZE = 26;

    private static final int L3_R3_BASE_Y = 60;

    private static final int START_X = 83;
    private static final int BACK_X = 34;
    private static final int START_BACK_Y = 64;
    private static final int START_BACK_WIDTH = 12;
    private static final int START_BACK_HEIGHT = 7;

    // Make the Guide Menu be in the center of START and BACK menu
    private static final int GUIDE_X = START_X-BACK_X;
    private static final int GUIDE_Y = START_BACK_Y;

    public static void createDefaultLayout(final VirtualController controller, final Context context) {

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);

        // Displace controls on the right by this amount of pixels to account for different aspect ratios
        int rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9;

        int height = screen.heightPixels;

        // NOTE: Some of these getPercent() expressions seem like they can be combined
        // into a single call. Due to floating point rounding, this isn't actually possible.

        if (!config.onlyL3R3)
        {
            controller.addElement(createDigitalPad(controller, context),
                    screenScale(DPAD_BASE_X, height),
                    screenScale(DPAD_BASE_Y, height),
                    screenScale(DPAD_SIZE, height),
                    screenScale(DPAD_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_A,
                            !config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                            !config.flipFaceButtons ? "A" : "B", R.drawable.facebutton_a,R.drawable.facebutton_a_press, controller, context),
                    screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            // ================================================================
            // 🎯 B键改造为 SlideButton:
            //    点一下=原B   上滑=十字键上(UP)   下滑=十字键下(DOWN)
            // ================================================================
            controller.addElement(createSlideButton(
                            VirtualControllerElement.EID_B,
                            config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG,  // 普通点击=原B
                            ControllerPacket.Y_FLAG,     // 上滑键 = 手柄Y
                            ControllerPacket.A_FLAG,     // 下滑键 = 手柄A
                            1,
                            config.flipFaceButtons ? "A" : "B",
                            R.drawable.facebutton_b, R.drawable.facebutton_b_press, controller, context),
                    screenScale(BUTTON_BASE_X + BUTTON_SIZE, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            // ================================================================
            // 🎯 X键改造为 SlideButtonLR:
            //    点一下=原X   左滑=十字键左(LEFT)   右滑=十字键右(RIGHT)   上滑=十字键上(UP)
            // ================================================================
            controller.addElement(createSlideButtonLR(
                            VirtualControllerElement.EID_X,
                            !config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG,  // 普通点击=原X
                            ControllerPacket.LEFT_FLAG,    // 左滑键 = 十字键左
                            ControllerPacket.RIGHT_FLAG,   // 右滑键 = 十字键右
                            ControllerPacket.UP_FLAG,      // 上滑键 = 十字键上
                            1,
                            !config.flipFaceButtons ? "X" : "Y",
                            R.drawable.facebutton_x, R.drawable.facebutton_x_press, controller, context),
                    screenScale(BUTTON_BASE_X - BUTTON_SIZE, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_Y,
                            config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                            config.flipFaceButtons ? "X" : "Y", R.drawable.facebutton_y,R.drawable.facebutton_y_press, controller, context),
                    screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            controller.addElement(createLeftTrigger(
                            1, "LT", R.drawable.facebutton_zl,R.drawable.facebutton_zl_press, controller, context),
                    screenScale(TRIGGER_L_BASE_X, height),
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createRightTrigger(
                            1, "RT", R.drawable.facebutton_zr,R.drawable.facebutton_zr_press, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_LB,
                            ControllerPacket.LB_FLAG, 0, 1, "LB", R.drawable.facebutton_l,R.drawable.facebutton_l_press, controller, context),
                    screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, height),
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_RB,
                            ControllerPacket.RB_FLAG, 0, 1, "RB", R.drawable.facebutton_r,R.drawable.facebutton_r_press, controller, context),
                    screenScale(TRIGGER_R_BASE_X, height) + rightDisplacement,
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            if(config.enableNewAnalogStick){
                controller.addElement(createLeftStick2(controller, context),
                        screenScale(ANALOG_L_BASE_X, height),
                        screenScale(ANALOG_L_BASE_Y, height),
                        screenScale(ANALOG_SIZE, height),
                        screenScale(ANALOG_SIZE, height)
                );

                controller.addElement(createRightStick2(controller, context),
                        screenScale(ANALOG_R_BASE_X, height) + rightDisplacement,
                        screenScale(ANALOG_R_BASE_Y, height),
                        screenScale(ANALOG_SIZE, height),
                        screenScale(ANALOG_SIZE, height)
                );
            }else{
                controller.addElement(createLeftStick(controller, context),
                        screenScale(ANALOG_L_BASE_X, height),
                        screenScale(ANALOG_L_BASE_Y, height),
                        screenScale(ANALOG_SIZE, height),
                        screenScale(ANALOG_SIZE, height)
                );

                controller.addElement(createRightStick(controller, context),
                        screenScale(ANALOG_R_BASE_X, height) + rightDisplacement,
                        screenScale(ANALOG_R_BASE_Y, height),
                        screenScale(ANALOG_SIZE, height),
                        screenScale(ANALOG_SIZE, height)
                );
            }
            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_BACK,
                            ControllerPacket.BACK_FLAG, 0, 2, "BACK", R.drawable.facebutton_minus,R.drawable.facebutton_minus_press, controller, context),
                    screenScale(BACK_X, height),
                    screenScale(START_BACK_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_START,
                            ControllerPacket.PLAY_FLAG, 0, 3, "START", R.drawable.facebutton_plus,R.drawable.facebutton_plus_press, controller, context),
                    screenScale(START_X, height) + rightDisplacement,
                    screenScale(START_BACK_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_LSB,
                            ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", R.drawable.facebutton_l3,R.drawable.facebutton_l3_press, controller, context),
                    screenScale(TRIGGER_L_BASE_X, height),
                    screenScale(L3_R3_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_RSB,
                            ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", R.drawable.facebutton_r3,R.drawable.facebutton_r3_press, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                    screenScale(L3_R3_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_TOUCHPAD,
                            ControllerPacket.TOUCHPAD_FLAG, 0, 1, "Trackpad", R.drawable.facebutton_touchpad_press,R.drawable.facebutton_touchpad, controller, context),
                    screenScale(50, height),
                    screenScale(50, height),
                    screenScale(20, height),
                    screenScale(12, height)
            );
        }
        else {
            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_LSB,
                            ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", -1, -1,controller, context),
                    screenScale(TRIGGER_L_BASE_X, height),
                    screenScale(L3_R3_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                            VirtualControllerElement.EID_RSB,
                            ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", -1,-1, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                    screenScale(L3_R3_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );
        }


        if(config.showGuideButton){
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_GDB,
                            ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, -1, controller, context),
                    screenScale(GUIDE_X, height)+ rightDisplacement,
                    screenScale(GUIDE_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );
        }

        controller.setOpacity(config.oscOpacity);
    }

    public static void saveProfile(final VirtualController controller,
                                   final Context context) {
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE).edit();

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        prefEditor.apply();
    }

    public static void loadFromPreferences(final VirtualController controller, final Context context) {
        SharedPreferences pref = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE);

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;

            String jsonConfig = pref.getString(prefKey, null);
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(new JSONObject(jsonConfig));
                } catch (JSONException e) {
                    e.printStackTrace();

                    // Remove the corrupt element from the preferences
                    pref.edit().remove(prefKey).apply();
                }
            }
        }
    }
}
