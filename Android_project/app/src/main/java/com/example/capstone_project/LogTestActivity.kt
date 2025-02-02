package com.example.capstone_project

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class LogTestActivity : AppCompatActivity() {

    private val TAG = "LogTestActivity"
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private lateinit var changeLogSender: ChangeLogSender

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intent에서 deviceId 가져오기
        val deviceId = intent.getStringExtra("deviceId")
        if (deviceId.isNullOrEmpty()) {
            Toast.makeText(this, "Device ID not provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Bluetooth 연결 후 fetchAndSendLogs 실행
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pairedDevice = findPairedDevice(bluetoothAdapter, deviceId)
        if (pairedDevice != null) {
            connectToDevice(pairedDevice)
            Thread.sleep(1000) // 연결 후 1초 대기
            fetchAndSendLogs(deviceId)
        } else {
            Toast.makeText(this, "No paired device found with ID: $deviceId", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findPairedDevice(bluetoothAdapter: BluetoothAdapter, deviceId: String): BluetoothDevice? {
        val pairedDevices = bluetoothAdapter.bondedDevices
        return pairedDevices?.find { it.name.startsWith(deviceId) }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream
            changeLogSender = ChangeLogSender(outputStream)
            Log.d(TAG, "Connected to device: ${device.name}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            Toast.makeText(this, "Failed to connect to device", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun fetchAndSendLogs(deviceId: String) {
        val syncRequest = SyncRequest(deviceId)
        RetrofitClient.instance.getSyncData(syncRequest).enqueue(object : Callback<SyncResponseWrapper> {
            override fun onResponse(call: Call<SyncResponseWrapper>, response: Response<SyncResponseWrapper>) {
                if (response.isSuccessful) {
                    val syncLogs = response.body()?.data ?: emptyList()
                    if (syncLogs.isNotEmpty()) {
                        syncLogs.forEach { log ->
                            val logJson = Gson().toJson(log)
                            changeLogSender.sendChangeLog(logJson)
                        }
                        markAsSynced(deviceId) // 모든 로그 전송 후 동기화 상태 업데이트
                    } else {
                        Log.d(TAG, "No sync logs to send.")
                    }
                } else {
                    Log.e(TAG, "Failed to fetch logs: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<SyncResponseWrapper>, t: Throwable) {
                Log.e(TAG, "Error fetching logs: ${t.message}")
            }
        })
    }

    private fun markAsSynced(deviceId: String) {
        val request = MarkSyncCompleteRequest(deviceId)
        RetrofitClient.instance.markAsSynced(request).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Marked logs as synced for deviceId: $deviceId")
                } else {
                    Log.e(TAG, "Failed to mark logs as synced: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e(TAG, "Error marking logs as synced: ${t.message}")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        }
    }
}

class a(
    private val outputStream: OutputStream?,
    private val maxRetry: Int = 3,          // 최대 재시도 횟수
    private val retryDelay: Long = 3000L    // 재시도 대기 시간 (밀리초)
) {
    private val TAG = "ChangeLogSender"

    /**
     * Change log를 Raspberry Pi로 전송합니다.
     *
     * @param changeLog 전송할 로그 데이터.
     * @param retryCount 현재 재시도 횟수 (기본값: 0).
     */
    fun sendChangeLog(changeLog: String, retryCount: Int = 0) {
        try {
            val taggedData = "<change_log_start>$changeLog<change_log_end>"
            outputStream?.write("$taggedData\n".toByteArray())
            Log.d(TAG, "Sent change log to Raspberry Pi: $taggedData")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send change log: ${e.message}")
            if (retryCount < maxRetry) {
                Log.d(TAG, "Retrying to send change log (Attempt ${retryCount + 1}/$maxRetry)...")
                Thread.sleep(retryDelay)
                sendChangeLog(changeLog, retryCount + 1)
            } else {
                Log.e(TAG, "Max retries reached. Failed to send change log: $changeLog")
            }
        }
    }
}
