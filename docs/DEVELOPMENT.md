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

当时没有安装模拟器系统镜像，也没有连接真实设备。因此可以完成编译和 JVM 单元测试，还不能在模拟器里启动应用。

构建前设置：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk
export ANDROID_SDK_ROOT=/home/ubuntu/android-sdk
```

## 构建与测试

```bash
./gradlew :core:test
./gradlew :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

在已启动的模拟器或已连接的设备上安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 可直接打开仓库根目录。模块 `:core` 是不依赖 Android 的 Kotlin 逻辑，模块 `:app` 是 Jetpack Compose 界面。

## 运行时边界

- 导入使用系统文件选择器，只接受 TXT 和 EPUB，副本放在应用私有目录 `files/moye/books/`。
- 书架、设置、阅读位置和阅读时长写在同一私有目录的 JSON 文件中，不访问网络。
- 清单里没有 `INTERNET`，也没有外部存储权限。
- 界面语言支持中文和英文。EPUB 使用文件声明的排版方向；TXT 才使用阅读设置里的横排/竖排。
- 阅读按页进行。一页可以有多句；点按右侧逐句显示，新句子可以逐字符打出。上一页 / 下一页、滑动，以及蓝牙翻页器，直接翻页，没有翻页动画。页眉和页脚默认隐藏，点按中间才显示。一次可以导入多个 TXT / EPUB；内容相同的书会跳过。
- 书架使用 EPUB 内嵌封面。没有封面的书显示用书名生成的文字封面。

## 设备验收还没做的部分

Issue 18 要求在模拟器和一台真实 Android 设备上走通主要流程。当前环境没有模拟器或真机，下面这些还需要在设备上确认：

- 系统文件选择器导入 TXT / EPUB，以及重启后仍能打开。
- 直接翻页、页眉页脚显隐、竖排和不同屏幕尺寸下的实际观感。
- 蓝牙翻页器或键盘的实体按键。
- 断网时的书架和阅读，以及切到后台后阅读时长停止。

`:core:test` 已覆盖切句、分页与页内逐句点按、打字机速度、章节、EPUB/TXT 解析、EPUB 封面提取、进度和时长记录、按键映射、书架搜索、移除时是否删除副本，以及设置持久化。

页容量按字号估算行数，拉丁文可能比屏幕实际能放下的句子更少。阅读进度记在当前已显示句子的起点；重新打开时，这一页会显示到该句并且该句直接完整出现，不会把打字机从半截恢复。手动更换封面还没有做。当前环境没有模拟器或真机，所以上面的界面行为还不能在设备上点按确认。
