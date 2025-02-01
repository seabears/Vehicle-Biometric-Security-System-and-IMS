package com.example.capstone_project

// Import Retrofit 패키지
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part


// 로그인 요청 처리 클래스
data class LoginRequest(val userId: String,
                        val password: String,
                        val androidId: String)
// 로그인 응답 처리 클래스
data class LoginResponse(val success: Boolean,
                         val name: String?,
                         val message: String?)


// 회원가입 요청 처리 클래스
data class SignUpRequest(val userId: String,
                         val password: String,
                         val userName: String)
// 회원가입 응답 처리 클래스
data class SignUpResponse(val success: Boolean,
                          val message: String?)


// 차량 정보 요청 처리 클래스
data class CarListRequest(val userId: String)
//차량 정보 응답 처리 클래스
data class CarListResponse(
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("car_model") val carModel: String?,
    @SerializedName("car_number") val carNumber: String?,
    @SerializedName("root") val root: String?,
    val pairedText: String = ""  // 페어링 여부 텍스트 추가
)


//사용자 정보 요청 처리 클래스
data class UserListRequest(val deviceId: String)
//사용자 정보 응답 처리 클래스
data class UserListResponse(
    val id: String?,
    val name: String?,
    val root: String?
)


//사용 기록 요청 처리 클래스
data class DeviceLogRequest(val deviceId: String)
//사용 기록 응답 처리 클래스
data class DeviceLogResponse(
    val user: String?,
    val timestamp: String?,
    val isValidAccess: Int
) { // isValidAccess 값을 Boolean으로 변환하는 함수
    fun isAccessValid(): Boolean {
        return isValidAccess == 1  // 1이면 true, 0이면 false
    }
}


//상세 사용 기록 요청 처리 클래스
data class LogDetailRequest(val deviceId: String, val timestamp: String)
//상세 사용 기록 응답 처리 클래스
data class LogDetailResponse(
    val user: String,
    val latitude: Double?,
    val longitude: Double?,
    val isValidAccess: Boolean,
    val photoUrl: String?  // 사진의 URL
)

data class CheckUserRequest(val userId: String)
data class CheckUserResponse(val exists: Boolean)

data class AddUserToDeviceRequest(val deviceId: String, val userId: String)
data class AddUserResponse(val success: Boolean)

data class DeleteUserRequest(
    val deviceId: String,
    val userId: String
)

data class DeleteUserResponse(
    val success: Boolean,
    val message: String?
)

data class SaveDeviceRequest(
    val deviceId: String,
    val carNumber: String,
    val carModel: String,
    val root: String
)

data class SaveDeviceResponse(
    val success: Boolean,
    val message: String
)


//로그용
// 요청 및 응답 데이터 클래스
// Data classes
data class SyncRequest(val deviceId: String)
data class SyncResponseWrapper(
    val success: Boolean,
    val data: List<SyncLog>
)
data class SyncLog(
    val changeId: Int,
    val deviceId: String,
    val userId: String,
    val changeType: String,
    val synced: Int,
    val changeDetails: String
)
data class MarkSyncCompleteRequest(val deviceId: String)


// 요청 데이터 클래스
data class SaveTokenRequest(val userId: String, val token: String)

// 공통 응답 데이터 클래스
data class ApiResponse(val success: Boolean, val message: String)

data class AutoLoginRequest(val userId: String, val androidId: String)
data class AutoLoginResponse(val success: Boolean, val name: String?, val message: String?)



// 로그 요청 데이터 클래스
data class LogRequest(
    val device_id: String,
    val user_name: String,
    val timestamp: String,
    val longitude: Double?,
    val latitude: Double?,
    val isValidAccess: Int,
    val photo: String
)

// 벡터 요청 데이터 클래스
data class VectorRequest(
    val user_id: String,
    val device_id: String,
    val face_vector: String // JSON 형식의 벡터 데이터를 문자열로 전달
)

// 설정 요청 데이터 클래스
data class ConfigRequest(
    val user_id: String,      // 사용자 ID
    val device_id: String,      // 사용자 ID
    val sidemirror1: Float,     // 좌측 사이드미러 각도
    val sidemirror2: Float,     // 우측 사이드미러 각도
    val seat1: Float,           // 좌석 1
    val seat2: Float,           // 좌석 2
    val seat3: Float            // 좌석 3
)




// Retrofit Interface 정의
interface ApiService {
    // 로그인 요청을 위한 API 인터페이스
    @POST("/login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    // 회원가입 요청을 위한 API 인터페이스
    @POST("/signup")
    fun signUp(@Body request: SignUpRequest): Call<SignUpResponse>

    // 차량 목록 요청을 위한 API 인터페이스
    @POST("/getcarlistofuser")
    fun getCars(@Body request: CarListRequest): Call<List<CarListResponse>>

    // 사용자 목록 요청을 위한 API 인터페이스
    @POST("/getuserlistofdevice")
    fun getUsers(@Body request: UserListRequest): Call<List<UserListResponse>>

    // 디바이스 사용 기록 요청을 위한 API 인터페이스
    @POST("/getloglist")
    fun getLogs(@Body request: DeviceLogRequest): Call<List<DeviceLogResponse>>

    // 상세 사용 기록 요청을 위한 API 인터페이스
    @POST("/logdetails")
    fun getLogDetails(@Body request: LogDetailRequest): Call<LogDetailResponse>

    @POST("/deleteUserFromDevice")
    fun deleteUserFromDevice(@Body request: DeleteUserRequest): Call<DeleteUserResponse>

    @POST("/checkUserExists")
    fun checkUserExists(@Body request: CheckUserRequest): Call<CheckUserResponse>

    @POST("/addUserToDevice")
    fun addUserToDevice(@Body request: AddUserToDeviceRequest): Call<AddUserResponse>

    @GET("/getCarTypes")
    fun getCarTypes(): Call<List<String>>

    @POST("/saveDevice")
    fun saveDevice(@Body request: SaveDeviceRequest): Call<SaveDeviceResponse>


    @POST("/getSyncData")
    fun getSyncData(@Body syncRequest: SyncRequest): Call<SyncResponseWrapper>

    @POST("/markAsSynced")
    fun markAsSynced(@Body request: MarkSyncCompleteRequest): Call<Void>



    @POST("/saveToken")
    fun saveToken(@Body request: SaveTokenRequest): Call<ApiResponse>

    @POST("/autoLogin")
    fun autoLogin(@Body autoLoginRequest: AutoLoginRequest): Call<AutoLoginResponse>



    // 로그 데이터 업로드
    @POST("/uploadLog")
    fun uploadLog(@Body logData: LogRequest): Call<Void>

    // 벡터 데이터 업로드
    @POST("/uploadVector")
    fun uploadVector(@Body vectorRequest: VectorRequest): Call<Void>

    // 설정 데이터 업로드
    @POST("/uploadConfig")
    fun uploadConfig(@Body configRequest: ConfigRequest): Call<Void>

    @Multipart
    @POST("/uploadImage")
    fun uploadImage(@Part photo: MultipartBody.Part): Call<Void>
}