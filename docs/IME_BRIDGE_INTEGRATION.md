# IME Bridge 集成指南

## 概述

IME Bridge 是一个用于 Android SSH 终端应用的功能模块，能够自动检测用户在 SSH 会话中的目录变化，读取项目的词库配置文件（`.ime/meta.json`），并通过广播将配置信息发送给输入法应用（fcitx5），实现词库 profile 的自动切换。

## 功能特性

- ✅ 实时监听 SSH 会话中的目录变化（`cd`、`pushd`、`popd` 命令）
- ✅ 使用独立的 SSH exec channel 进行文件探测，不干扰用户终端
- ✅ 自动读取 `.ime/meta.json` 配置文件
- ✅ 通过广播发送配置到 fcitx5 输入法应用
- ✅ 支持 Android 11+ 的包可见性限制
- ✅ 内置防抖和去重机制，避免重复广播

## 前置要求

1. Android 项目使用 Kotlin
2. 已集成 JSch 库用于 SSH 连接
3. 目标 Android API 30+（Android 11+）
4. 已安装 fcitx5 输入法应用（`com.lsl.lemonhall.fcitx5` 或 `com.lsl.lemonhall.fcitx5.debug`）

## 集成步骤

### 1. 添加依赖

在 `build.gradle.kts` 中添加必要的依赖：

```kotlin
dependencies {
    // Coroutines（如果还没有）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSch for SSH（如果还没有）
    implementation("com.jcraft.jsch:jsch:0.1.55")
}
```

### 2. 复制代码文件

从 `jediterm-android/sample` 项目复制以下文件到你的项目：

```
sample/src/main/java/com/lemonhall/jediterm/sample/imebridge/
├── DispatchGuard.kt
├── ImeBridgeConfig.kt
├── ImeBridgeController.kt
├── ImeBridgeHelper.kt
├── ImeBroadcastDispatcher.kt
├── MetaPayloadBuilder.kt
├── RemoteMetaProbe.kt
├── SessionContextMonitor.kt
└── model/
    ├── DispatchEvent.kt
    ├── ProbeResult.kt
    └── SessionContext.kt
```

**注意**：复制后需要修改包名，将 `com.lemonhall.jediterm.sample.imebridge` 改为你的项目包名。

### 3. 修改 AndroidManifest.xml

**这是关键步骤！** 从 Android 11 开始，应用需要显式声明要查询的其他应用包名。

在 `AndroidManifest.xml` 中添加 `<queries>` 标签：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- ⚠️ 重要：声明要查询的 IME 应用包名 -->
    <queries>
        <package android:name="com.lsl.lemonhall.fcitx5" />
        <package android:name="com.lsl.lemonhall.fcitx5.debug" />
    </queries>

    <application ...>
        ...
    </application>
</manifest>
```

**为什么需要这个？**
- Android 11+ 引入了包可见性限制
- 默认情况下，应用无法查询其他应用是否安装
- 必须在 Manifest 中显式声明才能使用 `PackageManager.getPackageInfo()`

### 4. 修改 JSchTtyConnector

在你的 SSH 连接类中添加目录监控功能。参考 `JSchTtyConnector.kt` 的实现：

#### 4.1 添加字段和回调

```kotlin
class YourSshConnector(...) {
    var onCwdChangeCallback: ((String) -> Unit)? = null
    private val readBuffer = StringBuilder()
    private var session: Session? = null  // 保存 JSch Session 引用

    // ... 其他字段
}
```

#### 4.2 在连接时注入 Shell 钩子

```kotlin
fun connect() {
    // ... 建立 SSH 连接的代码

    // 注入目录变化监控钩子
    val initCmd = """
        export LANG=en_US.UTF-8; export LC_ALL=en_US.UTF_8; stty iutf8
        __ime_report_cwd() { echo "__IME_CWD__$(pwd -P)__IME_CWD__"; }
        cd() { builtin cd "$@" && __ime_report_cwd; }
        pushd() { builtin pushd "$@" && __ime_report_cwd; }
        popd() { builtin popd "$@" && __ime_report_cwd; }
        __ime_report_cwd
    """.trimIndent() + "\n"

    outputStream.write(initCmd.toByteArray(StandardCharsets.UTF_8))
    outputStream.flush()
}
```

#### 4.3 在 read() 方法中检测目录变化

```kotlin
override fun read(buf: CharArray, offset: Int, length: Int): Int {
    val n = reader.read(buf, offset, length)
    if (n > 0) {
        detectCwdFromOutput(buf, offset, n)
    }
    return n
}

private fun detectCwdFromOutput(buf: CharArray, offset: Int, count: Int) {
    try {
        readBuffer.append(buf, offset, count)

        // 保持 buffer 大小合理
        if (readBuffer.length > 4096) {
            readBuffer.delete(0, readBuffer.length - 2048)
        }

        val marker = "__IME_CWD__"
        val text = readBuffer.toString()
        val startIdx = text.indexOf(marker)
        if (startIdx >= 0) {
            val endIdx = text.indexOf(marker, startIdx + marker.length)
            if (endIdx > startIdx) {
                val cwd = text.substring(startIdx + marker.length, endIdx).trim()
                if (cwd.isNotEmpty()) {
                    onCwdChangeCallback?.invoke(cwd)
                }
                readBuffer.delete(0, endIdx + marker.length)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error detecting cwd", e)
    }
}
```

#### 4.4 添加获取 Session 的方法

```kotlin
fun getSession(): Session? = session
fun getSessionInfo(): Triple<String, String, Int> = Triple(host, username, port)
```

### 5. 在 MainActivity 中集成

在 SSH 连接成功后启动 IME Bridge：

```kotlin
class MainActivity : ComponentActivity() {
    private var imeBridge: ImeBridgeHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            var connector by remember { mutableStateOf<YourSshConnector?>(null) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    // 建立 SSH 连接
                    val conn = YourSshConnector(...)
                    conn.connect()
                    connector = conn

                    // 启动 IME Bridge
                    val bridge = ImeBridgeHelper(
                        context = context,
                        connector = conn,
                        config = ImeBridgeConfig(enabled = true),
                    )
                    bridge.start()
                    imeBridge = bridge
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    imeBridge?.stop()
                    connector?.close()
                }
            }

            // ... UI 代码
        }
    }
}
```

## 配置说明

### ImeBridgeConfig 参数

```kotlin
data class ImeBridgeConfig(
    val enabled: Boolean = true,        // 是否启用 IME Bridge
    val debounceMs: Long = 500,         // 防抖延迟（毫秒）
    val probeTimeoutMs: Long = 1500,    // 文件探测超时（毫秒）
)
```

### 广播协议

IME Bridge 发送的广播格式：

- **Action**: `com.lsl.lemonhall.fcitx5.action.APPLY_PROJECT_META`
- **Extra Key**: `meta_json`
- **Extra Value**: JSON 字符串

JSON 格式示例：
```json
{
  "version": 1,
  "dict_profiles": ["profile1", "profile2", "profile3"]
}
```

如果未找到 `.ime/meta.json` 文件，会发送 fallback payload：
```json
{
  "version": 1,
  "dict_profiles": ["base"]
}
```

## 测试验证

### 1. 准备测试环境

在 SSH 服务器上创建测试文件：

```bash
cd ~/test-project
mkdir -p .ime
cat > .ime/meta.json << 'EOF'
{
  "version": 1,
  "project": "test-project",
  "dict_profiles": [
    "engineering.testing",
    "app.android"
  ]
}
EOF
```

### 2. 运行应用并测试

1. 启动应用并连接 SSH
2. 等待 5 秒（初始检测）
3. 执行 `cd ~/test-project`
4. 观察 logcat 日志

### 3. 查看日志

```bash
adb logcat -s ImeBroadcastDispatcher:* ImeBridgeHelper:* RemoteMetaProbe:* -v time
```

成功的日志应该包含：

```
ImeBridgeHelper: Directory changed detected: /Users/xxx/test-project
RemoteMetaProbe: ✓ Meta file read successfully, size=xxx
ImeBroadcastDispatcher: ✓ Package found: com.lsl.lemonhall.fcitx5.debug
ImeBroadcastDispatcher: ✓ Broadcast sent successfully
ImeBroadcastDispatcher:   Payload: {"version":1,"dict_profiles":[...]}
```

## 故障排查

### 问题 1：包检测失败

**症状**：
```
ImeBroadcastDispatcher: ✗ Package not found: com.lsl.lemonhall.fcitx5.debug
```

**解决方案**：
- 确认已在 `AndroidManifest.xml` 中添加 `<queries>` 标签
- 确认 fcitx5 应用已安装：`adb shell pm list packages | grep fcitx`
- 重新安装应用（Manifest 修改需要重新安装）

### 问题 2：文件探测失败

**症状**：
```
RemoteMetaProbe: ✗ Meta file not found in /path/to/dir
```

**解决方案**：
- 确认文件路径是 `.ime/meta.json`（注意有个点）
- 在 SSH 终端执行 `ls -la .ime/meta.json` 确认文件存在
- 检查文件权限是否可读

### 问题 3：目录变化未检测

**症状**：执行 `cd` 命令后没有触发检测

**解决方案**：
- 确认已在连接时注入 shell 钩子函数
- 检查 `onCwdChangeCallback` 是否正确设置
- 查看 logcat 是否有 `JeditermSsh: Directory changed to:` 日志

### 问题 4：探测命令干扰终端

**症状**：在终端中看到 `test -f .ime/meta.json` 等命令

**原因**：使用了 shell channel 的 outputStream 执行命令

**解决方案**：
- 确保 `RemoteMetaProbe` 使用独立的 exec channel
- 检查是否正确传递了 `Session` 对象而不是 `OutputStream`

## 工作原理

### 架构图

```
┌─────────────────┐
│  MainActivity   │
└────────┬────────┘
         │ creates
         ▼
┌─────────────────┐      ┌──────────────────┐
│ ImeBridgeHelper │─────▶│ JSchTtyConnector │
└────────┬────────┘      └──────────────────┘
         │ manages              │
         ▼                      │ onCwdChangeCallback
┌─────────────────┐             │
│ImeBridgeController│◀───────────┘
└────────┬────────┘
         │ coordinates
         ▼
┌────────────────────────────────────────┐
│  ┌──────────────┐  ┌─────────────────┐│
│  │RemoteMetaProbe│  │DispatchGuard    ││
│  └──────────────┘  └─────────────────┘│
│  ┌──────────────┐  ┌─────────────────┐│
│  │MetaPayload   │  │ImeBroadcast     ││
│  │Builder       │  │Dispatcher       ││
│  └──────────────┘  └─────────────────┘│
└────────────────────────────────────────┘
                │
                ▼
        ┌───────────────┐
        │  fcitx5 IME   │
        └───────────────┘
```

### 执行流程

1. **连接建立**：SSH 连接时注入 shell 钩子函数
2. **目录监控**：用户执行 `cd` 命令时，钩子函数输出特殊标记
3. **标记检测**：`JSchTtyConnector.read()` 检测到标记，提取目录路径
4. **回调触发**：调用 `onCwdChangeCallback`，通知 `ImeBridgeHelper`
5. **文件探测**：使用独立的 exec channel 执行 `test -f` 和 `cat` 命令
6. **内容解析**：读取 `.ime/meta.json` 文件内容
7. **去重防抖**：`DispatchGuard` 检查是否需要发送广播
8. **广播发送**：通过 `ImeBroadcastDispatcher` 发送广播到 fcitx5

## 参考资料

- 完整示例代码：`jediterm-android/sample` 项目
- PRD 文档：`docs/prd/ime-integration.md`
- JSch 文档：http://www.jcraft.com/jsch/
- Android 包可见性：https://developer.android.com/training/package-visibility

## 许可证

本集成指南和相关代码遵循 jediterm-android 项目的许可证。
