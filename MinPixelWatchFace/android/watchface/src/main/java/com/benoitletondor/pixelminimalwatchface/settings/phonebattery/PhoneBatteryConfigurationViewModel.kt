/*
 *   Copyright 2021 Benoit LETONDOR
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.benoitletondor.pixelminimalwatchface.settings.phonebattery

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.benoitletondor.pixelminimalwatchface.Injection
import com.benoitletondor.pixelminimalwatchface.helper.await
import com.benoitletondor.pixelminimalwatchface.model.Storage
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

private const val DATA_KEY_SYNC_ACTIVATED = "/batterySync/syncActivated"
private const val QUERY_SYNC_STATUS_PATH = "/batterySync/queryStatus"
private const val QUERY_ACTIVATED_SYNC_PATH = "/batterySync/activate"
private const val QUERY_DEACTIVATED_SYNC_PATH = "/batterySync/deactivate"

class PhoneBatteryConfigurationViewModel(application: Application)
    : AndroidViewModel(application), MessageClient.OnMessageReceivedListener
{
    private val storage: Storage = Injection.storage(application)

    private val mutableStateFlow = MutableStateFlow<State>(State.Loading)
    private var state
        get(): State = mutableStateFlow.value
        set(newValue) { mutableStateFlow.value = newValue }

    val stateFlow: Flow<State> = mutableStateFlow

    private val errorEventMutableFlow = MutableSharedFlow<ErrorEventType>()
    val errorEventFlow: Flow<ErrorEventType> = errorEventMutableFlow

    private val retryEventMutableFlow = MutableSharedFlow<Unit>()
    val retryEventFlow: Flow<Unit> = retryEventMutableFlow

    private var syncStatusQueryJob: Job? = null

    init {
        Wearable.getMessageClient(application as Context).addListener(this)

        viewModelScope.launch {
            delay(5000)
            if (state is State.Loading) {
                Log.e(TAG, "Timeout while searching for phone node")
                state = State.PhoneNotFound(syncActivated = storage.shouldShowPhoneBattery())
            }
        }
    }

    override fun onCleared() {
        Wearable.getMessageClient(getApplication() as Context).removeListener(this)
        syncStatusQueryJob?.cancel()

        super.onCleared()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == DATA_KEY_SYNC_ACTIVATED) {
            try {
                val currentState = state
                if (currentState is State.WaitingForPhoneStatusResponse) {
                    val syncActivated = messageEvent.data[0].toInt() == 1

                    state = State.PhoneStatusResponse(
                        node = currentState.node,
                        syncActivated = syncActivated,
                    )

                    storage.setShouldShowPhoneBattery(syncActivated)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error while parsing sync activated response from phone", t)
                state = State.Error(ErrorType.NO_RESPONSE_FROM_PHONE, syncActivated = storage.shouldShowPhoneBattery())
            }
        }
    }

    fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        capabilityInfo.nodes.findBestNode()?.let { onPhoneNodeFound(it) }
    }

    fun onPhoneAppDetectionResult(nodes: Set<Node>) {
        nodes.findBestNode()?.let { onPhoneNodeFound(it) }
    }

    fun onPhoneAppDetectionFailed(error: Throwable) {
        Log.e(TAG, "Error while searching for phone node", error)
        state = State.PhoneNotFound(syncActivated = storage.shouldShowPhoneBattery())
    }

    fun onSyncWithPhoneActivated() {
        val currentState = state
        if (currentState !is State.PhoneStatusResponse) {
            Log.e(TAG, "Got onSyncWithPhoneActivated while not being in right state ($currentState)")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            state = State.SendingStatusSyncToPhone(currentState.node, activating = true)

            try {
                currentState.node.startPhoneBatterySync(getApplication())

                state = State.WaitingForPhoneStatusResponse(currentState.node)
                delay(5000)

                val newState = state
                if (newState is State.WaitingForPhoneStatusResponse && newState.node.id == currentState.node.id) {
                    state = State.Error(ErrorType.NO_RESPONSE_FROM_PHONE, syncActivated = storage.shouldShowPhoneBattery())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }

                Log.e(TAG, "Error sending activate sync query", t)
                state = State.Error(ErrorType.UNABLE_TO_SEND_SYNC_QUERY_MESSAGE, syncActivated = storage.shouldShowPhoneBattery())
            }
        }
    }

    fun onSyncWithPhoneDeactivated() {
        val currentState = state
        if (currentState !is State.PhoneStatusResponse) {
            Log.e(TAG, "Got onSyncWithPhoneDeactivated while not being in right state ($currentState)")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            state = State.SendingStatusSyncToPhone(currentState.node, activating = false)

            try {
                currentState.node.stopPhoneBatterySync(getApplication())

                state = State.WaitingForPhoneStatusResponse(currentState.node)
                delay(5000)

                val newState = state
                if (newState is State.WaitingForPhoneStatusResponse && newState.node.id == currentState.node.id) {
                    state = State.Error(ErrorType.NO_RESPONSE_FROM_PHONE, syncActivated = storage.shouldShowPhoneBattery())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }

                Log.e(TAG, "Error sending activate sync query", t)
                state = State.Error(ErrorType.UNABLE_TO_SEND_SYNC_QUERY_MESSAGE, syncActivated = storage.shouldShowPhoneBattery())
            }
        }
    }

    private fun onPhoneNodeFound(node: Node) {
        when(val currentState = state) {
            is State.Loading, is State.PhoneNotFound, is State.Error -> {
                state = State.PhoneFound(node)
                checkPhoneStatus(node)
            }
            is State.PhoneFound -> {
                if(currentState.node != node) {
                    viewModelScope.launch {
                        errorEventMutableFlow.emit(ErrorEventType.PHONE_CHANGED)
                    }
                    state = State.PhoneFound(node)
                    checkPhoneStatus(node)
                }
            }
        }
    }

    private fun checkPhoneStatus(phoneNode: Node) {
        syncStatusQueryJob?.cancel()
        syncStatusQueryJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                Wearable.getMessageClient(getApplication() as Context).sendMessage(
                    phoneNode.id,
                    QUERY_SYNC_STATUS_PATH,
                    byteArrayOf(if (storage.shouldShowPhoneBattery()) { 1 } else { 0 }),
                ).await()

                state = State.WaitingForPhoneStatusResponse(phoneNode)
                delay(5000)

                val currentState = state
                if (currentState is State.WaitingForPhoneStatusResponse && phoneNode.id == currentState.node.id) {
                    state = State.Error(ErrorType.NO_RESPONSE_FROM_PHONE, syncActivated = storage.shouldShowPhoneBattery())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }

                Log.e(TAG, "Error sending sync status query", t)
                state = State.Error(ErrorType.UNABLE_TO_SEND_SYNC_QUERY_MESSAGE, syncActivated = storage.shouldShowPhoneBattery())
            }
        }
    }

    fun onForceDeactivateSyncClicked() {
        storage.setShouldShowPhoneBattery(false)

        when(val state = state) {
            is State.Error -> this.state = State.Error(state.errorType, syncActivated = false)
            is State.PhoneNotFound -> this.state = State.PhoneNotFound(syncActivated = false)
            State.Loading, is State.PhoneFound, is State.PhoneStatusResponse, is State.SendingStatusSyncToPhone, is State.WaitingForPhoneStatusResponse -> Unit
        }
    }

    fun onRetryConnectionClicked() {
        viewModelScope.launch {
            state = State.Loading
            retryEventMutableFlow.emit(Unit)
        }
    }

    sealed class State {
        object Loading : State()
        class PhoneNotFound(val syncActivated: Boolean) : State()
        class PhoneFound(val node: Node) : State()
        class WaitingForPhoneStatusResponse(val node: Node) : State()
        class PhoneStatusResponse(val node: Node, val syncActivated: Boolean) : State()
        class SendingStatusSyncToPhone(val node: Node, val activating: Boolean) : State()
        class Error(val errorType: ErrorType, val syncActivated: Boolean) : State()
    }

    enum class ErrorType {
        UNABLE_TO_SEND_SYNC_QUERY_MESSAGE,
        NO_RESPONSE_FROM_PHONE,
    }

    enum class ErrorEventType {
        PHONE_CHANGED
    }

    companion object {
        private const val TAG = "PhoneBatteryConfigurationVM"
    }
}

fun Set<Node>.findBestNode(): Node? {
    return firstOrNull { it.isNearby } ?: firstOrNull()
}

suspend fun Node.startPhoneBatterySync(context: Context) {
    Wearable.getMessageClient(context).sendMessage(
        id,
        QUERY_ACTIVATED_SYNC_PATH,
        null,
    ).await()
}

suspend fun Node.stopPhoneBatterySync(context: Context) {
    Wearable.getMessageClient(context).sendMessage(
        id,
        QUERY_DEACTIVATED_SYNC_PATH,
        null,
    ).await()
}
