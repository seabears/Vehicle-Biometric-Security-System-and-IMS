package com.example.capstone_project


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionActivity : AppCompatActivity() {

    // 요청할 권한 목록 (알림 권한 포함)
    private val requiredPermissions = arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    // 알림 권한 여부 확인
    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12 이하에서는 알림 권한이 필요 없음
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 모든 권한이 허용되었는지 확인
        if (checkAllPermissions() && isNotificationPermissionGranted()) {
            navigateToNextScreen() // 다음 화면으로 이동
        } else {
            requestPermissions() // 권한 요청
        }
    }

    // 권한 요청
    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>().apply {
            addAll(requiredPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
    }

    // 권한 요청 결과 처리
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001) {
            val deniedPermissions = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }

            if (deniedPermissions.isEmpty()) {
                navigateToNextScreen() // 모든 권한이 허용되었을 때만 진행
            } else {
                if (deniedPermissions.contains(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    showNotificationPermissionDialog()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        }
    }

    // 알림 권한 설정을 안내하는 다이얼로그
    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("알림 권한 필요")
            .setMessage("앱에서 알림을 받으려면 알림 권한을 허용해야 합니다.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton("종료") { _, _ ->
                finish() // 앱 종료
            }
            .setCancelable(false)
            .show()
    }

    // 알림 설정 화면 열기
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    // 권한 거부 시 다이얼로그 표시
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("앱을 사용하려면 모든 권한을 허용해야 합니다.")
            .setPositiveButton("권한 설정") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("종료") { _, _ ->
                finish() // 앱 종료
            }
            .setCancelable(false)
            .show()
    }

    // 모든 권한이 허용되었는지 확인
    private fun checkAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 다음 화면으로 이동
    private fun navigateToNextScreen() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }
}