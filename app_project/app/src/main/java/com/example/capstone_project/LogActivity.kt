package com.example.capstone_project

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var logAdapter: LogAdapter
    private var selectedLog: DeviceLogResponse? = null  // 선택한 로그 정보 저장

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        recyclerView = findViewById(R.id.LogRecyclerview)
        val buttonDetails = findViewById<Button>(R.id.ButtonDetails)

        //###넘겨받은 항목 deviceId###
        val deviceId = intent.getStringExtra("deviceId")
        deviceId?.let {
            fetchLogList(it)
        }

        // ButtonDetails 클릭 시 LogDetailActivity로 이동
        buttonDetails.setOnClickListener {
            if (selectedLog != null) {
                val intent = Intent(this@LogActivity, LogDetailActivity::class.java)
                //###넘겨주는 항목 timestamp, device_id###
                intent.putExtra("timestamp", selectedLog?.timestamp)
                intent.putExtra("deviceId", deviceId)
                startActivity(intent)
            } else {
                Toast.makeText(this, "로그를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchLogList(deviceId: String) {
        // POST 요청으로 deviceId를 서버에 전송
        val request = DeviceLogRequest(deviceId)
        RetrofitClient.instance.getLogs(request).enqueue(object : Callback<List<DeviceLogResponse>> {
            override fun onResponse(call: Call<List<DeviceLogResponse>>, response: Response<List<DeviceLogResponse>>) {
                if (response.isSuccessful && response.body() != null) {
                    val logList = response.body()!!
                    Log.d("LogActivity", "Received log details: $logList") // 서버 응답 데이터 확인

                    // 역순으로 정렬
                    val reversedLogList = logList.reversed()
                    Log.d("LogActivity", "Reversed log details: $reversedLogList")

                    // RecyclerView에 어댑터 설정
                    logAdapter = LogAdapter(reversedLogList) { log ->
                        selectedLog = log  // 선택된 로그 정보 저장
                    }
                    recyclerView.adapter = logAdapter
                    recyclerView.layoutManager = LinearLayoutManager(this@LogActivity)
                } else {
                    Toast.makeText(this@LogActivity, "로그 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<DeviceLogResponse>>, t: Throwable) {
                Toast.makeText(this@LogActivity, "서버와 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
