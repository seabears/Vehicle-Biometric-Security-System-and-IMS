package com.example.capstone_project

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// Retrofit 클라이언트를 위한 Singleton 객체
object RetrofitClient {
    private const val BASE_URL = "http://118.220.111.9:3000" // 서버 주소

    // ApiService 인스턴스 생성
    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create()) // JSON 변환기 설정
            .build()

        retrofit.create(ApiService::class.java)
    }
}

