package com.example.capstone_project

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    private val REQUEST_CODE_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val carInfoTextView = findViewById<TextView>(R.id.datacar)
        val adminIconImageView = findViewById<ImageView>(R.id.adminIcon)
        val userButton = findViewById<Button>(R.id.user)
        val logButton = findViewById<Button>(R.id.log)
        val pairingButton = findViewById<Button>(R.id.pairing)
        val accessButton = findViewById<Button>(R.id.access)

        // Intent로부터 선택한 차량 정보와 device_id, 관리자인지 여부, 페어링 상태 가져옴
        val selectedCar = intent.getStringExtra("selectedCar")
        val deviceId = intent.getStringExtra("deviceId") ?: ""
        val isAdmin = intent.getBooleanExtra("isAdmin", false)
        val isPaired = intent.getBooleanExtra("isPaired", false)  // 페어링 상태 확인
        val userId = intent.getStringExtra("user_id")
        val username = intent.getStringExtra("user_name")

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // 권한 확인 및 요청
        checkPermissions()

        // 선택한 차량 정보가 있으면 TextView에 표시
        selectedCar?.let {
            carInfoTextView.text = it
        }

        // 관리자인 경우 crown.png 이미지 표시
        adminIconImageView.visibility = if (isAdmin) ImageView.VISIBLE else ImageView.GONE

        // 페어링 상태에 따라 버튼 활성/비활성화
        if (isPaired) {
            pairingButton.visibility = View.GONE
        } else {
            accessButton.visibility = View.GONE

        }

        // user 버튼 클릭 시 UserListActivity로 이동
        userButton.setOnClickListener {
            val intent = Intent(this, UserListActivity::class.java)
            intent.putExtra("deviceId", deviceId)
            intent.putExtra("isAdmin", isAdmin)
            startActivity(intent)
        }

        // log 버튼 클릭 시 LogActivity로 이동
        logButton.setOnClickListener {
            val intent = Intent(this, LogActivity::class.java)
            intent.putExtra("deviceId", deviceId)
            startActivity(intent)
        }

        // pairing 버튼 클릭 시 "페어링" 토스트 메시지 표시
        pairingButton.setOnClickListener {
            if (bluetoothAdapter.isEnabled) {
                searchAndPairDevice(deviceId)
            } else {
                Toast.makeText(this, "Bluetooth를 활성화해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        accessButton.setOnClickListener {
            val intent = Intent(this, CarAccessActivity::class.java)
            intent.putExtra("deviceId", deviceId)
            intent.putExtra("user_Id", userId)
            intent.putExtra("user_name", username)
            startActivity(intent)
        }
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_OK) // 결과 값 반환
        super.onBackPressed()        // 기본 동작 수행
    }

    /**
     * 권한 확인 및 요청
     */
    private fun checkPermissions() {
        // Android 12(API 31) 이상에서만 추가 권한 필요
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missingPermissions = REQUIRED_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toTypedArray(),
                    REQUEST_CODE_PERMISSIONS
                )
            }
        }
    }

    /**
     * 권한 요청 결과 처리
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }

            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Bluetooth 관련 권한이 없으면 앱 기능이 제한될 수 있습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Bluetooth 장치 검색 및 페어링 요청
     */
    @SuppressLint("MissingPermission")
    private fun searchAndPairDevice(targetDeviceName: String) {
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        val discoveryReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && device.name == targetDeviceName) {
                            bluetoothAdapter.cancelDiscovery()
                            unregisterReceiver(this)

                            Toast.makeText(context, "기기 발견: ${device.name}. 페어링 요청 중...", Toast.LENGTH_SHORT).show()
                            pairDevice(device)
                        }
                    }
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        val pin = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 0)
                        Toast.makeText(context, "페어링 요청 PIN: $pin", Toast.LENGTH_SHORT).show()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Toast.makeText(context, "장치 검색 완료. 대상 장치를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(discoveryReceiver, filter)

        bluetoothAdapter.startDiscovery()
        Toast.makeText(this, "블루투스 장치 검색 중...", Toast.LENGTH_SHORT).show()

        Handler(mainLooper).postDelayed({
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            try {
                unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }, 10000) // 10초 후 검색 중단
    }

    /**
     * Bluetooth 장치와 페어링
     */
    private fun pairDevice(device: BluetoothDevice) {
        try {
            device.createBond() // 페어링 요청
            Toast.makeText(this, "${device.name} 페어링 요청 성공.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "페어링 요청 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
