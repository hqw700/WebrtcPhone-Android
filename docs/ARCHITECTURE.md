# WebrtcPhone 架构设计

云手机 Android 控制端。通过信令服务器（`webrtc-operator/standalone`）发现设备，与设备上的 `cloudphone-agent` 建立 WebRTC P2P 连接：视频/音频走媒体轨，控制走 DataChannel。

```
[Android App] ──HTTPS/WSS──> [信令服务器] <──WS── [cloudphone-agent (设备)]
      └──────────── WebRTC P2P（DTLS-SRTP + SCTP DataChannel）────────────┘
```

## 分层

包名 `com.webrtc_phone.android`，依赖方向只能自上而下：`ui → rtc / data → core`。

| 层 | 目录 | 职责 |
|---|---|---|
| core | `core/net` `core/security` `core/util` | 服务器地址、HTTP 客户端、TLS 信任、加密存储、时间/格式化工具。不依赖任何业务 |
| data | `data/api` `data/auth` `data/model` `data/realtime` `data/repo` `data/settings` | REST 接口、登录态、领域模型与 JSON 解析、仪表盘实时 WS、设备/标签/快捷文本仓库、本地设置 |
| rtc | `rtc` `rtc/signaling` `rtc/channel` | 信令、PeerConnection 会话、DataChannel 协议（输入/剪贴板/文件/终端）、统计、会话管理 |
| ui | `ui/*` | Jetpack Compose 界面 + ViewModel |

依赖注入为手写容器 `WebrtcPhoneApp.kt` 中的 `AppContainer`，通过 `Context.container` 获取：
- **进程级**：`secureStore` `pinStore` `httpFactory` `settings` `sessions` `rtcEngine` `streamSessions`
- **登录会话级**：`userScope()` 返回 `UserScope(devices, quickTexts)`；登录态变化时整体重建，避免跨账号数据串用

## core

- `ServerEndpoint`：宽松解析用户输入（缺协议默认 https，缺端口默认 8443，支持 IPv6，拒绝 `user:pass@host`）。`key` 用于证书固定。
- `HttpClientFactory`：所有 OkHttpClient 派生自同一根 client（共享连接池）；按端点缓存，REST 与 WebSocket 各一份（WS 无读超时 + 25s ping）。
- **TLS 信任**（`PinningTrustManager` + `CertificatePinStore`）：先走系统 CA；失败时仅当叶子证书 SHA-256 与用户确认过的指纹一致才放行（TOFU）。证书变更会抛出 `UntrustedCertificateException(changed=true)`，UI 红色警告。不存在任何“信任所有证书”的路径。`network_security_config.xml` 只信任系统 CA，不信任用户安装的 CA。
- `FragmentingSocketFactory`：把 TLS ClientHello 拆成两个 TCP 段（间隔 10ms）发送。原因：部分网络（实测 app.webrtc-phone.com:8899 的出口/网关）对“单包内携带 SNI 的 ClientHello”直接断连；浏览器的 ~1.5KB 后量子 ClientHello 天然跨包不受影响，Android 的 ~0.5KB 单包被拦。只在 HTTPS/WSS 客户端启用。
- `SecureStore`：Android Keystore AES-256-GCM 加密的 SharedPreferences。存登录 token、记住的密码。Keystore 失效时清空数据退化为“需重新登录”。
- `ApiException`：密封类，统一网络/HTTP/业务/解析/证书错误，UI 只按子类处理。

## data

- `ApiClient`：鉴权头、错误归一化（服务端错误体可能是纯文本 `http.Error` 或 `{error}`/`{msg}` JSON，常见英文错误转中文）、401 回调登出、`{code,msg,data}` 信封解析。
- `CloudPhoneApi`：对齐信令服务器路由表（`/api/login` `/api/me` `/devices` `/api/tags` `/api/quick_texts` `/api/share/*` `/api/admin/users*` `/api/devices/{id}` 等）。
- `SessionRepository`：登录态（token 只存 SecureStore）、最近服务器列表（明文 prefs，仅 URL+用户名）、记住密码（按 服务器+用户名 隔离加密保存）。收到 401 自动登出，`logoutReason` 供登录页提示。
- `DashboardSocket`：`/connect_client` 不发 `connect` 即为纯订阅模式，接收 `device_list_update` `snapshot_update` `snapshot_updated` `tags_update` `device_screen_state`。指数退避重连，20s 应用层 ping。
- `DeviceRepository`：设备列表 / 缩略图 / 标签 / 账号信息。实时推送为主，HTTP 轮询兜底；缩略图按 ETag 拉取、降采样 RGB_565。`setActive(false)` 时关闭长连接与轮询（投屏期间首页不可见，自动让出带宽）。
- `SettingsRepository`：画质（`StreamSettings` + `QualityPreset` 档位）与应用偏好（主题、宫格列数、振动、亮屏、剪贴板同步、导航键、性能浮层、自动重连、屏幕方向）。

## rtc

### 会话模型
- `StreamTarget`：一次投屏的全部不可变输入（端点、设备、鉴权、权限、模式 Screen/Camera、画质、分辨率提示）。**修改画质 / 切换镜头 / 切换设备 = 以新 target 重建会话**（agent 协议不支持运行时切换）。
- `RtcEngine`：进程级 EglBase + PeerConnectionFactory，跨会话复用（EglBase 若随会话释放，重连后渲染器黑屏）。
- `RtcSession`：一次完整连接。**所有状态变更与 PeerConnection/DataChannel 生命周期都在单线程 executor 上执行**，WebRTC/OkHttp 回调一律转投，消除竞态与死通道访问。阶段 `ConnectionPhase`：Signaling → WaitingOffer → Negotiating → Connected（Interrupted 为 ICE 瞬断宽限 8s）→ Failed/Closed。超时：连接 25s，首帧 15s。
- `SessionManager`：进程级，同一时刻一个会话。页面重建直接复用；页面不可见只停止渲染，保活 5 分钟后断开；可恢复错误在前台自动重连（指数退避，最多 3 次）。

### 信令（`rtc/signaling`）
- 登录用户：`Authorization: Bearer` 头；分享访客：`?share_token=&share_pwd=`（服务端只支持 query）。
- 流程：WS open → `connect{device_id}` → 收 `config{ice_servers}` → 建 PC 并**在协商前创建 `file-channel` / `adb-channel`** → 发 `forward{request-offer, scrcpy_options}` → 收 offer → 回 answer → 互换 candidate。
- `ScrcpyOptions`：`video_source` 两种模式都必须显式发送（agent 跨会话保留上次取值）。

### DataChannel（`rtc/channel`）
| 通道 | 创建方 | 协议类 | 内容 |
|---|---|---|---|
| `input-channel` | agent | `InputProtocol` | `touch` `inject_keycode` `inject_text` `inject_scroll` `hard_keyboard` |
| `clipboard-channel` | agent | `ClipboardProtocol` | `set_clipboard` / `get_clipboard` / 收 `clipboard`，带防回环 |
| `file-channel` | 本端 | `FileTransferClient` | list/mkdir/delete、上传（SHA-256 + 16KB 分块 + 流控）、下载、install_apk |
| `adb-channel` | 本端 | `TerminalChannel` | 首帧 `{"type":"init","rows","cols"}` 后双向裸字节（PTY）。**agent 不支持 resize** |

- `SafeChannel`：DataChannel 安全封装。native 释放后再调用会 SIGSEGV（try/catch 拦不住），所以 send/state 都在锁内判 `closed`；回调只拷贝数据后转投 executor，signaling 线程永不等待本锁（防死锁）。
- 触控坐标系是**设备逻辑分辨率**（`device_info.displays[0]`，方向不一致时宽高互换），不是编码后的视频帧尺寸。用错会被 agent 静默丢弃。

## ui

- `ui/MainActivity`：未登录 → `LoginScreen`；已登录 → NavHost（home / shares / users / tags / quick_texts / stream_settings / app_settings / certs / about）。
- `ui/home`：底部导航“设备 / 管理（仅 admin）/ 我的”。设备宫格（1–4 列）、状态/标签筛选、长按设备菜单（投屏、摄像头监控、分享、标签、信息、删除离线档案）。
- `ui/manage`：分享与卡密（创建/延期/撤销/复制/发送）、用户管理（新建/有效期与权限/备注/重置密码/踢下线/删除）、标签管理、快捷文本。
- `ui/stream`：`StreamActivity` 全屏沉浸。
  - `StreamLauncher`：StreamTarget 含凭据，只在进程内存传递，不进 Intent extras。
  - `VideoArea` 等比居中；`TouchForwarder` + `TouchMapper` 多点触控、鼠标右键=返回、滚轮。
  - 悬浮球（点击=工具面板，长按=设备侧边栏）、左侧边缘把手 `›`（打开设备侧边栏）、虚拟导航键。
  - 工具面板：电源/音量/通知栏/截图、键盘输入/文本下发/剪贴板、文件/安装应用/终端、切换设备/画质/声音/性能/连接信息/重连/断开。
  - `DeviceDrawer`：投屏中切换设备（`StreamViewModel.switchDevice`），侧边栏打开期间每 10s 刷新设备列表。
  - 终端 `TerminalWebView`：**必须懒创建**（进程内初始化 Chromium 后部分机型 libwebrtc 枚举不到 H264 硬解）；只加载 `file:///android_asset/terminal`，拦截所有跳转、禁止网络。
  - 摄像头监控：镜头/分辨率/帧率选择、环境音、镜像、抓拍、双指数字变焦（改 SurfaceView 尺寸实现）。

## 安全要点
- 凭据：token 与记住的密码只在 SecureStore；`allowBackup=false`，`data_extraction_rules` 排除全部数据。
- TLS：系统 CA + TOFU 指纹固定，绝不 trust-all。HTTP 明文仅为兼容服务端 `-tls=false`，登录页会提示。
- 权限最小化：仅 INTERNET / ACCESS_NETWORK_STATE / MODIFY_AUDIO_SETTINGS。文件走 SAF，截图走 MediaStore。
- 仅观看分享：客户端 `InputProtocol` 拦截 + 服务端信令层拦截双重保护。
- release 签名凭据只从 gradle 属性 / `local.properties` / 环境变量读取，不入库。
