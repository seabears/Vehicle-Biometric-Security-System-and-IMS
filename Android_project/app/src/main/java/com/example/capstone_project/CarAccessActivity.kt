package com.example.capstone_project

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class AccessStatus {
    FINGERPRINT_SUCCESS,
    FINGERPRINT_FAILED,
    FINGERPRINT_ERROR,
    FACIALRECOGNITION_SUCCESS,
    FACIALRECOGNITION_FAILED,
    FACIALRECOGNITION_ERROR
}
// 전역 변수로 photo 이름을 저장
private var receivedPhotoFileName: String? = null

class CarAccessActivity : AppCompatActivity() {

    private val TAG = "CarAccessActivity"
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val dataBuffer = StringBuilder()
    private var modeState = 0 // 모드 상태 변수

    private lateinit var biometricPrompt: BiometricPrompt   //지문 인증
    private lateinit var changeLogSender: ChangeLogSender   //로그 전송

    private lateinit var textview_access_process: TextView
    private lateinit var textview_access_explanation: TextView
    private lateinit var textview_which_process: TextView
    private lateinit var userId: String

    private var imageWidth = 160
    private var imageHeight = 90

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_caraccess)
        userId = intent.getStringExtra("user_Id") ?: "default_user"

        textview_access_process = findViewById(R.id.access_process)
        textview_access_explanation = findViewById(R.id.access_explanation)
        textview_which_process = findViewById(R.id.which_process)

        val deviceId = intent.getStringExtra("deviceId") ?: ""
        val userId = intent.getStringExtra("user_Id") ?: ""
        val username = intent.getStringExtra("user_name") ?: ""
        Log.d(TAG, "deviceId : $deviceId, userId : $userId, username : $username")

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
            //Thread.sleep(1000) // 연결 후 1초 대기
            //fetchAndSendLogs(deviceId)
        } else {
            Toast.makeText(this, "No paired device found with ID: $deviceId", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "no paired device")
            finish()
        }

        Toast.makeText(this, "BT 연결중", Toast.LENGTH_SHORT).show()
        try {
            startListeningForData(deviceId, userId, username)

            //fetchAndSendLogs(deviceId)
            Handler(Looper.getMainLooper()).postDelayed({
                // 2.5초 후 실행할 작업
                fetchAndSendLogs(deviceId)
            }, 2500) // 2500 밀리초 = 2.5초

            //Thread.sleep(2500)  //동기화용
            /*
            lifecycleScope.launch {
                delay(2500) // 2.5초 지연
                println("2.5초 후 작업 실행!")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                // 2.5초 후 실행할 작업
                Toast.makeText(this, "지문인증 시행", Toast.LENGTH_SHORT).show()
            }, 2500) // 2500 밀리초 = 2.5초
            ///

             */

            /*BT 연결 후 지문인증 시행*/
            /*
            Toast.makeText(this, "지문인증 시행", Toast.LENGTH_SHORT).show()
            // BiometricPrompt 초기화 및 지문 인증 시행
            if (checkBiometricSupport()) {
                startBiometricAuthentication { result->
                    when(result){
                        AccessStatus.FINGERPRINT_SUCCESS->{
                            Toast.makeText(this, "Test", Toast.LENGTH_SHORT).show()
                            sendAccessResultToRasspberryPi(result.toString(), userId, username) //RP에 지문인증 결과 전송
                        }
                        AccessStatus.FINGERPRINT_FAILED->{
                            sendAccessResultToRasspberryPi(result.toString(), userId, username)
                        }
                        AccessStatus.FINGERPRINT_ERROR->{
                            sendAccessResultToRasspberryPi(result.toString(), userId, username)
                        }
                        else -> {}
                    }

                }
            }

             */


        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect: ${e.message}")
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
            inputStream = bluetoothSocket?.inputStream
            changeLogSender = ChangeLogSender(outputStream)
            Log.d(TAG, "Connected to device: ${device.name}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            Toast.makeText(this, "Failed to connect to device", Toast.LENGTH_SHORT).show()
            //finish()
        }
    }


    // 생체 인증 지원 여부 확인
    private fun checkBiometricSupport(): Boolean {
        val biometricManager = BiometricManager.from(this)

        // 새로운 방식: 생체 인식 인증이 가능한지 확인 (지문, 얼굴 등)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Toast.makeText(this, "지문 인식 하드웨어가 없습니다.", Toast.LENGTH_SHORT).show()
                false
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Toast.makeText(this, "등록된 지문이 없습니다.", Toast.LENGTH_SHORT).show()
                false
            }
            else -> {
                Toast.makeText(this, "생체 인증을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    // 지문 인증 시작
    private fun startBiometricAuthentication(callback: (AccessStatus) -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                //Toast.makeText(this@CarAccessActivity, "지문 인증 성공", Toast.LENGTH_SHORT).show()

                // 지문 인증 성공 시, EditText에 user_id 및 user_password 설정
                val sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials", // 파일 이름
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), // MasterKey 생성
                    this@CarAccessActivity,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, // 키 암호화 방식
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM // 값 암호화 방식
                )

                //지문인증과 매핑된 (폰에서 불러온) id, 비번
                val storedUserId = sharedPreferences.getString("user_id", null)
                val storedPassword = sharedPreferences.getString("user_password", null)
                val storeUsername = sharedPreferences.getString("user_name", null)
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

                //서버에 로그인 요청으로 올바른 id 확인
                if (storedUserId != null && storedPassword != null) {
                    // 서버에 로그인 요청
                    val loginRequest = LoginRequest(storedUserId, storedPassword, androidId)
                    RetrofitClient.instance.login(loginRequest).enqueue(object : Callback<LoginResponse> {
                        override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                            if (response.isSuccessful) {
                                val loginResponse = response.body()
                                if (loginResponse != null && loginResponse.success) {
                                    // 로그인 성공 시
                                    Toast.makeText(this@CarAccessActivity, "${loginResponse.name ?: storeUsername ?: "사용자"}님 인증 성공", Toast.LENGTH_SHORT).show()
                                    callback(AccessStatus.FINGERPRINT_SUCCESS)
                                    //얼굴 인식 도움(?)화면으로 전환 //프래그먼트
                                    //showFaceRecognitionUI(storedUserId)

                                } else {
                                    // 로그인 실패 시 메시지 출력
                                    Toast.makeText(this@CarAccessActivity, loginResponse?.message ?: "사용자 인증 실패", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(this@CarAccessActivity, "서버 오류", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                            // 네트워크 또는 서버 오류 처리
                            Toast.makeText(this@CarAccessActivity, "로그인 실패: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                } else {
                    Toast.makeText(this@CarAccessActivity, "등록된 사용자 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@CarAccessActivity, "지문 인증 오류: $errString", Toast.LENGTH_SHORT).show()
                callback(AccessStatus.FINGERPRINT_ERROR)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@CarAccessActivity, "지문 인증 실패", Toast.LENGTH_SHORT).show()
                callback(AccessStatus.FINGERPRINT_FAILED)
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("지문 인증")
            .setSubtitle("본인 인증을 위해 지문을 사용하세요.")
            .setNegativeButtonText("취소")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    //여기서부턴 핸드폰에서 라즈베리파이로 데이터를 보내는 내용
    //먼저 동기화 코드
    private val MAX_RETRY = 3 // 최대 재전송 횟수
    private val RETRY_DELAY = 3000L // 재전송 대기 시간 (밀리초)

    private fun sendAccessResultToRasspberryPi(status: String, userId:String, username:String, retryCount: Int = 0) {
        val status_split = status.split("_") //status.toString().split("_")
        val tag = status_split[0]
        val access_result = status_split[1]

        try {
            val taggedData = "<${tag}_start>$access_result/$userId/$username<${tag}_end>"
            outputStream?.write("$taggedData\n".toByteArray())
            Log.d(TAG, "Sent ${tag} Result Raspberry Pi: $taggedData")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send ${tag} Result: ${e.message}")
            if (retryCount < MAX_RETRY) {
                Log.d(TAG, "Retrying to send ${tag} Result (Attempt ${retryCount + 1}/$MAX_RETRY)...")
                Thread.sleep(RETRY_DELAY)
                sendAccessResultToRasspberryPi(status, userId, username, retryCount + 1)
            } else {
                Log.e(TAG, "Max retries reached. Failed to send ${tag} Result: $status")
            }
        }
    }

    private fun sendDataToRasspberryPi(tag: String, data: String, retryCount: Int = 0) {
        try {
            val taggedData = "<${tag}_start>$data<${tag}_end>"
            outputStream?.write("$taggedData\n".toByteArray())
            Log.d(TAG, "Sent ${tag} Result Raspberry Pi: $taggedData")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send ${tag} Result: ${e.message}")
            if (retryCount < MAX_RETRY) {
                Log.d(TAG, "Retrying to send ${tag} Result (Attempt ${retryCount + 1}/$MAX_RETRY)...")
                Thread.sleep(RETRY_DELAY)
                sendDataToRasspberryPi(tag, data, retryCount + 1)
            } else {
                Log.e(TAG, "Max retries reached. Failed to send ${tag} Result: $data")
            }
        }
    }

    private fun fetchAndSendLogs(deviceId: String) {
        val syncRequest = SyncRequest(deviceId)
        RetrofitClient.instance.getSyncData(syncRequest).enqueue(object : Callback<SyncResponseWrapper> {
            override fun onResponse(call: Call<SyncResponseWrapper>, response: Response<SyncResponseWrapper>) {
                if (response.isSuccessful) {
                    val syncLogs = response.body()?.data ?: emptyList()
                    if (syncLogs.isNotEmpty()) {
                        //Sync 데이터 있을 경우
                        syncLogs.forEach { log ->
                            val logJson = Gson().toJson(log)
                            changeLogSender.sendChangeLog(logJson)
                        }

                        markAsSynced(deviceId) // 모든 로그 전송 후 동기화 상태 업데이트
                    } else {
                        //Sync 데이터 없으면 없다고 보내기
                        changeLogSender.sendChangeLog("nosynclogs")
                        Log.d(TAG, "No sync logs to send.")
                    }
                } else {
                    Log.e(TAG, "Failed to fetch logs: ${response.errorBody()?.string()}")
                }
                //다 보내고 complete 보내기
                changeLogSender.sendChangeLog("complete")
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



    private fun startListeningForData(deviceId: String, userId: String, username: String) {
        Thread {
            var currentTag = ""
            while (true) {
                try {
                    val buffer = ByteArray(1024)
                    val bytes = inputStream?.read(buffer) ?: 0
                    if (bytes > 0) {
                        val receivedData = String(buffer, 0, bytes)
                        Log.d(TAG, "Received raw data: $receivedData")

                        // Handle start tag
                        val startMatch = Regex("<(\\w+)_start>").find(receivedData)
                        if (startMatch != null) {
                            currentTag = startMatch.groupValues[1]
                            dataBuffer.clear()
                        }

                        // Append data if a tag is active
                        if (currentTag.isNotEmpty()) {
                            dataBuffer.append(receivedData)
                        }

                        // Handle end tag
                        val endMatch = Regex("<(\\w+)_end>").find(receivedData)
                        if (endMatch != null && currentTag == endMatch.groupValues[1]) {
                            var completeData = dataBuffer.toString()

                            // Remove start and end tags
                            completeData = completeData.replace(
                                Regex("<${currentTag}_start>|<${currentTag}_end>"),
                                ""
                            ).trim()

                            Log.d(TAG, "Processed data without tags: $completeData")

                            handleReceivedData(currentTag, completeData, deviceId, userId, username)
                            currentTag = ""
                            dataBuffer.clear()
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading data: ${e.message}")
                    break
                }
            }
        }.start()
    }


    private fun handleReceivedData(tag: String, data: String, deviceId: String, userId: String, username: String) {
        when (tag) {
            "log" -> handleLogData(data)
            "vector" -> handleVectorData(data, deviceId, userId)
            "image" -> handleImageData(data)
            "FACE" -> handleFaceProcessData(data, deviceId, userId)
            "READY" -> handleReady(userId, username)
            "already_exist" -> handleAlreadyExist()
            "motor_angles" -> processMotorData(data)
            "mode" -> updateModeState(data)
            else -> Log.w(TAG, "Unknown tag: $tag")
        }
    }

    private fun handleReady(userId: String, username: String) {
        /*BT 연결 후 지문인증 시행*/
        runOnUiThread {
            Toast.makeText(this, "지문인증 시행", Toast.LENGTH_SHORT).show()

            // BiometricPrompt 초기화 및 지문 인증 시행
            if (checkBiometricSupport()) {
                startBiometricAuthentication { result->
                    when(result){
                        AccessStatus.FINGERPRINT_SUCCESS->{
                            Toast.makeText(this, "Test", Toast.LENGTH_SHORT).show()
                            sendAccessResultToRasspberryPi(result.toString(), userId, username) //RP에 지문인증 결과 전송
                        }
                        AccessStatus.FINGERPRINT_FAILED->{
                            sendAccessResultToRasspberryPi(result.toString(), userId, username)
                        }
                        AccessStatus.FINGERPRINT_ERROR->{
                            sendAccessResultToRasspberryPi(result.toString(), userId, username)
                        }
                        else -> {}
                    }

                }
            }
        }
    }

    private fun handleFaceProcessData(data: String, deviceId: String, userId: String) {
        try {
            val isAuthorized = data.trim()
            when {
                isAuthorized == "SUCCESS" -> {  //얼굴 인증 성공
                    updateProcessText("","", "${userId}님 ${deviceId} 성공")
                    Handler(Looper.getMainLooper()).postDelayed({
                        updateProcessText("초록색 모드 버튼을 눌러 사이드 미러 및 시트를 조정하세요.", " ", " ")
                    }, 2000)
                }
                isAuthorized == "FAIL" -> { //얼굴 인증 실패
                    updateProcessText("","", "${userId}님 ${deviceId} 실패")
                    showExitConfirmationDialog() // 앱 종료 팝업 띄우기
                }
                isAuthorized == "RETRY" -> {
                    updateProcessText("","", "촬영 재시도")
                }
                isAuthorized == "CAPTURE_BUT_NOFACE" -> {
                    updateProcessText("","", "NO FACE")
                }
                isAuthorized.startsWith("ACCESS/") -> {
                    val step = isAuthorized.split("/")[1]
                    handleAccessStep(step)
                }
                isAuthorized.startsWith("EXTRACT/") -> {
                    val step = isAuthorized.split("/")[1]
                    handleExtractStep(step)
                }
                else -> {
                    Log.w(TAG, "?????: $isAuthorized")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle face process data: ${e.message}")
        }
    }
    // 얼굴인증 FAIL시 알림 팝업
    private fun showExitConfirmationDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("얼굴 인증 실패")
                .setMessage("앱을 종료하시겠습니까?")
                .setPositiveButton("예") { _, _ -> finishAffinity() }
                .setNegativeButton("아니오", null)
                .show()
        }

    }
    private fun exitApp() {
        finishAffinity() // 현재 액티비티와 연관된 모든 액티비티 종료
        System.exit(0)    // 앱 프로세스 종료
    }

    // ACCESS 단계 처리
    private fun handleAccessStep(step: String) {
        val accessGuidanceMap = mapOf(
            "0" to "카메라를 보세요"
            /*
            "1" to "얼굴이 탐지되었습니다.",
            "2" to "얼굴이 탐지되었습니다.",
            "3" to "얼굴이 탐지되었습니다.",
            "4" to "얼굴이 탐지되었습니다.",
            "5" to "잘하셨어요"
             */
        )
        val process_text = accessGuidanceMap[step] //?: "알 수 없는 단계"
        updateProcessText("인증 시도중", "진행도 : ( $step / 5 )", "$process_text")
    }
    // EXTRACT 단계 처리
    private fun handleExtractStep(step: String) {
        val extractGuidanceMap = mapOf(
            "0" to "카메라를 보세요",
            "1" to "살짝 오른쪽을 보세요",
            "2" to "살짝 왼쪽을 보세요",
            "3" to "살짝 위쪽을 보세요",
            "4" to "살짝 아래쪽을 보세요",
            "5" to "잘하셨어요"
        )
        val process_text = extractGuidanceMap[step] ?: "알 수 없는 단계"
        updateProcessText("얼굴 데이터 추출중", "진행도 : ( $step / 5 )", "$process_text")
    }
    private fun updateProcessText(Text_a: String, Text_b:String, Text_c: String) {
        runOnUiThread {
            if (Text_a.isNotEmpty()) { // Text_b가 빈 문자열이 아닐 때만 업데이트
                textview_which_process.text = Text_a
            }
            if (Text_b.isNotEmpty()) { // Text_a가 빈 문자열이 아닐 때만 업데이트
                textview_access_process.text = Text_b
            }
            if (Text_c.isNotEmpty()) { // Text_b가 빈 문자열이 아닐 때만 업데이트
                textview_access_explanation.text = Text_c
            }
        }
        if (Text_a.isNotEmpty()) {
            Log.d(TAG, Text_a)
        }
        if (Text_b.isNotEmpty()) {
            Log.d(TAG, Text_b)
        }
        if (Text_c.isNotEmpty()) {
            Log.d(TAG, Text_c)
        }
    }


    private fun handleLogData(data: String) {
        try {
            // JSON 문자열을 LogRequest 데이터 클래스로 변환
            val logRequest = Gson().fromJson(data, LogRequest::class.java)

            // 라즈베리파이에서 받은 photo 이름 저장
            receivedPhotoFileName = logRequest.photo
            Log.d(TAG, "Received photo file name: $receivedPhotoFileName")

            RetrofitClient.instance.uploadLog(logRequest)
                .enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        if (response.isSuccessful) {
                            Log.d(TAG, "Log uploaded successfully.")
                        } else {
                            Log.e(TAG, "Failed to upload log: ${response.errorBody()?.string()}")
                        }
                    }

                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        Log.e(TAG, "Error uploading log: ${t.message}")
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle log data: ${e.message}")
        }
    }

    private fun handleVectorData(data: String, deviceId: String, userId: String) {
        try {
            // JSON 문자열을 JsonObject로 변환
            val faceVector = JsonParser.parseString(data).toString()

            // VectorRequest 객체 생성
            val vectorRequest = VectorRequest(
                user_id = userId,  // 임의의 user_id   //admin_1
                device_id = deviceId,                   //RP11-111
                face_vector = faceVector
            )

            RetrofitClient.instance.uploadVector(vectorRequest)
                .enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        if (response.isSuccessful) {
                            Log.d(TAG, "Vector uploaded successfully.")
                        } else {
                            Log.e(TAG, "Failed to upload vector: ${response.errorBody()?.string()}")
                        }
                    }

                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        Log.e(TAG, "Error uploading vector: ${t.message}")
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle vector data: ${e.message}")
        }
    }

    private fun handleImageData(data: String) {
        try {
            // Extract float array from JSON
            val floatArray = JSONArray(data).let { jsonArray ->
                FloatArray(jsonArray.length()) { jsonArray.getDouble(it).toFloat() }
            }

            // Convert float array to bitmap
            val bitmap = floatArrayToBitmap(floatArray)

            // 라즈베리파이에서 받은 photo 이름 사용
            val imageFileName = receivedPhotoFileName ?: throw IllegalStateException("Photo file name is missing!")
            uploadImageToServer(bitmap, imageFileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image: ${e.message}")
        }
    }




    private fun floatArrayToBitmap(floatArray: FloatArray): Bitmap {
        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val pixelIndex = (y * imageWidth + x) * 3
                val r = floatArray[pixelIndex].toInt()
                val g = floatArray[pixelIndex + 1].toInt()
                val b = floatArray[pixelIndex + 2].toInt()
                bitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bitmap
    }

    private fun uploadImageToServer(bitmap: Bitmap, fileName: String) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            val byteArray = outputStream.toByteArray()


            val imagePart = MultipartBody.Part.createFormData(
                "photo",
                fileName,
                byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )

            RetrofitClient.instance.uploadImage(imagePart).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Image uploaded successfully.")
                    } else {
                        Log.e(TAG, "Failed to upload image: ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<Void>, t: Throwable) {
                    Log.e(TAG, "Error uploading image: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload image: ${e.message}")
        }
    }

    private fun handleAlreadyExist() {
        Log.d(TAG, "Received 'already_exist'. Prompting user for adjustment.")

        // UI 스레드에서 실행
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("조정 확인")
                .setMessage("사이드미러, 시트 조정이 필요하신가요?")
                .setPositiveButton("예") { _, _ ->
                    // "예" 선택 시 라즈베리파이에 태그 전송
                    sendDataToRasspberryPi("more_adjust", "1")
                    updateProcessText("모드버튼을 눌러 조정을 시작하세요.", " ", " ")
                }
                .setNegativeButton("아니요") { _, _ ->
                    // "아니요" 선택 시 앱 종료
                    Toast.makeText(this, "조정을 종료합니다.", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }


    private fun processMotorData(data: String) {
        try {
            // JSON 데이터 파싱
            val configJson = JsonParser.parseString(data).asJsonObject

            // 필드 이름을 매핑하여 ConfigRequest 객체 생성
            val configRequest = ConfigRequest(
                user_id = userId,
                device_id = "RP11-111",

                sidemirror1 = configJson.get("motor_1").asFloat,  // motor_1 -> sidemirror1
                sidemirror2 = configJson.get("motor_2").asFloat,  // motor_2 -> sidemirror2
                seat1 = configJson.get("motor_3").asFloat,        // motor_3 -> seat1
                seat2 = configJson.get("motor_4").asFloat,        // motor_4 -> seat2
                seat3 = configJson.get("motor_5").asFloat         // motor_5 -> seat3
            )

            // 서버로 ConfigRequest 전송
            sendConfigRequest(configRequest)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing motor data: ${e.message}")
        }
    }
    private fun sendConfigRequest(configRequest: ConfigRequest) {
        RetrofitClient.instance.uploadConfig(configRequest).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Config uploaded successfully.")
                } else {
                    Log.e(TAG, "Failed to upload config: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e(TAG, "Error uploading config: ${t.message}")
            }
        })
    }
    private fun updateModeState(data: String) {
        when (data) {
            "one" -> {
                modeState = 1
                updateUI()
            }
            "two" -> {
                modeState = 2
                updateUI()
            }
            "three" -> {
                modeState = 3
                updateUI()
            }
        }
    }
    private fun updateUI() {
        runOnUiThread {
            when (modeState) {
                1 -> updateProcessText("파란색 버튼으로 사이드미러 수직각도, 빨간색 버튼으로 수평각도를 조정하세요, 조정 후 모드 버튼을 눌러주세요.", " ", " ")
                2 -> updateProcessText("파란색 버튼으로 시트 각도, 빨간색 버튼으로 앞뒤 조절해주고, 초록색 모드버튼을 눌러주세요.", " ", " ")
                3 -> updateProcessText("수고하셨습니다", " ", " ")
            }
        }
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



class ChangeLogSender(
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
