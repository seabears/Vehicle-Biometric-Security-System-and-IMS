plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.capstone_project"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.capstone_project"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    //지문인식
    implementation ("androidx.biometric:biometric:1.4.0-alpha02")

    //보안 : 지문과 계정 매핑
    implementation ("androidx.security:security-crypto:1.1.0-alpha03") // 추가

    // Retrofit
    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    // Retrofit에서 JSON 파싱을 위한 Gson 변환기
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    // 네트워크 통신에 필요한 OkHttp (선택적)
    implementation ("com.squareup.okhttp3:logging-interceptor:4.9.0")

    implementation ("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor ("com.github.bumptech.glide:compiler:4.12.0")


    //앱 푸시 알람
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-messaging-ktx:24.1.0")

    //json
    implementation("com.google.code.gson:gson:2.8.9")


    implementation ("org.osmdroid:osmdroid-android:6.1.10")
    implementation ("org.osmdroid:osmdroid-mapsforge:6.1.10")
}