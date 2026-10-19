# AGENTS.md

云手机 Android 控制端（WebRTC + scrcpy agent）。先读 [docs/STATUS.md](docs/STATUS.md)（进度、未提交改动、待办），再读 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 环境与构建

- JDK 17+（本机为 21），Android SDK 在 `local.properties` 的 `sdk.dir`（本机 `/Volumes/m2/sdk`）。
- AGP 8.8 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / compileSdk 35 / minSdk 24。依赖版本统一在 `gradle/libs.versions.toml`。

```bash
./gradlew :app:compileDebugKotlin        # 快速检查编译
./gradlew :app:testDebugUnitTest         # 单元测试（app/src/test，纯 JVM，org.json 测试依赖）
./gradlew :app:installDebug              # 安装 debug 包（com.webrtc_phone.android.debug）
./gradlew :app:assembleRelease           # release 包，需 local.properties 中的签名配置
```

产物：`app/build/outputs/apk/{debug,release}/WebrtcPhone-v<版本>-<variant>.apk`。版本号取仓库上层 `VERSION` 文件。

每次改代码后至少跑 `compileDebugKotlin`；改了 `core` / `data/model` / `rtc/channel` / `rtc/signaling` 的纯逻辑要补或跑单元测试；改了 WebRTC / WebView / 反射相关代码要用 release 包验证（R8 规则见 `app/proguard-rules.pro`）。

## 真机调试

- 设备：`adb devices`（常用 28301FDH300F49，1080×2340，截图坐标 × 1.4917 = 物理坐标）。
- 屏幕易锁：先 `adb shell input keyevent 224` + 上滑解锁。
- `adb shell input text` 不支持 `;`、引号；空格用 `%s`。`keyevent 111`（ESC）会被输入框当字符吃掉，收键盘用 `keyevent 4`。
- 日志：`adb logcat -d | grep -E "RtcSession|SessionManager|Terminal|AndroidRuntime"`，崩溃 `adb logcat -d -b crash`。
- agent 日志在云手机 `/data/local/tmp/cloudphone-agent.log`（可在 App 终端里查看）。
- 服务端源码在上层仓库：信令 `webrtc-operator/standalone/main.go`（路由表在 `registerAppRoutes`），agent `android-agentd/cloudphone-agent/webrtc.go`。**协议有疑问先读这两处源码，不要猜。**

## 代码约定

- 分层依赖只能 `ui → rtc / data → core`；新业务对象在 `AppContainer` 注册（进程级或 `userScope()` 会话级）。
- Kotlin + Compose + StateFlow；ViewModel 用 `viewModel { ... }` 工厂手动构造，无 DI 框架。
- 注释与界面文案用中文；注释写“为什么”，尤其是踩坑原因。
- 网络错误统一抛 `ApiException` 子类，UI 只显示 `message`。

## 不要做 / 踩过的坑

- **不要信任所有证书**，不要关 hostname 校验。自签证书只能走 `CertificatePinStore` 指纹固定。
- 凭据（token、密码、share token）只放 `SecureStore` 或进程内存；不进日志、URL（分享访客除外，服务端限制）、Intent extras、普通 prefs。
- `RtcSession` 内状态只在其单线程 executor 上改；DataChannel 一律经 `SafeChannel`，回调里不要同步 send/close（死锁）。
- `file-channel` / `adb-channel` 必须在 SDP 协商**之前**创建，否则部分 libwebrtc 不回调 OPEN。
- 终端 WebView 必须懒创建（协商完成、用户打开终端时才创建）。
- 触控坐标用设备逻辑分辨率，不是视频帧尺寸。
- 不要发送 `hard_keyboard`（agent 会停视频）。
- 不要往 PTY 发 init 以外的控制 JSON（agent 不支持 resize，会写进 shell）。
- `ScrcpyOptions` 的 `video_source` 两种模式都要显式发送。
- 不要提交 `local.properties`（含签名密码）和 `build/` 产物。提交前 `git status --short` 确认。
- 推送到 `main` 前先征得用户同意。
