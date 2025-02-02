package com.example.capstone_project

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SignUpActivity : AppCompatActivity() {

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        // 각 입력 필드와 버튼 연결
        val idField = findViewById<EditText>(R.id.idblank)
        val passwordField = findViewById<EditText>(R.id.passwordblank)
        val nameField = findViewById<EditText>(R.id.nameblank)
        val nextButton = findViewById<Button>(R.id.nextbutton)

        // BiometricPrompt 초기화
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@SignUpActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Toast.makeText(this@SignUpActivity, "Authentication succeeded!", Toast.LENGTH_SHORT).show()

                // 인증 성공 시 회원가입 처리
                val userId = idField.text.toString()
                val password = passwordField.text.toString()
                val name = nameField.text.toString()
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

                if (userId.isNotEmpty() && password.isNotEmpty() && name.isNotEmpty()) {
                    saveUserInfo(userId, password, name) // 사용자 정보 저장
                    val signUpRequest = SignUpRequest(userId, password, name)

                    // 서버로 회원가입 요청
                    RetrofitClient.instance.signUp(signUpRequest).enqueue(object : Callback<SignUpResponse> {
                        override fun onResponse(call: Call<SignUpResponse>, response: Response<SignUpResponse>) {
                            val signUpResponse = response.body()
                            if (signUpResponse != null && signUpResponse.success) {
                                Toast.makeText(this@SignUpActivity, "회원가입 성공", Toast.LENGTH_SHORT).show()
                                val intent = Intent(this@SignUpActivity, LoginActivity::class.java)
                                startActivity(intent)
                            } else {
                                Toast.makeText(this@SignUpActivity, signUpResponse?.message ?: "회원가입 실패", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onFailure(call: Call<SignUpResponse>, t: Throwable) {
                            Toast.makeText(this@SignUpActivity, "회원가입 실패: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                } else {
                    Toast.makeText(this@SignUpActivity, "모든 필드를 입력하세요.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@SignUpActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        })

        // 지문 인증을 위한 PromptInfo 설정
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("지문 인증")
            .setSubtitle("계정을 등록하려면 지문 인증이 필요합니다.")
            .setNegativeButtonText("취소")
            .build()

        // nextButton 클릭 시 지문 인증을 시작
        nextButton.setOnClickListener {
            val userId = idField.text.toString()
            val password = passwordField.text.toString()
            val name = nameField.text.toString()

            if (userId.isNotEmpty() && password.isNotEmpty() && name.isNotEmpty()) {
                // 지문 인증이 성공해야만 회원가입 진행
                biometricPrompt.authenticate(promptInfo)
            } else {
                Toast.makeText(this, "모든 필드를 입력하세요.", Toast.LENGTH_SHORT).show()
            }
        }
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