package com.shady.autonope

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.autopayhunter.engine.AutoPayEngine
import app.autopayhunter.engine.Report
import app.autopayhunter.engine.ScanResult
import app.autopayhunter.engine.Sms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Idle : State
        data class Working(val step: String) : State
        data class Done(val inbox: List<Sms>, val result: ScanResult, val report: String, val seconds: Double) : State
        data class Failed(val error: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    fun scan() {
        if (state is State.Working) return
        state = State.Working("Reading your inbox…")
        viewModelScope.launch {
            state = try {
                val inbox = withContext(Dispatchers.IO) { SmsReader.readInbox(getApplication()) }
                state = State.Working("Checking ${"%,d".format(inbox.size)} messages…")
                val start = System.nanoTime()
                val (result, report) = withContext(Dispatchers.Default) {
                    val r = AutoPayEngine(LocalDate.now()).scan(inbox)
                    r to Report.render(r)
                }
                State.Done(inbox, result, report, (System.nanoTime() - start) / 1e9)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // Shown on screen with a copy button, so the first lines can be pasted back for a fix.
                State.Failed(e.stackTraceToString().lines().take(12).joinToString("\n"))
            }
        }
    }
}
