package com.example.capstone_project

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
        val userId = sharedPreferences.getString("userId", null)

        if (userId != null) {
            saveFcmTokenToServer(userId, token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // 알림 데이터 확인
        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: "알림"
        val body = data["body"] ?: remoteMessage.notification?.body ?: "내용 없음"

        val deviceId = data["device_id"] // 서버에서 추가한 데이터
        val isValidAccess = data["isValidAccess"]?.toIntOrNull()

        // 로그로 데이터 확인
        Log.d("FCM", "Received notification for deviceId: $deviceId, isValidAccess: $isValidAccess")

        // 알림 생성 및 표시
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationChannelId = "default_channel"

        // 채널 설정 (Android 8.0 이상)
        val channel = NotificationChannel(
            notificationChannelId,
            "기본 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        // 알림 빌더 설정
        val notificationBuilder = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.app_logo) // 앱의 알림 아이콘 설정
            .setAutoCancel(true)

        // 알림 표시
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    private fun saveFcmTokenToServer(userId: String, token: String) {
        val request = SaveTokenRequest(userId, token)
        RetrofitClient.instance.saveToken(request).enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (response.isSuccessful) {
                    Log.d("FCM", "FCM token updated successfully: ${response.body()?.message}")
                } else {
                    Log.e("FCM", "Failed to update FCM token: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                Log.e("FCM", "Error updating FCM token: ${t.message}")
            }
        })
    }
}
