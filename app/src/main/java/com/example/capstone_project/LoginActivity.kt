package com.example.capstone_project

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.firebase.messaging.FirebaseMessaging
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class LoginActivity : AppCompatActivity() {

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val userId = sharedPreferences.getString("userId", null)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        if (userId != null) {
            // 자동 로그인 요청
            autoLogin(userId, androidId)
        }

        // 로그인 화면 표시
        setContentView(R.layout.activity_login)


        // 레이아웃 객체 연결
        val editTextId = findViewById<EditText>(R.id.editTextId)
        val editTextPassword = findViewById<EditText>(R.id.editTextPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnSignUp = findViewById<Button>(R.id.btnSignUp)

        // 로그인 버튼 클릭 시 로그인 동작
        btnLogin.setOnClickListener {
            val id = editTextId.text.toString()
            val password = editTextPassword.text.toString()

            if (id.isNotEmpty() && password.isNotEmpty()) {
                val loginRequest = LoginRequest(id, password, androidId)
                RetrofitClient.instance.login(loginRequest).enqueue(object : Callback<LoginResponse> {
                    override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                        if (response.isSuccessful) {
                            val loginResponse = response.body()
                            saveUserInfo(id, password, loginResponse?.name.toString()) // 사용자 정보 저장
                            if (loginResponse != null && loginResponse.success) {
                                // FCM 토큰 저장
                                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val fcmToken = task.result
                                        saveFcmTokenToServer(id, fcmToken)
                                    } else {
                                        Log.e("FCM", "Failed to retrieve FCM token: ${task.exception?.message}")
                                    }
                                }

                                // 로그인 상태 저장
                                sharedPreferences.edit()
                                    .putString("userId", id)
                                    .apply()

                                // 다음 화면으로 이동
                                val intent = Intent(this@LoginActivity, CarListActivity::class.java)
                                intent.putExtra("username", loginResponse.name)
                                intent.putExtra("userId", id)
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(this@LoginActivity, loginResponse?.message ?: "로그인 실패", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@LoginActivity, "서버 오류", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                        Toast.makeText(this@LoginActivity, "로그인 실패: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            } else {
                Toast.makeText(this, "아이디와 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // 회원가입 버튼 클릭 시
        btnSignUp.setOnClickListener {
            val intent = Intent(this@LoginActivity, SignUpActivity::class.java)
            startActivity(intent)
        }
    }

    // 자동 로그인 함수
    private fun autoLogin(userId: String, androidId: String) {
        val autoLoginRequest = AutoLoginRequest(userId, androidId)
        RetrofitClient.instance.autoLogin(autoLoginRequest).enqueue(object : Callback<AutoLoginResponse> {
            override fun onResponse(call: Call<AutoLoginResponse>, response: Response<AutoLoginResponse>) {
                if (response.isSuccessful) {
                    val autoLoginResponse = response.body()
                    if (autoLoginResponse != null && autoLoginResponse.success) {
                        val intent = Intent(this@LoginActivity, CarListActivity::class.java)
                        intent.putExtra("username", autoLoginResponse.name)
                        intent.putExtra("userId", userId)
                        startActivity(intent)
                        finish()
                    }
                }
            }

            override fun onFailure(call: Call<AutoLoginResponse>, t: Throwable) {
                setContentView(R.layout.activity_login) // 네트워크 오류 시 로그인 화면 표시
            }
        })
    }

    // FCM 토큰 저장 함수
    private fun saveFcmTokenToServer(userId: String, token: String) {
        val request = SaveTokenRequest(userId, token)
        RetrofitClient.instance.saveToken(request).enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (response.isSuccessful) {
                    Log.d("FCM", "FCM token saved successfully: ${response.body()?.message}")
                } else {
                    Log.e("FCM", "Failed to save FCM token: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                Log.e("FCM", "Error saving FCM token: ${t.message}")
            }
        })
    }
    // 사용자 정보를 안전하게 저장하는 함수
    private fun saveUserInfo(userId: String, password: String, name: String) {
        //1. MasterKey를 생성하여 암호화 알고리즘에 사용할 키 생성
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        //2. EncryptedSharedPreferences 객체 생성
        val sharedPreferences = EncryptedSharedPreferences.create(
            "user_credentials", //파일 이름
            masterKey,  //암호화 사용할 MasterKey
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,  //키 암호화 방식
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM //값 암호화 방식
        )

        //3. 사용자 정보를 SharedPreferences에 안전하게 저장
        with(sharedPreferences.edit()) {
            putString("user_id", userId)
            putString("user_password", password)
            putString("user_name", name)
            //putString("user_androidId", androidId)
            apply()
        }

        Toast.makeText(this, "사용자 정보가 안전하게 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }
}

