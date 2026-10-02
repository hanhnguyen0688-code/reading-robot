# Reading Robot: TomAI Voice (LiveKit)

> **Muốn chạy ngay trên tablet hoặc điện thoại Android?** Xem [`app/README.md`](app/README.md). App chạy độc lập, chấm đọc ngay trên máy, không cần server: mở bằng Chrome hoặc build APK từ thư mục `android/`.
> Phần dưới đây là bản server (LiveKit + Azure Pronunciation Assessment), dùng khi cần chấm phát âm chính xác và đưa vào TomAI Voice.

Reading Robot chạy trên nền LiveKit Agents. Nó bám theo kịch bản khách hàng gồm 4 bước:

| # | Kịch bản | Màn hình | Thành phần trong code |
|---|---|---|---|
| 1 | Nhận diện và chào: *"Hi Cathy! Ready to read to me?"* | 3a | `GreeterAgent` |
| 2 | Hiện bài đọc trên màn hình robot | 3b | `ReadingAgent.on_enter` → event `passage` |
| 3 | Cathy đọc to, có lỗi, tốc độ thay đổi | 3c | `sources` → `StreamingAligner` → `LiveTracker` |
| 4 | Kết thúc: *"Great work Cathy. I'll send some key information to your teacher…"* | 3d | `ReadingAgent.finish` → report → giáo viên |

**Nguyên tắc thiết kế quan trọng nhất:** trong lúc bé đọc, LLM bị **tắt**. Mọi con số (đúng/sai từng từ, accuracy, WCPM) đều do engine tính một cách tất định. LLM chỉ viết lời nhận xét cho giáo viên *từ* các số liệu đó, và còn bị kiểm tra chéo: nếu câu nhận xét không chứa đúng % accuracy thì hệ thống bỏ đi và dùng template thay thế.

---

## 1. Chạy thử ngay (không cần key)

```bash
pip install -r requirements.txt
python -m pytest                       # 19 test: engine + luồng agent
python -m reading_robot.simulate       # Cathy đọc giả lập → in report + ghi web/demo_events.json
python -m http.server -d web 8080      # mở http://localhost:8080/?demo=1
```

Trang demo phát lại **đúng output của engine thật** cho một lượt đọc giả lập. Lượt này có đủ các tình huống: đánh vần ("fav… favourite"), tự sửa ("moose… mouse"), bỏ từ ("very"), đọc sai ("speak" thay cho "squeak"), dừng lâu trước "fridge", xin "Help!" ở "snoring", và đọc lặp "like a little".

Kết quả kỳ vọng: 51 từ, 3 lỗi, **accuracy 94.1% (Instructional)**, error rate 1:17, SC rate 1:4, khoảng 78 WCPM.

## 2. Chạy thật với LiveKit

```bash
cp .env.example .env                   # điền LIVEKIT_*, AZURE_SPEECH_*, OPENAI_API_KEY
python agent_main.py dev               # worker "reading-robot"
uvicorn server.app:app --port 8000     # token server + màn hình robot + trang giáo viên
```

* Màn hình robot: `http://<host>:8000/?student=cathy` (thêm `&camera=1` nếu muốn bật camera)
* Trang giáo viên: `http://<host>:8000/teacher`
* Thiết bị robot (Android WebView/kiosk) chỉ cần mở URL trên. Trình duyệt phải được cấp quyền mic và cho phép autoplay audio.

`/api/token` tạo room mới cho mỗi lượt đọc. Token mang thông tin học sinh (attributes) và **dispatch agent `reading-robot`** vào room. Agent đọc danh tính bé từ token nên không cần nhận diện khuôn mặt.

## 3. Ghép vào TomAI Voice hiện tại

Có 3 cách, xếp theo độ ít đụng chạm code hiện có:

**A. Chạy song song (khuyến nghị cho pilot).** Deploy worker này với `AGENT_NAME=reading-robot`. App TomAI Voice khi vào chế độ "Reading" thì xin token có dispatch agent `reading-robot` (giống `server/app.py`). Agent hội thoại hiện tại không cần sửa gì.

**B. Handoff từ agent hiện tại.** Copy thư mục `reading_robot/` vào repo, rồi từ agent đang có:

```python
from reading_robot.agent import ReadingAgent, ReadingContext
rc = ReadingContext(job=ctx, student={"id": "cathy", "name": "Cathy", "teacher": "Ms. Patel"},
                    passage_id="mission-7", child_identity=participant.identity)
session.update_agent(ReadingAgent(rc))      # bước 2–4 chạy xong sẽ tự kết thúc
```

`ReadingAgent` dùng chính STT/TTS của session hiện tại. Riêng Azure PA thì tự mở thêm một luồng audio.

**C. Chỉ dùng engine.** `StreamingAligner` + `LiveTracker` + `build_report` là Python thuần, không phụ thuộc LiveKit. Bất kỳ pipeline nào có *từ + timestamp* đều cắm vào được.

### Giao thức màn hình (data channel, topic `reading-robot`)

| Event (agent → màn hình) | Nội dung |
|---|---|
| `state` | `greet` / `reading` / `finish` / `sleep` / `not_me` (+ `student` khi ở `greet`) |
| `passage` | danh sách từ (đã tách sẵn), level, word_count (**do máy đếm**), reactions |
| `progress` | `cursor`, `next`, `marks[]` (p/r/w/s/o/t), `new_sentences`, `stars_session`, `wcpm`, `pace` |
| `robot_says` / `user_says` | phụ đề |
| `report` | báo cáo đầy đủ (xem `reports/sample_cathy.json`) |
| `report_sent` | đã gửi giáo viên + teacher_note |

RPC (màn hình → agent), dùng làm phương án chạm khi giọng nói không nghe được: `start_reading` (nút "Raise your hand"), `not_me`, `help`, `finish`.

## 4. Engine chấm đọc hoạt động thế nào

1. **Nguồn từ** (`sources.py`)
   * `azure_pronunciation`: Azure Speech *scripted pronunciation assessment* với reference text là bài đọc, locale `en-AU`. Cho timestamp chính xác từng từ và điểm phát âm 0–100.
   * `session_stt`: dùng transcript của STT session (Deepgram/Azure…). Không cần key thêm, nhưng timing chỉ gần đúng và không có điểm phát âm.
2. **Căn chỉnh** (`aligner.py`): thuật toán Needleman–Wunsch bán toàn cục theo *từ*. Mỗi lần ASR cập nhật (kể cả kết quả interim) thì căn chỉnh lại toàn bộ, nên interim sai một lúc không làm hỏng trạng thái. Lý do không dựa vào Azure miscue: Azure không tính miscue ở chế độ đọc liên tục.
3. **Phân loại theo chuẩn running record**: Substitution, Omission, Insertion, Told là **lỗi**. Self-correction, đánh vần, đọc lặp **không phải lỗi**. Hesitation (≥3 giây) và phát âm yếu được đánh dấu để luyện thêm.
4. **Chỉ số**: Accuracy = (đã đọc − lỗi)/đã đọc, xếp band ≥95 Independent / 90–94 Instructional / <90 Frustration. Ngoài ra có WCPM, error rate, SC rate, % hoàn thành và danh sách từ cần luyện.
5. **Echo**: các từ nghe được trong lúc robot đang nói sẽ bị bỏ qua. Nhờ vậy câu "say Help" của robot không bị tính là bé đọc hay bé xin trợ giúp.

Mọi ngưỡng nghiệp vụ nằm trong `reading_robot/config.py`.

## 5. Hiệu chỉnh trước khi đi Perth (bắt buộc)

1. Thu 30–50 bản ghi trẻ đọc 3–5 bài, có cả lỗi cố ý.
2. Giáo viên hoặc BA chấm tay theo running record. Đây là ground truth.
3. Chạy engine trên cùng bản ghi. Mục tiêu: khớp ≥90% ở cấp từ, và accuracy lệch ≤3 điểm so với người chấm.
4. Chỉnh các tham số trong `config.py`: `low_pronunciation_score`, `hesitation_seconds`, `stall_*`, và `pace_bands` theo level.
5. **Không** bật keyword boost hay phrase list bằng chính từ trong bài cho STT. Làm vậy ASR sẽ "nghe" ra từ đúng và che mất lỗi thật.

## 6. Điểm cần chốt với khách (TmrwX)

| Vấn đề | Hiện trạng trong code | Cần quyết định |
|---|---|---|
| Mockup ghi **"58 WORDS"**, nhưng bài thật có **51 từ** | Số từ do máy đếm | Xác nhận lại văn bản bài đọc |
| Nhận diện khuôn mặt ("I CAN SEE YOU!") | Danh tính lấy từ token/hàng đợi giáo viên, có nút "That's not me" | Consent phụ huynh nếu muốn làm Face ID (đề xuất để V2) |
| Gạch chân lỗi khi bé đang đọc | Mặc định **tắt** (`show_errors_live=False`, theo đúng thực hành running record); demo bật để khách thấy | Khách chọn bật hay tắt |
| Cơ chế "+N Sentence smashed" và sao | Hết câu: +1. Câu không lỗi: +1. Đọc xong bài: +3. Accuracy ≥95%: +2 | Khách chốt công thức |
| "Sent to Ms. Patel" qua kênh nào | Lưu file JSON + POST `TEACHER_WEBHOOK_URL` + trang `/teacher` | Teams Reading Progress / LMS: thuộc V2 |
| "Raise your hand" | Hiện là nút chạm (RPC) | Nếu cần nhận diện cử chỉ bằng camera: V2 |
| Tiếng ồn lớp học | Đã bật AEC/NS phía client, VAD chờ im lặng 0.8 giây | Mic gắn trên robot hay headset? |

## 7. Cấu trúc thư mục

```
reading_robot/   text.py · aligner.py · scoring.py · config.py   ← engine (Python thuần)
                 sources.py · agent.py · teacher.py · passages.py ← lớp LiveKit + giao giáo viên
agent_main.py    worker LiveKit
server/app.py    token server, roster, nơi nhận/xem report
web/             index.html (màn hình robot 3a–3d + demo) · teacher.html
app/             app độc lập (PWA) chạy trên Android/Chrome, engine JS giống hệt Python
android/         project Android Studio bọc app/ thành APK (nhận dạng giọng + TTS native)
data/            passages.json (bài đọc + reactions từng câu) · roster.json
tests/           test_engine.py · test_agent_flow.py · smoke/ (chạy với livekit-server thật)
```

### Smoke test với LiveKit server thật (fake STT/TTS)

```bash
livekit-server --dev &                                  # >= 1.11
LIVEKIT_URL=ws://127.0.0.1:7880 LIVEKIT_API_KEY=devkey LIVEKIT_API_SECRET=secret \
  uvicorn server.app:app --port 8000 &
python tests/smoke/smoke_agent.py dev &
python tests/smoke/smoke_child.py                       # → "SMOKE OK"
```
