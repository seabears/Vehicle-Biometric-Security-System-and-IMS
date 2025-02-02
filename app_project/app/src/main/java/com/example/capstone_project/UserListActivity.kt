package com.example.capstone_project


import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class UserListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var userListAdapter: UserListAdapter
    private lateinit var rstUserButton: Button
    private lateinit var deleteUserButton: Button
    private lateinit var detailsButton: Button

    private var isAdmin: Boolean = false  // 관리자 여부를 나타내는 변수
    private var selectedUser: UserListResponse? = null  // 선택된 사용자
    private var rootId: String = ""  // 관리자의 ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_list)

        recyclerView = findViewById(R.id.UserRecyclerview)
        rstUserButton = findViewById(R.id.rstUser)
        deleteUserButton = findViewById(R.id.deleteUser)
        detailsButton = findViewById(R.id.details)

        //###넘겨받은 항목 isAdmin, deviceId###
        val deviceId = intent.getStringExtra("deviceId")
        isAdmin = intent.getBooleanExtra("isAdmin", false)

        deviceId?.let {
            fetchUserList(it)
        }

        // 관리자 여부에 따른 버튼 가시성 설정
        configureButtonsVisibility()

        // 사용자 등록 버튼 클릭 시
        rstUserButton.setOnClickListener {
            showAddUserDialog(deviceId)
        }

        // 사용자 제거 버튼 클릭 시
        deleteUserButton.setOnClickListener {
            selectedUser?.let { user ->
                if (user.id == rootId) {
                    Toast.makeText(this, "관리자는 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    deleteUser(deviceId, user)
                }
            } ?: Toast.makeText(this, "삭제할 사용자를 선택하세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchUserList(deviceId: String) {
        val request = UserListRequest(deviceId)
        RetrofitClient.instance.getUsers(request).enqueue(object : Callback<List<UserListResponse>> {
            override fun onResponse(call: Call<List<UserListResponse>>, response: Response<List<UserListResponse>>) {
                if (response.isSuccessful && response.body() != null) {
                    val userList = response.body()!!.toMutableList()
                    rootId = userList.firstOrNull()?.root ?: ""  // root ID 설정

                    // 어댑터 설정
                    userListAdapter = UserListAdapter(userList, rootId) { user ->
                        selectedUser = user  // 선택된 사용자 설정
                    }
                    recyclerView.adapter = userListAdapter
                    recyclerView.layoutManager = LinearLayoutManager(this@UserListActivity)
                } else {
                    Toast.makeText(this@UserListActivity, "사용자 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<UserListResponse>>, t: Throwable) {
                Toast.makeText(this@UserListActivity, "서버와의 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun configureButtonsVisibility() {
        // 관리자 여부에 따라 버튼 가시성 설정
        rstUserButton.visibility = if (isAdmin) View.VISIBLE else View.GONE
        deleteUserButton.visibility = if (isAdmin) View.VISIBLE else View.GONE
        detailsButton.visibility = View.VISIBLE  // 모든 사용자에게 보이도록 설정
    }

    private fun showAddUserDialog(deviceId: String?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_user, null)
        val userIdEditText = dialogView.findViewById<EditText>(R.id.userIdEditText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("사용자 추가")
            .setView(dialogView)
            .setPositiveButton("추가") { _, _ ->
                val userId = userIdEditText.text.toString()
                if (userId.isNotEmpty()) {
                    checkUserExistsAndAdd(deviceId, userId)
                } else {
                    Toast.makeText(this, "User ID를 입력하세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .create()

        dialog.show()
    }

    private fun checkUserExistsAndAdd(deviceId: String?, userId: String) {
        if (deviceId == null) return

        val request = CheckUserRequest(userId)
        RetrofitClient.instance.checkUserExists(request).enqueue(object : Callback<CheckUserResponse> {
            override fun onResponse(call: Call<CheckUserResponse>, response: Response<CheckUserResponse>) {
                if (response.isSuccessful && response.body()?.exists == true) {
                    addUserToDevice(deviceId, userId)
                } else {
                    Toast.makeText(this@UserListActivity, "해당 User ID가 존재하지 않습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<CheckUserResponse>, t: Throwable) {
                Toast.makeText(this@UserListActivity, "서버와의 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun addUserToDevice(deviceId: String, userId: String) {
        val existingUser = userListAdapter.getUserList().find { it.id == userId }
        if (existingUser != null) {
            Toast.makeText(this, "이미 존재하는 사용자입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val request = AddUserToDeviceRequest(deviceId, userId)
        RetrofitClient.instance.addUserToDevice(request).enqueue(object : Callback<AddUserResponse> {
            override fun onResponse(call: Call<AddUserResponse>, response: Response<AddUserResponse>) {
                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@UserListActivity, "사용자가 추가되었습니다.", Toast.LENGTH_SHORT).show()
                    fetchUserList(deviceId)  // 사용자 목록 갱신
                } else {
                    Toast.makeText(this@UserListActivity, "사용자 추가에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<AddUserResponse>, t: Throwable) {
                Toast.makeText(this@UserListActivity, "서버와의 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun deleteUser(deviceId: String?, user: UserListResponse) {
        if (deviceId == null) return

        // 확인 대화창 생성
        val alertDialog = AlertDialog.Builder(this)
            .setTitle("사용자 제거")
            .setMessage("${user.name}님을 제거하시겠습니까?")  // 사용자 이름 표시
            .setPositiveButton("확인") { _, _ ->
                // 확인 버튼 클릭 시 사용자 제거 요청
                val request = DeleteUserRequest(deviceId, user.id ?: "")
                RetrofitClient.instance.deleteUserFromDevice(request).enqueue(object : Callback<DeleteUserResponse> {
                    override fun onResponse(call: Call<DeleteUserResponse>, response: Response<DeleteUserResponse>) {
                        if (response.isSuccessful && response.body()?.success == true) {
                            Toast.makeText(this@UserListActivity, "${user.name}님이 제거되었습니다.", Toast.LENGTH_SHORT).show()
                            userListAdapter.removeUser(user)  // 사용자 목록 갱신
                            selectedUser = null
                        } else {
                            Toast.makeText(this@UserListActivity, "사용자 제거에 실패했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<DeleteUserResponse>, t: Throwable) {
                        Toast.makeText(this@UserListActivity, "서버와의 통신에 실패했습니다: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss()  // 취소 버튼 클릭 시 대화창 닫기
            }
            .create()

        alertDialog.show()
    }
}

