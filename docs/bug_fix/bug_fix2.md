## 任务：修复 jediterm-android 的软键盘输入无回显问题

项目路径：`E:\development\jediterm-android`

### 根因

`ComposeTerminalView.kt` 中隐藏的 EditText 使用了 `inputType = InputType.TYPE_NULL`，导致 Android IME 的 composition/commit 文本不经过 `TextWatcher.afterTextChanged`，只有硬件按键事件走 `onKeyDown`。用户在软键盘上打字母时，字母根本没有被发送到远程 SSH 服务器。

### 需要修改的文件和具体改动

#### 1. `lib/src/main/java/com/lemonhall/jediterm/android/ComposeTerminalView.kt`

- 找到 EditText 创建处的 `inputType = InputType.TYPE_NULL`，改为：
  ```kotlin
  inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
  ```

- 找到 `mapKeyEventToTerminalBytes` 函数中 Enter 键的处理，确保只发送 CR（`\r`），不要发 CRLF：
  ```kotlin
  if (native.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
      native.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
  ) {
      return byteArrayOf(Ascii.CR)
  }
  ```

- 找到 `setOnEditorActionListener` 中发送 Enter 的地方，确保发送 `"\r"` 而不是 `"\r\n"` 或 `"\n"`。

- 找到 `afterTextChanged` 中的 `sendString` 调用，确保换行替换为 CR：
  ```kotlin
  session.sendString(text.replace("\n", "\r"), userInput = true)
  ```

#### 2. `sample/src/main/java/com/lemonhall/jediterm/sample/JSchTtyConnector.kt`

简化 `connect()` 方法，去掉 `PipedInputStream`/`PipedOutputStream`/`setInputStream` 以及 stdout/stderr 合并的 pump 线程。改为直接使用 JSch 原生的流：

```kotlin
fun connect() {
    val jsch = JSch()
    val session = jsch.getSession(username, host, port).apply {
        setPassword(password)
        setConfig("StrictHostKeyChecking", "no")
        connect(15_000)
    }
    sshSession = session

    val sshChannel = (session.openChannel("shell") as ChannelShell).apply {
        setPtyType("xterm-256color", columns, rows, 0, 0)
    }

    // 在 connect() 之前获取流
    val input = sshChannel.inputStream
    val output = sshChannel.outputStream

    sshChannel.connect(10_000)

    channel = sshChannel
    outputStream = output
    reader = InputStreamReader(input, StandardCharsets.UTF_8)
}
```

不要调用 `setInputStream`。不要创建 PipedInputStream/PipedOutputStream。不要创建 pump 线程。`write()` 方法中保留 `out.flush()` 调用。

### 不要改动的东西

- 不要修改 `lib/src/main/java/com/jediterm/terminal/` 下的任何文件
- 不要修改 Gradle 配置
- 不要删除已有的 Log 语句（调试用，保留即可）
- 不要改变 `ComposeTerminalView` 的 Composable 函数签名

### 验证方式

改完后执行 `.\gradlew.bat :sample:installDebug` 确保编译通过。

