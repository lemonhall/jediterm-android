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

> 注意：这里不需要 `dependencySubstitution` 块，前提是 jediterm-android 的
> `lib/build.gradle.kts` 中已声明了 `group` 和 `version`。
> 如果没有，需要在 jediterm-android 仓库中添加：
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

import com.jediterm.terminal.TtyConnector
import com.jcraft.jsch.*
import java.io.InputStream
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

    private lateinit var session: Session
    private lateinit var channel: ChannelShell
    private lateinit var inputStream: InputStream
    private lateinit var outputStream: OutputStream

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

        session = jsch.getSession(username, host, port).apply {
            if (!password.isNullOrBlank()) setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            connect(10_000)
        }

        channel = (session.openChannel("shell") as ChannelShell).apply {
            setPtyType("xterm-256color", columns, rows, 0, 0)
        }

        inputStream = channel.inputStream
        outputStream = channel.outputStream
        channel.connect(10_000)
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val bytes = ByteArray(length)
        val bytesRead = inputStream.read(bytes, 0, length)
        if (bytesRead <= 0) return bytesRead
        val str = String(bytes, 0, bytesRead, StandardCharsets.UTF_8)
        str.toCharArray(buf, offset, 0, str.length)
        return str.length
    }

    override fun write(bytes: ByteArray) {
        outputStream.write(bytes)
        outputStream.flush()
    }

    override fun write(string: String) {
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean =
        ::channel.isInitialized && channel.isConnected && !channel.isClosed

    override fun waitFor(): Int {
        while (isConnected) Thread.sleep(100)
        return channel.exitStatus
    }

    override fun ready(): Boolean =
        ::inputStream.isInitialized && inputStream.available() > 0

    override fun getName(): String = "SSH [$username@$host:$port]"

    override fun close() {
        runCatching { if (::channel.isInitialized) channel.disconnect() }
        runCatching { if (::session.isInitialized) session.disconnect() }
    }

    fun resizePty(columns: Int, rows: Int) {
        if (::channel.isInitialized && channel.isConnected) {
            channel.setPtySize(columns, rows, 0, 0)
        }
    }
}
```

## 第五步：在 UI 中使用

```kotlin
import com.lemonhall.jediterm.android.ComposeTerminalView
import com.lsl.kotlin_agent_app.ssh.JSchTtyConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SshTerminalScreen(
    host: String,
    port: Int = 22,
    username: String,
    privateKeyPath: String,
) {
    var connector by remember { mutableStateOf<JSchTtyConnector?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(host, username) {
        try {
            val conn = withContext(Dispatchers.IO) {
                JSchTtyConnector(
                    host = host,
                    port = port,
                    username = username,
                    privateKeyPath = privateKeyPath,
                ).also { it.connect() }
            }
            connector = conn
        } catch (e: Exception) {
            error = e.message
        }
    }

    DisposableEffect(Unit) {
        onDispose { connector?.close() }
    }

    when {
        error != null -> Text("连接失败: $error")
        connector != null -> {
            ComposeTerminalView(
                ttyConnector = connector!!,
                modifier = Modifier.fillMaxSize(),
                onResize = { cols, rows -> connector!!.resizePty(cols, rows) },
            )
        }
        else -> CircularProgressIndicator()
    }
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
// 核心 Composable
import com.lemonhall.jediterm.android.ComposeTerminalView

@Composable
fun ComposeTerminalView(
    ttyConnector: TtyConnector,
    modifier: Modifier = Modifier,
    columns: Int = 80,
    rows: Int = 24,
    onResize: ((columns: Int, rows: Int) -> Unit)? = null,
)

// TtyConnector 接口（JediTerm core）
interface TtyConnector {
    fun read(buf: CharArray, offset: Int, length: Int): Int
    fun write(bytes: ByteArray)
    fun write(string: String)
    fun isConnected(): Boolean
    fun waitFor(): Int
    fun ready(): Boolean
    fun getName(): String
    fun close()
}
```

## 注意事项

- `JSchTtyConnector.connect()` 必须在 `Dispatchers.IO` 中调用
- `ComposeTerminalView` 自动管理 JediTerm emulator 线程生命周期
- Composable dispose 时自动停止 session
- 文本选择功能尚未实现（规划中）
- `onResize` 回调用于同步远端 PTY 尺寸，不传的话屏幕旋转后 vim 等 TUI 程序不会正确重绘