/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;

import com.limelight.nvstream.input.ControllerPacket;

public class RightAnalogStickFree extends AnalogStickFree {
    public RightAnalogStickFree(final VirtualController controller, final Context context) {
        super(controller, context, EID_RS);

        strStickSide = "R";

        addAnalogStickListener(new AnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.rightStickX = (short) (x * 0x7FFE);
                inputContext.rightStickY = (short) (y * 0x7FFE);

                // 【静音震动】：传入 -1，拦截移动时的持续嗡嗡震动
                controller.sendControllerInputContext(-1, 0);
            }

            @Override
            public void onClick() {
                // 【修复 R3 按键】：将原本双击的逻辑搬到了单击这里
                // 手指摸到右摇杆瞬间触发 R3
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= ControllerPacket.RS_CLK_FLAG;

                // 传入 -1，防止主控中心触发二次震动
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
                inputContext.inputMap &= ~ControllerPacket.RS_CLK_FLAG;

                // 手指抬起时松开 R3，静音发包
                controller.sendControllerInputContext(-1, 0);
            }
        });
    }
}