package com.example.capstone_project
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response



class CarListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var carListAdapter: CarListAdapter
    private var selectedCar: CarListResponse? = null
    private var userId: String? = null
    private var pairedDevices: Set<String> = emptySet()  // 페어링된 디바이스 이름 저장

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_list)

        val usernameTextView = findViewById<TextView>(R.id.username)
        val username = intent.getStringExtra("username")
        userId = intent.getStringExtra("userId")
        usernameTextView.text = username

        recyclerView = findViewById(R.id.recyclerViewCarList)
        val selectCarButton = findViewById<Button>(R.id.selectCar)
        val logoutButton = findViewById<Button>(R.id.logout)
        val registerCarButton = findViewById<Button>(R.id.rstCar)


        // 페어링된 디바이스 목록 가져오기
        pairedDevices = getPairedDeviceNames()




        selectCarButton.setOnClickListener {
            selectedCar?.let { car ->
                val intent = Intent(this, MainActivity::class.java)
                val carInfo = String.format("%s(%s)", car.carNumber, car.carModel)
                val isAdmin = userId == car.root
                val isPaired = pairedDevices.contains(car.deviceId)  // 페어링 여부 확인
                intent.putExtra("selectedCar", carInfo)
                intent.putExtra("deviceId", car.deviceId)
                intent.putExtra("isAdmin", isAdmin)
                intent.putExtra("isPaired", isPaired)  // 페어링 상태 전달
                intent.putExtra("user_id", userId)
                intent.putExtra("user_name", username)
                startActivity(intent)
            }
        }

        logoutButton.setOnClickListener {
            // SharedPreferences에 저장된 로그인 정보를 삭제
            val sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.clear()  // 모든 저장 데이터 삭제
            editor.apply()

            // LoginActivity로 이동
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        registerCarButton.setOnClickListener {
            val intent = Intent(this, CarregistActivity::class.java)
            intent.putExtra("user_id", userId)
            startActivity(intent)
        }


        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@CarListActivity)
                    .setTitle("앱 종료")
                    .setMessage("앱을 종료하시겠습니까?")
                    .setPositiveButton("예") { _, _ -> finishAffinity() }
                    .setNegativeButton("아니오", null)
                    .show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        pairedDevices = getPairedDeviceNames()
        fetchCarListWithPairingCheck(userId)
    }

    private fun fetchCarListWithPairingCheck(userId: String?) {
        if (userId != null) {
            val request = CarListRequest(userId)
            RetrofitClient.instance.getCars(request)
                .enqueue(object : Callback<List<CarListResponse>> {
                    override fun onResponse(
                        call: Call<List<CarListResponse>>,
                        response: Response<List<CarListResponse>>
                    ) {
                        if (response.isSuccessful && response.body() != null) {
                            val carList = response.body()!!
                            if (carList.isNotEmpty()) {
                                val updatedCarList = carList.map { car ->
                                    val isPaired = pairedDevices.contains(car.deviceId)
                                    CarListResponse(
                                        deviceId = car.deviceId,
                                        carModel = car.carModel,
                                        carNumber = car.carNumber,
                                        root = car.root,
                                        pairedText = if (isPaired) "paired" else "unpaired"
                                    )
                                }

                                carListAdapter = CarListAdapter(updatedCarList) { car ->
                                    selectedCar = car
                                }
                                recyclerView.adapter = carListAdapter
                                recyclerView.layoutManager = LinearLayoutManager(this@CarListActivity)
                            } else {
                                Toast.makeText(
                                    this@CarListActivity,
                                    "차량 정보가 없습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                this@CarListActivity,
                                "차량 정보를 불러오지 못했습니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onFailure(call: Call<List<CarListResponse>>, t: Throwable) {
                        Toast.makeText(
                            this@CarListActivity,
                            "서버와 통신 실패: ${t.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
        } else {
            Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPairedDeviceNames(): Set<String> {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "블루투스 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                return emptySet()
            }
            val pairedDevices = bluetoothAdapter.bondedDevices.mapNotNull { it.name }.toSet()

            // Log paired devices for debugging
            Log.d("PairedDevices", "페어링된 디바이스 이름: $pairedDevices")
            return pairedDevices
        }
        return emptySet()
    }
}
