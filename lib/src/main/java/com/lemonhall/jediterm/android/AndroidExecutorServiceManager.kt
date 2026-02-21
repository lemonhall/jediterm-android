package com.lemonhall.jediterm.android

import android.os.Handler
import android.os.Looper
import com.jediterm.terminal.TerminalExecutorServiceManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class AndroidExecutorServiceManager(
  private val uiHandler: Handler = Handler(Looper.getMainLooper()),
) : TerminalExecutorServiceManager {
  private val threadId = AtomicInteger(0)
  private val threadFactory = ThreadFactory { runnable ->
    Thread(runnable, "jediterm-android-${threadId.incrementAndGet()}").apply { isDaemon = true }
  }

  private val singleThreadScheduledExecutor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(threadFactory)

  private val unboundedExecutorService: ExecutorService =
    Executors.newCachedThreadPool(threadFactory)

  override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = singleThreadScheduledExecutor

  override fun getUnboundedExecutorService(): ExecutorService = unboundedExecutorService

  override fun shutdownWhenAllExecuted() {
    singleThreadScheduledExecutor.shutdown()
    unboundedExecutorService.shutdown()
  }

  fun runOnUiThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      uiHandler.post(block)
    }
  }
}

