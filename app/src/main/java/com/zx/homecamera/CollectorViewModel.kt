package com.zx.homecamera

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorState
import com.zx.homecamera.core.app.ConnectionStatus
import com.zx.homecamera.core.app.RecordingStatus
import com.zx.homecamera.core.app.ServiceStatus
import com.zx.homecamera.service.CollectorForegroundService
import com.zx.homecamera.video.CollectorCameraRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CollectorViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(CollectorState())
    val state: StateFlow<CollectorState> = _state.asStateFlow()

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CollectorForegroundService.ACTION_COLLECTOR_STATUS) return
            val status = intent.getStringExtra(CollectorForegroundService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(CollectorForegroundService.EXTRA_MESSAGE)
            val clientCount = intent.getStringExtra(CollectorForegroundService.EXTRA_CLIENT_COUNT)?.toIntOrNull()
            viewModelScope.launch {
                when (status) {
                    CollectorForegroundService.STATUS_RUNNING -> {
                        _state.value = _state.value.copy(
                            serviceStatus = ServiceStatus.Running,
                            recordingStatus = RecordingStatus.Recording,
                            errorMessage = null,
                        )
                    }
                    CollectorForegroundService.STATUS_ERROR -> {
                        _state.value = _state.value.copy(
                            serviceStatus = ServiceStatus.Error,
                            recordingStatus = RecordingStatus.Error,
                            errorMessage = message ?: "采集端启动失败",
                        )
                    }
                    CollectorForegroundService.STATUS_STOPPED -> {
                        _state.value = _state.value.copy(
                            serviceStatus = ServiceStatus.Stopped,
                            recordingStatus = RecordingStatus.Idle,
                            connectedClientCount = 0,
                            connectionStatus = ConnectionStatus.NoClient,
                        )
                    }
                    CollectorForegroundService.STATUS_RECORDING_ERROR -> {
                        _state.value = _state.value.copy(
                            recordingStatus = RecordingStatus.Error,
                            errorMessage = message ?: "录像写入失败",
                        )
                    }
                }
                clientCount?.let { count ->
                    _state.value = _state.value.copy(
                        connectedClientCount = count,
                        connectionStatus = if (count > 0) ConnectionStatus.Connected else ConnectionStatus.NoClient,
                    )
                }
            }
        }
    }

    init {
        val filter = IntentFilter(CollectorForegroundService.ACTION_COLLECTOR_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(serviceStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(serviceStatusReceiver, filter)
        }
    }

    fun onServiceStarting() {
        _state.value = _state.value.copy(
            serviceStatus = ServiceStatus.Starting,
            recordingStatus = RecordingStatus.Starting,
            errorMessage = null,
        )
    }

    fun setDisplayRotationDegrees(degrees: Int) {
        CollectorCameraRuntime.setDisplayRotationDegrees(degrees)
    }

    fun startCollectorService() {
        getApplication<Application>().startCollectorService(CollectorForegroundService.ACTION_START)
    }

    fun stopCollectorService() {
        getApplication<Application>().startCollectorService(CollectorForegroundService.ACTION_STOP)
        _state.value = _state.value.copy(
            serviceStatus = ServiceStatus.Stopped,
            recordingStatus = RecordingStatus.Idle,
            connectedClientCount = 0,
            connectionStatus = ConnectionStatus.NoClient,
        )
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unregisterReceiver(serviceStatusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onCleared()
    }
}

private fun Context.startCollectorService(action: String) {
    val intent = Intent(this, CollectorForegroundService::class.java).setAction(action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
