package com.lemonhall.jediterm.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
  var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Connecting) }
  var connector by remember { mutableStateOf<JSchTtyConnector?>(null) }

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

