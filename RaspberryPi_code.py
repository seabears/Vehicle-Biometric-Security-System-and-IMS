import time
import RPi.GPIO as GPIO
import board
import busio
from adafruit_pca9685 import PCA9685
from scipy.optimize import fsolve

import subprocess
import time
import serial
import os
import json
import cv2
import numpy as np
from datetime import datetime
import threading
import dlib
from picamera2 import Picamera2
import shutil
import sqlite3
from queue import Queue
import gps
import math

# 서보 모터 설정
i2c = busio.I2C(board.SCL, board.SDA)
pwm = PCA9685(i2c)
pwm.frequency = 50  # SG90 모터는 50Hz에서 동작


user_id = None  # 전역 변수로 초기화


# GPIO 설정 (BCM 모드)
GPIO.setmode(GPIO.BCM)

# 버튼 핀 설정
button_pin_18 = 18  # 모터 각도 증가
button_pin_23 = 23  # 모터 각도 감소
button_pin_24 = 24  # 모터 4, 5 각도 증가
button_pin_25 = 25  # 모터 4, 5 각도 감소
button_pin_17 = 17  # 채널 전환 버튼
GPIO.setup(button_pin_18, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
GPIO.setup(button_pin_23, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
GPIO.setup(button_pin_24, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
GPIO.setup(button_pin_25, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
GPIO.setup(button_pin_17, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)

# 서보 모터 각도 초기값
current_angle_1 = 90  # 모터 1 (채널 0)의 초기 각도
current_angle_2 = 90  # 모터 2 (채널 1)의 초기 각도
current_angle_3 = 90  # 모터 3 (채널 2)의 초기 각도
current_angle_4 = 90  # 모터 4 (채널 3)의 초기 각도
current_angle_5 = 90  # 모터 5 (채널 4)의 초기 각도

# 현재 제어하는 채널
active_channel = -1  # -1: 조정 시작 전, 0: 모터 1,2 조정, 2: 모터 3,4,5 조정
last_button_press_time = 0  # 버튼 마지막 눌림 시간




''' 디바이스 정보 '''
DEVICEID = os.popen('hostname').read().strip()  #호스트 네임  ex)"RP77-777"
PORT = "/dev/rfcomm0"          # Bluetooth 포트
BAUDRATE = 115200              # 통신 속도
#ENCODINGS_FILE = "vector_encodings.txt"  # Encodings 저장 파일

''' 필요 경로 '''
CURRENT_DIR = os.getcwd()                               # 현재 위치 경로
MODEL_DIR = f'{CURRENT_DIR}/model'                      # 모델 폴더 경로
ENCODINGS_DIR = f"{CURRENT_DIR}/face_encoding_data"     # 저장된 얼굴 벡터 폴더 경로
TEST_IMG_DIR = f"{CURRENT_DIR}/test_img"                # 촬영한 사진 폴더 경로
DB_FILE = f"{CURRENT_DIR}/bluetooth_logs.db"            # SQLite 데이터베이스 파일 경로

''' 모델 저장할 Q '''
PICAM2_QUEUE = Queue()      # 카메라 객체
DETECTOR_QUEUE = Queue()
SHAPE_PREDICTOR_QUEUE = Queue()
FACE_RECOGNIZER_QUEUE = Queue()

''' 스레드 '''
LOAD_MODEL_THREAD = None    # 모델 Load

'''img 크기 조정 비율 '''
SCALE_FACTOR_A = 1
SCALE_FACTOR_B = 1
SCALE_FACTOR_LOG_IMG = 0.125

''' 인증 판별 기준 '''
THRESHOLD_POINT = 60    # 정확도 임계점

''' 재전송 관련 '''
RETRY_DELAY = 2  # 재전송 대기 시간 (초)
MAX_RETRY = 3  # 최대 재시도 횟수

''' 재촬영 관련 '''
CAPTURE_RETRY = 5   # 최대 재촬영 시도 횟수

''' GPS 세션 관련 '''
gps_session = None
gps_data = {"latitude": None, "longitude": None}  # GPS 데이터를 저장할 전역 변수
gps_thread_running = True  # GPS 스레드 실행 플래그


def start_bluetooth_watch():
    """_summary_
    SPP 를 사용해서 라즈벨파이를 서버로써 OPEN
    """
    try:
        process = subprocess.Popen(
            ["sudo", "rfcomm", "watch", "hci0"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        print("Bluetooth watch started successfully. Waiting for a connection...")
        return process
    except Exception as e:
        print(f"Failed to start Bluetooth watch: {e}")
        return None

def wait_for_connection(port="/dev/rfcomm0", timeout=30):
    """_summary_
    핸드폰과 연결이 되면 rfcomm0 파일이 생김
    => rfcomm0 파일이 생기는지는 기다리고 있다가 해당 파일이 생기면 연결되었다고 판단
    기다리는 시간은 "30초"

    Args:
        port (str, optional): _description_. Defaults to "/dev/rfcomm0".
        timeout (int, optional): _description_. Defaults to 30.

    Returns:
        _type_: 성공 실패 여부
    """
    start_time = time.time()
    while time.time() - start_time < timeout:
        if os.path.exists(port):
            print(f"Bluetooth device connected on {port}.")
            return True
        time.sleep(0.5)
    print("Connection timed out.")
    return False

def connect_to_bluetooth(port="/dev/rfcomm0"):
    """_summary_
    이제 핸드폰과 Serial 라인 생성

    Args:
        port (str, optional): _description_. Defaults to "/dev/rfcomm0".
    """
    try:
        bluetooth = serial.Serial(port, 115200)
        bluetooth.flushInput()
        print(f"Connected to Bluetooth on {port}.")
        return bluetooth
    except Exception as e:
        print(f"Failed to connect to Bluetooth: {e}")
        return None

def listen_for_data(bluetooth):
    """
    Bluetooth 데이터를 수신하고 처리
    태그를 기준으로 데이터를 받아 데이터의 시작과 끝을 확인
    """
    buffer = ""
    current_tag = ""
    while True:
        try:
            # Bluetooth 데이터 읽기
            data = bluetooth.readline().decode("utf-8").strip()  # 한 번에 최대 1024바이트 읽기
            print(f"Received raw data: {data}")  # 디버깅용 로그

            # 태그 시작 감지
            if "<" in data and "_start>" in data:
                current_tag = data.split("_start>")[0][1:]
                buffer = ""

            # 데이터 누적
            if current_tag:
                buffer += data

            # 태그 종료 감지
            if "<" in data and "_end>" in data and current_tag in data:
                end_tag = f"<{current_tag}_end>"
                processed_data = buffer.split(end_tag)[0].split(f"<{current_tag}_start>")[1]

                process_received_tag(current_tag, processed_data)

                current_tag = ""  # 태그 초기화
        except Exception as e:
            print(f"Error reading data: {e}")
            break

def process_received_tag(tag, data):
    """
    수신한 태그에 따른 행동 실행
    """
    try:
        print(f"\033[31mProcessing {tag} {data}\033[0m")

        if tag == "change_log":
            process_log_data(data)

        elif tag == "FINGERPRINT":
            process_fingerprint(data)

        elif tag == "more_adjust":
            print("Received 'more_adjust' command. Starting motor adjustment...")
            adjust_motor_angles(bluetooth)  # adjust_motor_angles 함수 호출

        else:
            print(f"Unknown tag: {tag}")

    except Exception as e:
        print(f"Error process tag: {e}")



def process_log_data(data):
    '''
    change_log 태그에 해당하는 데이터 처리
    '''
    global user_id

    # 1. sync 데이터 없을 때
    if data == "nosynclogs":
        send_data_with_tags(bluetooth, "READY", "ready")
        return

    # 1. sync 데이터 있을 때
    elif data == "complete":
        send_data_with_tags(bluetooth, "READY", "ready")
        return
    log_data = json.loads(data)
    if log_data.get("changeType") == "ADD_USER":
        user_id = log_data.get("userId")
        motor_angles = log_data.get("motorAngles", [-1, -1, -1, -1, -1])
        if user_id:
            save_user_to_database(user_id, motor_angles)
    elif log_data.get("changeType") == "DELETE_USER":
        user_id = log_data.get("userId")
        if user_id:
            delete_user_from_database(user_id)
    elif log_data.get("changeType") == "UPDATE_CONFIG":
        change_details = log_data.get("changeDetails")
        if change_details:
            try:
                # changeDetails 안의 데이터를 다시 JSON으로 파싱
                parsed_details = json.loads(change_details)
                update_motor_data_in_database(log_data.get("userId"), parsed_details)
            except json.JSONDecodeError as e:
                print(f"Failed to parse changeDetails JSON: {e}")
        else:
            print("No change details found in the log.")
    elif log_data.get("changeType") == "UPDATE_VECTOR":
        user_id = log_data.get("userId")
        change_details = log_data.get("changeDetails")
        if user_id:
            parsed_details = json.loads(change_details)
            update_face_vector(user_id, parsed_details)
    elif log_data.get("changeType") == "CAR_REGIST":
            change_details = log_data.get("changeDetails")
            if change_details:
                try:
                    # JSON 데이터 파싱
                    parsed_details = json.loads(change_details)

                    # 필요한 값 추출
                    length_side_1 = parsed_details.get("length_side_1")
                    length_side_2 = parsed_details.get("length_side_2")
                    length_xl = parsed_details.get("length_xl")
                    length_seat = parsed_details.get("length_seat")
                    height_side = parsed_details.get("height_side")

                    # 모든 값이 있는 경우 저장 함수 호출
                    if all(v is not None for v in [length_side_1, length_side_2, length_xl, length_seat, height_side]):
                        save_car_dimensions_to_database(length_side_1, length_side_2, length_xl, length_seat, height_side)
                    else:
                        print("Invalid car dimension data in CAR_REGIST.")
                except json.JSONDecodeError as e:
                    print(f"Failed to parse changeDetails JSON in CAR_REGIST: {e}")

def update_face_vector(user_id, parsed_details):
    '''
    서버에서 받은 얼굴 벡터 데이터 업데이트
    '''

    # face_vector 문자열을 JSON으로 변환
    face_data = json.loads(parsed_details['face_vector'])

    # encodings, names 추출
    encodings = face_data['encodings']
    name = face_data['names']

    #유저 폴더, 파일 이름
    user_dir = f"{ENCODINGS_DIR}/{user_id}"
    file_name = f"{user_id}.json"

    # 디렉토리 없으면 생성, 있으면 패쓰
    os.makedirs(user_dir, exist_ok=True)

    # JSON 형식으로 저장
    with open(os.path.join(user_dir, file_name), 'w') as enc_file:
        json.dump({
            "encodings": encodings,
            "names": name,
        }, enc_file)

def initialize_car_registration_database():
    # SQLite 데이터베이스 연결
    conn = sqlite3.connect("car_registration.db")
    cursor = conn.cursor()

    # 테이블 생성 (필드 추가)
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS car_dimensions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,  -- 자동 증가 식별자
            length_side_1 REAL NOT NULL,          -- 길이(side 1)
            length_side_2 REAL NOT NULL,          -- 길이(side 2)
            length_xl REAL NOT NULL,              -- 길이(xl)
            length_seat REAL NOT NULL,            -- 길이(seat)
            height_side REAL NOT NULL             -- 높이(side)
        )
    ''')

    # created_at 필드가 없으면 추가
    try:
        cursor.execute('ALTER TABLE car_dimensions ADD COLUMN created_at TEXT DEFAULT CURRENT_TIMESTAMP')
        print("created_at 컬럼이 추가되었습니다.")
    except sqlite3.OperationalError:
        # 필드가 이미 존재하면 무시
        print("created_at 컬럼이 이미 존재합니다.")

    # 변경사항 저장
    conn.commit()
    conn.close()
    print("자동차 치수 데이터베이스가 초기화되었습니다.")


def save_car_dimensions_to_database(length_side_1, length_side_2, length_xl, length_seat, height_side):
    try:
        conn = sqlite3.connect("car_registration.db")
        cursor = conn.cursor()
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        # 데이터 저장
        cursor.execute('''
            INSERT INTO car_dimensions (length_side_1, length_side_2, length_xl, length_seat, height_side, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (length_side_1, length_side_2, length_xl, length_seat, height_side, timestamp))

        conn.commit()
        conn.close()
        print(f"Car dimensions saved: length_side_1={length_side_1}, length_side_2={length_side_2}, length_xl={length_xl}, length_seat={length_seat}, height_side={height_side}")
    except Exception as e:
        print(f"Failed to save car dimensions: {e}")


def process_fingerprint(data):
    '''
    FINGERPRINT 태그에 해당하는 행동 실행
    얼굴 인증 과정 수행
    '''
    global user_id
    access_data = data.split('/')[0]    # 지문인증 성공 여부
    user_id = data.split('/')[1]        # user_id
    user_name = data.split('/')[2]      # user_name
    userdata_dir_path = os.path.join(ENCODINGS_DIR, user_id) # 해당 Id 유저의 데이터 경로

    if access_data == "SUCCESS":
        # 해당 Id의 얼굴 데이터 보유: 1.초기추출
        #                    없으면: 1.인증단계

        # 1.초기 추출 단계
        if not os.path.isdir(userdata_dir_path):
            # 1-1.폰에 추출 태그 보내기
            send_data_with_tags(bluetooth, "FACE", "EXTRACT/0")
            # 1-2.사진 촬영
            capture_faces(5, "before")
            # 1-2.추출
            face_extract(userdata_dir_path)
            # 1-3.서버에 업로드
            json_file_path = os.path.join(userdata_dir_path, f"{user_id}.json")
            send_vector(bluetooth, json_file_path)

            #default
            matched_path = f"{TEST_IMG_DIR}/1.jpg"
            name = user_id


        # 1.인증 단계
        else :
            # 1-1.폰에 인증 태그 보내기
            send_data_with_tags(bluetooth, "FACE", "ACCESS/0")
            # 1-2.사진 촬영
            cur_test = 0
            while cur_test < CAPTURE_RETRY:
                capture_faces(1, "after")
                # 1-3. 얼굴 인식 진행
                name, similarity, matched_path = face_access(userdata_dir_path)
                print(f"{name} : {similarity:.2f}%")
                if similarity >= THRESHOLD_POINT:
                    cur_test += 1
                    send_data_with_tags(bluetooth, "FACE", f"ACCESS/{cur_test}")
                    break
                else :
                    cur_test += 1
                    send_data_with_tags(bluetooth, "FACE", f"ACCESS/{cur_test}")
                    send_data_with_tags(bluetooth, "FACE", "RETRY") # 재시도시 폰에 "RETRY" 보내기
                    time.sleep(1)
                    continue


        # 2. 결과 폰에 전송
        if user_id == name:
            send_data_with_tags(bluetooth, "FACE", "SUCCESS")
            isValidAccess = 1
            # 추출후 motor 조정단계
            handle_motor_adjustment(bluetooth, user_id)  # 모터 조정 함수 호출

        else:
            send_data_with_tags(bluetooth, "FACE", "FAIL")
            isValidAccess = 0
            play_siren(3)  # 인증 실패 시 3초 동안 사이렌 울리기\

        time.sleep(0.5)
        send_log_with_image(bluetooth, user_name, isValidAccess, matched_path)
    ########################

    elif access_data == "FAILED":
        print(f"Processing  {data}")
    elif access_data == "ERROR":
        print(f"Processing {data}")

def face_extract(userdata_dir_path):
    '''
    초기 얼굴 데이터 추출
    '''
    if os.path.exists(userdata_dir_path):
        shutil.rmtree(userdata_dir_path)
    os.makedirs(userdata_dir_path)
    user_id = os.path.basename(userdata_dir_path)
    # 저장할 배열
    known_face_encodings = []
    known_user_id = []

    #스레드에서 모델 가져오기
    while LOAD_MODEL_THREAD.is_alive():
        time.sleep(0.5)
    detector, shape_predictor, face_recognizer = get_model()

    for test_img_name in os.listdir(TEST_IMG_DIR):
        test_img_path = os.path.join(TEST_IMG_DIR, test_img_name)
        test_img = cv2.imread(test_img_path)
        if test_img is None:
            continue

        # 해상도 축소
        small_image = cv2.resize(test_img, (0, 0), fx=SCALE_FACTOR_A, fy=SCALE_FACTOR_A)

        # 회색조 변환
        gray = cv2.cvtColor(small_image, cv2.COLOR_BGR2GRAY)

        # 얼굴 탐지
        faces = detector(gray)

        for face in faces:
            # 원래 이미지 크기로 좌표 복원
            face_rectangle = dlib.rectangle(
                int(face.left() / SCALE_FACTOR_A),
                int(face.top() / SCALE_FACTOR_A),
                int(face.right() / SCALE_FACTOR_A),
                int(face.bottom() / SCALE_FACTOR_A)
            )

            shape = shape_predictor(gray, face_rectangle)
            face_encoding = face_recognizer.compute_face_descriptor(test_img, shape)

            # 리스트로 변환하여 저장
            known_face_encodings.append(list(face_encoding))
            known_user_id.append(user_id)


    file_name = f"{user_id}.json"

    # JSON 형식으로 저장
    with open(os.path.join(userdata_dir_path, file_name), 'w') as enc_file:
        json.dump({
            "encodings": known_face_encodings,
            "names": known_user_id,
    }, enc_file)

    print(f"Extract Complete : {file_name}")

def face_access(userdata_dir_path, capture_cnt = 0):
    '''
    얼굴 인식
    TEST_IMG_DIR의 사진 중 저장된 데이터와 가장 유사한 id 반환
    '''
    highest_similarity = 0
    all_userdata_path = os.path.dirname(userdata_dir_path)
    known_face_encodings, known_user_ids = load_face_encodings(all_userdata_path)

    #스레드에서 모델 가져오기
    while LOAD_MODEL_THREAD.is_alive():
        time.sleep(0.5)
    detector, shape_predictor, face_recognizer = get_model()

    for test_img_name in os.listdir(TEST_IMG_DIR):
        test_img_path = os.path.join(TEST_IMG_DIR, test_img_name)
        test_img = cv2.imread(test_img_path)
        if test_img is None:
            continue

        small_image = cv2.resize(test_img, (0, 0), fx=SCALE_FACTOR_B, fy=SCALE_FACTOR_B)
        gray_test = cv2.cvtColor(small_image, cv2.COLOR_BGR2GRAY)
        faces_in_test = detector(gray_test)

        #사진에서 얼굴 탐지 안 될 경우
        if not faces_in_test :
            print("재시도")
            #send_data_with_tags(bluetooth, "FACE", "RETRY") # 재시도시 폰에 "RETRY" 보내기
            #capture_faces(1, "after")
            #name, similarity, matched_path = face_access(userdata_dir_path, capture_cnt+1)
            return "", 0, ""

        # 가장 큰 얼굴 찾기
        largest_face = max(faces_in_test, key=lambda f: f.width() * f.height())

        # 좌표를 원래 이미지 크기로 복원
        largest_face_original = dlib.rectangle(
            int(largest_face.left() / SCALE_FACTOR_B),
            int(largest_face.top() / SCALE_FACTOR_B),
            int(largest_face.right() / SCALE_FACTOR_B),
            int(largest_face.bottom() / SCALE_FACTOR_B)
        )

        # 원래 크기에서 얼굴을 다시 인식
        shape = shape_predictor(cv2.cvtColor(test_img, cv2.COLOR_BGR2GRAY), largest_face_original)
        face_encoding = np.array(face_recognizer.compute_face_descriptor(test_img, shape))

        distances = np.linalg.norm(known_face_encodings - face_encoding, axis=1)
        min_distance = np.argmin(distances)
        similarity = max(0, 1 - distances[min_distance]) * 100

        #최대 유사도 갱신
        if similarity > highest_similarity:
            highest_similarity = similarity
            matched_path = test_img_path

            # 유사도가 임계점보다 높은지
            if similarity >= THRESHOLD_POINT:
                name = known_user_ids[min_distance]
            else:
                name = "Unknown"
        print(f" {similarity}")

    return name, highest_similarity, matched_path

def load_face_encodings(all_userdata_path = f"{ENCODINGS_DIR}"):
    '''
     json파일로부터 encoding, name 로드
    '''

    # 인코딩 데이터와 이름을 저장할 리스트
    all_encodings = []
    all_names = []

    # all_userdata_path 안의 모든 폴더를 확인
    for user_folder in os.listdir(all_userdata_path):
        user_folder_path = os.path.join(all_userdata_path, user_folder)         # 해당 유저 데이터 폴더 경로
        json_file_path = os.path.join(user_folder_path, f"{user_folder}.json")  # 해당 유저 json 파일 경로

        # json 파일이 존재하는지 확인
        if os.path.exists(json_file_path):
            with open(json_file_path, 'r') as enc_file:
                data = json.load(enc_file)

                # 'encodings'와 'names' 항목을 가져와서 리스트에 추가
                all_encodings.extend(data["encodings"])
                all_names.extend(data["names"])

    # 반환값은 NumPy 배열로 변환한 encodings와 names 리스트
    return np.array(all_encodings), all_names

def camera():
    '''
    카메라 ON && 카메라 객체 queue에 추가
    '''
    picam2 = Picamera2()
    video_config = picam2.create_video_configuration()
    picam2.configure(video_config)
    picam2.start()
    PICAM2_QUEUE.put(picam2)

def get_camera():
    picam2 = PICAM2_QUEUE.get()
    PICAM2_QUEUE.put(picam2)
    return picam2

def capture_faces(num_photos, when):
    '''
    촬영 시작 && 얼굴 탐지된 사진만 TEST_IMG_DIR에 저장
    '''
    #picam2 = PICAM2_QUEUE.get()
    picam2 = get_camera()

    # 현재 위치에 test_img 디렉터리가 있는지 확인하고, 없으면 생성
    if os.path.exists(TEST_IMG_DIR):
        shutil.rmtree(TEST_IMG_DIR)
    os.makedirs(TEST_IMG_DIR)

    #모델 가져오기
    while LOAD_MODEL_THREAD.is_alive():
        time.sleep(0.5)
    detector, _, _ = get_model()

    captured_faces = 1
    no_face = 1
    while captured_faces <= num_photos:

        # 카메라로 한 프레임 캡처
        request = picam2.capture_request(flush=True)
        #request.save("main", test_img_path)
        frame = request.make_array("main")
        request.release()

        # 얼굴 탐지
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = detector(gray)

        if faces:
        # 얼굴이 탐지된 경우
            test_img_path = os.path.join(TEST_IMG_DIR, f"{captured_faces}.jpg")
            cv2.imwrite(test_img_path, frame)
            print(f"Captured face {captured_faces}")

            if when == "before":        ##TODO
                send_data_with_tags(bluetooth, "FACE", f"EXTRACT/{captured_faces}")
                time.sleep(2)
            elif when == "after":
                pass
                #send_data_with_tags(bluetooth, "FACE", f"ACCESS/{captured_faces}")
                #time.sleep(0.5)

            captured_faces += 1

        else:
            if when == "before":
                no_face += 1
                if no_face == 10:
                    send_data_with_tags(bluetooth, "FACE", f"CAPTURE_BUT_NOFACE")
                    no_face = 1
            pass
            #print("no face")

    # 카메라 중지
    #picam2.stop()
    print("촬영 끝")

def send_data_with_tags(bluetooth, tag, data, retry_count=0):
    """_summary_
    보낼 데이터와 [tag]를 인자로 받아 태그를 데이터의 앞뒤로 붙혀
    블루투스로 전송을 해주는 함수

    전송 실패시 재전송

    블루투스가 자동으로 보낼 수 있는만큼 데이터를 보내기 때문에
    별도의 데이터를 나눠 보내는 기능 필요없음

    Args:
        bluetooth (_type_): _description_
        tag (_type_): _description_
        data (_type_): _description_
        retry_count (int, optional): _description_. Defaults to 0.
    """
    try:
        start_tag = f"<{tag}_start>".encode()
        end_tag = f"<{tag}_end>".encode()

        print(f"Sending data with tag: {tag}")
        bluetooth.write(start_tag)
        if isinstance(data, bytes):
            bluetooth.write(data)
        else:
            bluetooth.write(data.encode())
        bluetooth.write(end_tag)

        print(f"{tag.capitalize()} data sent successfully.")
    except Exception as e:
        print(f"Failed to send {tag} data: {e}")
        if retry_count < MAX_RETRY:
            print(f"Retrying to send {tag} data (Attempt {retry_count + 1}/{MAX_RETRY})...")
            time.sleep(RETRY_DELAY)
            send_data_with_tags(bluetooth, tag, data, retry_count + 1)
        else:
            print(f"Max retries reached for {tag}. Data could not be sent.")




def send_log_with_image(bluetooth, user_name, isValidAccess, matched_path):
    """
    Sends log data and converts image to RGB format for transmission.
    """
    try:

        # GPS 데이터 가져오기
        latitude = gps_data.get("latitude")
        longitude = gps_data.get("longitude")

        # Generate timestamp and photo file name
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
        photo_file_name = f"{DEVICEID}_{timestamp}.jpg"

        # Prepare JSON log data
        log_data = {
            "device_id": DEVICEID,
            "user_name": user_name,
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "longitude": longitude,
            "latitude": latitude,
            "isValidAccess": isValidAccess,
            "photo": photo_file_name
        }
        log_json = json.dumps(log_data)

        # Send log JSON
        send_data_with_tags(bluetooth, "log", log_json)
        time.sleep(0.5)

        # Read the image and convert BGR to RGB
        image_path = matched_path
        image = cv2.imread(image_path, cv2.IMREAD_COLOR)
        image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

        resized_image = cv2.resize(image_rgb, None, fx=SCALE_FACTOR_LOG_IMG, fy=SCALE_FACTOR_LOG_IMG, interpolation=cv2.INTER_AREA)

        # Convert image to float array
        image_float = resized_image.astype(np.float32).flatten().tolist()

        # Send the float array as JSON
        image_float_json = json.dumps(image_float)
        send_data_with_tags(bluetooth, "image", image_float_json)
    except Exception as e:
        print(f"Failed to send log and image: {e}")


def send_config(bluetooth):
    """_summary_
    편의성 정보를 생성

    ****TODO****
    마찬가지로 편의성 정보를 임의로 생성 중.
    상황에 따라 데이터를 생성하도록 수정해야함.
    Args:
        bluetooth (_type_): _description_
    """
    try:
        config_data = {
            "seat": 10,
            "sidemirror": 15
        }
        config_json = json.dumps(config_data)
        send_data_with_tags(bluetooth, "config", config_json)
    except Exception as e:
        print(f"Failed to send config data: {e}")

def send_vector(bluetooth, json_file_path):
    """
    vector 값을 생성하는 함수

    ****TODO****
    마찬가지로 벡터는 json 파일을 사용하여 전송중.
    이 부분도 변경된 사용자에 대한 벡터값을 보낼 수 있도록 적절히 수정 필요
    Args:
        bluetooth (_type_): _description_
    """
    try:
        # Read the JSON file containing vector data
        #json_file_path = os.path.expanduser("vector_json.json")  # Adjust path as needed
        with open(json_file_path, "r") as file:
            vector_data = json.load(file)  # Load the JSON data into a Python dictionary

        # Convert the dictionary to a JSON string
        vector_json = json.dumps(vector_data)

        # Send the JSON data with tags
        send_data_with_tags(bluetooth, "vector", vector_json)
        print(f"Vector data sent successfully. Size: {len(vector_json)} bytes")
    except Exception as e:
        print(f"Failed to send vector data: {e}")





# SQLite 데이터베이스 초기화
def initialize_database():
    connection = sqlite3.connect(DB_FILE)
    cursor = connection.cursor()

    # 테이블 생성
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL UNIQUE,
            added_at TEXT NOT NULL,
            motor1 INTEGER DEFAULT -1,
            motor2 INTEGER DEFAULT -1,
            motor3 INTEGER DEFAULT -1,
            motor4 INTEGER DEFAULT -1,
            motor5 INTEGER DEFAULT -1
        )
    ''')
    connection.commit()
    connection.close()
    print("Database initialized.")

# 데이터베이스에 사용자 추가
def save_user_to_database(user_id, motor_angles=None):
    try:
        connection = sqlite3.connect(DB_FILE)
        cursor = connection.cursor()

        # 현재 시간 기록
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        # 모터 각도 설정 (기본값 -1)
        if motor_angles is None or len(motor_angles) != 5:
            motor_angles = [-1, -1, -1, -1, -1]  # 5개로 고정

        # 사용자 저장
        cursor.execute('''
            INSERT OR IGNORE INTO users (user_id, added_at, motor1, motor2, motor3, motor4, motor5)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ''', (user_id, timestamp, *motor_angles))
        connection.commit()
        connection.close()
        print(f"User {user_id} with motors {motor_angles} saved to database.")
    except Exception as e:
        print(f"Failed to save user to database: {e}")

# 데이터베이스에서 사용자 삭제
def delete_user_from_database(user_id):
    try:
        connection = sqlite3.connect(DB_FILE)
        cursor = connection.cursor()
        cursor.execute('DELETE FROM users WHERE user_id = ?', (user_id,))
        connection.commit()
        connection.close()
        print(f"User {user_id} deleted from database.")
    except Exception as e:
        print(f"Failed to delete user from database: {e}")

def update_motor_data_in_database(user_id, motor_data):
    """
    change_details에서 받은 모터 데이터를 테이블에 업데이트
    """
    try:
        # changeDetails가 문자열로 전달된 경우 다시 JSON 파싱
        if isinstance(motor_data, str):
            motor_data = json.loads(motor_data)  # 문자열을 JSON으로 변환

        # motor_data 파싱
        motor1 = motor_data.get("sidemirror1", -1)
        motor2 = motor_data.get("sidemirror2", -1)
        motor3 = motor_data.get("seat1", -1)
        motor4 = motor_data.get("seat2", -1)
        motor5 = motor_data.get("seat3", -1)

        # 첫 번째 데이터베이스 연결: car_registration.db
        conn_car = sqlite3.connect("car_registration.db")
        cursor_car = conn_car.cursor()
        cursor_car.execute(
            "SELECT length_xl, length_seat, height_side, length_side_1, length_side_2 FROM car_dimensions ORDER BY id DESC LIMIT 1"
        )
        result = cursor_car.fetchone()
        if not result:
            print("Error: Required car dimensions not found in database.")
            return
        length_xl, length_seat, height_side, length_side_1, length_side_2 = result
        conn_car.close()

        # 두 번째 데이터베이스 연결: bluetooth_logs.db
        conn_logs = sqlite3.connect("bluetooth_logs.db")
        cursor_logs = conn_logs.cursor()

        # d 값 계산: motor5 + length_seat
        x = 7  # x는 상수

        # motor5 값을 각도(new_motor5_value)로 변환
        radius = 2  # radius 값 설정 (필요에 따라 변경)
        print(f"Motor5 : {motor5} ")
        new_motor5_value = ((motor5-length_xl) / (radius)) * (180 / math.pi)
        d1 = length_side_1 + 2 * ((math.pi / 180) * new_motor5_value) + length_seat

        new_motor5_value = round(new_motor5_value, 2)
        new_motor4_value = 180 - new_motor5_value

        # new_motor1_value 계산
        def equation(new_motor1):
            term1 = height_side / (
                 math.cos(math.radians(90 - 2 * (90 - new_motor1)) - math.atan(7 / d1))
            )
            return term1 - motor1

        initial_guess = 90
        new_motor1_value = fsolve(equation, initial_guess)[0]
        new_motor1_value = round(new_motor1_value, 2)

         # Beta 값 계산
        l1 = length_side_1 + 2 * ((math.pi / 180) * new_motor5_value) + length_seat
        beta = math.degrees(math.atan(l1 / length_side_2))  # Beta 각도 (도 단위 변환)

        # Alpha 값 계산: Zeta에서 Beta를 빼고 나누기 2
        alpha = (motor2 - beta) / 2  # motor2는 Zeta 값

        # Theta 값 계산: Alpha + 90
        new_motor2_value = alpha + 90
        new_motor2_value = round(new_motor2_value, 2)

        # users 테이블 업데이트
        cursor_logs.execute(
            '''
            UPDATE users
            SET motor1 = ?, motor2 = ?, motor3 = ?, motor4 = ?, motor5 = ?
            WHERE user_id = ?
            ''',
            (new_motor1_value, new_motor2_value, motor3, new_motor4_value, new_motor5_value, user_id)
        )
        conn_logs.commit()
        conn_logs.close()

        print(f"Motor data for user {user_id} updated successfully.")
        print(f"new_motor1: {new_motor1_value}, new_motor2: {new_motor2_value}, new_motor5: {new_motor5_value}")

    except Exception as e:
        print(f"Failed to update motor data: {e}")



# Encodings 데이터를 파일에 저장
def save_face_vector_to_file(face_vector):
    """
    face_vector 데이터를 텍스트 파일에 저장
    """
    try:
        # JSON 데이터를 텍스트 파일에 저장
        with open(ENCODINGS_DIR, "w") as file:
            file.write(json.dumps(face_vector, indent=4))
        print(f"Face vector data saved to {ENCODINGS_DIR}.")
    except Exception as e:
        print(f"Failed to save face vector to file: {e}")

def read_gps_data():
    """
    GPS 데이터를 읽어 전역 변수에 저장하는 스레드 함수
    """
    global gps_session, gps_data, gps_thread_running
    try:
        while gps_thread_running:
            if gps_session is not None:
                try:
                    report = gps_session.next()
                    if report['class'] == 'TPV':  # 위치 데이터
                        gps_data["latitude"] = getattr(report, 'lat', None)
                        gps_data["longitude"] = getattr(report, 'lon', None)
                    else:
                        gps_data["latitude"] = None
                        gps_data["longitude"] = None
                except Exception as e:
                    print(f"GPS 데이터 읽기 실패: {e}")
                    gps_data["latitude"] = None
                    gps_data["longitude"] = None
            time.sleep(1)  # 1초 대기
    except Exception as thread_error:
        print(f"GPS 스레드 오류: {thread_error}")





def load_model():
    detector = dlib.get_frontal_face_detector()
    shape_predictor = dlib.shape_predictor(f'{MODEL_DIR}/shape_predictor_68_face_landmarks.dat')
    face_recognizer = dlib.face_recognition_model_v1(f'{MODEL_DIR}/dlib_face_recognition_resnet_model_v1.dat')

    DETECTOR_QUEUE.put(detector)
    SHAPE_PREDICTOR_QUEUE.put(shape_predictor)
    FACE_RECOGNIZER_QUEUE.put(face_recognizer)

def get_model():
    detector =  DETECTOR_QUEUE.get()
    shape_predictor = SHAPE_PREDICTOR_QUEUE.get()
    face_recognizer = FACE_RECOGNIZER_QUEUE.get()

    DETECTOR_QUEUE.put(detector)
    SHAPE_PREDICTOR_QUEUE.put(shape_predictor)
    FACE_RECOGNIZER_QUEUE.put(face_recognizer)
    return detector, shape_predictor, face_recognizer

#모터 코드

def handle_motor_adjustment(bluetooth, user_id):
    """
    데이터베이스를 확인하고, 모터 값을 설정하거나 조정합니다.
    """
    # 데이터베이스에서 모터 값을 로드하고 상태 확인
    motor_angles, needs_adjustment = load_motor_angles_and_check(bluetooth, user_id)

    if not needs_adjustment:
        # 모터 값이 이미 설정된 경우: 저장된 값으로 모터를 설정 (데이터 전송 없음)
        print("Setting motors to saved angles.")
        for channel, angle in enumerate(motor_angles):
            set_servo_angle(channel, angle)
            print(f"Motor {channel + 1} set to {angle} degrees.")
    else:
        # 모터 값이 -1인 경우: 조정 단계로 이동
        print("Adjusting motor angles...")
        adjust_motor_angles(bluetooth)


def load_motor_angles(user_id):
    """
    주어진 사용자 ID에 대한 모터 값을 'users' 테이블에서 가져옵니다.
    """
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute("SELECT motor1, motor2, motor3, motor4, motor5 FROM users WHERE user_id = ?", (user_id,))
    result = cursor.fetchone()
    conn.close()
    return result  # (motor1, motor2, motor3, motor4, motor5)


def save_motor_angles(user_id, motor_angles):
    """
    주어진 사용자 ID에 대한 모터 값을 'users' 테이블에 저장합니다.
    """
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute('''
        UPDATE users
        SET motor1 = ?, motor2 = ?, motor3 = ?, motor4 = ?, motor5 = ?
        WHERE user_id = ?
    ''', (*motor_angles, user_id))
    conn.commit()
    conn.close()


# 서보 모터 제어 함수
def set_servo_angle(channel, angle):
    pulse_min = 0x0666  # 2.5% 듀티 사이클
    pulse_max = 0x199A  # 12.5% 듀티 사이클
    pulse_range = pulse_max - pulse_min
    pulse_width = int(pulse_min + (pulse_range * (angle / 180.0)))
    pwm.channels[channel].duty_cycle = pulse_width



def load_motor_angles_and_check(bluetooth, user_id):
    """
    주어진 사용자 ID에 대한 모터 값을 로드하고,
    값이 -1인 경우 모터 조정을 시작하며,
    그렇지 않은 경우 이미 존재한다고 Bluetooth로 메시지를 전송합니다.
    """
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute("SELECT motor1, motor2, motor3, motor4, motor5 FROM users WHERE user_id = ?", (user_id,))
    result = cursor.fetchone()
    conn.close()

    if result:
        motor_angles = list(result)
        if all(angle != -1 for angle in motor_angles):
            # 모든 모터 값이 -1이 아닌 경우
            print("Motor angles already exist in the database.")
            send_data_with_tags(bluetooth, "already_exist", "Motor angles are already set.")
            return motor_angles, False  #//해당값으로 조정
        else:
            # -1 값이 있는 경우 조정 필요
            print("Motor angles need adjustment.")
            return motor_angles, True  #//조정하는 함수로

def adjust_motor_angles(bluetooth):
    global current_angle_1, current_angle_2, current_angle_3, current_angle_4, current_angle_5
    global user_id
    # 모터 조정 루프 시작
    print("Start adjusting motor angles.")
    active_channel = 0
    mode_presses = 0
    last_button_press_time = 0

    while mode_presses < 3:
        if GPIO.input(button_pin_17) == GPIO.HIGH:
            current_time = time.time()
            if current_time - last_button_press_time > 1:  # 1초 딜레이
                mode_presses += 1
                if mode_presses == 1:
                    active_channel = 1
                    print("Start adjusting Motors 1 and 2.")
                    send_data_with_tags(bluetooth, "mode", "one")
                elif mode_presses == 2:
                    active_channel = 2
                    print("Start adjusting Motors 3, 4, and 5.")
                    send_data_with_tags(bluetooth, "mode", "two")
                elif mode_presses == 3:
                    print("Finalizing settings...")
                    send_data_with_tags(bluetooth, "mode", "three")
                    break
                last_button_press_time = current_time

        if active_channel == 1:  # 모터 1, 2 조정
            if GPIO.input(button_pin_18) == GPIO.HIGH and current_angle_1 < 180:
                current_angle_1 += 2
                set_servo_angle(0, current_angle_1)
                print(f"Motor 1 (Channel 0) angle: {current_angle_1} degrees")
                time.sleep(0.02)

            if GPIO.input(button_pin_23) == GPIO.HIGH and current_angle_1 > 0:
                current_angle_1 -= 2
                set_servo_angle(0, current_angle_1)
                print(f"Motor 1 (Channel 0) angle: {current_angle_1} degrees")
                time.sleep(0.02)

            if GPIO.input(button_pin_24) == GPIO.HIGH and current_angle_2 < 180:
                current_angle_2 += 2
                set_servo_angle(1, current_angle_2)
                print(f"Motor 2 (Channel 1) angle: {current_angle_2} degrees")
                time.sleep(0.02)

            if GPIO.input(button_pin_25) == GPIO.HIGH and current_angle_2 > 0:
                current_angle_2 -= 2
                set_servo_angle(1, current_angle_2)
                print(f"Motor 2 (Channel 1) angle: {current_angle_2} degrees")
                time.sleep(0.02)

        elif active_channel == 2:  # 모터 3, 4, 5 조정
            if GPIO.input(button_pin_18) == GPIO.HIGH and current_angle_3 < 180:
                current_angle_3 += 2
                set_servo_angle(2, current_angle_3)
                print(f"Motor 3 (Channel 2) angle: {current_angle_3} degrees")
                time.sleep(0.02)

            if GPIO.input(button_pin_23) == GPIO.HIGH and current_angle_3 > 0:
                current_angle_3 -= 2
                set_servo_angle(2, current_angle_3)
                print(f"Motor 3 (Channel 2) angle: {current_angle_3} degrees")
                time.sleep(0.02)

            if GPIO.input(button_pin_24) == GPIO.HIGH and current_angle_4 < 180 and current_angle_5 > 0:
                current_angle_4 += 2
                current_angle_5 -= 2
                set_servo_angle(3, current_angle_4)
                set_servo_angle(4, current_angle_5)
                print(f"Motor 4 (Channel 3) angle: {current_angle_4} degrees")
                print(f"Motor 5 (Channel 4) angle: {current_angle_5} degrees")
                time.sleep(0.02)

            if GPIO.input(button_pin_25) == GPIO.HIGH and current_angle_4 > 0 and current_angle_5 < 180:
                current_angle_4 -= 2
                current_angle_5 += 2
                set_servo_angle(3, current_angle_4)
                set_servo_angle(4, current_angle_5)
                print(f"Motor 4 (Channel 3) angle: {current_angle_4} degrees")
                print(f"Motor 5 (Channel 4) angle: {current_angle_5} degrees")
                time.sleep(0.02)

    # 최종 모터 각도 저장
    motor_angles = [current_angle_1, current_angle_2, current_angle_3, current_angle_4, current_angle_5]
    save_motor_angles(user_id, motor_angles)
    # 딜레이 추가
    time.sleep(0.1)  # 1초 대기
    update_and_send_motor(bluetooth, user_id)
    print(f"Motor angles for user {user_id} saved and sent to phone: {motor_angles}")


def update_and_send_motor(bluetooth, user_id):
    """
    데이터베이스에서 필요한 값을 가져와 motor1과 motor5를 계산 후 전송
    """
    try:
        # 두 개의 데이터베이스 연결
        conn_car = sqlite3.connect("car_registration.db")
        cursor_car = conn_car.cursor()

        conn_users = sqlite3.connect("bluetooth_logs.db")
        cursor_users = conn_users.cursor()

        # car_dimensions 값 가져오기
        cursor_car.execute("SELECT length_xl, length_seat, height_side, length_side_1, length_side_2 FROM car_dimensions ORDER BY id DESC LIMIT 1")
        result = cursor_car.fetchone()
        if not result:
            print("Error: Missing car dimensions in the database.")
            return
        length_xl, length_seat, height_side, length_side1, length_side2 = result

        # users 테이블에서 motor5 값 가져오기
        cursor_users.execute("SELECT motor5, motor2 FROM users WHERE user_id = ?", (user_id,))
        motor_result = cursor_users.fetchone()
        if not motor_result:
            print("Error: Missing motor5 or motor2 value for the user in database.")
            return
        motor5_value = motor_result[0]
        motor2_value = motor_result[1]  # motor2 값 가져오기

        # motor1 값 가져오기
        cursor_users.execute("SELECT motor1 FROM users WHERE user_id = ?", (user_id,))
        motor1_result = cursor_users.fetchone()
        motor1_value = motor1_result[0] if motor1_result else 0

        # motor5 호의 길이 계산
        new_motor5_value = length_xl + 2 * ((math.pi / 180) * motor5_value)

        # d1 값 계산
        d1 = length_side1 + 2 * ((math.pi / 180) * motor5_value) + length_seat

        # 새로운 motor1 값 계산
        numerator = height_side
        denominator = math.cos(math.radians(90 - 2 * (90 - motor1_value)) - math.atan(7 / d1))
        if denominator == 0:
            print("Error: Division by zero while calculating motor1 value.")
            return
        new_motor1_value = numerator / denominator

        # motor2 값 계산
        l1 = length_side1 + 2 * ((math.pi / 180) * motor5_value) + length_seat
        l2 = length_side2
        beta = math.degrees(math.atan(l1 / l2))
        alpha = motor2_value - 90
        new_motor2_value = 2 * alpha + beta

        # 모터 데이터 JSON으로 전송
        motor_data_json = json.dumps({
            "motor_1": round(new_motor1_value, 2),
            "motor_2": round(new_motor2_value, 2),
            "motor_3": current_angle_3,
            "motor_4": current_angle_4,
            "motor_5": round(new_motor5_value, 2)
        })
        send_data_with_tags(bluetooth, "motor_angles", motor_data_json)

        print(f"Sent updated motor1: {new_motor1_value:.2f}, motor2: {new_motor2_value:.2f}, and motor5: {new_motor5_value:.2f} to Raspberry Pi.")

    except Exception as e:
        print(f"Error updating and sending motor values: {e}")
    finally:
        # 두 데이터베이스 연결 닫기
        conn_car.close()
        conn_users.close()






def play_siren(duration=3):
    """
    사이렌 소리를 일정 시간 동안 울리는 함수.

    Args:
        duration (int): 사이렌 울릴 시간 (초). 기본값은 3초.
    """
    try:
        # GPIO 핀 설정
        buzzer_pin = 13  # GPIO 핀 번호
        GPIO.setup(buzzer_pin, GPIO.OUT)

        # PWM 객체 생성
        pwm = GPIO.PWM(buzzer_pin, 1000)
        pwm.start(50)  # 듀티 사이클 50%

        # 사이렌 효과
        start_time = time.time()
        while time.time() - start_time < duration:
            # 주파수 증가
            for freq in range(1000, 2000, 10):
                pwm.ChangeFrequency(freq)
                time.sleep(0.01)
            # 주파수 감소
            for freq in range(2000, 1000, -10):
                pwm.ChangeFrequency(freq)
                time.sleep(0.01)
    except Exception as e:
        print(f"Error in playing siren: {e}")
    finally:
        pwm.stop()  # PWM 중지
        GPIO.cleanup(buzzer_pin)  # 해당 핀만 초기화




if __name__ == "__main__":
    try:
        # Bluetooth agent 실행
        bt_agent_command = ["sudo", "bt-agent", "-c", "DisplayOnly", "-p", "~/pins.txt"]
        subprocess.Popen(bt_agent_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        print("Bluetooth agent started successfully.")
    except Exception as e:
        print(f"Failed to start Bluetooth agent: {e}")

    initialize_database()
    initialize_car_registration_database()

    # 모드 버튼 클릭 대기 함수
    def wait_for_mode_button_press():
        print("Waiting for mode button press...")
        while True:
            if GPIO.input(button_pin_17) == GPIO.HIGH:  # 모드 버튼이 눌렸는지 확인
                time.sleep(0.3)  # 버튼 bouncing 방지
                # 버튼이 여전히 눌려 있는지 확인 (디바운싱 처리)
                if GPIO.input(button_pin_17) == GPIO.HIGH:
                    print("Mode button pressed. Proceeding to the next step.")
                    return  # 버튼이 눌리면 함수 종료
        time.sleep(0.1)  # CPU 과부하 방지용 딜레이

    wait_for_mode_button_press()
    # GPS 세션 초기화
    try:
        gps_session = gps.gps(mode=gps.WATCH_ENABLE)
        gps_session.stream(gps.WATCH_ENABLE | gps.WATCH_NEWSTYLE)
        print("GPS 세션이 시작되었습니다.")
    except Exception as e:
        gps_session = None
        print(f"GPS 초기화 실패: {e}")

    # GPS 데이터 읽기 스레드 시작
    gps_thread = threading.Thread(target=read_gps_data, daemon=True)
    gps_thread.start()

    s1 = time.time()
    rfcomm_process = start_bluetooth_watch()
    LOAD_MODEL_THREAD = threading.Thread(target=load_model, daemon=True)
    LOAD_MODEL_THREAD.start()

    print(time.time() - s1)
    CAMERA_THREAD = threading.Thread(target=camera, daemon=True)
    CAMERA_THREAD.start()


    if wait_for_connection():
        bluetooth = connect_to_bluetooth()

        if bluetooth is not None:
            # 데이터 수신 감지 스레드 시작
            threading.Thread(target=listen_for_data, args=(bluetooth,), daemon=True).start()

            while True:
                print("\nAvailable commands:")
                print("1. send_log - Send log data with image")
                print("2. send_vector - Send face vector data")
                print("3. send_usersmeta - Send user convenience data")
                print("4. Exit - Any other command to exit")

                # 모드 버튼 입력 대기
                wait_for_mode_button_press()  # 다음 단계로 넘어가기 전에 버튼 입력 대기

                command = input("Enter your command: ").strip().lower()

                if command == "send_log":
                    pass
                    # send_log_with_image(bluetooth)
                elif command == "send_vector":
                    # send_vector(bluetooth)
                    print("")
                elif command == "send_usersmeta":
                    send_config(bluetooth)
                else:
                    print("Exiting...")
                    break

            bluetooth.close()
            print("Bluetooth connection closed.")

            # 인증 시도로 촬영한 사진 삭제
            if os.path.exists(TEST_IMG_DIR):
                shutil.rmtree(TEST_IMG_DIR)
            # 카메라 off
            picam2 = PICAM2_QUEUE.get()
            picam2.stop()

    # GPS 스레드 종료
    gps_thread_running = False
    gps_thread.join()

    if rfcomm_process:
        rfcomm_process.terminate()
        print("Bluetooth watch terminated.")
