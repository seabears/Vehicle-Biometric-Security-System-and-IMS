package com.example.capstone_project

import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LogDetailActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var mapView: MapView
    private lateinit var mapContainer: FrameLayout
    private lateinit var btnVehiclePhoto: Button
    private lateinit var btnLocation: Button
    private var logLatitude: Double? = null
    private var logLongitude: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_detail)

        imageView = findViewById(R.id.image)
        mapView = findViewById(R.id.map)
        mapContainer = findViewById(R.id.map_container)
        btnVehiclePhoto = findViewById(R.id.btn_vehicle_photo)
        btnLocation = findViewById(R.id.btn_location)

        // OpenStreetMap 초기화
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        mapView.setBuiltInZoomControls(true)
        mapView.setMultiTouchControls(true)

        // Intent 데이터 가져오기
        val deviceId = intent.getStringExtra("deviceId")
        val timestamp = intent.getStringExtra("timestamp")

        if (deviceId != null && timestamp != null) {
            val formattedTimestamp = convertUtcToKst(timestamp)
            fetchLogDetails(deviceId, formattedTimestamp)
        }

        // 차량 내부 사진 버튼 클릭 처리
        btnVehiclePhoto.setOnClickListener {
            mapContainer.visibility = View.GONE
            mapView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
        }

        // 위치 버튼 클릭 처리
        btnLocation.setOnClickListener {
            if (logLatitude != null && logLongitude != null) {
                mapContainer.visibility = View.VISIBLE
                imageView.visibility = View.GONE

                // 강제로 지도를 다시 렌더링하여 표시
                mapView.visibility = View.VISIBLE
                mapView.invalidate()

                showLocationOnMap(logLatitude!!, logLongitude!!)
            } else {
                Toast.makeText(this, "위치 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun fetchLogDetails(deviceId: String, timestamp: String) {
        val request = LogDetailRequest(deviceId, timestamp)
        RetrofitClient.instance.getLogDetails(request).enqueue(object : Callback<LogDetailResponse> {
            override fun onResponse(call: Call<LogDetailResponse>, response: Response<LogDetailResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val logDetails = response.body()!!

                    // 위도와 경도를 저장
                    logLatitude = logDetails.latitude
                    logLongitude = logDetails.longitude

                    // 위치 버튼 활성화/비활성화 처리
                    if (logLatitude == null || logLongitude == null) {
                        btnLocation.isEnabled = false // 버튼 비활성화
                        btnLocation.alpha = 0.5f // 비활성화 시 시각적 피드백
                    } else {
                        btnLocation.isEnabled = true // 버튼 활성화
                        btnLocation.alpha = 1.0f
                    }

                    // 데이터 리스트 구성
                    val data = listOf(
                        "사용자" to logDetails.user,
                        "시간" to timestamp,
                        "위도" to (logLatitude?.toString() ?: ""), // null이면 빈 문자열
                        "경도" to (logLongitude?.toString() ?: ""), // null이면 빈 문자열
                        "접근종류" to if (logDetails.isValidAccess) "정상" else "비정상"
                    )

                    populateLogDetails(data)

                    // 이미지 설정
                    val imageUrl = "http://118.220.111.9:3000" + logDetails.photoUrl
                    Glide.with(this@LogDetailActivity)
                        .load(imageUrl)
                        .placeholder(R.drawable.logo)
                        .error(R.drawable.warning)
                        .into(imageView)
                }
            }

            override fun onFailure(call: Call<LogDetailResponse>, t: Throwable) {
                Toast.makeText(this@LogDetailActivity, "서버와의 통신에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun populateLogDetails(data: List<Pair<String, String>>) {
        // 각 텍스트 뷰에 데이터를 설정
        findViewById<TextView>(R.id.item_text_right1).text = data[0].second // 사용자
        findViewById<TextView>(R.id.item_text_right2).text = data[1].second // 시간
        findViewById<TextView>(R.id.item_text_right3).text = if (data[2].second.isNotEmpty()) data[2].second else "-" // 위도
        findViewById<TextView>(R.id.item_text_right4).text = if (data[3].second.isNotEmpty()) data[3].second else "-" // 경도
        findViewById<TextView>(R.id.item_text_right5).text = data[4].second // 접근종류
    }

    private fun showLocationOnMap(latitude: Double, longitude: Double) {
        val startPoint = GeoPoint(latitude, longitude)

        mapView.controller.setZoom(18.0) // 기본 줌 레벨을 18로 설정
        mapView.controller.setCenter(startPoint)

        val marker = Marker(mapView)
        marker.position = startPoint
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = "위치: $latitude, $longitude"
        mapView.overlays.clear() // 기존 마커 제거
        mapView.overlays.add(marker)

        // 강제로 지도를 다시 렌더링
        mapView.invalidate()
        mapContainer.visibility = View.VISIBLE
        mapView.visibility = View.VISIBLE
    }



    private fun convertUtcToKst(utcTimestamp: String?): String {
        val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        utcFormat.timeZone = TimeZone.getTimeZone("UTC")

        val kstFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        kstFormat.timeZone = TimeZone.getTimeZone("Asia/Seoul")

        return try {
            val parsedDate = utcFormat.parse(utcTimestamp)
            kstFormat.format(parsedDate!!)
        } catch (e: Exception) {
            Log.e("LogDetailActivity", "Timestamp conversion failed: ${e.message}")
            ""
        }
    }
}
