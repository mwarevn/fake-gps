# Feature: Smart Traffic Light Simulation (Auto-Stop at Intersections)

## 1. Logic & Mathematical Rules
- **Detection:** Tự động quét các giao lộ (intersections) trên lộ trình navigation (chỉ áp dụng cho Ngã 3, Ngã 4).
- **Probability:** Khi đến gần giao lộ, kích hoạt tỷ lệ dừng đèn đỏ ngẫu nhiên: `30% - 50%`.
- **Timing:** 
    - Thời gian chờ: Random từ `8s` đến `35s`.
    - Phải có bộ đếm ngược (countdown) thực tế theo từng giây, hiển thị trên control bottombar và notify.
- **Speed Profile:**
    - **Deceleration:** Khi cách đèn đỏ 20m hoặc nhỏ hơn, giảm dần tốc độ về 0km/h sao cho giống thực tế nhất.
    - **Acceleration:** Khi hết đèn đỏ (hoặc Skip), tăng dần tốc độ về `target_speed` (tốc độ đã set ở sidebar) sao cho giống thực tế nhất.

## 2. State Management (Các trạng thái cần quản lý)
- `isAutoLightEnabled`: Boolean (Bật/tắt tính năng từ BottomBar).
- `trafficLightStatus`: enum (`moving`, `approaching`, `waiting`, `passed`).
- `remainingSeconds`: Number (Đếm ngược thời gian chờ).
- `canSkip`: Boolean (Hiển thị khi ở trạng thái `approaching` hoặc `waiting`).

## 3. UI/UX Components
### A. Bottom Bar Control
- Thêm **Toggle Switch**: "Tự động đèn đỏ".
- Hiển thị **Countdown Badge**: Nếu đang `waiting`, hiện số giây đếm ngược màu đỏ.

### B. Action Buttons (Contextual)
- **Nút "Bỏ qua đèn đỏ":** 
    - Nếu trạng thái là `approaching`: Hiện "Bỏ qua đèn đỏ sắp tới".
    - Nếu trạng thái là `waiting`: Hiện "Bỏ qua đèn đỏ hiện tại".
    - Click vào: Reset speed về `target_speed` và đổi status sang `passed`.

### C. Notification System
- Update nội dung notify theo real-time:
    - Di chuyển: "Đang di chuyển - [Tốc độ] km/h"
    - Sắp tới đèn đỏ: "Chuẩn bị dừng đèn đỏ..."
    - Chờ: "Đang chờ đèn đỏ: [x] giây còn lại"
    - Vượt qua: "Đã qua giao lộ"

## 4. Edge Cases (Trường hợp ngoại lệ)
- Nếu User tắt "Tự động đèn đỏ" khi đang đứng chờ: Lập tức tiếp tục di chuyển.
- Nếu đích đến (Destination) nằm ngay tại giao lộ: Ưu tiên hoàn thành lộ trình thay vì dừng đèn đỏ.



# Feature: Require update app

## 1. Logic & Mathematical Rules
- **Detection:** Tự động quét phiên bản mới 
- **Probability:** khi user mở app / onresume thì quét link github release lấy file yml mới nhất check với phiên bản hiện tại, nếu không có bản mới thì không làm gì. nếu có bản mới thì bắt buộc user phải update app mới có thể sử dụng được app (hiện dialog yêu cầu update) nhấn update mở link download file. nhấn cancel thì thoát app.
- **Attention** Hiện tại app đã có sẵn tính năng check update. hãy comment lại cái nút toggle bật tắt update để khi tôi dev còn tắt được tính năng này. xử lý sao cho không để các cửa sổ báo update chồng lặp lên nhau nhiều lần.
 