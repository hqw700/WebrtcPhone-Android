# WebrtcPhone 进度与待办

## 2026-10-30 终端小屏手势

- 双指缩放字号（9–26），本地保存；双指手势不触发点击输入或单指滚动。
- 长按选中词，保持按住拖动可扩选多行，浮动菜单支持复制与全选；支持滚动历史和中文双宽字符。
- 字号改变仅重排客户端显示，不向远端 PTY 发送不支持的 resize 数据。
- JS 行为回归检查：`node scripts/test-terminal-gestures.cjs`；真实触摸手势仍需真机复测。

## 2026-10-30 客户终端滚动与投屏首帧排查

- 终端仅在点击时聚焦/请求键盘，滑动期间禁止父布局拦截；PTY 碎片输出按 16ms 合并再分片交给 WebView，清屏/换通道/销毁时清除待输出内容。
- WebRTC 渲染关闭硬件 Surface 缩放，减少首帧尺寸切换时的缓冲区重建；避免重复设置屏幕方向，等待会话时保留设备尺寸提示。
- 客户确认是 WebRTC：静止画面连接后局部放大，虚机画面变化后才恢复。补充 `StaticFrameRenderer`，首帧/分辨率切换期间暂存 I420 快照，布局与 Surface 尺寸改变后本地重绘，不依赖新远端帧；稳定后释放快照，避免长期占用解码器纹理或逐帧拷贝。
- 仍需真机复测静止画面首次连接、旋转和终端连续滚动，尚不能将代码验证等同于客户故障已解决。

## 2026-10-30 被控端修复

- 已恢复外部信令的被控端模式，不内嵌信令服务端或 Web；仅 Root 模式提供开机自启动，Shizuku ADB 模式需每次重启后重新激活。
- Shizuku 提供应用内授权入口；运行中锁定配置，停止时等待在途启动结束；以实际 Agent PID 判断启动结果。
- 新安装默认设备 ID 使用持久化 UUID；Shell 配置参数使用单引号转义。
- CI 执行 `bash scripts/fetch-agent-assets.sh`，每次构建查询上游最新正式 Release，下载 `cloudphone-<tag_name>.zip` 并按 GitHub 返回的 SHA-256 校验；查询/下载失败、缺少摘要或必需资源时直接失败。开发者也使用该脚本获取资源；显式传入 `-PsyncLocalAgentAssets=true` 才同步外层 `release/agentd`。二进制继续由 .gitignore 排除。
- 下方为此前历史快照。


最后更新：2026-10-18。用于换会话/换人接手。架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 背景

从 `app/WebrtcTest`（旧版 ScrcpyOverWebRTC 客户端，包名 `com.buutuu.webrtctest`）重写为独立项目：
- 去掉被控端（Agent Host）模式、Shizuku、Bugly、内置 agent 二进制。
- 新包名 `com.webrtc_phone.android`（`webrtc-phone` 中横线在包名中非法），debug 包后缀 `.debug`。
- 仓库分支：`main`。
- 外层 cloudphone 仓库中 `app/WebrtcPhone` 可作为独立仓库或子模块管理。

## Git 状态

- 已推送：`f01a785` 首次提交。
- **未提交**（工作区）：
  1. `core/net/FragmentingSocketFactory.kt`（新增）+ `HttpClientFactory.kt`：修复 app.webrtc-phone.com:8899 无法登录
  2. 登录页去掉“卡密连接”，增加“记住密码”：`ui/login/*`、`data/auth/SessionRepository.kt`、`ui/MainActivity.kt`，删除 `CardRedeemResult` / `redeemCard` / `Parsers.cardRedeem`
  3. 投屏页设备侧边栏：`ui/stream/DeviceDrawer.kt`（新增）、`StreamViewModel.kt`、`StreamScreen.kt`、`StreamControls.kt`（悬浮球长按 + `DrawerHandle`）、`StreamTools.kt`、`StreamActivity.kt`
  4. `docs/`、`AGENTS.md`（本次新增）
  5. 标签管理完善：`data/repo/DeviceRepository.kt`（Mutex 串行化标签写 + 在途保存屏蔽广播/GET 回退 + 委托 TagOps）、`data/model/Models.kt`（新增纯函数 `TagOps`）、`ui/manage/TagsAndQuickTexts.kt`（`TagEditDialog` 重名校验）、`app/src/test/.../CoreLogicTest.kt`（+4 个 TagOps 单测）
  6. 首页标签显示/搜索：`ui/home/DevicesTab.kt`（紧凑 3–4 列布局下卡片用彩色圆点显示标签，之前完全隐藏；搜索框 placeholder 加「标签」）、`ui/home/HomeViewModel.kt`（抽出纯函数 `DeviceFilter.matches`，搜索新增命中标签名）、`CoreLogicTest.kt`（+4 个 `DeviceFilterTest`）
  7. 投屏侧边栏显示设备标签：`ui/stream/DeviceDrawer.kt`（状态药丸后追加标签名药丸，超 2 个用圆点）、`ui/stream/StreamViewModel.kt`（暴露 `tags` 流 + 侧边栏刷新时顺带 `loadMeta` 拉标签）
  8. 文件管理「服务器中转」模式（上传到服务器盘→下发/安装到设备，对齐 Web 端）：`data/model/Models.kt`（`ServerFile`/`SubTaskStatus`/`TaskDetails`/`ServerTaskType`/纯函数 `ServerFileOps`）、`data/model/Parsers.kt`（`serverFiles`/`taskDetails`）、`data/api/ApiClient.kt`（`postRaw`）、`data/api/CloudPhoneApi.kt`（`serverFiles`/`uploadServerFile`/`serverFileSignedUrl`/`deleteServerFile`/`createFileTask`/`taskDetails`）、`data/repo/ServerFileRepository.kt`（新，上传带进度/列表/签URL/建任务轮询/删除）、`WebrtcPhoneApp.kt`（`UserScope.serverFiles`）、`ui/stream/StreamViewModel.kt`（`canServerRelay`=admin+User、`uploadToServer`/`dispatchToDevice`/`deleteServerFile`/`refreshServerFiles`）、`ui/stream/FilePanel.kt`（顶部「直连/服务器中转」切换 + `ServerRelayPanel`）、`CoreLogicTest.kt`（+5 个 `ServerRelayTest`）。服务端契约：`/upload?name=`、`/api/files` GET/DELETE、`/api/files/url`、`/api/tasks`(非 shell 为 admin-only)、`/api/tasks/details`。
  9. 管理页「批量文件分发」（对齐 Web 端批量安装/传输，一次任务下发多台设备）：`data/repo/ServerFileRepository.kt`（`dispatchBatch(file,deviceIds,destPath)` + `task`/`dispatching` StateFlow + `clearTask`）、`ui/manage/BatchFilesViewModel.kt`、`ui/manage/BatchFilesScreen.kt`、`ui/home/ManageAndMeTabs.kt`（「批量操作」入口 + `Routes.BATCH_FILES`）、`ui/MainActivity.kt`（NavHost 挂载）。**界面重设计（应对大量文件/设备）**：两步向导（①选文件 ②选设备，顶部步骤指示 + 完成态勾选）；文件与设备各自独立的搜索框 + 设备侧状态(在线/全部)/标签筛选 + 「全选当前」(只影响筛选结果)/清空；文件/设备均为可独立滚动的卡片 `LazyColumn`（选中态蓝边+勾选）；底部常驻操作条显示当前文件/已选台数并发起下发（apk→安装、其它→可编辑 dest_path）；任务进度改为底部弹层看板（汇总 Pill + 双色进度条 + per-device 滚动列表）。视觉沿用全局设计系统（Brand 主色、large 圆角、Pill、StatusDot、EmptyState）。
  10. WS 模式投屏（WebSocket H.264 中转，WebRTC 免穿透替代，协议对齐 web-app `useWebSocketStream.js`/`stores/devices.js`）：`rtc/ws/WsProtocol.kt`（新，纯函数：PREV 49 字节头解析 + start_preview/stop_preview/group_control_event 构造）、`rtc/ws/WsStreamSession.kt`（新，OkHttp WS `/connect_client?token=` → start_preview → 二进制 PREV → 单线程 MediaCodec(AVC) 解码到 Surface → 输入经 group_control_event；暴露 phase/videoSize/firstFrame/stats；1s 统计 fps/bitrate；close 发 stop_preview）、`rtc/StreamTarget.kt`（`StreamTransport{WEBRTC,WEBSOCKET}` + `transport`/`isWs`）、`ui/stream/StreamViewModel.kt`（transport-aware：WS 走 `_wsSession`，`activeInput` 统一输入，`setWsSurface`/`restartWs`/`onCleared`，WS 下 files/terminal=null、文本走 inject_text）、`ui/stream/StreamActivity.kt`（WS→`SurfaceView`+holder callback→`setWsSurface`，WebRTC→`SurfaceViewRenderer`，renderer 可空保护 mirror/screenshot/attach）、`ui/stream/StreamScreen.kt`+`StreamOverlays.kt`（`VideoArea`/`StreamScreen` 参数泛化为 `View`）、`ui/stream/TouchForwarder.kt`（改走 `vm.touch/scroll/press`→activeInput，修复 WS 下 `vm.session` 为 null 不发事件）、`ui/home/HomeViewModel.kt`（`targetFor(transport=)`）、`ui/home/DeviceSheets.kt`+`HomeScreen.kt`（设备菜单「WS 模式投屏」入口）、`CoreLogicTest.kt`（+6 个 `WsProtocolTest`）。限制：WS 模式仅视频+直控，无音频/文件/终端/截图/镜像（与 web 一致）。
  10b. WS 模式交互完善：`ui/stream/StreamScreen.kt`（顶部「WS 中转模式」蓝色 Banner 标识）；`ui/stream/StreamTools.kt`（WS 模式下 截图/发送剪贴板/获取剪贴板/文件/安装应用/终端/声音 置灰 enabled=false；「画面与连接」新增「切到 WebRTC / 切到 WS 模式」切换项，分享访客不显示）；`ui/stream/StreamViewModel.kt`（`switchTransport(to)` 关闭当前会话→改 target.transport→`recreateEvent`）；`ui/stream/StreamActivity.kt`（收 `recreateEvent` → finish + 重新 launch，换渲染视图）。真机验证：WS 徽标显示、禁用项置灰、切到 WebRTC 后 ICE CONNECTED + 局域网直连，往返切换正常。
- `local.properties` 已含 release 签名配置（从 `app/WebrtcTest/local.properties` 复制），被 `.gitignore` 忽略，**不要提交**。

## 已在真机验证（Android 15 真机设备）

测试服务器：内网私有部署服务器（自签证书）；公网部署服务器。账号见实际环境，不写入仓库。

- 登录：自签证书 TOFU 确认、账号登录、重启后自动恢复登录、错误中文提示、退出登录
- 公网服务器登录（ClientHello 拆包修复后）
- 设备大盘：WS 实时更新、缩略图、状态/标签筛选
- 投屏：画面、局域网直连约 41ms、触控、导航键、键盘输入、快捷文本下发、截图、连接信息、重连、断开
- 文件浏览、终端（输入输出、键盘弹出时面板不被压缩）
- 标签：新建、分配、卡片标签、按标签筛选（服务端 API 已确认持久化）
- 分享：创建（仅观看 + 密码）、延期、撤销（撤销后兑换返回 404）
- 卡密访客（该入口现已从登录页移除）：密码提示、错误密码、仅观看拦截
- release 包（R8 混淆）：登录、投屏、终端 JS 桥正常
- 记住密码：加密写入 SecureStore（已确认文件中无明文）
- **投屏中设备侧边栏**（真机设备验证）：左侧 `›` 把手打开侧边栏；当前设备置顶高亮“当前设备”、在线（空闲）、离线（灰显）排序正确；点在线设备 `switchDevice` 重建会话（ICE 重新 CHECKING→CONNECTED，画面确为新设备）；返回键先关侧边栏不退出投屏。compileDebugKotlin / testDebugUnitTest / assembleRelease(R8) 均通过。
- **标签管理完善**（2026-10-18 本会话验证，admin）：重名拦截（输入已存在 `pve` → 红色错误「已存在同名标签」+ 保存禁用）；唯一名恢复可保存；新建「test-tag」入列 + snackbar「已保存」；删除确认 → 复原两项 + 「已删除」；logcat 无 crash。底层修复：`TagOps` 纯函数 + `tagWriteMutex` 串行化 + `tagSaveInFlight` 屏蔽在途广播/GET 回退（消除并发丢更新）。

## 未完成 / 未验证

### 需要编译 + 真机验证（代码已写，未编译）
- **首帧超时提示**（15s 无画面给出原因）：已编译安装，未观察到触发。

### 未在真机测过的功能
- 文件上传/下载、APK 安装（本机选择 → 上传 → 自动安装）
- 剪贴板双向同步
- 用户新建 / 编辑有效期与权限 / 删除 / 踢下线
- 退出登录后再进登录页，密码是否自动填回

### 已知问题 / 限制
- **agent `hard_keyboard`**：开启后视频停止（0 fps，需重连）。App 已不再发送该命令，代价是云手机软键盘可能弹出遮挡画面。根治需修 agent。
- agent 不支持 PTY resize：终端行列在 init 时确定。
- 摄像头模式下悬浮球隐藏，长按入口不可用；左侧把手 `DrawerHandle` 不判断 `isCamera`，摄像头模式下仍显示，可以用它切换设备（切换后回到普通投屏模式）。
- 界面文案硬编码中文，未抽到 `strings.xml`，不支持多语言。
- 仓库无 README。

### 用户提过、尚未开始
- 是否把**首页**也改成侧边栏布局（类似网页管理台左侧导航）——已向用户确认，未得到答复。
- 预留未实现：从本机已安装应用提取 APK 推送、云手机应用管理（卸载/清数据）、批量群控。

## 建议的下一步
1. ~~编译并真机验证侧边栏~~（已完成，见“已验证”）。
2. 测摄像头（真实摄像头设备）与上面“未在真机测过”的列表（文件上传下载 / APK 安装 / 剪贴板同步 / 用户管理）。
3. 全部编译/测试已通过（compileDebugKotlin / testDebugUnitTest / assembleRelease），可提交并推送全部未提交改动（推 `main` 前先征得用户同意）。
