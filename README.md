<div align="center">
  <img src="https://appteka.store/get/e2Up1_EuDKFD0waaQl8JO6XM-wyltVOoAoApheveMlNnVjR8a0kCaw0yzYrynb3hDG_bXNowZQv41fWBWe_HRonr-A==/820e855e9fa3b83fc5b305aa545b569d84efa8ee.png" alt="Fake GPS Logo" width="120" onerror="this.src='https://via.placeholder.com/120?text=Fake+GPS'"/>
  
  # 🚀 Fake GPS: Advanced Navigation & Joystick
  **Ultra-realistic System-Level GPS spoofing for Rooted Android devices (LsPosed).**
  <br/>
  **Mô phỏng vị trí GPS siêu chân thực ở cấp độ Hệ thống (System-level) dành cho thiết bị Android đã Root.**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-green.svg?logo=android)](https://www.android.com)
[![LsPosed](https://img.shields.io/badge/LsPosed-Required-red.svg)](#)
[![Mapbox](https://img.shields.io/badge/Mapbox-v11-blue.svg)](#)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%7C%20MVVM-orange.svg)](#)
[![Maintainer](https://img.shields.io/badge/Maintained%3F-yes-brightgreen.svg)](#)

  <br/>

### [ 🇬🇧 English ](#-english) | [ 🇻🇳 Tiếng Việt ](#-tiếng-việt)

</div>

---

## 🇬🇧 English

### 🌟 Introduction

**Fake GPS** is a powerful tool designed to alter your device's GPS location precisely, naturally, and undetectably.
This project was originally forked from an open-source repository that was no longer actively maintained. We have resurrected it, actively updating and completely refactoring it to follow Clean Architecture, alongside adding numerous unprecedented, "Exclusive" features.

Whether you need to bypass geographical restrictions, conduct localization testing, or just play games — this application delivers a flawless simulation workflow ranging from standing still, walking via a Joystick, to simulated driving that actually obeys traffic lights!

### 🔥 Key Features

#### 🛸 1. LsPosed Simulation (System-level Fake)

Unlike standard mock location apps that are easily detected, Fake GPS directly intervenes in the system's core via the **Xposed / LsPosed API**:

- **Location Hook:** Completely overrides the location system framework without leaving traces.
- **Network & Sensor Hook:** Sensor and network parameters are smoothly simulated and synchronized with your virtual coordinates.

#### 🚥 2. Smart Traffic Light Simulation 🚀 _[New Feature]_

- Automatically scans and detects intersections (3-way, 4-way) along the navigation route.
- Applies a realistic random red light stop probability (30-50%).
- Implements vehicle physics: Smooth braking (Deceleration) when approaching and accelerating when the light turns green.
- Syncs a countdown timer (8s - 35s) directly on the BottomBar Control and within the Notification!

#### 🛡 3. Anti-Detection Pro 🧪 _[Beta]_

An advanced anti-detection package to trick the strictest security mechanisms:

- Bypasses Google Location Accuracy algorithms and implements Geocoder spoofing.
- Spoofs physical network data: Blocks WiFi scanning, Bluetooth spoofing, and Cell tower spoofing.
- Provides real-time popup notifications when third-party apps attempt to access your location, enabling a "1-Tap Kill."

#### 🗺 4. Smart Cross-Platform Routing

Extracts and simulates highly natural routes from leading map providers:

- **Google Maps** | **Apple Maps** | **Grab** | **Waze** | **OSRM**

#### 🕹 5. Joystick Control & Auto-Walking

- A floating virtual Joystick controller on your screen, allowing easy navigation anytime, anywhere with smart speed synchronization.

#### 🔄 6. Import Movement Routes

- Flexibly and rapidly extracts and loads travel routes from third-party map applications or systems, minimizing manual setup efforts.

### 📸 Demo & Screenshots

<div align="center">
   <img width="220" height="auto" alt="Screenshot_20260330_220528_Fake_GPS_Fake_GPS_Navigation_ _Joystick" src="https://github.com/user-attachments/assets/47ce1bc9-a2a9-4d31-8ef9-63e5816d3751" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img width="220" height="auto" alt="Screenshot_20260330_215955_Fake_GPS_Fake_GPS_Navigation_ _Joystick" src="https://github.com/user-attachments/assets/72d1c793-ec01-47c2-8a3d-088f0f54a497" />
  &nbsp;&nbsp;&nbsp;&nbsp;
<a href="https://youtube.com/shorts/sV6WfKAYL0g"><img src="https://github.com/user-attachments/assets/b48ad251-c413-4341-a19b-ce64b0c9a4c7" alt="Watch the video" width="220"/></a>

</div>

### 🛠 Tech Stack

Built upon Modern Android Development (MAD) standards:

- **Language:** `Kotlin`
- **Architecture:** `Clean Architecture` with `MVVM` in a clear modular structure.
- **Mapping:** `Mapbox SDK v11.19` (Smooth map, modern UI).
- **Injection:** `Dagger Hilt` for Dependency Injection (DI).
- **Asynchronous:** `Kotlin Coroutines` & `Flow/LiveData`.
- **Database / Network:** `Room Database`, `Retrofit`.
- **Core system:** `HiddenApiBypass`, `Virtual Joystick Android`, `LSPosed / Xposed SDK`.

### 📦 Installation Guide

1. Ensure your device is **Rooted** (KernelSU or Magisk is recommended).
2. Install the **LSPosed** Framework (Zygisk version).
3. Clone the project and build the APK via Android Studio, or download the latest release from the [Releases](../../releases) tab.
4. Install the `Fake GPS` APK onto your device.
5. Open **LSPosed Manager** -> Enable the Module for `Fake GPS` and **select the Target Apps** that you wish to spoof location for.
6. Grant Mock Location permissions in Developer Options (Optional fallback).
7. Reboot your device.
8. **Open the App & Enjoy!**

### 🤝 Contributing

All Pull Requests (PRs) contributing ideas or fixing bugs are warmly welcomed!
As the project strictly follows **Clean Architecture**, please ensure you adhere to the modular structure to avoid side effects. If you're proposing a major change, please open an `Issue` first to discuss it.

---

## 🇻🇳 Tiếng Việt

### 🌟 Giới thiệu

**Fake GPS** là một công cụ mạnh mẽ giúp bạn thay đổi vị trí GPS của thiết bị một cách tự nhiên và khó bị phát hiện nhất.
Dự án này ban đầu được fork từ một nguồn mã mở, nhưng do dự án gốc đã ngừng duy trì khá lâu. Chúng tôi đã quyết định đưa dự án trở lại hoạt động, duy trì thường xuyên, tái cấu trúc toàn diện theo Clean Architecture và bổ sung hàng loạt tính năng "Độc quyền" chưa từng có.

Cho dù bạn cần bypass giới hạn địa lý của các ứng dụng, phục vụ công việc kiểm thử (testing) hoặc chỉ đơn thuần muốn chơi game — ứng dụng sẽ cung cấp cho bạn một luồng mô phỏng hoàn hảo từ việc đứng im, đi dạo bằng Joystick cho đến lái xe giả lập tuân thủ đèn giao thông!

### 🔥 Tính năng Nổi Bật

#### 🛸 1. Mô phỏng qua LsPosed (System-level Fake)

Không giống như các app giả lập vị trí thông thường bị phát hiện dễ dàng, Fake GPS can thiệp trực tiếp vào Core của hệ thống thông qua **Xposed / LsPosed API**:

- **Location Hook:** Ghi đè toàn trình hệ thống định vị mà không để lại dấu vết.
- **Network Hook & Sensor Hook:** Thông số cảm biến và mạng được mô phỏng trơn tru, đồng bộ với tọa độ ảo.

#### 🚥 2. Smart Traffic Light Simulation (Tự động dừng đèn giao thông) 🚀 _[Tính năng Mới]_

- Tự động quét và nhận diện giao lộ (Ngã 3, Ngã 4) trên hành trình định vị dọc theo đường đi.
- Áp dụng tỷ lệ dừng đèn đỏ ngẫu nhiên chân thực (30-50%).
- Hiện thực hóa vật lý mô phỏng xe chạy: Tự động phanh mượt mà (Deceleration) khi lại gần và tăng tốc (Acceleration) khi hết đèn.
- Đồng bộ hiển thị đếm ngược (8s - 35s) trực tiếp trên BottomBar Control và Notification!

#### 🛡 3. Anti-Detection Pro (Chống phát hiện chuyên sâu) 🧪 _[Beta]_

Gói giải pháp chống phát hiện tiên tiến giúp đánh lừa những hệ thống kiểm tra khắt khe nhất:

- Lừa tín hiệu thuật toán Google Location Accuracy và Geocoder spoofing.
- Fake các thông tin mạng vật lý: Khóa quét WiFi, Bluetooth spoofing, Cell tower spoofing.
- Hỗ trợ popup thông báo real-time khi có app bên thứ ba đang cố lấy vị trí của bạn, cho phép "Kill" ngay trong một chạm.

#### 🗺 4. Định tuyến Thông minh Đa Nền tảng (Hỗ trợ nhiều nguồn)

Hỗ trợ trích xuất và giả lập tuyến đường cực kỳ tự nhiên từ các nhà cung cấp bản đồ hàng đầu:

- **Google Maps** | **Apple Maps** | **Grab** | **Waze** | **OSRM**

#### 🕹 5. Joystick Control & Đi bộ tự động

- Controller Joystick ảo nổi (floating) trên màn hình, dễ dàng điều hướng mọi lúc mọi nơi. Đồng bộ tốc độ thông minh.

#### 🔄 6. Nhập Lộ Trình Di Chuyển (Import lộ trình từ app khác)

- Cho phép trích xuất và nạp lộ trình di chuyển từ các ứng dụng bản đồ hoặc hệ thống bên thứ ba cực kỳ linh hoạt và nhanh chóng, giảm thiểu thao tác thiết lập thủ công.

### 📸 Demo & Giao diện

<div align="center">  
  <img width="220" height="auto" alt="Screenshot_20260330_220528_Fake_GPS_Fake_GPS_Navigation_ _Joystick" src="https://github.com/user-attachments/assets/47ce1bc9-a2a9-4d31-8ef9-63e5816d3751" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img width="220" height="auto" alt="Screenshot_20260330_215955_Fake_GPS_Fake_GPS_Navigation_ _Joystick" src="https://github.com/user-attachments/assets/72d1c793-ec01-47c2-8a3d-088f0f54a497" />
  &nbsp;&nbsp;&nbsp;&nbsp;
<a href="https://youtube.com/shorts/sV6WfKAYL0g"><img src="https://github.com/user-attachments/assets/b48ad251-c413-4341-a19b-ce64b0c9a4c7" alt="Watch the video" width="220"/></a>

</div>

### 🛠 Công Nghệ Sử Dụng (Tech Stack)

Dự án được xây dựng dựa trên các tiêu chuẩn phát triển Android hiện đại (Modern Android Development - MAD):

- **Ngôn ngữ:** `Kotlin`
- **Kiến trúc:** `Clean Architecture` kết hợp `MVVM` theo cấu trúc module rõ liên kết.
- **Bản đồ:** `Mapbox SDK v11.19` (Bản đồ siêu mượt, UI hiện đại).
- **Injection:** `Dagger Hilt` cho Dependency Injection (DI).
- **Asynchronous:** `Kotlin Coroutines` & `Flow/LiveData`.
- **Database / Network:** `Room Database`, `Retrofit`.
- **Core system:** `HiddenApiBypass`, `Virtual Joystick Android`, `LSPosed / Xposed SDK`.

### 📦 Hướng dẫn cài đặt

1. Đảm bảo thiết bị của bạn đã được **Root** (Khuyến khích KernelSU hoặc Magisk).
2. Cài đặt Framework **LSPosed** (phiên bản Zygisk).
3. Clone project và build file APK thông qua Android Studio, hoặc tải bản Release mới nhất từ mục [Releases](../../releases).
4. Cài đặt file APK `Fake GPS` vào máy.
5. Mở **LSPosed Manager** -> Bật Module cho `Fake GPS` và **chọn các ứng dụng (Target Apps)** mà bạn muốn fake vị trí.
6. Cấp Mock Location permission trong Tùy chọn nhà phát triển (Developer Options).
7. Khởi động lại thiết bị (Reboot).
8. **Mở App & Trải nghiệm!**

### 🤝 Hướng Dẫn Đóng Góp

Mọi PR (Pull Request) đều được hoan nghênh. Vui lòng đảm bảo tuân thủ cấu trúc module `Clean Architecture` để tránh side-effects. Nếu có thay đổi lớn, vui lòng mở một `Issue` trước để thảo luận.

---

<p align="center">Made with ❤️ for the Android Community</p>
