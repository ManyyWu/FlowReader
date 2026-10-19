# T01 环境检查点

检查时间：2026-10-02 22:50–22:54（Asia/Shanghai）。随后建立了最小工程骨架；未安装依赖、未执行构建。

## 已确认

| 项目 | 结果 |
| --- | --- |
| 主机 | macOS，Darwin 24.6.0，x86_64；下载工具时选择 Intel Mac 版本 |
| Java | `/usr/libexec/java_home -V` 报告找不到 Java Runtime；`/usr/bin/java` 为系统入口，不代表已安装 JDK |
| JDK 搜索 | 系统 JavaVirtualMachines 目录为空；用户 JavaVirtualMachines、`.jdks`、SDKMAN Java 路径不存在；Homebrew 包列表未见 JDK |
| Android SDK | 未配置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`；常用路径 `~/Library/Android/sdk` 不存在；当前 PATH 无 `sdkmanager` |
| Android Studio | `/Applications/Android Studio.app` 不存在；未发现常用位置的安装，不代表已排查所有自定义路径 |
| Gradle | 当前 PATH 无 `gradle`，`~/.gradle` 不存在；后续使用项目 Gradle Wrapper |
| Homebrew / Git | 已安装，Git 2.54.0；项目尚未初始化仓库 |
| ADB | `/usr/local/bin/adb`，platform-tools 37.0.1 |
| 设备 | 已通过无线 ADB 连接 SM-S9180，状态为 `device`；未记录设备序列号 |
| 手机系统 | `ro.build.version.release=16`，`ro.build.version.sdk=36` |

ADB 在沙盒内启动失败，原因是本地端口/USB 访问受限；经授权在沙盒外执行 `adb devices -l` 和上述两项 `getprop` 成功。后续 ADB 操作仍需遵循工具权限要求。未安装或启动任何新 APK。

## 选定的构建版本

采用固定组合：JDK 17、AGP 8.13.2、Gradle 8.13、Kotlin 2.3.10、compileSdk/targetSdk 36。仅核对了文档兼容性，尚未在本机构建验证。

- [AGP 8.13 发布说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：Gradle 8.13、JDK 17；8.13.2 支持 Kotlin 2.3。
- [Kotlin Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)：Kotlin 2.3.10 的完全支持范围覆盖上述 AGP/Gradle；原候选 2.2.21 的 AGP 完全支持范围截至 8.11.1，因此调整 Kotlin 版本。
- [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)：该 Gradle 版本支持以 JDK 17 运行。

## 接续顺序

1. T01b：准备可用的 JDK 17 和 Android SDK 命令行工具、API 36 平台及 AGP 所需 Build Tools。先复用用户提供的自定义安装路径；没有时再安装。记录实际路径与版本，下载和系统目录写入按权限要求执行。
2. T01c：Gradle Wrapper 已加入；初始化 Git，执行 `assembleDebug` 并修复构建配置问题。
3. T01d：确认目标设备仍连接，安装并启动 APK，保存启动结果；这一步通过后才能将 T01 标为完成。不得自动进入 T02。

不需要为了命令行构建额外安装完整 Android Studio。Android SDK 许可在实际安装时按要求处理，目前未接受许可或修改系统配置。

## 最小工程骨架

已新增根 Gradle 配置、单 `app` 模块配置、Manifest、一个启动 Activity、一个 XML 布局及基础资源。启动页只显示 FlowReader 名称和“朗读原型开发中”。这批文件尚未通过编译；文件清单见项目 `README.md`。

## 2026-10-17 环境复查

| 项目 | 当前结果 |
| --- | --- |
| JDK | Eclipse Temurin 17.0.20.1 已安装，`java`、`javac` 可用 |
| Android CLI | `/usr/local/bin/android` 入口存在；运行 `android --no-metrics -V` 时仍尝试下载 CLI 可执行文件，当前失败原因为 `dl.google.com` 主机名 DNS 解析失败 |
| Android SDK | `~/Library/Android/sdk` 存在但为空；未安装 `platforms/android-36`、Build Tools、SDK Platform-Tools；`sdkmanager` 不在 PATH |
| 命令行工具包 | Homebrew 的 `android-commandlinetools` 未安装；`android-cli` Homebrew 包也未列为已安装 |
| Gradle | 系统 Gradle 不在 PATH；项目未生成 Gradle Wrapper，因此尚不能构建 |
| Git / ADB | Git、ADB 37.0.1 可用；当前沙盒无法启动本地 ADB daemon，设备连接状态本次未复核 |

当前阻塞点是下载 Android 工具时的 DNS/网络访问。CLI 版本查询也会触发下载，故不能视为 CLI 已正常工作。解决下载访问后，可安装 Android Command-line Tools，再安装 API 36、Build Tools 36.0.0 和 Platform-Tools；随后为本工程生成 Wrapper 并执行首次构建。

### SDK 包安装后复查

同日稍后再次检查：Temurin JDK 17.0.20.1 可用；`ANDROID_HOME=/Users/manyy/Library/Android/sdk` 下的 `platforms/android-36`、`build-tools/36.0.0` 和 `platform-tools` 现已存在。`/usr/local/bin/android` 与 SDK 内的 `adb` 可找到；`gradle`、`sdkmanager` 均不在 PATH。沙盒内执行 `android --no-metrics -V` 因无法写入 `~/.android/cli/bundles/lock` 而失败，此结果不足以判断用户自己的终端能否运行 Android CLI。ADB 本次也未能启动 daemon，设备状态待复核。尚未构建或安装 APK。

### Gradle Wrapper 安装后复查

为项目生成了 Gradle 8.13 Wrapper：根目录包含 `gradlew`、`gradlew.bat`，`gradle/wrapper/` 包含 Wrapper JAR 和属性文件。发行包 SHA-256 与 Gradle 官方校验值一致，配置中固定了相同的 `distributionSha256Sum`。执行 `./gradlew --version` 后 Wrapper 成功下载并启动 Gradle 8.13；输出识别到 Temurin 17.0.20.1 和 macOS x86_64。随后用户确认已连接手机并成功运行一次应用；具体构建命令和日志未记录。
