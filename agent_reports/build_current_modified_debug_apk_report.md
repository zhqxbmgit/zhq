# Build Current Modified Debug APK Report

## 1. git status 结果
```
On branch moonlight-noir
Your branch is up to date with 'origin/moonlight-noir'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
        modified:   app/src/main/java/com/limelight/binding/input/touch/TrackpadContext.java

Untracked files:
  (use "git add <file>..." to include in what will be committed)
        agent_reports/

no changes added to commit (use "git add" and/or "git commit -a")
```

## 2. 当前是否存在 TrackpadContext.java 本地修改
**是**。
具体修改为：
```diff
-    private static final double SMOOTHING_TIME_CONSTANT = 0.04;
+    private static final double SMOOTHING_TIME_CONSTANT = 0.035;
```

## 3. 构建命令
`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleNonRoot_gameDebug`

## 4. 构建是否成功
**成功** (BUILD SUCCESSFUL)

## 5. APK 完整路径
- `C:\zhq\app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-arm64-v8a-debug.apk`
- `C:\zhq\app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-armeabi-v7a-debug.apk`
- `C:\zhq\app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-x86-debug.apk`
- `C:\zhq\app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-x86_64-debug.apk`

## 6. APK 文件名
- `app-nonRoot_game-arm64-v8a-debug.apk`
- `app-nonRoot_game-armeabi-v7a-debug.apk`
- `app-nonRoot_game-x86-debug.apk`
- `app-nonRoot_game-x86_64-debug.apk`

## 7. 错误信息
无。构建圆满成功。
