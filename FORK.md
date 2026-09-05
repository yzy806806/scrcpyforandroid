# FORK.md — 与上游的差异

本仓库是 [Miuzarte/ScrcpyForAndroid](https://github.com/Miuzarte/ScrcpyForAndroid) 的个人 fork。

> ⚠️ **重要**:上游会 force-push 重写历史（fork 与上游无共同祖先），
> `git merge upstream/main` 永远会报 `refusing to merge unrelated histories`。
> 同步上游必须逐文件手动对比，见下文「同步步骤」。

## 差异总览

fork 相对上游新增/改动的文件：

| 文件 | 类型 | 说明 |
|------|------|------|
| `app/src/main/java/.../nativecore/QuicTunnelManager.kt` | 新增 | QUIC 隧道管理（本地 TCP listener → QUIC stream → 对端） |
| `app/src/main/java/.../storage/TunnelDevicesStore.kt` | 新增 | 多设备隧道配置存储（设备列表 JSON + 选中 id） |
| `app/libs/libquictunnel.aar` | 新增 | gomobile 编译的 quic-go 库（2.6MB，含 libgojni.so） |
| `app/src/main/java/.../services/DeviceAdbConnectionCoordinator.kt` | 修改 | 连接前判断隧道配置，走 QuicTunnelManager |
| `app/src/main/java/.../storage/AppSettings.kt` | 修改 | 新增 tunnelEnabled/tunnelHost/tunnelPort/tunnelKey/tunnelLocalPort 字段 |
| `app/src/main/java/.../models/DeviceModels.kt` | 修改 | 新增 TunnelDevice / TunnelDevices 模型 |
| `app/src/main/java/.../pages/SettingsScreen.kt` | 修改 | 设置页 TCP 隧道区块 → 设备列表管理（增删改选） |
| `app/src/main/java/.../pages/DeviceTabScreen.kt` | 修改 | 首页隧道设备快速切换入口 + 底部抽屉 |
| `app/src/main/java/.../pages/DeviceTabViewModel.kt` | 修改 | 隧道设备列表状态同步 + 切换/增删改 + 旧配置迁移 |
| `app/src/main/res/values/strings.xml` | 修改 | 隧道相关字符串（中英） |
| `app/src/main/res/values-zh/strings.xml` | 修改 | 同上 |
| `gradle/libs.versions.toml` | 修改 | 移除 wireguard/jsch，保留 desugar |
| `app/build.gradle.kts` | 修改 | 引入 libquictunnel.aar，版本号 versionCode 46 |
| `.github/workflows/build-apk.yml` | 新增 | fork 自己的构建 CI（签名 release） |
| `.github/workflows/android.yml` | 删除 | 上游的 CI（需上游的签名 secrets，fork 没有） |
| `.github/workflows/pr-check.yml` → `renovate-check.yml` | 重命名 | 上游 rename 跟随 |
| `CHANGELOG.md` / `README.md` | 修改 | fork 说明 + QUIC 隧道文档 |
| `nativecore/UsbAdb*.kt`、`res/xml/usb_device_filter.xml` | 上游新增 | USB 有线 ADB（v0.6.0 同步引入） |
| `scrcpy/GamepadInput.kt` | 上游新增 | 手柄支持（v0.5.6 同步引入） |

## QUIC 隧道方案（核心差异）

**为什么不用上游的直连 / SSH / WireGuard：**

1. **上游直连 5555 不安全**：OnePlus 等设备的 adbd 跳过 RSA 认证，公网直连 5555 等于裸奔
2. **SSH 隧道延迟大**：JSch 是 Java 用户态加密 + TCP-in-TCP，Nagle 延迟叠加
3. **WireGuard 需要 VpnService**：Android 一台手机同时只能跑一个 VPN，和 V2Ray 冲突

**最终方案：QUIC 隧道（quic-go）**

- 协议：QUIC（UDP 传输，自带 TLS 1.3 加密 + 可靠传输 + 流复用 + 拥塞控制）
- 认证：预共享密钥（PSK）通过 QUIC stream 发送，对端验证
- 不占用 VpnService，与 V2Ray 共存
- 对端（OnePlus）跑一个 Go 编译的 tunnel-server 二进制

```
小米 app                          OnePlus (被控端)
本地 TCP listener (127.0.0.1)     tunnel-server (Go, 监听 22289/udp)
    ↓ adb 连接                     ↓ PSK 认证
QUIC stream (TLS 1.3 加密)  ←→   转发到 127.0.0.1:5555 (adbd)
```

## 对端（OnePlus）配置

OnePlus 上需要配套的 tunnel-server，不在本仓库内（是 Go 二进制 + Magisk 模块）：

- 二进制：`/data/local/tmp/tunnel-server`（`quic-tunnel/cmd/main.go` 编译，`-mode server`）
- 预共享密钥：`/data/local/tmp/tunnel-key`
- 监听：`22289/udp`，认证后转发 `127.0.0.1:5555`
- 防火墙：`lo→5555 ACCEPT`，`5555 DROP`（不暴露公网）
- Magisk 模块：`tunnel_server`（开机自启 + iptables 加固）

Go 源码在独立私有仓库 [yzy806806/quic-tunnel](https://github.com/yzy806806/quic-tunnel)（含 client AAR + server 二进制的构建说明），不在本仓库提交。

## 同步上游步骤（重要）

上游 force-push 导致无共同祖先，**不能用 git merge**。手动同步：

```bash
# 1. fetch 上游
git fetch https://github.com/Miuzarte/ScrcpyForAndroid.git main

# 2. 看差异
git diff HEAD FETCH_HEAD --stat

# 3. 逐文件判断：
#    - 上游新增的功能性修复 → 手动 cherry-pick 到 fork
#    - 上游改动与 QUIC 隧道文件冲突（SettingsScreen/AppSettings/DeviceAdbConnectionCoordinator）→ 手动合并，保留 QUIC 代码
#    - 上游的 CI（android.yml）→ 不要引入（fork 用自己的 build-apk.yml）
#    - gradlew 执行位 → 保留 fork 的 755（fork CI 无 chmod 步骤）

# 4. 验证 QUIC 隧道代码未被覆盖
grep -r "QuicTunnelManager\|libquictunnel" app/ || echo "❌ 隧道代码丢失！"

# 5. 提交 + push + 看 CI
```

## CI 守卫

CI 构建前会自动检查 QUIC 隧道关键代码是否完整，防止同步时误删（见 `.github/workflows/build-apk.yml`）。

## 版本约定

- `versionName` 带后缀标识 fork 特性：`0.6.0-quic`（当前，同步上游 v0.6.0）
- `versionCode` 单调递增：当前 47
- 发布走 GitHub Release + tag（如 `v0.6.0-quic`）
