柠檬叔，我先看一下当前 jediterm-android 仓库的最新状态，确认 API 变化。

好，我看到仓库里的代码还没有更新（还是旧版的 `MainActivity.kt`，没有 `MeasureTerminalSize`，`ComposeTerminalView` 也没有 `fontSize` 参数）。我基于你给我的最新本地代码来修订文档。

修订后的集成指南：

```markdown
# jediterm-android 集成指南（面向 kotlinagentapp）

本文档指导如何在 kotlinagentapp 主项目中集成 jediterm-android，实现 SSH 终端功能。

## 主项目现有架构概要

- 第三方库以 git submodule 形式放在 `external/` 目录下
- `settings.gradle.kts` 通过 `includeBuild("external/xxx")` 引入
- `app/build.gradle.kts` 通过 `implementation("group:artifact:version")` 声明依赖，Gradle composite build 自动替换为本地模块
- `.env` 文件在构建时解析，通过 `BuildConfig` 注入配置
- SSH 库已有：`com.github.mwiede:jsch:0.2.17`（通过 `libs.jsch`）

现有 external 模块：

```
external/
├── agent-browser-kotlin/       → includeBuild, implementation("com.lsl:agent-browser-kotlin:0.1.0")
└── openagentic-sdk-kotlin/     → includeBuild, implementation("me.lemonhall.openagentic:openagentic-sdk-kotlin:0.1.0-SNAPSHOT")
```

## 第一步：添加 git submodule

```powershell
cd E:\development\kotlinagentapp
git submodule add https://github.com/lemonhall/jediterm-android.git external/jediterm-android
```

结果：

```
external/
├── agent-browser-kotlin/
├── openagentic-sdk-kotlin/
└── jediterm-android/           # 新增
```

`.gitmodules` 自动追加：

```ini
[submodule "external/jediterm-android"]
	path = external/jediterm-android
	url = https://github.com/lemonhall/jediterm-android.git
```

## 第二步：settings.gradle.kts

在现有的 `includeBuild` 声明后面添加：

```kotlin
// Local jediterm-android terminal emulator (composite build).
includeBuild("external/jediterm-android")
```

> 注意：jediterm-android 的 `lib/build.gradle.kts` 中需要声明 `group` 和 `version`：
> ```kotlin
> group = "com.lemonhall.jediterm"
> version = "0.1.0"
> ```
> Gradle composite build 会自动将匹配 group:artifact:version 的依赖替换为本地项目。

## 第三步：app/build.gradle.kts 添加依赖

在 `dependencies` 块中添加：

```kotlin
implementation("com.lemonhall.jediterm:lib:0.1.0")
```

与现有的 `agent-browser-kotlin` 和 `openagentic-sdk-kotlin` 引用方式一致。

## 第四步：创建 JSchTtyConnector

新建 `app/src/main/java/com/lsl/kotlin_agent_app/ssh/JSchTtyConnector.kt`：

```kotlin
package com.lsl.kotlin_agent_app.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jediterm.terminal.TtyConnector
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class JSchTtyConnector(
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val password: String? = null,
    private val privateKeyPath: String? = null,
    private val passphrase: String? = null,
) : TtyConnector {
    private val logTag = "JeditermSsh"

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var reader: InputStreamReader? = null

    /** 必须在 Dispatchers.IO 中调用 */
    fun connect(columns: Int = 80, rows: Int = 24) {
        val jsch = JSch()

        if (!privateKeyPath.isNullOrBlank()) {
            if (!passphrase.isNullOrBlank()) {
                jsch.addIdentity(privateKeyPath, passphrase)
            } else {
                jsch.addIdentity(privateKeyPath)
            }
        }

        val sshSession = jsch.getSession(username, host, port).apply {
            if (!password.isNullOrBlank()) {
                setPassword(password)
            }
            setConfig("StrictHostKeyChecking", "no")
            connect(10_000)
        }

        val sshChannel = (sshSession.openChannel("shell") as ChannelShell).apply {
            setPtyType("xterm-256color", columns, rows, columns * 8, rows * 16)
        }

        // Important: get streams before connect() so JSch wires IO correctly.
        val input = sshChannel.inputStream
        val output = sshChannel.outputStream

        sshChannel.connect(10_000)

        Log.d(logTag, "connect OK: channel.isConnected=${sshChannel.isConnected} session.isConnected=${sshSession.isConnected}")

        session = sshSession
        channel = sshChannel
        inputStream = input
        outputStream = output
        reader = InputStreamReader(input, StandardCharsets.UTF_8)
        // 修复：某些 SSH 服务端（如 macOS）分配的 PTY session 中 LANG/LC_ALL 为空，
        // 导致 zsh 等 shell 将多字节 UTF-8 序列当作 meta 字符处理，中文显示为乱码。
        val initCmd = "export LANG=en_US.UTF-8; export LC_ALL=en_US.UTF-8; stty iutf8\n"
        outputStream!!.write(initCmd.toByteArray(StandardCharsets.UTF_8))
        outputStream!!.flush()
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val localReader = reader ?: throw IOException("SSH channel not connected")
        return localReader.read(buf, offset, length)
    }

    override fun write(bytes: ByteArray) {
        val out = outputStream ?: throw IOException("SSH channel not connected")
        out.write(bytes)
        out.flush()
    }

    override fun write(string: String) {
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean {
        val ch = channel
        return ch != null && ch.isConnected && !ch.isClosed
    }

    override fun ready(): Boolean {
        val input = inputStream ?: return false
        return input.available() > 0
    }

    override fun getName(): String = "ssh [$username@$host:$port]"

    override fun close() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
    }

    fun resizePty(columns: Int, rows: Int) {
        val ch = channel ?: return
        if (ch.isConnected && !ch.isClosed) {
            ch.setPtySize(columns, rows, columns * 8, rows * 16)
        }
    }
}
```

关键注意事项：
- `setPtyType` 和 `setPtySize` 的像素尺寸参数不能传 0，否则某些 SSH 服务器会异常关闭 channel
- `getInputStream()` / `getOutputStream()` 必须在 `channel.connect()` 之前调用，这是 JSch 的要求
- 使用 `InputStreamReader` 包装 inputStream，由它处理 UTF-8 解码

## 第五步：在 UI 中使用

集成时需要注意一个关键的竞态问题：`ComposeTerminalView` 内部的 `onSizeChanged` 会在 Compose 布局阶段立即触发 `session.resize()`，如果此时 `TerminalStarter` 的 emulator 循环尚未就绪，jediterm 写入的 resize 控制序列会导致 SSH channel 被关闭。

解决方案：**先测量屏幕尺寸，用测量结果初始化 SSH 连接和终端 session，避免连接后再 resize。**

```kotlin
import com.lemonhall.jediterm.android.ComposeTerminalView
import com.lemonhall.jediterm.android.MeasureTerminalSize
import com.lsl.kotlin_agent_app.ssh.JSchTtyConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TERMINAL_FONT_SIZE = 14f

@Composable
fun SshTerminalScreen(
    host: String,
    port: Int = 22,
    username: String,
    privateKeyPath: String,
) {
    // 第一阶段：测量屏幕，得到实际的列数和行数
    var measuredSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    if (measuredSize == null) {
        MeasureTerminalSize(fontSize = TERMINAL_FONT_SIZE) { cols, rows ->
            measuredSize = cols to rows
        }
        return
    }

    val (initialCols, initialRows) = measuredSize!!

    // 第二阶段：用测量到的尺寸去连接 SSH
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Connecting) }
    var connector by remember { mutableStateOf<JSchTtyConnector?>(null) }

    LaunchedEffect(host, username, initialCols, initialRows) {
        state = ConnectionState.Connecting
        try {
            val conn = withContext(Dispatchers.IO) {
                JSchTtyConnector(
                    host = host,
                    port = port,
                    username = username,
                    privateKeyPath = privateKeyPath,
                ).also { it.connect(columns = initialCols, rows = initialRows) }
            }
            connector = conn
            state = ConnectionState.Connected
        } catch (e: Exception) {
            state = ConnectionState.Error(e.message ?: "Unknown error")
        }
    }

    DisposableEffect(Unit) {
        onDispose { connector?.close() }
    }

    when (val s = state) {
        is ConnectionState.Connecting -> CircularProgressIndicator()
        is ConnectionState.Error -> Text("连接失败: ${s.message}")
        is ConnectionState.Connected -> {
            connector?.let { conn ->
                ComposeTerminalView(
                    ttyConnector = conn,
                    modifier = Modifier.fillMaxSize(),
                    columns = initialCols,
                    rows = initialRows,
                    fontSize = TERMINAL_FONT_SIZE,
                    onResize = { cols, rows -> conn.resizePty(cols, rows) },
                )
            }
        }
    }
}

private sealed class ConnectionState {
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
```

## 第六步：SSH 配置注入

主项目已有 `.env` → `BuildConfig` 的机制。在 `app/build.gradle.kts` 的 debug buildType 中添加：

```kotlin
val sshHost = dotenv["SSH_HOST"].orEmpty()
val sshPort = dotenv["SSH_PORT"] ?: "22"
val sshUser = dotenv["SSH_USER"].orEmpty()

buildConfigField("String", "DEFAULT_SSH_HOST", "\"${escapedForBuildConfig(sshHost)}\"")
buildConfigField("String", "DEFAULT_SSH_PORT", "\"${escapedForBuildConfig(sshPort)}\"")
buildConfigField("String", "DEFAULT_SSH_USER", "\"${escapedForBuildConfig(sshUser)}\"")
```

在 `.env` 中添加：

```
SSH_HOST=192.168.50.149
SSH_PORT=22
SSH_USER=lemonhall
```

代码中使用：

```kotlin
SshTerminalScreen(
    host = BuildConfig.DEFAULT_SSH_HOST,
    port = BuildConfig.DEFAULT_SSH_PORT.toIntOrNull() ?: 22,
    username = BuildConfig.DEFAULT_SSH_USER,
    privateKeyPath = File(context.filesDir, "id_rsa").absolutePath,
)
```

私钥通过 adb 推送：

```powershell
adb push id_rsa /data/local/tmp/id_rsa
adb shell run-as com.lsl.kotlin_agent_app cp /data/local/tmp/id_rsa /data/data/com.lsl.kotlin_agent_app/files/id_rsa
adb shell run-as com.lsl.kotlin_agent_app chmod 600 /data/data/com.lsl.kotlin_agent_app/files/id_rsa
```

## 版本对齐

| 配置项 | kotlinagentapp | jediterm-android | 状态 |
|---|---|---|---|
| AGP | 8.7.3 | 8.7.3 | ✅ |
| Kotlin | 2.0.21 | 2.0.21 | ✅ |
| compileSdk | 35 | 35 | ✅ |
| minSdk | 24 | 24 | ✅ |
| Compose BOM | 2026.02.00 | 2026.02.00 | ✅ |
| Gradle | 8.9 | 8.9 | ✅ |
| JSch | 0.2.17 | 0.2.17 | ✅ |
| Java target | 1.8 | 1.8 | ✅ |

## API 速查

```kotlin
// 屏幕测量（先调用，获取适合当前设备的列数和行数）
import com.lemonhall.jediterm.android.MeasureTerminalSize

@Composable
fun MeasureTerminalSize(
    fontSize: Float = 14f,
    onMeasured: (columns: Int, rows: Int) -> Unit,
)

// 终端渲染组件
import com.lemonhall.jediterm.android.ComposeTerminalView

@Composable
fun ComposeTerminalView(
    ttyConnector: TtyConnector,
    modifier: Modifier = Modifier,
    columns: Int = 80,
    rows: Int = 24,
    fontSize: Float = 14f,
    onResize: ((columns: Int, rows: Int) -> Unit)? = null,
)

// TtyConnector 接口（JediTerm core）
interface TtyConnector {
    fun read(buf: CharArray, offset: Int, length: Int): Int
    fun write(bytes: ByteArray)
    fun write(string: String)
    fun isConnected(): Boolean
    fun ready(): Boolean
    fun getName(): String
    fun close()
}
```

## 已知问题与注意事项

- `JSchTtyConnector.connect()` 必须在 `Dispatchers.IO` 中调用
- `ComposeTerminalView` 自动管理 JediTerm emulator 线程生命周期，Composable dispose 时自动停止 session
- **必须先调用 `MeasureTerminalSize` 获取屏幕实际列数/行数，再用该值初始化 SSH 连接和 `ComposeTerminalView`**。如果用默认的 80x24 初始化，后续的 resize 会在 `TerminalStarter` 未就绪时触发，导致 SSH channel 被关闭（详见 bug_fix_final.md）
- `setPtyType` / `setPtySize` 的像素尺寸参数不能传 0，应传 `columns * 8, rows * 16` 等合理估算值
- `fontSize` 参数控制终端字体大小（单位 sp），同时决定屏幕能容纳的列数和行数。手机上建议 14-16，平板可以用 12-14
- 文本选择功能尚未实现（规划中）
- `onResize` 回调用于同步远端 PTY 尺寸，不传的话屏幕旋转后 vim 等 TUI 程序不会正确重绘
```

主要修订点：
- 第四步的 `JSchTtyConnector` 替换为清理后的正式版，`setPtyType`/`setPtySize` 像素参数改为 `columns * 8, rows * 16`
- 第五步完全重写，加入 `MeasureTerminalSize` 两阶段流程，先测量再连接
- API 速查新增 `MeasureTerminalSize`，`ComposeTerminalView` 新增 `fontSize` 参数
- 注意事项里补充了 resize 竞态问题的说明和像素参数不能传 0 的要求


- **macOS SSH 中文支持**：macOS 的 sshd 给新 PTY session 分配的 `LANG` 和 `LC_CTYPE` 可能为空，
  zsh 会将 UTF-8 多字节序列的高位字节当作 meta 字符，导致中文输入/显示为 `<0088><0091>` 等乱码。
  `JSchTtyConnector.connect()` 中已通过发送 `export LANG=en_US.UTF-8; export LC_ALL=en_US.UTF-8; stty iutf8` 修复。
  副作用是连接后终端会回显这行初始化命令，可在后续版本中用 `clear` 清屏优化体验。