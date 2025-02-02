package com.example.capstone_project

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class TestActivity : AppCompatActivity() {

    private lateinit var deviceIdEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var bluetoothCheck: Button
    private var userId: String? = null  // CarListActivity에서 전달받은 userId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        deviceIdEditText = findViewById(R.id.deviceId)
        sendButton = findViewById(R.id.send)
        bluetoothCheck = findViewById(R.id.bluetoothCheck)

        // Intent로부터 user_id 가져오기
        userId = intent.getStringExtra("user_id")

        // "전송" 버튼 클릭 시 서버로 동기화 요청
        sendButton.setOnClickListener {
            val deviceId = deviceIdEditText.text.toString().trim()

            if (deviceId.isEmpty()) {
                Toast.makeText(this, "Device ID를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 서버에 동기화 데이터 요청
            fetchSyncData(deviceId)
        }

        // 블루투스 확인 버튼 클릭 시
        bluetoothCheck.setOnClickListener {
            if (hasBluetoothPermissions()) {
                showPairedDevices()
            } else {
                requestBluetoothPermissions()
            }
        }
    }

    private fun fetchSyncData(deviceId: String) {
        val request = SyncRequest(deviceId)
        RetrofitClient.instance.getSyncData(request).enqueue(object : Callback<SyncResponseWrapper> {
            override fun onResponse(call: Call<SyncResponseWrapper>, response: Response<SyncResponseWrapper>) {
                if (response.isSuccessful && response.body() != null) {
                    val syncDataWrapper = response.body()
                    val syncData = syncDataWrapper?.data
                    if (syncData.isNullOrEmpty()) {
                        Toast.makeText(this@TestActivity, "동기화할 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        syncData.forEach { log ->
                            Toast.makeText(
                                this@TestActivity,
                                "변경 유형: ${log.changeType}, 사용자 ID: ${log.userId}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        //markSyncComplete(deviceId)
                    }
                } else {
                    Toast.makeText(this@TestActivity, "서버로부터 데이터를 가져오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<SyncResponseWrapper>, t: Throwable) {
                Toast.makeText(this@TestActivity, "서버와 통신 실패: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
/*
    private fun markSyncComplete(deviceId: String) {
        val request = MarkSyncCompleteRequest(deviceId)
        RetrofitClient.instance.markAsSynced(request).enqueue(object : Callback<MarkSyncCompleteResponse> {
            override fun onResponse(call: Call<MarkSyncCompleteResponse>, response: Response<MarkSyncCompleteResponse>) {
                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@TestActivity, "동기화 완료 처리되었습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@TestActivity, "동기화 완료 처리 실패.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<MarkSyncCompleteResponse>, t: Throwable) {
                Toast.makeText(this@TestActivity, "서버와 통신 실패2: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
*/

    // 블루투스 권한 확인
    @SuppressLint("ObsoleteSdkInt")
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 블루투스 권한 요청
    @SuppressLint("ObsoleteSdkInt")
    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH)
        }
        ActivityCompat.requestPermissions(this, permissions, 1)
    }

    // 페어링된 디바이스 목록 출력
    private fun showPairedDevices() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "블루투스를 지원하지 않는 기기입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "블루투스를 활성화해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 권한 체크
        if (!hasBluetoothPermissions()) {
            Toast.makeText(this, "블루투스 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // 페어링된 디바이스 목록 가져오기
        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        if (pairedDevices.isNotEmpty()) {
            // 블루투스 이름만 리스트로 수집
            val deviceNames = pairedDevices.map { device ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                device.name ?: "Unknown Device"
            }

            AlertDialog.Builder(this)
                .setTitle("페어링된 블루투스 디바이스")
                .setItems(deviceNames.toTypedArray(), null)
                .setPositiveButton("닫기", null)
                .show()
        } else {
            Toast.makeText(this, "페어링된 디바이스가 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}