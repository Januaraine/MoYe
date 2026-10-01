# 墨页开发环境与运行说明

本文对应开发 Issue 01，记录当前可用的构建环境和后续启动步骤。应用不申请账号、不包含联网内容服务，也不请求广泛存储权限。

## 当前环境

在编写首版代码时，本机已具备：

- JDK 21（`/usr/lib/jvm/java-21-openjdk-amd64`）
- Android SDK：`/home/ubuntu/android-sdk`
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools 37.0.1
- Gradle 8.11.1（由 `gradlew` 下载）

Cloud Agent 环境还包含模拟器系统镜像 `system-images;android-35;google_apis;x86_64` 和虚拟设备 `moye_api_35`（Pixel 6，Android 15，2 GB 内存，硬件键盘）。没有连接真实设备。编译、JVM 单元测试和模拟器启动都可以在这个环境里完成。

登录 shell 会从 `/etc/profile.d/moye-android.sh` 载入下面这些变量。非登录 shell 需要自行导出：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk
export ANDROID_SDK_ROOT=/home/ubuntu/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

每次环境启动会把 `sdk.dir` 写到仓库根目录的 `local.properties`。该文件不入库。模拟器使用 `/dev/kvm`，启动时会把该设备设为可读写。

## 构建与测试

```bash
./gradlew :core:test
./gradlew :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

启动模拟器、安装并打开调试包：

```bash
emulator -avd moye_api_35 -gpu swiftshader_indirect -no-audio -no-boot-anim -accel on
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.moye/.MainActivity
```

Android Studio 可直接打开仓库根目录。模块 `:core` 是不依赖 Android 的 Kotlin 逻辑，模块 `:app` 是 Jetpack Compose 界面。

## 运行时边界

- 导入使用系统文件选择器，只接受 TXT 和 EPUB，副本放在应用私有目录 `files/moye/books/`。
- 书架、设置、阅读位置和阅读时长写在同一私有目录的 JSON 文件中，不访问网络。
- 清单里没有 `INTERNET`，也没有外部存储权限。
- 界面语言支持中文和英文。EPUB 使用文件声明的排版方向；TXT 才使用阅读设置里的横排/竖排。

## 设备验收还没做的部分

Issue 18 要求在模拟器和一台真实 Android 设备上走通主要流程。Cloud Agent 环境可以启动 `moye_api_35`。下面这些仍要在模拟器或真机上确认，蓝牙翻页器只能在真机上确认：

- EPUB 导入，以及应用重启后已导入的作品仍能打开。TXT 导入并打开阅读已在 `moye_api_35` 上走过一遍。
- 仿真翻页、竖排和不同屏幕尺寸下的实际观感。
- 蓝牙翻页器或键盘的实体按键。
- 断网时的书架和阅读，以及切到后台后阅读时长停止。

`:core:test` 已覆盖切句、章节、EPUB/TXT 解析、进度和时长记录、按键映射、书架搜索、移除时是否删除副本，以及设置持久化。
