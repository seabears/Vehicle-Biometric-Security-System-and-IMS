package com.example.capstone_project

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CarregistActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var editTextDeviceId: EditText
    private lateinit var editTextCarNumber: EditText
    private lateinit var spinnerCarType: Spinner
    private lateinit var carTypeAdapter: ArrayAdapter<String>
    private lateinit var carTypes: MutableList<String> // 서버에서 가져온 차량 리스트
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    private val REQUEST_CODE_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registcar)

        // 권한 요청
        checkPermissions()

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        editTextDeviceId = findViewById(R.id.editTextDeviceId)
        editTextCarNumber = findViewById(R.id.editTextCarNumber)
        spinnerCarType = findViewById(R.id.spinnerCarType)

        val selectCarButton = findViewById<Button>(R.id.selectCar)
        val scanDeviceButton = findViewById<Button>(R.id.scan_device)

        // user_id 가져오기
        val userId = intent.getStringExtra("user_id")

        carTypes = mutableListOf()
        carTypeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, carTypes)
        carTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCarType.adapter = carTypeAdapter

        fetchCarTypes()

        // "저장" 버튼 클릭
        selectCarButton.setOnClickListener {
            val deviceId = editTextDeviceId.text.toString().trim()
            val carNumber = editTextCarNumber.text.toString().trim()
            val carType = spinnerCarType.selectedItem?.toString()?.trim()

            if (deviceId.isEmpty() || carNumber.isEmpty() || carType.isNullOrEmpty()) {
                // 빈칸이 있는 경우
                Toast.makeText(this, "모든 필드를 채워주세요.", Toast.LENGTH_SHORT).show()
            } else if (userId.isNullOrEmpty()) {
                Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            } else {
                // 모든 필드가 채워진 경우 서버로 데이터 전송
                val request = SaveDeviceRequest(deviceId, carNumber, carType, userId)
                saveDeviceToServer(request)
            }
        }

        // "스캔" 버튼 클릭 시 블루투스 장치 검색
        scanDeviceButton.setOnClickListener {
            startBluetoothScan()
        }
    }


    private fun fetchCarTypes() {
        RetrofitClient.instance.getCarTypes().enqueue(object : Callback<List<String>> {
            override fun onResponse(call: Call<List<String>>, response: Response<List<String>>) {
                if (response.isSuccessful && response.body() != null) {
                    carTypes.clear()
                    carTypes.addAll(response.body()!!)
                    carTypeAdapter.notifyDataSetChanged()
                } else {
                    Toast.makeText(this@CarregistActivity, "차량 목록을 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<String>>, t: Throwable) {
                Toast.makeText(this@CarregistActivity, "서버와의 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun saveDeviceToServer(request: SaveDeviceRequest) {
        RetrofitClient.instance.saveDevice(request).enqueue(object : Callback<SaveDeviceResponse> {
            override fun onResponse(call: Call<SaveDeviceResponse>, response: Response<SaveDeviceResponse>) {
                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@CarregistActivity, "장치가 성공적으로 등록되었습니다.", Toast.LENGTH_SHORT).show()
                    finish() // Activity 종료
                } else {
                    Toast.makeText(this@CarregistActivity, "장치 등록에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<SaveDeviceResponse>, t: Throwable) {
                Toast.makeText(this@CarregistActivity, "서버와의 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }



    /**
     * 권한 요청
     */
    private fun checkPermissions() {
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
                    "Bluetooth 관련 권한이 필요합니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 블루투스 장치 검색 시작
     */
    private fun startBluetoothScan() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth를 활성화해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        discoveredDevices.clear()

        val discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            // 이름이 "RP"로 시작하는 기기만 추가
                            if (!discoveredDevices.contains(it) && (it.name?.startsWith("RP") == true)) {
                                discoveredDevices.add(it)
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        unregisterReceiver(this)
                        if (discoveredDevices.isEmpty()) {
                            Toast.makeText(context, "검색된 RP 기기가 없습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            showDeviceSelectionDialog()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(discoveryReceiver, filter)

        bluetoothAdapter.startDiscovery()
        Toast.makeText(this, "블루투스 장치 검색 중...", Toast.LENGTH_SHORT).show()

        Handler(mainLooper).postDelayed({
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        }, 10000) // 10초 후 검색 중단
    }

    /**
     * 사용자에게 RP로 시작하는 기기 선택 대화 상자 표시
     */
    private fun showDeviceSelectionDialog() {
        val deviceNames = discoveredDevices.map { it.name ?: "Unknown Device" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("RP로 시작하는 장치를 선택하세요")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = discoveredDevices[which]
                editTextDeviceId.setText(selectedDevice.name) // 기기 이름을 EditText에 설정
                pairDevice(selectedDevice)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 블루투스 장치와 페어링
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
