🔍 完整诊断
你之前的改动其实方向是对的，但问题在于——
你目前代码里 EditText 的核心问题是 alpha = 0f。

在 Android 上，当一个 View 的 alpha = 0f 时：

很多国产 IME（华为、小米等）会跳过对"完全透明"输入框的 commitText 调用
即便 IME 调了 commitText，EditText 的 Editable 更新也可能不触发 TextWatcher——因为系统认为它"不需要更新显示"
更关键的是：Canvas 层叠在 EditText 上面（fillMaxSize），Canvas 的 pointerInput 拦截了触摸事件。虽然你在 detectTapGestures 里手动调用了 requestFocus + showSoftInput，但 EditText 实际上从来没真正收到过 IME 的焦点确认
链路验证
看你的 write() 方法里有日志：

<KOTLIN>
Log.d(logTag, "write bytes=${bytes.size}")
如果你在 logcat 里能看到 afterTextChanged 的日志但看不到 write 的日志——说明 session.sendString() 到 TerminalStarter.sendString() 到 myTtyConnector.write() 这条链路断了。

如果你连 afterTextChanged 的日志都看不到——那就确认了：华为 IME 根本没把文字送到 TextWatcher。

根本解法：抛弃 EditText，用自定义 View + onCreateInputConnection
这是 Termux 的方案，也是 Android 终端模拟器的标准做法。EditText + TextWatcher 在终端场景下天生就是不可靠的。

✅ 完整修改方案
修改 1：新建 TerminalInputView.kt
路径：lib/src/main/java/com/lemonhall/jediterm/android/TerminalInputView.kt

<KOTLIN>
package com.lemonhall.jediterm.android
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
/**
 * A custom View whose sole purpose is to receive soft-keyboard (IME) input
 * and forward it to the terminal session.
 *
 * We override [onCreateInputConnection] so we have full control over
 * [commitText], [deleteSurroundingText], and [sendKeyEvent] — exactly
 * the same approach Termux uses (TerminalView.java).
 *
 * This replaces the previous hidden-EditText + TextWatcher approach, which
 * was unreliable on Huawei / HarmonyOS because the TextWatcher was
 * sometimes not invoked even though commitText() was called by the IME.
 */
class TerminalInputView(context: Context) : View(context) {
    companion object {
        private const val TAG = "JeditermIme"
    }
    /** Called when IME commits text (letters, paste, etc.) */
    var onCommitText: ((String) -> Unit)? = null
    /** Called when IME sends a key event (hardware key, backspace, etc.) */
    var onKeyEvent: ((KeyEvent) -> Boolean)? = null
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Do NOT set alpha=0.  Some IMEs ignore invisible views.
        // We keep it barely visible (the Canvas draws on top anyway).
    }
    // ---- Tell the framework this view accepts text input ----
    override fun onCheckIsTextEditor(): Boolean = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD avoids auto-correct / prediction overlays.
        // NO_SUGGESTIONS kills the suggestion strip on most keyboards.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        // Don't let IME request initial text or cursor position
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            // ---- Main entry point: IME finished composing and sends final text ----
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    val str = text.toString()
                    Log.d(TAG, "commitText len=${str.length} text='${str.take(32)}'")
                    // Normalize line endings to CR for the terminal
                    val normalized = str.replace("\r\n", "\r").replace("\n", "\r")
                    onCommitText?.invoke(normalized)
                }
                return true
            }
            // ---- Some IMEs use setComposingText for "preview" (e.g. Gboard swipe) ----
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // For a terminal we send composing text immediately as well,
                // because there is no concept of "composing preview" in a terminal.
                if (!text.isNullOrEmpty()) {
                    val str = text.toString()
                    Log.d(TAG, "setComposingText len=${str.length} text='${str.take(32)}'")
                    val normalized = str.replace("\r\n", "\r").replace("\n", "\r")
                    onCommitText?.invoke(normalized)
                }
                // Return true but don't call super — we don't maintain an Editable buffer
                return true
            }
            override fun finishComposingText(): Boolean {
                Log.d(TAG, "finishComposingText")
                // We already sent in setComposingText, nothing extra needed
                return true
            }
            // ---- Backspace via IME ----
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength")
                for (i in 0 until beforeLength) {
                    onKeyEvent?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                }
                return true
            }
            // ---- IME-generated key events (Enter on some keyboards, hardware keys) ----
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val handled = onKeyEvent?.invoke(event) ?: false
                    if (handled) return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
    // ---- Hardware key events (e.g. Bluetooth keyboard) ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return onKeyEvent?.invoke(event) ?: super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Consume ACTION_UP to prevent default handling
        return true
    }
}
修改 2：修改 ComposeTerminalView.kt
路径：lib/src/main/java/com/lemonhall/jediterm/android/ComposeTerminalView.kt

把 EditText 相关的所有东西替换成 TerminalInputView。变动集中在 3 处：import、state 变量、以及 AndroidView 块。

完整替换后的文件：

<KOTLIN>
package com.lemonhall.jediterm.android
import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.viewinterop.AndroidView
import com.jediterm.core.util.Ascii
import com.jediterm.core.input.InputEvent
import com.jediterm.core.input.KeyEvent as JediKeyEvent
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.util.CharUtils
import kotlin.math.floor
@Composable
fun ComposeTerminalView(
    ttyConnector: TtyConnector,
    modifier: Modifier = Modifier,
    columns: Int = 80,
    rows: Int = 24,
    onResize: ((columns: Int, rows: Int) -> Unit)? = null,
) {
    val logTag = "JeditermIme"
    val executorServiceManager = remember { AndroidExecutorServiceManager() }
    val session = remember {
        TerminalSessionManager(columns = columns, rows = rows, executorServiceManager = executorServiceManager)
    }
    var bufferVersion by remember { mutableIntStateOf(0) }
    var inputViewRef by remember { mutableStateOf<TerminalInputView?>(null) }
    DisposableEffect(session) {
        val listener = TerminalModelListener { executorServiceManager.runOnUiThread { bufferVersion++ } }
        session.addModelListener(listener)
        onDispose { session.removeModelListener(listener) }
    }
    LaunchedEffect(session, ttyConnector) { session.startSession(ttyConnector) }
    DisposableEffect(session) { onDispose { session.stopSession() } }
    val textMeasurer = rememberTextMeasurer()
    val baseTextStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
    val charLayout = remember(textMeasurer, baseTextStyle) {
        textMeasurer.measure(text = "M", style = baseTextStyle)
    }
    val charWidthPx = charLayout.size.width.toFloat().coerceAtLeast(1f)
    val charHeightPx = charLayout.size.height.toFloat().coerceAtLeast(1f)
    var currentColumns by remember { mutableIntStateOf(columns) }
    var currentRows by remember { mutableIntStateOf(rows) }
    var scrollOrigin by remember { mutableIntStateOf(0) } // [-historyLinesCount, 0]
    var scrollRemainderPx by remember { mutableFloatStateOf(0f) }
    val isUsingAlternateBuffer = remember(bufferVersion) {
        session.terminalTextBuffer.isUsingAlternateBuffer
    }
    LaunchedEffect(isUsingAlternateBuffer) {
        if (isUsingAlternateBuffer) {
            scrollOrigin = 0
            scrollRemainderPx = 0f
        }
    }
    LaunchedEffect(bufferVersion, isUsingAlternateBuffer) {
        if (!isUsingAlternateBuffer) {
            val historyCount = session.terminalTextBuffer.historyLinesCount
            scrollOrigin = scrollOrigin.coerceIn(-historyCount, 0)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                val newCols = floor(size.width / charWidthPx).toInt().coerceAtLeast(5)
                val newRows = floor(size.height / charHeightPx).toInt().coerceAtLeast(2)
                if (newCols != currentColumns || newRows != currentRows) {
                    currentColumns = newCols
                    currentRows = newRows
                    session.resize(newCols, newRows)
                    onResize?.invoke(newCols, newRows)
                }
            },
    ) {
        // ── IME input layer: TerminalInputView ──
        AndroidView(
            factory = { context ->
                TerminalInputView(context).apply {
                    onCommitText = { text ->
                        Log.d(logTag, "onCommitText len=${text.length}")
                        session.sendString(text, userInput = true)
                    }
                    onKeyEvent = handler@{ event ->
                        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@handler false
                        val bytes = mapKeyEventToTerminalBytes(KeyEvent(event), session)
                        if (bytes != null) {
                            Log.d(logTag, "onKeyDown keyCode=${event.keyCode} sendBytes=${bytes.size}")
                            session.sendBytes(bytes)
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                if (inputViewRef !== view) {
                    inputViewRef = view
                }
                if (!view.hasFocus()) {
                    view.requestFocus()
                    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    Log.d(logTag, "requestFocus+showSoftInput")
                }
            },
        )
        // ── Rendering layer ──
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        inputViewRef?.let { view ->
                            view.requestFocus()
                            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
好的，接着来。从 Canvas 的 `pointerInput` 部分继续：

```kotlin
                            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                    as InputMethodManager
                            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                            Log.d(logTag, "tap → requestFocus + showSoftInput")
                        }
                    }
                }
                .pointerInput(isUsingAlternateBuffer) {
                    if (!isUsingAlternateBuffer) {
                        detectDragGestures { _, dragAmount ->
                            scrollRemainderPx += dragAmount.y
                            val linesToScroll = (scrollRemainderPx / charHeightPx).toInt()
                            if (linesToScroll != 0) {
                                scrollRemainderPx -= linesToScroll * charHeightPx
                                val historyCount = session.terminalTextBuffer.historyLinesCount
                                scrollOrigin = (scrollOrigin + linesToScroll).coerceIn(-historyCount, 0)
                            }
                        }
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION")
            bufferVersion // force recomposition on buffer changes

            val textBuffer = session.terminalTextBuffer
            val cursorY = session.terminal.cursorY
            val cursorX = session.terminal.cursorX

            for (row in 0 until currentRows) {
                val bufferRow = row + 1 + scrollOrigin
                val line = try {
                    textBuffer.getLine(bufferRow)
                } catch (_: Exception) { null } ?: continue

                for (col in 0 until currentColumns) {
                    val entry = try { line.getEntry(col) } catch (_: Exception) { null }

                    val char = entry?.character ?: ' '
                    val style = entry?.style

                    val bgColor = style?.let { resolveBackgroundColor(it) } ?: Color.Black
                    val fgColor = style?.let { resolveForegroundColor(it) } ?: Color.White

                    val x = col * charWidthPx
                    val y = row * charHeightPx

                    if (bgColor != Color.Black) {
                        drawRect(color = bgColor, topLeft = Offset(x, y), size = Size(charWidthPx, charHeightPx))
                    }

                    val isCursorHere = (scrollOrigin == 0 && bufferRow == cursorY && col + 1 == cursorX)
                    if (isCursorHere) {
                        drawRect(color = Color.Green, topLeft = Offset(x, y), size = Size(charWidthPx, charHeightPx))
                    }

                    if (char != ' ' && char != CharUtils.NUL_CHAR) {
                        val effectiveFg = if (isCursorHere) Color.Black else fgColor

                        val textStyle = baseTextStyle.copy(
                            color = effectiveFg,
                        )

                        val layoutResult = textMeasurer.measure(text = char.toString(), style = textStyle)
                        drawText(layoutResult, topLeft = Offset(x, y))
                    }
                }
            }
        }
    }
}

// ── Key mapping ──

private fun mapKeyEventToTerminalBytes(
    event: KeyEvent,
    session: TerminalSessionManager,
): ByteArray? {
    val native = event.nativeKeyEvent

    // Enter → CR only (not CRLF)
    if (native.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        native.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    ) {
        return byteArrayOf(Ascii.CR)
    }

    // Backspace → DEL (0x7F)
    if (native.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
        return byteArrayOf(Ascii.DEL)
    }

    // Tab
    if (native.keyCode == android.view.KeyEvent.KEYCODE_TAB) {
        return byteArrayOf(Ascii.HT)
    }

    // Escape
    if (native.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
        return byteArrayOf(Ascii.ESC)
    }

    // Arrow keys → ANSI escape sequences
    return when (native.keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP    -> byteArrayOf(Ascii.ESC, '['.code.toByte(), 'A'.code.toByte())
        android.view.KeyEvent.KEYCODE_DPAD_DOWN  -> byteArrayOf(Ascii.ESC, '['.code.toByte(), 'B'.code.toByte())
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> byteArrayOf(Ascii.ESC, '['.code.toByte(), 'C'.code.toByte())
        android.view.KeyEvent.KEYCODE_DPAD_LEFT  -> byteArrayOf(Ascii.ESC, '['.code.toByte(), 'D'.code.toByte())
        else -> null // let commitText handle printable characters
    }
}

// ── Color helpers ──

private fun resolveBackgroundColor(style: com.jediterm.terminal.TextStyle): Color {
    val bg = style.background
    return terminalColorToComposeColor(bg) ?: Color.Black
}

private fun resolveForegroundColor(style: com.jediterm.terminal.TextStyle): Color {
    val fg = style.foreground
    return terminalColorToComposeColor(fg) ?: Color.White
}

private fun terminalColorToComposeColor(color: com.jediterm.terminal.TerminalColor?): Color? {
    if (color == null) return null
    // If it's an indexed color, map it; if it's RGB, use directly
    return try {
        val awt = color.toAwtColor()
        Color(awt.red, awt.green, awt.blue, awt.alpha)
    } catch (_: Exception) {
        null
    }
}
```

---

### 修改 3：`JSchTtyConnector.kt` 简化

**路径**：`sample/src/main/java/com/lemonhall/jediterm/sample/JSchTtyConnector.kt`

把 `PipedInputStream`/`PipedOutputStream`/pump 线程全部去掉，直接用 JSch 原生流：

```kotlin
package com.lemonhall.jediterm.sample

import android.util.Log
import com.jediterm.terminal.TtyConnector
import com.jsch.JSch
import com.jsch.ChannelShell
import com.jsch.Session
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class JSchTtyConnector(
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val password: String,
    private var columns: Int = 80,
    private var rows: Int = 24,
) : TtyConnector {

    companion object {
        private const val TAG = "JSchTtyConnector"
    }

    private var sshSession: Session? = null
    private var channel: ChannelShell? = null
    private var reader: InputStreamReader? = null
    private var outputStream: OutputStream? = null

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

        // Get streams BEFORE connect()
        val input = sshChannel.inputStream
        val output = sshChannel.outputStream

        sshChannel.connect(10_000)

        channel = sshChannel
        outputStream = output
        reader = InputStreamReader(input, StandardCharsets.UTF_8)

        Log.d(TAG, "SSH connected to $host:$port")
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val r = reader ?: throw IllegalStateException("Not connected")
        return r.read(buf, offset, length)
    }

    override fun write(bytes: ByteArray) {
        val out = outputStream ?: throw IllegalStateException("Not connected")
        Log.d(TAG, "write bytes=${bytes.size}")
        out.write(bytes)
        out.flush()
    }

    override fun write(string: String) {
        Log.d(TAG, "write string len=${string.length}")
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean {
        return channel?.isConnected == true
    }

    override fun close() {
        Log.d(TAG, "close()")
        try { channel?.disconnect() } catch (_: Exception) {}
        try { sshSession?.disconnect() } catch (_: Exception) {}
        channel = null
        sshSession = null
        reader = null
        outputStream = null
    }

    override fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        channel?.setPtySize(columns, rows, 0, 0)
        Log.d(TAG, "resize ${columns}x${rows}")
    }

    override fun getName(): String = "JSch"

    override fun waitFor(): Int {
        while (isConnected) {
            Thread.sleep(200)
        }
        return 0
    }
}
```

---

## 总结

三个改动：

1. **新建 `TerminalInputView.kt`** — 自定义 View，通过 `onCreateInputConnection` 直接拦截 IME 的 `commitText`/`setComposingText`/`deleteSurroundingText`，绕过 EditText + TextWatcher 的不可靠路径。这是 Termux 验证过的方案。

2. **重写 `ComposeTerminalView.kt`** — 把 `EditText` 替换成 `TerminalInputView`，`onCommitText` 直接调 `session.sendString()`，`onKeyEvent` 走 `mapKeyEventToTerminalBytes`。

3. **简化 `JSchTtyConnector.kt`** — 去掉 Piped 流和 pump 线程，直接用 JSch 原生的 `inputStream`/`outputStream`，减少一层可能丢数据的中间环节。

你先把这三个文件改了，然后 `.\gradlew.bat :sample:installDebug` 编译一下。如果编译有问题贴给我，我帮你调。关键验证点是 logcat 里能看到 `commitText` 的日志——如果能看到，说明 IME 输入链路通了。