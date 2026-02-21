package com.lemonhall.jediterm.android

internal data class ImeDispatchResult(
  val toSend: String?,
  val nextValue: String,
)

internal fun dispatchImeText(newValue: String): ImeDispatchResult {
  return ImeDispatchResult(
    toSend = newValue.takeIf { it.isNotEmpty() },
    nextValue = "",
  )
}

