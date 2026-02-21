## Codex 指令：Step 3 — Sample App 接入真实 SSH 连接

### 背景

Sample app 当前使用 `mockColorTestConnector()` 做渲染验证。现在要让它连接真实的 SSH 服务器（`192.168.50.149:22`，用户名 `lemonhall`，私钥认证），这样可以在 sample 里直接验证 vim/htop/tmux 等 TUI 程序的渲染效果，验证通过后主项目直接抄过去。

### 1. 添加 JSch 依赖

`gradle/libs.versions.toml` 添加：

```toml
[versions]
# ... 现有内容不动，新增：
jsch = "0.2.17"

[libraries]
# ... 现有内容不动，新增：
jsch = { group = "com.github.mwiede", name = "jsch", version.ref = "jsch" }
```

`sample/build.gradle.kts` 的 dependencies 中添加：

```kotlin
implementation(libs.jsch)
```

### 2. 创建 .env 文件

在项目根目录创建 `.env` 文件：

```
# SSH connection config (DO NOT COMMIT REAL KEYS)
SSH_HOST=192.168.50.149
SSH_PORT=22
SSH_USER=lemonhall
SSH_PASSWORD=
SSH_PRIVATE_KEY_PATH=id_rsa
SSH_PRIVATE_KEY_PASSPHRASE=
```

### 3. 更新 .gitignore

在项目根目录的 `.gitignore` 末尾追加：

```
# SSH secrets
.env
id_rsa
id_rsa.pub
*.pem
```

### 4. 创建 JSchTtyConnector

新建文件 `sample/src/main/java/com/lemonhall/jediterm/sample/JSchTtyConnector.kt`：

```kotlin
package com.lemonhall.jediterm.sample

import com.jediterm.terminal.TtyConnector
import com.jcraft.jsch.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 基于 JSch 的 TtyConnector 实现。
 * 将 JSch 的 ChannelShell 包装为 JediTerm 所需的 TtyConnector 接口。
 */
class JSchTtyConnector(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val privateKeyPath: String?,   // app 内部存储的绝对路径
    private val passphrase: String?,
) : TtyConnector {

    private lateinit var session: Session
    private lateinit var channel: ChannelShell
    private lateinit var inputStream: InputStream
    private lateinit var outputStream: OutputStream

    /**
     * 必须在后台线程调用。建立 SSH 连接并打开 shell channel。
     */
    fun connect(columns: Int = 80, rows: Int = 24) {
        val jsch = JSch()

        // 私钥认证
        if (!privateKeyPath.isNullOrBlank()) {
            if (!passphrase.isNullOrBlank()) {
                jsch.addIdentity(privateKeyPath, passphrase)
            } else {
                jsch.addIdentity(privateKeyPath)
            }
        }

        session = jsch.getSession(username, host, port).apply {
            // 密码认证（如果提供了密码）
            if (!password.isNullOrBlank()) {
                setPassword(password)
            }
            // sample 用途，跳过 host key 验证
            setConfig("StrictHostKeyChecking", "no")
            connect(10_000)  // 10 秒超时
        }

        channel = (session.openChannel("shell") as ChannelShell).apply {
            setPtyType("xterm-256color", columns, rows, 0, 0)
            // 不要用 setPty(false)，我们需要 PTY
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

    override fun isConnected(): Boolean {
        return ::channel.isInitialized && channel.isConnected && !channel.isClosed
    }

    override fun waitFor(): Int {
        // 阻塞直到 channel 关闭
        while (isConnected) {
            Thread.sleep(100)
        }
        return channel.exitStatus
    }

    override fun ready(): Boolean {
        return ::inputStream.isInitialized && inputStream.available() > 0
    }

    override fun getName(): String = "JSch SSH [$username@$host:$port]"

    override fun close() {
        try { if (::channel.isInitialized) channel.disconnect() } catch (_: Exception) {}
        try { if (::session.isInitialized) session.disconnect() } catch (_: Exception) {}
    }

    /**
     * 调整远端 PTY 尺寸。在 TerminalSessionManager.resize() 时调用。
     */
    fun resizePty(columns: Int, rows: Int) {
        if (::channel.isInitialized && channel.isConnected) {
            channel.setPtySize(columns, rows, 0, 0)
        }
    }
}
```

注意事项：
- `read()` 方法里要正确处理 UTF-8 多字节字符。上面的实现对于大多数情况够用，但如果一个 UTF-8 字符被拆到两次 `read()` 里会出问题。如果 JediTerm core 里有 `TtyBasedArrayDataStream` 来处理这个，那 `read()` 只需要返回原始字节对应的字符即可，JediTerm 内部会处理。查看 `TtyBasedArrayDataStream` 的源码确认它是怎么调用 `TtyConnector.read()` 的。
- `write(bytes: ByteArray)` 和 `write(string: String)` 是 TtyConnector 接口要求的两个方法，确认接口签名与 JediTerm core 中的定义一致（之前 Step 1 移除了 `Dimension` 相关方法，确认 `resize` 不在接口里）。

### 5. 创建 SshConfigReader

新建文件 `sample/src/main/java/com/lemonhall/jediterm/sample/SshConfigReader.kt`：

```kotlin
package com.lemonhall.jediterm.sample

import android.content.Context
import java.io.File

/**
 * 从 app 内部存储读取 SSH 配置。
 * 
 * 使用方式：
 * 1. 通过 adb push 把 .env 和 id_rsa 推送到 app 的 files 目录
 * 2. SshConfigReader 从 files 目录读取并解析
 */
data class SshConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String?,
    val privateKeyPath: String?,   // 绝对路径
    val passphrase: String?,
)

object SshConfigReader {

    /**
     * 从 context.filesDir 下读取 .env 文件，解析 SSH 配置。
     * 如果 .env 不存在，返回硬编码的默认值。
     */
    fun read(context: Context): SshConfig {
        val filesDir = context.filesDir
        val envFile = File(filesDir, ".env")

        val env = mutableMapOf<String, String>()
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value = trimmed.substring(eqIndex + 1).trim()
                        env[key] = value
                    }
                }
            }
        }

        val host = env["SSH_HOST"] ?: "192.168.50.149"
        val port = (env["SSH_PORT"] ?: "22").toIntOrNull() ?: 22
        val user = env["SSH_USER"] ?: "lemonhall"
        val password = env["SSH_PASSWORD"]?.takeIf { it.isNotBlank() }

        // 私钥路径：.env 里配的是相对于 filesDir 的路径，转成绝对路径
        val keyRelPath = env["SSH_PRIVATE_KEY_PATH"]?.takeIf { it.isNotBlank() }
        val keyAbsPath = if (keyRelPath != null) {
            val keyFile = File(filesDir, keyRelPath)
            if (keyFile.exists()) keyFile.absolutePath else null
        } else {
            // 默认查找 filesDir/id_rsa
            val defaultKey = File(filesDir, "id_rsa")
            if (defaultKey.exists()) defaultKey.absolutePath else null
        }

        val passphrase = env["SSH_PRIVATE_KEY_PASSPHRASE"]?.takeIf { it.isNotBlank() }

        return SshConfig(
            host = host,
            port = port,
            user = user,
            password = password,
            privateKeyPath = keyAbsPath,
            passphrase = passphrase,
        )
    }
}
```

### 6. 重写 MainActivity

修改 `sample/src/main/java/com/lemonhall/jediterm/sample/MainActivity.kt`：

```kotlin
package com.lemonhall.jediterm.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lemonhall.jediterm.android.ComposeTerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "JediTermSample"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = SshConfigReader.read(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SshTerminalScreen(config)
                }
            }
        }
    }
}

@Composable
private fun SshTerminalScreen(config: SshConfig) {
    // 连接状态：Connecting / Connected / Error
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Connecting) }
    var connector by remember { mutableStateOf<JSchTtyConnector?>(null) }

    // 在后台线程建立 SSH 连接
    LaunchedEffect(config) {
        state = ConnectionState.Connecting
        try {
            val conn = withContext(Dispatchers.IO) {
                JSchTtyConnector(
                    host = config.host,
                    port = config.port,
                    username = config.user,
                    password = config.password,
                    privateKeyPath = config.privateKeyPath,
                    passphrase = config.passphrase,
                ).also { it.connect() }
            }
            connector = conn
            state = ConnectionState.Connected
            Log.i(TAG, "SSH connected to ${config.user}@${config.host}:${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "SSH connection failed", e)
            state = ConnectionState.Error(e.message ?: "Unknown error")
        }
    }

    // 断开连接时清理
    DisposableEffect(Unit) {
        onDispose {
            connector?.close()
        }
    }

    when (val s = state) {
        is ConnectionState.Connecting -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Connecting to ${config.user}@${config.host}:${config.port}...")
                }
            }
        }
        is ConnectionState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connection failed", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is ConnectionState.Connected -> {
            connector?.let { conn ->
                ComposeTerminalView(
                    ttyConnector = conn,
                    modifier = Modifier.fillMaxSize(),
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

### 7. AndroidManifest 添加网络权限

确认 `sample/src/main/AndroidManifest.xml` 中有：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

如果没有 `AndroidManifest.xml`，创建一个：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

Activity 的声明应该已经在主 manifest 里了，确认一下。

### 8. 推送密钥到手机的 adb 命令

在 README 或代码注释中说明使用方式。用户需要执行：

```powershell
# 先把 .env 和 id_rsa 推送到 sample app 的内部存储
adb push .env /data/local/tmp/.env
adb push id_rsa /data/local/tmp/id_rsa
adb shell run-as com.lemonhall.jediterm.sample cp /data/local/tmp/.env /data/data/com.lemonhall.jediterm.sample/files/.env
adb shell run-as com.lemonhall.jediterm.sample cp /data/local/tmp/id_rsa /data/data/com.lemonhall.jediterm.sample/files/id_rsa
adb shell run-as com.lemonhall.jediterm.sample chmod 600 /data/data/com.lemonhall.jediterm.sample/files/id_rsa
```

把这段命令写到 `sample/README.md`（新建）中。

### 9. TerminalSessionManager 中 resize 联动 SSH PTY

当前 `TerminalSessionManager.resize()` 调用了 `terminalStarter.postResize()`，但这只通知了 JediTerm core。远端的 PTY 尺寸也需要同步更新，否则 vim 等程序不知道窗口变了。

在 `TerminalSessionManager` 中，`startSession()` 时保存 `ttyConnector` 的引用，然后在 `resize()` 中：

```kotlin
fun resize(columns: Int, rows: Int, origin: RequestOrigin = RequestOrigin.User) {
    terminalStarter?.postResize(TermSize(columns, rows), origin)
    // 如果是 JSchTtyConnector，同步更新远端 PTY 尺寸
    (currentTtyConnector as? JSchTtyConnector)?.resizePty(columns, rows)
}
```

但这样会让 `:lib` 依赖 sample 的类，不对。更好的方案是：

在 `:lib` 的 `TtyConnector` 接口或者 `TerminalSessionManager` 中定义一个回调：

```kotlin
// TerminalSessionManager.kt 中增加
var onResizeRequested: ((columns: Int, rows: Int) -> Unit)? = null

fun resize(columns: Int, rows: Int, origin: RequestOrigin = RequestOrigin.User) {
    terminalStarter?.postResize(TermSize(columns, rows), origin)
    onResizeRequested?.invoke(columns, rows)
}
```

然后在 `MainActivity` / `SshTerminalScreen` 中设置回调：

```kotlin
// 在创建 session 后
session.onResizeRequested = { cols, rows ->
    connector.resizePty(cols, rows)
}
```

不对，`session` 是在 `ComposeTerminalView` 内部创建的，外部拿不到。那就改 `ComposeTerminalView` 的参数，增加一个 `onResize` 回调：

```kotlin
@Composable
fun ComposeTerminalView(
    ttyConnector: TtyConnector,
    modifier: Modifier = Modifier,
    onResize: ((columns: Int, rows: Int) -> Unit)? = null,
)
```

在 `ComposeTerminalView` 内部 `onSizeChanged` 触发 resize 时，同时调用 `onResize`。

调用方：

```kotlin
ComposeTerminalView(
    ttyConnector = conn,
    modifier = Modifier.fillMaxSize(),
    onResize = { cols, rows -> conn.resizePty(cols, rows) },
)
```

这样 `:lib` 不需要知道 `JSchTtyConnector` 的存在，干净。

### 验证标准

1. `.\gradlew.bat :lib:assembleDebug` 编译通过
2. `.\gradlew.bat :sample:assembleDebug` 编译通过
3. `.\gradlew.bat :lib:testDebugUnitTest` 测试通过
4. 安装 sample app 到手机，通过 adb push 推送 `.env` 和 `id_rsa` 后：
   - app 启动显示 "Connecting..." 加载状态
   - 连接成功后显示远端 shell 的终端输出（bash prompt）
   - 能输入命令（如 `ls`、`whoami`）并看到输出
   - 能运行 `vim`，界面渲染正确，能用方向键移动光标，能按 `:q` 退出
   - 旋转屏幕后终端尺寸自动调整，vim 重绘正确

### 注意事项

- JSch 的 `com.github.mwiede:jsch:0.2.17` 需要从 Maven Central 拉取，确认项目的 `settings.gradle.kts` 或根 `build.gradle.kts` 中有 `mavenCentral()` 仓库配置
- `JSchTtyConnector.connect()` 必须在非 UI 线程调用（已在 `Dispatchers.IO` 中）
- 不要修改 `:lib` 模块中 `com.jediterm.*` 包下的任何文件
- `.env` 和 `id_rsa` 绝对不能被提交到 git