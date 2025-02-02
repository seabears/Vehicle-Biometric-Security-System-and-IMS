package com.example.capstone_project

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


// 1. CarList Adapter
class CarListAdapter(
    private val carList: List<CarListResponse>,
    private val onCarSelected: (CarListResponse) -> Unit
) : RecyclerView.Adapter<CarListAdapter.CarViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    class CarViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceIdTextView: TextView = view.findViewById(R.id.deviceIdTextView)
        val carModelTextView: TextView = view.findViewById(R.id.carModelTextView)
        val carNumberTextView: TextView = view.findViewById(R.id.carNumberTextView)
        val pairedImage: ImageView = view.findViewById(R.id.pairedImage)
        val unpairedTextView: TextView = view.findViewById(R.id.unpairedTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carlist, parent, false)
        return CarViewHolder(view)
    }

    override fun onBindViewHolder(holder: CarViewHolder, position: Int) {
        val car = carList[position]

        // Set text values for each TextView
        holder.deviceIdTextView.text = car.deviceId
        holder.carModelTextView.text = car.carModel
        holder.carNumberTextView.text = car.carNumber

        // Show or hide the paired image based on pairing status
        if (car.pairedText == "paired") {
            holder.pairedImage.visibility = View.VISIBLE
            holder.unpairedTextView.visibility = View.GONE
        } else {
            holder.pairedImage.visibility = View.GONE
            holder.unpairedTextView.visibility = View.VISIBLE
        }

        // Highlight selected item
        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(holder.itemView.context.getColor(R.color.selected_item_color))
        } else {
            holder.itemView.setBackgroundColor(holder.itemView.context.getColor(android.R.color.transparent))
        }

        // Handle item click
        holder.itemView.setOnClickListener {
            notifyItemChanged(selectedPosition)
            selectedPosition = holder.adapterPosition
            notifyItemChanged(selectedPosition)
            onCarSelected(car)
        }
    }

    override fun getItemCount(): Int = carList.size
}

// 2. User List RecyclerView Adapter
class UserListAdapter(
    private val userList: MutableList<UserListResponse>,
    private val rootId: String,
    private val onUserSelected: (UserListResponse) -> Unit
) : RecyclerView.Adapter<UserListAdapter.UserViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userNumberTextView: TextView = view.findViewById(R.id.userNumberTextView)
        val userNameTextView: TextView = view.findViewById(R.id.userNameTextView)
        val userIdTextView: TextView = view.findViewById(R.id.userIdTextView)
        val adminTextView: TextView = view.findViewById(R.id.adminTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_userlist, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, @SuppressLint("RecyclerView") position: Int) {
        val user = userList[position]

        // 순번 표시
        holder.userNumberTextView.text = (position + 1).toString()

        // 사용자 이름과 ID 설정
        holder.userNameTextView.text = user.name
        holder.userIdTextView.text = user.id

        // 관리자 여부 표시
        holder.adminTextView.text = if (user.id == rootId) "관리자" else ""

        // 선택된 항목의 배경색 변경
        holder.itemView.setBackgroundColor(
            if (position == selectedPosition)
                ContextCompat.getColor(holder.itemView.context, R.color.selected_item_color)
            else
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
        )

        // 클릭 이벤트 처리
        holder.itemView.setOnClickListener {
            notifyItemChanged(selectedPosition)  // 이전 선택 초기화
            selectedPosition = position         // 현재 선택 업데이트
            notifyItemChanged(selectedPosition) // 새 선택 강조
            onUserSelected(user)
        }
    }

    override fun getItemCount(): Int = userList.size

    fun removeUser(user: UserListResponse) {
        val position = userList.indexOf(user)
        if (position != -1) {
            userList.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    // 사용자 목록 반환 함수
    fun getUserList(): List<UserListResponse> = userList
}


// 3. Detail List RecyclerView Adapter
class LogAdapter(
    private val logList: List<DeviceLogResponse>,  // 서버에서 받아온 DeviceLogResponse 목록
    private val onLogSelected: (DeviceLogResponse) -> Unit  // DeviceLogResponse 객체를 넘기도록 수정
) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION  // 선택된 항목의 위치 저장

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logUserTextView: TextView = view.findViewById(R.id.logUserTextView)  // 사용자 이름 텍스트
        val logTimestampTextView: TextView = view.findViewById(R.id.logTimestampTextView)  // 타임스탬프 텍스트
        val warningImageView: ImageView = view.findViewById(R.id.warningImageView)  // 경고 아이콘 이미지
        val normalTextView: TextView = view.findViewById(R.id.normal)  // Normal 텍스트
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logList[position]

        // 사용자 이름과 타임스탬프 표시
        holder.logUserTextView.text = log.user
        holder.logTimestampTextView.text = convertUtcToKst(log.timestamp)

        // isValidAccess 값에 따라 경고 아이콘 표시 또는 숨김
        if (!log.isAccessValid()) {
            holder.warningImageView.visibility = View.VISIBLE  // 경고 이미지 표시
            holder.normalTextView.visibility = View.GONE  // Normal 텍스트 숨김
        } else {
            holder.warningImageView.visibility = View.GONE  // 경고 이미지 숨김
            holder.normalTextView.visibility = View.VISIBLE  // Normal 텍스트 표시
        }

        // 선택된 항목의 배경색을 변경
        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(holder.itemView.context.getColor(R.color.selected_item_color))  // 선택된 항목의 배경색
        } else {
            holder.itemView.setBackgroundColor(holder.itemView.context.getColor(android.R.color.transparent))  // 기본 배경색
        }

        // 항목 클릭 시 선택 상태로 설정
        holder.itemView.setOnClickListener {
            notifyItemChanged(selectedPosition)  // 이전 선택된 항목 초기화
            selectedPosition = holder.adapterPosition  // 새로운 선택 항목 업데이트
            notifyItemChanged(selectedPosition)  // 새로 선택된 항목 강조
            onLogSelected(log)  // 선택된 DeviceLogResponse 객체 전달
        }
    }

    override fun getItemCount(): Int {
        return logList.size
    }

    // UTC를 KST로 변환하는 함수
    private fun convertUtcToKst(utcTimestamp: String?): String {
        val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        utcFormat.timeZone = TimeZone.getTimeZone("UTC")

        val kstFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        kstFormat.timeZone = TimeZone.getTimeZone("Asia/Seoul")

        return try {
            val parsedDate = utcFormat.parse(utcTimestamp)
            kstFormat.format(parsedDate!!)
        } catch (e: Exception) {
            ""
        }
    }
}


