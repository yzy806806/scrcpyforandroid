# Scrcpy For Android

[Scrcpy](https://github.com/Genymobile/scrcpy) android client

## 截图

<!-- markdownlint-disable MD033 -->

<p align="center">
  <img src="https://github.com/user-attachments/assets/64e24f71-0326-407a-a527-070586bbec9a" height="320" alt="Screenshot 1" />
  <img src="https://github.com/user-attachments/assets/74170ada-6dee-4ec7-ab24-c5ef2a231a47" height="320" alt="Screenshot 2" />
  <img src="https://github.com/user-attachments/assets/6301f2fb-624b-4209-b548-6f37b9bcedc8" height="320" alt="Screenshot 3" />
</p>

## 已知问题

- ADB 配对流程未实现
- 多指触控抬起后滞留
- 快速离开再进入全屏会导致视频流关键帧丢失

## 构建

- JDK 17+
- Android SDK（含 `compileSdk 36` / `buildTools 36.0.0`）
- Android NDK `28.2.13676358`

```bash
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

## Credits

- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- [YuKongA/miuix](https://github.com/compose-miuix-ui/miuix)
- [tiann/KernelSU/manager](https://github.com/tiann/KernelSU/tree/main/manager)

## License

[Apache License 2.0](LICENSE)
