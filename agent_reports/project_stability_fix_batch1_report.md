# 项目稳定性修复报告 (第一批)

## 1. 修改的文件列表
- `app/src/main/java/com/limelight/binding/input/touch/RelativeTouchContext.java`
- `app/src/main/java/com/limelight/Game.java`
- `app/src/main/java/com/limelight/AppView.java`

## 2. RelativeTouchContext 坐标缩放一致性
**已修复**。
- 覆盖了 `touchDownEvent` 和 `touchUpEvent`，并应用了 `scaleX` / `scaleY` 缩放。
- `touchMoveEvent` 保持原有的缩放逻辑。
- 这确保了 `TrackpadContext` 在计算位移（位移 = 当前坐标 - 初始坐标）时，所有坐标都处于同一个缩放后的坐标系中。

## 3. Game.quit() 逻辑优化
**已修复**。
- 从 `quit()` 方法开头移除了 `terminatedByUser = true`。
- 现在仅在用户点击确认退出对话框的“是”按钮（positiveButton 回调）后，才在 `finish()` 之前设置 `terminatedByUser = true`。
- `disconnect()` 中的设置保持不变。

## 4. AppView 自动启动 Desktop 时机控制
**已修复**。
- 新增了 `receivedServerInfo` 标志位。
- 在 `notifyComputerUpdated` 收到 `details` 并更新 `lastRunningAppId` 时，将其设为 `true`。
- `tryAutoStartDesktopStreamOnce()` 现在会检查该标志位，确保在获取到真实服务器状态后再尝试自动启动，避免使用缓存数据导致误判。

## 5. 编译结果
**编译成功** (BUILD SUCCESSFUL)。
APK 已生成，路径：`app\build\outputs\apk\nonRoot_game\debug\`

## 6. git diff --name-only
```
app/src/main/java/com/limelight/AppView.java
app/src/main/java/com/limelight/Game.java
app/src/main/java/com/limelight/binding/input/touch/RelativeTouchContext.java
app/src/main/java/com/limelight/binding/input/touch/TrackpadContext.java
app/src/main/java/com/limelight/binding/input/virtual_controller/SlideButton.java
app/src/main/java/com/limelight/binding/input/virtual_controller/SlideButtonLR.java
```
*(注：列表包含之前任务的修改，本次仅涉及前三个文件)*
