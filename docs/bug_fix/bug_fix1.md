## Codex 指令：修复软键盘输入不发送到远端

### 问题

软键盘能弹出来了，但输入字符后终端没有回显，回车也不执行。根本原因有两个：

### Bug 1：JSchTtyConnector 中 inputStream 被创建了两次

`connect()` 方法中：

```kotlin
inputStream = sshChannel.inputStream      // 第一个流
outputStream = sshChannel.outputStream
reader = InputStreamReader(sshChannel.inputStream, StandardCharsets.UTF_8)  // 第二个流！
```

JSch 的 `ChannelShell.getInputStream()` 每次调用返回不同的 `PipedInputStream`。`reader`（用于 `read()`）和 `inputStream`（用于 `ready()`）读的是两个不同的流，数据会丢失或分裂。

修复 `sample/src/main/java/com/lemonhall/jediterm/sample/JSchTtyConnector.kt`：

```kotlin
fun connect(columns: Int = 80, rows: Int = 24) {
    val jsch = JSch()
    // ... 省略 identity 和 session 创建，不变 ...

    val sshChannel = (sshSession.openChannel("shell") as ChannelShell).apply {
        setPtyType("xterm-256color", columns, rows, 0, 0)
        connect(10_000)
    }

    session = sshSession
    channel = sshChannel
    val input = sshChannel.inputStream    // 只调用一次！
    inputStream = input
    outputStream = sshChannel.outputStream
    reader = InputStreamReader(input, StandardCharsets.UTF_8)  // 用同一个流
}
```

### Bug 2：BasicTextField 的 onValueChange 可能不触发

某些 Android IME（华为 WeType 等）在 `BasicTextField` 的 value 被立即重置为空字符串时，会停止触发 `onValueChange`。这是因为 IME 认为 app 拒绝了输入。

解决方案：不用 `BasicTextField`，改用 Android 原生的 `AndroidView` 包装一个隐藏的 `EditText`。`EditText` 对 IME 的兼容性远好于 Compose 的 `BasicTextField`。

修改 `lib/src/main/java/com/lemonhall/jediterm/android/ComposeTerminalView.kt`：

把 `BasicTextField` 替换为 `AndroidView` + `EditText`：

```kotlin
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.viewinterop.AndroidView

// 替换原来的 BasicTextField
AndroidView(
    factory = { context ->
        EditText(context).apply {
            // 完全透明，不可见但可交互
            alpha = 0f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.TRANSPARENT)
            isCursorVisible = false
            textSize = 1f

            // 输入类型：纯文本，不自动纠错
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE

            // 监听文本变化
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: return
                    if (text.isNotEmpty()) {
                        session.sendString(text, userInput = true)
                        // 清空，但用 post 延迟避免 IME 状态混乱
                        post { s.clear() }
                    }
                }
            })

            // 处理回车键（IME 的 action 按钮）
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEND) {
                    session.sendString("\r", userInput = true)
                    true
                } else {
                    false
                }
            }

            // 处理物理按键（方向键、Ctrl 组合等）
            setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val keyEvent = KeyEvent(event)
                val bytes = mapKeyEventToTerminalBytes(keyEvent, session)
                if (bytes != null) {
                    session.sendBytes(bytes)
                    true
                } else {
                    false
                }
            }
        }
    },
    modifier = Modifier
        .matchParentSize(),
    update = { editText ->
        // 每次 recomposition 时确保 EditText 有焦点
        if (!editText.hasFocus()) {
            editText.requestFocus()
            val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    },
)
```

同时修改点击 Canvas 时的焦点恢复逻辑。需要保存 `EditText` 的引用：

```kotlin
var editTextRef by remember { mutableStateOf<EditText?>(null) }

// 在 AndroidView 的 factory 里：
// ... 创建 EditText 后 ...
editTextRef = this  // 保存引用

// Canvas 的 pointerInput 里：
.pointerInput(Unit) {
    detectTapGestures {
        editTextRef?.let { et ->
            et.requestFocus()
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
```

移除不再需要的：
- `focusRequester` 相关代码（`FocusRequester`、`focusRequester.requestFocus()`）
- `keyboardController` 相关代码（`LocalSoftwareKeyboardController`）
- `inputValue` 状态变量
- `BasicTextField` 组件
- `ImeInputDispatch.kt` 文件（不再需要）
- `import androidx.compose.foundation.text.BasicTextField`
- `import androidx.compose.ui.focus.FocusRequester`
- `import androidx.compose.ui.focus.focusRequester`
- `import androidx.compose.ui.platform.LocalSoftwareKeyboardController`
- `import androidx.compose.ui.draw.alpha`

新增 import：
```kotlin
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.viewinterop.AndroidView
```

注意：`mapKeyEventToTerminalBytes` 函数的参数类型需要调整。当前它接收 `androidx.compose.ui.input.key.KeyEvent`，但 `EditText.setOnKeyListener` 传入的是 `android.view.KeyEvent`。需要做一个转换，或者写一个重载版本直接接收 `android.view.KeyEvent`：

```kotlin
private fun mapNativeKeyEventToTerminalBytes(
    nativeEvent: android.view.KeyEvent,
    session: TerminalSessionManager
): ByteArray? {
    if (nativeEvent.action != android.view.KeyEvent.ACTION_DOWN) return null
    val modifiers = buildJediModifiers(nativeEvent)

    if ((modifiers and InputEvent.CTRL_MASK) != 0) {
        val keyCode = nativeEvent.keyCode
        if (keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
            val ctrl = keyCode - android.view.KeyEvent.KEYCODE_A + 1
            return byteArrayOf(ctrl.toByte())
        }
    }

    val vk = when (nativeEvent.keyCode) {
        // ... 跟现有的 mapKeyEventToTerminalBytes 里的 when 完全一样 ...
    }

    if (vk == JediKeyEvent.VK_ESCAPE) return byteArrayOf(Ascii.ESC)
    if (vk == JediKeyEvent.VK_TAB) return byteArrayOf(Ascii.HT)
    return vk?.let { session.terminal.getCodeForKey(it, modifiers) }
}
```

或者更简单：保留原来的 `mapKeyEventToTerminalBytes`，在 `setOnKeyListener` 里把 `android.view.KeyEvent` 包装成 Compose 的 `KeyEvent`：

```kotlin
setOnKeyListener { _, _, event ->
    if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
    val composeKeyEvent = KeyEvent(event)
    val bytes = mapKeyEventToTerminalBytes(composeKeyEvent, session)
    if (bytes != null) {
        session.sendBytes(bytes)
        true
    } else {
        false
    }
}
```

`KeyEvent(nativeKeyEvent: android.view.KeyEvent)` 是 Compose 提供的构造函数，可以直接用。

### 删除 ImeInputDispatch.kt

删除 `lib/src/main/java/com/lemonhall/jediterm/android/ImeInputDispatch.kt` 和对应的测试文件 `lib/src/test/java/com/lemonhall/jediterm/android/ImeInputDispatchTest.kt`（如果存在的话）。

### 验证

1. `.\gradlew.bat :lib:assembleDebug :sample:assembleDebug` 编译通过
2. `.\gradlew.bat :lib:testDebugUnitTest` 测试通过
3. 安装到真机后：
   - SSH 连接成功，能看到 shell prompt
   - 软键盘自动弹出
   - 输入 `ls` 能看到字符回显
   - 按回车能执行命令，看到输出
   - 退格键能删除字符
   - 点击屏幕任意位置，软键盘重新弹出

### 注意

- 不要改 Canvas 绘制逻辑
- 不要改 `TerminalSessionManager`、`TerminalRenderSnapshot`、`ComposeTerminalDisplay`
- `JSchTtyConnector.kt` 只改 `inputStream` 那一行

---

这次用 `EditText` 替代 `BasicTextField`，彻底绕开 Compose IME 兼容性问题。Android 原生 `EditText` 跟所有 IME 都兼容，不会有 composing 状态的坑。