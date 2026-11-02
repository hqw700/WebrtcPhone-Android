# Shizuku 被控端启动故障复盘

记录日期：2026-11-02。涉及 `app/WebrtcPhone`，已在连接的 Android 15 真机上完成签名 Release 包启动、停止、再次启动验证。

## 结论

这次有两个独立问题：首先是 App 对 Shizuku 远端进程状态的检测方式不兼容 Binder 异常转换；修复后，又暴露出 Root 与 Shizuku 共用日志文件的权限冲突。第二个问题还被后台启动命令的退出码和旧日志掩盖。

## 问题一：读取日志失败，提示 `process hasn't exited`

`ProcessDeadline` 用 `Process.exitValue()` 轮询进程状态，捕获 `IllegalThreadStateException` 后认为进程仍在运行。

本地进程符合这一行为，但 Shizuku 的 `exitValue()` 通过 Binder 调用远端进程。`IllegalThreadStateException` 继承 `IllegalArgumentException`，经过 Android Parcel 序列化后只保留父类型。App 收到的是 `IllegalArgumentException`，原有 catch 无法捕获，正常运行中的命令因此提前失败。

部署组件、查询 PID 和读取日志共用这条执行路径，所以界面虽然显示“读取日志失败”，问题实际影响了整个 Shizuku 命令流程。真机独立 Parcel 探针复现了转换结果：

```text
java.lang.IllegalArgumentException: process hasn't exited
```

修复方式：Shizuku 命令改用 `ShizukuRemoteProcess.alive()` 查询状态，确认退出后才读取退出码；保留 15 秒截止时间和 1 MiB 输出上限。每次命令执行还会复用 stdout/stderr 流，避免 Shizuku 的 stderr getter 在轮询中反复创建包装。

## 问题二：提示 Agent 尚未启动，却显示鉴权成功日志

ADB 检查确认，当时 Shizuku 服务正常运行，Agent 进程不存在，而共用日志文件的属性是：

```text
-rw-r--r-- root root /data/local/tmp/cloudphone-agent-run.log
```

Shizuku 以 UID 2000（shell）运行，无法追加 Root 所有、权限为 `0644` 的文件。因此后台进程在打开日志时就失败，Agent 没有真正启动。

还有两处行为掩盖了原因：

1. 启动使用 `(命令 >> 日志 2>&1 &)`，子进程重定向失败时，外层 Shell 仍返回 0。watchdog 误以为启动命令已正常执行，最终只能提示“Agent 尚未启动”。
2. 日志读取仍指向共用文件，界面显示了此前 01:20:23–24 的注册、鉴权成功记录，容易误以为当前 Agent 已启动但 PID 检测漏判。

以 shell 身份在故障设备上复现：

```text
background_parent_exit=0
sh: can't create /data/local/tmp/cloudphone-agent-run.log: Permission denied
```

修复方式：

- Root 日志保留 `/data/local/tmp/cloudphone-agent-run.log`。
- Shizuku 日志改为 `/data/local/tmp/cloudphone-agent-shizuku-run.log`，启动与读取使用同一模式对应的路径。
- 启动前通过 `(: >> 日志) && (后台启动命令)` 同步检查可写性；检查放在子 Shell 中，避免重定向错误终止常驻 Root Shell。
- Shizuku 命令执行失败时返回退出码与 stderr；当前模式尚未生成日志时显示暂无日志。

组件部署路径仍共用 `/data/local/tmp/cloudphone-agent` 和 `/data/local/tmp/libsys_core.so`。旧版 Agent 内部有固定核心文件路径的兼容逻辑，本次只隔离日志。

## 验证结果

- `compileDebugKotlin`、全部 56 个 JVM 单元测试及 `assembleRelease`（R8）通过。
- 新增回归覆盖远端进程状态、超时、状态查询错误传播、输出流复用、模式日志隔离和日志重定向失败的退出码。
- 修复后的签名 Release APK 已覆盖安装到故障设备，保留原配置和 Shizuku 授权。
- App 内首次启动观察到 PID 11057，界面显示“正在受控运行中”，新日志完成信令注册和鉴权。
- App 内停止后 PID 消失；再次启动观察到 PID 11289，再次注册和鉴权成功。验证结束时保持服务运行；这些 PID 仅为当时的观察值。

本次真机验证覆盖被控端启动、停止、重启与信令鉴权，未覆盖视频、触控等投屏功能。

## 后续排查

先核实实际进程、日志属主与当前模式的日志，不能仅凭历史“鉴权成功”判断当前运行状态：

```bash
adb shell 'id; pidof cloudphone-agent; pgrep -x cloudphone-agen'
adb shell 'ls -l /data/local/tmp/cloudphone-agent-run.log /data/local/tmp/cloudphone-agent-shizuku-run.log'
adb shell 'tail -n 40 /data/local/tmp/cloudphone-agent-shizuku-run.log'
adb logcat -d -t 100 -s AgentController AgentHostService
```

实现入口：[AgentController.kt](../app/src/main/java/com/webrtc_phone/android/host/AgentController.kt)、[TimedShell.kt](../app/src/main/java/com/webrtc_phone/android/host/TimedShell.kt)。
