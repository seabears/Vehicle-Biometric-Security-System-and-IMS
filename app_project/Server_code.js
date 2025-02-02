const express = require('express');
const mysql = require('mysql2');
const bodyParser = require('body-parser');
const path = require('path');  // path 모듈 추가
const multer = require('multer');
const app = express();
const morgan = require('morgan');

//파이어베이스용
const admin = require('firebase-admin');
const serviceAccount = require('./firebase-service-account.json');

admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
});
///////////

// morgan을 사용해 로그를 출력 (요청 메서드, URL, 상태 코드, 응답 시간)
app.use(morgan('combined'));

// 사용자 정의 미들웨어로 상세한 요청 로그 출력
app.use((req, res, next) => {
    const now = new Date();
    const kstDate = new Date(now.getTime() + 9 * 60 * 60 * 1000); // UTC + 9시간 (KST)
    const formattedTime = kstDate.toISOString().replace('T', ' ').slice(0, 19); // `YYYY-MM-DD HH:MM:SS` 형식

    console.log(`[${formattedTime}] ${req.method} ${req.url} - IP: ${req.ip}`);
    next(); // 다음 미들웨어로 이동
});







app.use(bodyParser.json()); // JSON 형식의 요청을 처리

// 'image' 폴더를 공개(public) 디렉토리로 설정
app.use('/images', express.static(path.join(__dirname, 'images')));


// MySQL 연결 설정
const db = mysql.createConnection({
    host: 'localhost',
    user: 'root',         // MySQL 사용자 이름
    password: 'xxxxxxxxxxx', // MySQL 비밀번호
    database: 'project',    // 사용할 데이터베이스 이름
    supportBigNumbers: true,
    stringifyObjects: false,
});


// MySQL 연결
db.connect((err) => {
    if (err) throw err;
    console.log('MySQL에 연결되었습니다.');
});


app.post('/sendDeviceAlert', (req, res) => {
    const { deviceId, isValidAccess } = req.body;

    if (isValidAccess !== 0) {
        return res.status(400).json({ success: false, message: 'Access is valid. No alert needed.' });
    }

    // `device_details` 테이블에서 `device_id`를 기준으로 `root` 관리자 ID 조회
    const queryRoot = 'SELECT root FROM device_details WHERE device_id = ?';
    db.query(queryRoot, [deviceId], (err, rootResults) => {
        if (err || rootResults.length === 0) {
            return res.status(500).json({ success: false, message: 'Failed to find device manager' });
        }

        const rootUserId = rootResults[0].root;

        // 관리자의 FCM 토큰 조회
        const queryToken = 'SELECT token FROM fcm_tokens WHERE user_id = ?';
        db.query(queryToken, [rootUserId], (err, tokenResults) => {
            if (err || tokenResults.length === 0) {
                return res.status(500).json({ success: false, message: 'Failed to retrieve manager token' });
            }

            const token = tokenResults[0].token;

            // 알림 데이터 생성
            const title = '비정상 접근 알림';
            const body = `Device ID: ${deviceId}에서 비정상 접근이 감지되었습니다.`;

            // 알림 전송
            sendNotification(token, title, body);

            res.json({ success: true, message: 'Notification sent to manager' });
        });
    });
});

app.post('/saveToken', (req, res) => {
    const { userId, token } = req.body;

    const query = `
        INSERT INTO fcm_tokens (user_id, token)
        VALUES (?, ?)
        ON DUPLICATE KEY UPDATE token = ?;
    `;

    db.query(query, [userId, token, token], (err, results) => {
        if (err) {
            return res.status(500).json({ success: false, message: 'Failed to save token' });
        }
        res.json({ success: true, message: 'Token saved successfully' });
    });
});






app.post('/autoLogin', (req, res) => {
    const { userId, androidId } = req.body;

    const query = 'SELECT user_name FROM users WHERE user_id = ? AND android_id = ?';
    db.query(query, [userId, androidId], (err, results) => {
        if (err || results.length === 0) {
            return res.status(401).json({ success: false, message: '자동 로그인 실패' });
        }

        res.json({ success: true, name: results[0].user_name, message: '자동 로그인 성공' });
    });
});


// 로그인 엔드포인트
app.post('/login', (req, res) => {
    const { userId, password, androidId } = req.body;

    const query = 'SELECT user_name FROM users WHERE user_id = ? AND password = ?';
    db.query(query, [userId, password], (err, results) => {
        if (err) {
            return res.status(500).json({ success: false, message: '서버 오류가 발생했습니다.' });
        }

        if (results.length > 0) {
            // Android ID 저장
            const updateQuery = 'UPDATE users SET android_id = ? WHERE user_id = ?';
            db.query(updateQuery, [androidId, userId], (updateErr) => {
                if (updateErr) {
                    return res.status(500).json({ success: false, message: 'Android ID 저장 실패' });
                }
                res.json({ success: true, name: results[0].user_name, message: '로그인 성공' });
            });
        } else {
            res.json({ success: false, message: '아이디 또는 비밀번호가 잘못되었습니다.' });
        }
    });
});



app.post('/signup', (req, res) => {
    const { userId, password, userName } = req.body;

    // 중복된 사용자 체크
    const queryCheck = 'SELECT * FROM users WHERE user_id = ?';
    db.query(queryCheck, [userId], (err, results) => {
        if (err) throw err;
        
        if (results.length > 0) {
            res.json({ success: false, message: '이미 존재하는 ID입니다.' });
        } else {
            // 사용자 정보 저장
            const query = 'INSERT INTO users (user_id, password, user_name) VALUES (?, ?, ?)';
            db.query(query, [userId, password, userName], (err, result) => {
                if (err) throw err;
                res.json({ success: true, message: '회원가입 성공' });
            });
        }
    });
});


app.post('/getcarlistofuser', (req, res) => {
    const userId = req.body.userId;

    if (!userId) {
        return res.status(400).json({ success: false, message: 'User ID가 필요합니다.' });
    }

    // 해당 device_id에 대한 car_model, car_number와 root 정보를 가져오는 쿼리
    const query = `
        SELECT device_users.device_id, device_details.car_model, device_details.car_number, device_details.root
        FROM device_users
        LEFT JOIN device_details ON device_users.device_id = device_details.device_id
        WHERE device_users.user_id = ?;
    `;
    
    db.query(query, [userId], (err, results) => {
        if (err) throw err;
        res.json(results);
    });
});


app.post('/getuserlistofdevice', (req, res) => {
    const deviceId = req.body.deviceId; // 클라이언트에서 전송한 deviceId를 받음

    if (!deviceId) {
        return res.status(400).json({ success: false, message: 'Device ID가 필요합니다.' });
    }

    // 해당 device_id에 등록된 사용자 목록과 root(관리자) 정보 가져오기
    const query = `
        SELECT users.user_id, users.user_name, device_details.root
        FROM users
        JOIN device_users ON users.user_id = device_users.user_id
        JOIN device_details ON device_users.device_id = device_details.device_id
        WHERE device_users.device_id = ?;
    `;

    db.query(query, [deviceId], (err, results) => {
        if (err) throw err;

        // results 배열의 모든 요소에 대해 변환 작업을 수행
        const formattedResults = results.map(temp => ({
            id: temp.user_id,
            name: temp.user_name,
            root: temp.root
        }));

        // 변환된 배열을 반환
        res.json(formattedResults);
    });
});


app.post('/getloglist', (req, res) => {
    const deviceId = req.body.deviceId; // 클라이언트에서 전송한 deviceId

    if (!deviceId) {
        return res.status(400).json({ success: false, message: 'Device ID가 필요합니다.' });
    }

    // 해당 device_id에 대한 사용 기록을 가져오는 쿼리
    const query = `
        SELECT user_name, timestamp, isValidAccess
        FROM usagelog
        WHERE device_id = ?;
    `;

    db.query(query, [deviceId], (err, results) => {
        if (err) throw err;

        // results 배열의 모든 요소에 대해 변환 작업을 수행
        const formattedResults = results.map(log => ({
            user: log.user_name,
            timestamp: log.timestamp,
            isValidAccess: log.isValidAccess
        }));

        // 변환된 배열을 반환
        res.json(formattedResults);
    });
});




app.post('/logdetails', (req, res) => {
    const { deviceId, timestamp } = req.body;

    const query = `
        SELECT user_name, latitude, longitude, isValidAccess, photo 
        FROM usagelog 
        WHERE device_id = ? AND timestamp = ?
    `;

    db.query(query, [deviceId, timestamp], (err, results) => {
        if (err) {
            return res.status(500).json({ success: false, message: 'Database query failed' });
        }

        if (results.length > 0) {
            const log = results[0];
            res.json({
                user: log.user_name,
                latitude: log.latitude,
                longitude: log.longitude,
                isValidAccess: log.isValidAccess === 1,  // boolean 처리
                photoUrl: `/images/${log.photo}`  // 서버의 사진 URL 경로
            });
        } else {
            res.status(404).json({ success: false, message: 'Log not found' });
        }
    });
});



// 사용자 존재 여부 확인
app.post('/checkUserExists', (req, res) => {
    const { userId } = req.body;

    const query = 'SELECT COUNT(*) AS count FROM users WHERE user_id = ?';
    db.query(query, [userId], (err, results) => {
        if (err) {
            console.error('Error checking user existence:', err);
            return res.status(500).json({ success: false, message: 'Database query failed' });
        }

        // 사용자 존재 여부 반환
        const userExists = results[0].count > 0;
        res.json({ exists: userExists });
    });
});


// 사용자 추가 엔드포인트
app.post('/addUserToDevice', (req, res) => {
    const { deviceId, userId } = req.body;

    // users 테이블에서 user_id 확인
    const userCheckQuery = 'SELECT COUNT(*) AS count FROM users WHERE user_id = ?';
    db.query(userCheckQuery, [userId], (err, userResults) => {
        if (err) {
            console.error('Error checking user existence:', err);
            return res.status(500).json({ success: false, message: 'Database query failed' });
        }

        // 사용자 존재 확인
        if (userResults[0].count === 0) {
            return res.status(404).json({ success: false, message: 'User ID does not exist' });
        }

        // 사용자와 장치를 device_users 테이블에 추가
        const addUserQuery = 'INSERT INTO device_users (device_id, user_id) VALUES (?, ?)';
        db.query(addUserQuery, [deviceId, userId], (err, results) => {
            if (err) {
                console.error('Error adding user to device:', err);
                return res.status(500).json({ success: false, message: 'Database insert failed' });
            }

            // 변경 로그 생성
            const logQuery = `
                INSERT INTO change_logs (user_id, device_id, change_type, change_details, synced)
                VALUES (?, ?, 'ADD_USER', ?, 0)
            `;
            const changeDetails = JSON.stringify({ userId });
            db.query(logQuery, [userId, deviceId, changeDetails], (err, logResults) => {
                if (err) {
                    console.error('Error logging change:', err);

                    // 롤백: 변경 로그 생성 실패 시 device_users에서도 추가 취소
                    const rollbackQuery = 'DELETE FROM device_users WHERE device_id = ? AND user_id = ?';
                    db.query(rollbackQuery, [deviceId, userId], (rollbackErr) => {
                        if (rollbackErr) {
                            console.error('Error rolling back user addition:', rollbackErr);
                        }
                        return res.status(500).json({ success: false, message: 'Log creation failed. Rollback executed.' });
                    });
                } else {
                    // Vector와 Config 데이터를 확인하고 변경 로그 추가
                    handleVectorAndConfigLogs(userId, deviceId, res);
                }
            });
        });
    });
});

// Vector와 Config 데이터 확인 및 변경 로그 추가
function handleVectorAndConfigLogs(userId, deviceId, res) {
    // users_meta에서 Vector와 Config 데이터를 조회
    const getUserMetaQuery = `
        SELECT face_vector, sidemirror1, sidemirror2, seat1, seat2, seat3
        FROM users_meta
        WHERE user_id = ?
    `;

    db.query(getUserMetaQuery, [userId], (err, userMetaResults) => {
        if (err) {
            console.error('Error fetching user meta data:', err);
            return res.status(500).json({ success: false, message: 'Failed to fetch user meta data.' });
        }

        if (userMetaResults.length === 0) {
            return res.json({ success: true, message: 'User added to device successfully. No vector or config data found.' });
        }

        const userMeta = userMetaResults[0];
        let logsToInsert = [];

        // Vector 데이터가 있는 경우
        if (userMeta.face_vector) {
            const vectorLogDetails = JSON.stringify({ face_vector: userMeta.face_vector });
            logsToInsert.push(['UPDATE_VECTOR', vectorLogDetails]);
        }

        // Config 데이터가 있는 경우
        if (
            userMeta.sidemirror1 !== null ||
            userMeta.sidemirror2 !== null ||
            userMeta.seat1 !== null ||
            userMeta.seat2 !== null ||
            userMeta.seat3 !== null
        ) {
            const configLogDetails = JSON.stringify({
                sidemirror1: userMeta.sidemirror1,
                sidemirror2: userMeta.sidemirror2,
                seat1: userMeta.seat1,
                seat2: userMeta.seat2,
                seat3: userMeta.seat3
            });
            logsToInsert.push(['UPDATE_CONFIG', configLogDetails]);
        }

        if (logsToInsert.length === 0) {
            return res.json({ success: true, message: 'User added to device successfully. No vector or config data to log.' });
        }

        // 변경 로그 추가
        let completed = 0;
        logsToInsert.forEach(([changeType, changeDetails]) => {
            const logQuery = `
                INSERT INTO change_logs (user_id, device_id, change_type, change_details, synced)
                VALUES (?, ?, ?, ?, 0)
            `;
            db.query(logQuery, [userId, deviceId, changeType, changeDetails], (logErr) => {
                if (logErr) {
                    console.error(`Error adding ${changeType} log:`, logErr);
                }
                completed++;
                if (completed === logsToInsert.length) {
                    res.json({ success: true, message: 'User added to device successfully and additional logs created.' });
                }
            });
        });
    });
}


// 사용자 제거 엔드포인트
app.post('/deleteUserFromDevice', (req, res) => {
    const { deviceId, userId } = req.body;

    if (!deviceId || !userId) {
        return res.status(400).json({ success: false, message: 'Device ID와 User ID가 필요합니다.' });
    }

    const deleteUserQuery = 'DELETE FROM device_users WHERE device_id = ? AND user_id = ?';
    db.query(deleteUserQuery, [deviceId, userId], (err, results) => {
        if (err) {
            console.error('Error deleting user from device:', err);
            return res.status(500).json({ success: false, message: 'Database delete failed' });
        }

        if (results.affectedRows === 0) {
            return res.status(404).json({ success: false, message: 'User not found for the device' });
        }

        // 변경 로그 생성
        const logQuery = `
            INSERT INTO change_logs (user_id, device_id, change_type, change_details, synced)
            VALUES (?, ?, 'DELETE_USER', ?, 0)
        `;
        const changeDetails = JSON.stringify({ userId });
        db.query(logQuery, [userId, deviceId, changeDetails], (err, logResults) => {
            if (err) {
                console.error('Error logging change:', err);

                // 롤백: 변경 로그 생성 실패 시 device_users에서 제거 취소
                const rollbackQuery = 'INSERT INTO device_users (device_id, user_id) VALUES (?, ?)';
                db.query(rollbackQuery, [deviceId, userId], (rollbackErr) => {
                    if (rollbackErr) {
                        console.error('Error rolling back user deletion:', rollbackErr);
                    }
                    return res.status(500).json({ success: false, message: 'Log creation failed. Rollback executed.' });
                });
            } else {
                res.json({ success: true, message: 'User deleted from device successfully and log created.' });
            }
        });
    });
});




app.get('/getCarTypes', (req, res) => {
    const query = 'SELECT car_model FROM car_details';
    db.query(query, (err, results) => {
        if (err) return res.status(500).json({ success: false, message: 'Database error' });

        const carTypes = results.map(row => row.car_model);
        res.json(carTypes); // 차량 모델 리스트 반환
    });
});


app.post('/saveDevice', (req, res) => {
    const { deviceId, carNumber, carModel, root } = req.body;

    if (!deviceId || !carNumber || !carModel || !root) {
        return res.status(400).json({ success: false, message: '모든 필드를 입력해야 합니다.' });
    }

    // 첫 번째 쿼리: device_details에 추가
    const insertDeviceDetailsQuery = `
        INSERT INTO device_details (device_id, car_number, car_model, root)
        VALUES (?, ?, ?, ?)
    `;

    db.query(insertDeviceDetailsQuery, [deviceId, carNumber, carModel, root], (err, result) => {
        if (err) {
            return res.status(500).json({ success: false, message: 'Database error: device_details' });
        }

        // 두 번째 쿼리: device_users에 user_id와 device_id 추가
        const insertDeviceUsersQuery = `
            INSERT INTO device_users (user_id, device_id)
            VALUES (?, ?)
        `;

        db.query(insertDeviceUsersQuery, [root, deviceId], (err, result) => {
            if (err) {
                return res.status(500).json({ success: false, message: 'Database error: device_users' });
            }

            // 세 번째 쿼리: car_details에서 height와 width 조회
            const carDetailsQuery = `
                SELECT length_side_1, length_side_2, length_xl, length_seat, height_side
                FROM car_details
                WHERE car_model = ?
            `;

            db.query(carDetailsQuery, [carModel], (err, carDetailsResult) => {
                if (err || carDetailsResult.length === 0) {
                    return res.status(500).json({ success: false, message: 'Database error: car_details or no matching car_model' });
                }

                const { length_side_1, length_side_2, length_xl, length_seat, height_side } = carDetailsResult[0];

                // 네 번째 쿼리: change_logs에 기록 추가
                const insertChangeLogQuery = `
                    INSERT INTO change_logs (user_id, device_id, change_type, change_details, synced)
                    VALUES (?, ?, 'CAR_REGIST', ?, 0)
                `;
                const changeDetails = JSON.stringify({ length_side_1, length_side_2, length_xl, length_seat, height_side });

                db.query(insertChangeLogQuery, [root, deviceId, changeDetails], (err, logResult) => {
                    if (err) {
                        return res.status(500).json({ success: false, message: 'Database error: change_logs' });
                    }

                    res.json({ success: true, message: '장치가 성공적으로 등록되고 로그가 기록되었습니다.' });
                });
            });
        });
    });
});




//변경로그 구현부
app.post('/getSyncData', (req, res) => {
    const { deviceId } = req.body;

    if (!deviceId) {
        return res.status(400).json({ success: false, message: 'Device ID가 필요합니다.' });
    }

    const query = `
        SELECT id AS changeId, device_id AS deviceId, user_id AS userId, change_type AS changeType, change_details AS changeDetails,synced
        FROM change_logs
        WHERE device_id = ? AND synced = 0
        ORDER BY timestamp ASC
    `;
    db.query(query, [deviceId], (err, results) => {
        if (err) {
            console.error('Error fetching sync data:', err);
            return res.status(500).json({ success: false, message: '데이터베이스 오류' });
        }

        res.json({ success: true, data: results });
    });
});


app.post('/markAsSynced', (req, res) => {
    const { deviceId } = req.body;

    if (!deviceId) {
        return res.status(400).json({ success: false, message: 'Device ID가 필요합니다.' });
    }

    const query = `
        UPDATE change_logs
        SET synced = 1
        WHERE device_id = ? AND synced = 0
    `;
    db.query(query, [deviceId], (err, result) => {
        if (err) {
            console.error('Error marking logs as synced:', err);
            return res.status(500).json({ success: false, message: '데이터베이스 오류' });
        }

        res.json({ success: true, message: '동기화 상태가 업데이트되었습니다.' });
    });
});








app.post('/uploadLog', (req, res) => {
    const log_data = req.body;

    if (!log_data) {
        return res.status(400).json({ success: false, message: 'Missing log data.' });
    }

    const { device_id, user_name, timestamp, longitude, latitude, isValidAccess, photo } = log_data;

    const query = `
        INSERT INTO usagelog (device_id, user_name, timestamp, longitude, latitude, isValidAccess, photo)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    `;

    // 로그 데이터 저장
    db.query(query, [device_id, user_name, timestamp, longitude, latitude, isValidAccess, photo], (err, results) => {
        if (err) {
            console.error('Error inserting log:', err);
            return res.status(500).json({ success: false, message: 'Failed to insert log data.' });
        }

        // 알림을 보낼 조건 확인
        if (isValidAccess === 0) {
            sendDeviceAlert(device_id, res);
        } else {
            res.json({ success: true, message: 'Log data saved successfully. No alert needed.' });
        }
    });
});

function sendDeviceAlert(deviceId, res) {
    // `device_details` 테이블에서 `device_id`를 기준으로 `root` 관리자 ID 조회
    const queryRoot = 'SELECT root FROM device_details WHERE device_id = ?';
    db.query(queryRoot, [deviceId], (err, rootResults) => {
        if (err || rootResults.length === 0) {
            console.error('Failed to find device manager:', err);
            return res.status(500).json({ success: false, message: 'Failed to find device manager.' });
        }

        const rootUserId = rootResults[0].root;

        // 관리자의 FCM 토큰 조회
        const queryToken = 'SELECT token FROM fcm_tokens WHERE user_id = ?';
        db.query(queryToken, [rootUserId], (err, tokenResults) => {
            if (err || tokenResults.length === 0) {
                console.error('Failed to retrieve manager token:', err);
                return res.status(500).json({ success: false, message: 'Failed to retrieve manager token.' });
            }

            const token = tokenResults[0].token;

            // 알림 데이터 생성
            const title = '비정상 접근 알림';
            const body = `Device ID: ${deviceId}에서 비정상 접근이 감지되었습니다.`;

            // 알림 전송
            sendNotification(token, title, body, res);
        });
    });
}

const sendNotification = (token, title, body, res) => {
    const message = {
        notification: {
            title: title,
            body: body,
        },
        token: token,
    };

    admin.messaging().send(message)
        .then((response) => {
            console.log('Successfully sent message:', response);
            res.json({ success: true, message: 'Log data saved and notification sent to manager.' });
        })
        .catch((error) => {
            console.error('Error sending message:', error);
            res.status(500).json({ success: false, message: 'Log data saved, but failed to send notification.' });
        });
};

// Vector 데이터 업데이트 및 변경 로그 추가
app.post('/uploadVector', (req, res) => {
    const { user_id, device_id, face_vector } = req.body;

    if (!user_id || !device_id || !face_vector) {
        return res.status(400).json({ success: false, message: 'Missing required vector data.' });
    }

    // Vector 데이터 업데이트
    const updateVectorQuery = `
        INSERT INTO users_meta (user_id, face_vector)
        VALUES (?, ?)
        ON DUPLICATE KEY UPDATE 
        face_vector = VALUES(face_vector)
    `;

    db.query(updateVectorQuery, [user_id, face_vector], (err, updateResults) => {
        if (err) {
            console.error('Error updating face vector:', err);
            return res.status(500).json({ success: false, message: 'Failed to update face vector data.' });
        }

        // 사용자가 등록된 모든 device_id 조회
        const getDevicesQuery = `
            SELECT device_id 
            FROM device_users 
            WHERE user_id = ?
        `;

        db.query(getDevicesQuery, [user_id], (err, deviceResults) => {
            if (err) {
                console.error('Error fetching devices:', err);
                return res.status(500).json({ success: false, message: 'Failed to fetch devices for user.' });
            }

            const deviceIds = deviceResults.map(row => row.device_id);

            // 현재 업데이트 중인 device_id 제외
            const otherDevices = deviceIds.filter(id => id !== device_id);

            // 변경 로그 추가
            const changeDetails = JSON.stringify({ face_vector });
            const addLogQuery = `
                INSERT INTO change_logs (user_id, device_id, change_type, change_details, synced)
                VALUES (?, ?, 'UPDATE_VECTOR', ?, 0)
            `;

            let completed = 0;
            otherDevices.forEach(otherDeviceId => {
                db.query(addLogQuery, [user_id, otherDeviceId, changeDetails], (logErr) => {
                    if (logErr) {
                        console.error('Error logging vector update:', logErr);
                    }
                    completed++;
                    if (completed === otherDevices.length) {
                        res.json({ success: true, message: 'Vector updated and change logs added successfully.' });
                    }
                });
            });

            // 모든 로그 처리가 없는 경우 응답
            if (otherDevices.length === 0) {
                res.json({ success: true, message: 'Vector updated successfully. No other devices to update.' });
            }
        });
    });
});

// Config 데이터 업데이트 및 변경 로그 추가
app.post('/uploadConfig', (req, res) => {
    const { user_id, device_id, sidemirror1, sidemirror2, seat1, seat2, seat3 } = req.body;

    // 필수 데이터 확인
    if (!user_id || !device_id || sidemirror1 === undefined || sidemirror2 === undefined ||
        seat1 === undefined || seat2 === undefined || seat3 === undefined) {
        return res.status(400).json({ success: false, message: 'Missing required config data.' });
    }

    // Config 데이터 업데이트 쿼리
    const updateConfigQuery = `
        INSERT INTO users_meta (user_id, sidemirror1, sidemirror2, seat1, seat2, seat3)
        VALUES (?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE 
        sidemirror1 = VALUES(sidemirror1),
        sidemirror2 = VALUES(sidemirror2),
        seat1 = VALUES(seat1),
        seat2 = VALUES(seat2),
        seat3 = VALUES(seat3)
    `;

    // Config 데이터 업데이트 처리
    db.query(updateConfigQuery, [user_id, sidemirror1, sidemirror2, seat1, seat2, seat3], (err, updateResults) => {
        if (err) {
            console.error('Error updating config:', err);
            return res.status(500).json({ success: false, message: 'Failed to update config data.' });
        }

        // 사용자가 등록된 모든 device_id 조회
        const getDevicesQuery = `
            SELECT device_id 
            FROM device_users 
            WHERE user_id = ?
        `;

        db.query(getDevicesQuery, [user_id], (err, deviceResults) => {
            if (err) {
                console.error('Error fetching devices:', err);
                return res.status(500).json({ success: false, message: 'Failed to fetch devices for user.' });
            }

            const deviceIds = deviceResults.map(row => row.device_id);

            // 현재 업데이트 중인 device_id 제외
            const otherDevices = deviceIds.filter(id => id !== device_id);

            // 변경 로그 추가
            const changeDetails = JSON.stringify({ sidemirror1, sidemirror2, seat1, seat2, seat3 });
            const addLogQuery = `
                INSERT INTO change_logs (user_id, device_id, change_type, change_details, synced)
                VALUES (?, ?, 'UPDATE_CONFIG', ?, 0)
            `;

            let completed = 0;
            otherDevices.forEach(otherDeviceId => {
                db.query(addLogQuery, [user_id, otherDeviceId, changeDetails], (logErr) => {
                    if (logErr) {
                        console.error('Error logging config update:', logErr);
                    }
                    completed++;
                    if (completed === otherDevices.length) {
                        res.json({ success: true, message: 'Config updated and change logs added successfully.' });
                    }
                });
            });

            // 변경 로그가 없을 경우 바로 응답
            if (otherDevices.length === 0) {
                res.json({ success: true, message: 'Config updated successfully. No other devices to update.' });
            }
        });
    });
});





// 'image' 폴더 경로 설정
const imageStorage = multer.diskStorage({
    destination: (req, file, cb) => {
        const uploadPath = path.join(__dirname, 'images');
        cb(null, uploadPath); // 이미지 저장 경로
    },
    filename: (req, file, cb) => {
        // 클라이언트에서 받은 파일 이름 그대로 사용
        cb(null, file.originalname);
    }
});

// 이미지 업로드 미들웨어
const upload = multer({ storage: imageStorage });


app.post('/uploadImage', upload.single('photo'), (req, res) => {
    if (!req.file) {
        return res.status(400).json({ success: false, message: 'No image file received.' });
    }

    console.log(`Image uploaded: ${req.file.filename}`);
    res.json({ success: true, message: 'Image uploaded successfully.', fileName: req.file.filename });
});




// 서버 실행
const PORT = 3000;
app.listen(PORT,'0.0.0.0', () => {
    console.log(`서버가 포트 ${PORT}에서 실행 중입니다.`);
});
