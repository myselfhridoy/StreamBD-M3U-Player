package com.streambd.iptv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelJumpController @Inject constructor() {

    companion object {
        const val TIMEOUT_MS = 1_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _display = MutableStateFlow("")
    val display: StateFlow<String> = _display.asStateFlow()

    private val _commits = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val commits: SharedFlow<String> = _commits.asSharedFlow()

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var timer: Job? = null

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) clear()
    }

    fun onDigit(digit: Char) {
        if (!isEnabled || !digit.isDigit()) return
        _display.value = (_display.value + digit).take(4)
        timer?.cancel()
        timer = scope.launch {
            delay(TIMEOUT_MS)
            val value = _display.value
            _display.value = ""
            if (value.isNotEmpty()) _commits.tryEmit(value)
        }
    }

    fun clear() {
        timer?.cancel()
        _display.value = ""
    }
}