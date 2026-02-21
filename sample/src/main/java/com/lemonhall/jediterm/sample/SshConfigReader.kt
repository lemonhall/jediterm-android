package com.lemonhall.jediterm.sample

import android.content.Context
import java.io.File

data class SshConfig(
  val host: String,
  val port: Int,
  val user: String,
  val password: String?,
  val privateKeyPath: String?,
  val passphrase: String?,
)

object SshConfigReader {
  fun read(context: Context): SshConfig = readFromFilesDir(context.filesDir)

  internal fun readFromFilesDir(filesDir: File): SshConfig {
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

    val keyRelPath = env["SSH_PRIVATE_KEY_PATH"]?.takeIf { it.isNotBlank() }
    val keyAbsPath =
      if (keyRelPath != null) {
        val keyFile = File(filesDir, keyRelPath)
        if (keyFile.exists()) keyFile.absolutePath else null
      } else {
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

