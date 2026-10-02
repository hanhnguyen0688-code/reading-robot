# Reading Robot: app chạy độc lập trên Android

App chạy hoàn toàn trên tablet hoặc điện thoại: bé đọc vào mic, app chấm ngay trên máy, báo cáo lưu trên máy. **Không cần server LiveKit, không cần key.**

Engine chấm đọc (`engine.js`) là bản JavaScript của engine Python. Test `tests/engine_parity.test.js` kiểm tra 41 lượt đọc và cho thấy kết quả giống Python từng con số.

## Luồng sử dụng

| Màn hình | Ai thao tác | Chuyện gì xảy ra |
|---|---|---|
| **Who's reading today?** | Bé chạm vào tên mình | Robot chào: *"Hi Cathy! Ready to read to me?"* |
| 3a Greet | Bé nói "Ready!" (hoặc chạm nút) | Nói "That's not me!" thì robot nhờ bé gọi giáo viên |
| 3b/3c Reading | Bé đọc to | Robot nghe, tô sáng từ đang đọc, đồng hồ tốc độ. Bé nói "Help!" thì robot đọc mẫu từ đó |
| 3d Finish | Không ai cần thao tác | *"Great work Cathy. I'll send some key information to your teacher…"* Báo cáo được lưu, robot ngủ, rồi quay về màn hình chọn tên |
| **Teacher** (PIN mặc định `1234`) | Giáo viên | Xem báo cáo (running record, accuracy, WCPM, từ cần luyện), chia sẻ, xuất CSV, quản lý học sinh và bài đọc, cài đặt giọng, thử mic |

## Cách 1: chạy bằng Chrome (nhanh nhất, không cần build)

1. Đưa cả thư mục `app/` lên một host **HTTPS** bất kỳ, ví dụ Netlify Drop (kéo thả thư mục), GitHub Pages, Firebase Hosting, hoặc web server của công ty.
2. Mở link bằng **Chrome trên Android**, chạm tên bé, rồi chọn **Cho phép** micro.
3. Vào menu Chrome, chọn **Thêm vào màn hình chính**. App có icon riêng và chạy toàn màn hình.

Chạy thử trên máy tính: `python -m http.server -d app 8080`, rồi mở `http://localhost:8080` bằng Chrome.

## Cách 2: build file APK (Android Studio)

1. Mở thư mục `android/` bằng Android Studio (bản Ladybug trở lên). Studio tự tải Gradle và Android SDK.
2. Bấm **Build › Build APK(s)**. File ra ở `android/app/build/outputs/apk/debug/app-debug.apk`.
3. Cài lên tablet hoặc robot Android, mở app và cho phép micro.

Bản APK dùng **nhận dạng giọng nói và đọc chữ thành tiếng có sẵn của Android** (`MainActivity.java`). Mỗi lần build, toàn bộ web app trong `app/` được tự động copy vào APK. Cần Android 7.0 trở lên.

Từ dòng lệnh: `cd android && ./gradlew assembleDebug` (cần cài sẵn Android SDK, đặt biến `ANDROID_HOME`).

## Lưu ý khi dùng thật

* **Độ chính xác.** Nhận dạng giọng nói của Android/Chrome có xu hướng tự sửa cho thành từ đúng, và không cho điểm phát âm. App đếm tốt các lỗi bỏ từ, đọc sai thành từ khác, xin trợ giúp, ngập ngừng và đọc lặp, nhưng có thể bỏ sót lỗi phát âm nhẹ. Nếu cần đánh giá phát âm chi tiết, dùng bản server với Azure Pronunciation Assessment (thư mục gốc của dự án).
* **Mạng.** Nhận dạng giọng nói của Chrome cần Internet. Trên Android có thể tải gói giọng nói tiếng Anh (Settings › Google › Voice › Offline speech recognition) để chạy offline với bản APK.
* **Tiếng bíp.** Bộ nhận dạng của Android thường kêu bíp khi bắt đầu nghe. Bản APK đã tắt tiếng thông báo trong lúc nghe; Chrome thì không tắt được.
* **Dữ liệu.** Báo cáo chỉ nằm trên máy đó. Vào Teacher › Settings › *Download backup* để sao lưu, hoặc dùng *Export CSV* / *Share with teacher*.
* **PIN giáo viên** đổi trong Teacher › Settings.

## Cấu trúc

```
app/index.html     màn hình robot 3a–3d + màn hình chọn tên + khu giáo viên (build từ tools/)
app/engine.js      engine chấm đọc (giống hệt bản Python)
app/session.js     luồng chào → đọc → kết thúc (giống agent LiveKit)
app/speech.js      mic + giọng robot: Android native hoặc Chrome Web Speech
app/store.js       học sinh, bài đọc, cài đặt, báo cáo (lưu trên máy)
app/admin.js       khu giáo viên
app/tools/         shell.html + build_index.py (ghép giao diện từ web/index.html)
android/           project Android Studio (WebView + SpeechRecognizer + TextToSpeech)
```
