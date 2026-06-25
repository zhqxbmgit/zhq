/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;

import com.limelight.nvstream.input.ControllerPacket;

public class LeftAnalogStickFree extends AnalogStickFree {
    public LeftAnalogStickFree(final VirtualController controller, final Context context) {
        super(controller, context, EID_LS);

        strStickSide = "L";

        addAnalogStickListener(new AnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.leftStickX = (short) (x * 0x7FFE);
                inputContext.leftStickY = (short) (y * 0x7FFE);

                // 【静音震动】：传入 -1，拦截移动时的持续嗡嗡震动
                controller.sendControllerInputContext(-1, 0);
            }

            @Override
            public void onClick() {
                // 【修复 L3 按键】：将原本双击的逻辑搬到了单击这里
                // 现在只要手指摸到摇杆，就会瞬间触发 L3，配合单次震动，手感极佳
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= ControllerPacket.LS_CLK_FLAG;

                // 同样传入 -1，防止主控中心触发二次震动
                controller.sendControllerInputContext(-1, 0);
            }

            @Override
            public void onDoubleClick() {
                // 留空即可，底层已经不再发送双击事件了
            }

            @Override
            public void onRevoke() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~ControllerPacket.LS_CLK_FLAG;

                // 手指抬起时松开 L3，静音发包
                controller.sendControllerInputContext(-1, 0);
            }
        });
    }
}