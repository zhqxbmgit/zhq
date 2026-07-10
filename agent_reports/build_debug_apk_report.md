# Build Debug APK Report

## 1. git status 结果
```
On branch moonlight-noir
Your branch is up to date with 'origin/moonlight-noir'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
        modified:   app/src/main/java/com/limelight/binding/input/touch/TrackpadContext.java

no changes added to commit (use "git add" and/or "git commit -a")
```

## 2. 构建命令
`gradlew.bat :app:assembleNonRoot_gameDebug`

## 3. 构建是否成功
**失败** (工作区不是 clean)

## 4. APK 文件路径
N/A

## 5. APK 文件名
N/A

## 6. 错误信息
工作区包含未提交的修改：`app/src/main/java/com/limelight/binding/input/touch/TrackpadContext.java`。
根据指令第6条：“如果工作区不是 clean，停止并报告”，构建过程已停止。
