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
| `docs/multi-app-session-design.md` | 新增 | 多应用会话模式设计文档（虚拟显示 + display-holder） |
| `magisk-module/` | 合并 | 被控端 Magisk 模块（QUIC 隧道服务端 + display-holder），已从独立仓库合并进来（`git subtree` 保留历史） |
| `.gitignore` | 修改 | 加例外放行 `docs/multi-app-session-design.md`（上游默认忽略 `docs/`） |
| `nativecore/UsbAdb*.kt`、`res/xml/usb_device_filter.xml` | 上游新增 | USB 有线 ADB（v0.6.0 同步引入） |
| `scrcpy/GamepadInput.kt` | 上游新增 | 手柄支持（v0.5.6 同步引入） |
| `i18n/AppLocale.kt`、`i18n/LocalizedActivity.kt` | 上游新增 | 多语言收拢（v0.6.5 同步引入）；`preBuild` 校验要求所有 Activity 继承 `LocalizedActivity` |
| `util/QrCodeEncoder.kt`、`widgets/QrPairingDialog.kt`、`nativecore/QrPairingCredentials.kt` | 上游新增 | 二维码配对（v0.6.2 同步引入） |

## QUIC 隧道方案（核心差异）

**为什么不用上游的直连 / SSH / WireGuard：**

1. **上游直连 5555 不安全**：OnePlus 等设备的 adbd 跳过 RSA 认证，公网直连 5555 等于裸奔
2. **SSH 隧道延迟大**：JSch 是 Java 用户态加密 + TCP-in-TCP，Nagle 延迟叠加
3. **WireGuard 需要 VpnService**：Android 一台手机同时只能跑一个 VPN，和 V2Ray 冲突

**最终方案：QUIC 隧道（quic-go）**

- 协议：QUIC（UDP 传输，自带 TLS 1.3 加密 + 可靠传输 + 流复用 + 拥塞控制）
- 认证：预共享密钥（PSK）通过 QUIC stream 发送，对端验证
- 不占用 VpnService，与 V2Ray 共存
- 被控端跑一个 Go 编译的 tunnel-server 二进制

```
主控 App                           被控端 (root + Magisk)
本地 TCP listener (127.0.0.1)     tunnel-server (Go, 监听 UDP，默认 22289)
    ↓ adb 连接                     ↓ PSK 认证
QUIC stream (TLS 1.3 加密)  ←→   转发到 127.0.0.1:5555 (adbd)
```

## 被控端配置

被控端需要本仓库自带的 Magisk 模块（[magisk-module/](magisk-module/)），它包含两个常驻组件：

- `tunnel-server`：QUIC 隧道服务端，监听 UDP（默认 `22289`，可在模块目录放 `port` 文件自定义），PSK 认证后转发 `127.0.0.1:5555`（adbd）
- `display-holder`：常驻 `app_process`，持有最多 4 个虚拟显示——多应用挂机的核心

### 安装（被控端，需 root + Magisk）

```bash
# 方式一：本地构建模块包（需要 Android SDK 与 Go 工具链）
git clone --recursive <本仓库>
cd scrcpyforandroid/magisk-module
bash module/build.sh          # 产出 tunnel_server_vX.Y.zip

# 方式二：直接用 Release 附件里附带的模块 zip
```

然后把 zip 刷进 Magisk（App 里「模块 → 从存储安装」或 `magisk --install-module`），
重启即生效：`service.sh` 会拉起并守护两个组件，开机自启。

### 首次使用

1. **生成预共享密钥**（模块安装时若 `/data/local/tmp/tunnel-key` 不存在会自动生成，
   也可手动生成：`head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > /data/local/tmp/tunnel-key && chmod 600 /data/local/tmp/tunnel-key`）
2. **主控端**：设置 → TCP 隧道 → 开启 → 添加设备
   （设备名任意 / 对端地址 = 被控端的公网可达地址 / 端口 = 上一步的隧道端口，默认 `22289` / 密钥 = PSK）
3. 主控端连接时 App 会自动先建 QUIC 隧道再连 adb，无需手动 `adb connect`
4. 多应用挂机：打开 App 的「应用」tab，点收藏或「+ 添加」把应用放进挂机位

### 安全模型

- adbd 只监听 `127.0.0.1`，模块用 iptables 保证 `5555` 不暴露公网
- 公网上只暴露隧道端口（默认 `22289`/udp，可自定义），且每个连接必须先通过 PSK 认证
- 换端口后记得同步调整端口映射/防火墙放行，并更新主控端的隧道设备配置
- 密钥泄露 = 被控端 adbd 完全暴露，请当作密码对待

### 卸载

Magisk 里移除模块并重启即可；`/data/local/tmp/display-holder/` 与
`/data/local/tmp/tunnel-key` 是运行时文件，可手动删除。

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

- `versionName` 带后缀标识 fork 特性：`0.7.0-quic`（当前，同步上游 v0.7.0）
- `versionCode` 单调递增：当前 57+（多应用挂机与 UI 迭代期间增长较快）
- 发布走 GitHub Release + tag（如 `v0.6.6-quic`）

## 同步记录

- 2026-09-05 → v0.6.0-quic（versionCode 47）：USB ADB / 手柄 / Android 17 权限
- 2026-09-23 → v0.6.6-quic（versionCode 50 → 51 修复版）：外观重构 / 二维码配对 / 虚拟按键重构 / 多语言收拢 / 会话保活
- 2026-09-30 → v0.6.8-quic（versionCode 52）：备选设备地址保存丢失修复 / 横屏虚拟按键布局 / 手柄返回键误判
- 2026-10-07 → v0.7.0-quic（versionCode 53）：scrcpy-server v5.0（音频采集覆盖全部 usage / 语音捕获修复）/ NDK 30

## 上游同步注意（新增坑）

- 上游 v0.6.5 起 `preBuild` 有 `verifyAppLocaleWiring` 校验：Activity 未继承 `LocalizedActivity`、或 `AppLocale.SUPPORTED_TAGS` / `locales_config.xml` / `values-xx` 三者不一致时**构建失败**
- 上游 v0.6.6 起 JDK 目标为 21（`sourceCompatibility`/`targetCompatibility`/`jvmTarget`），CI 用 JDK 21
- 上游 v0.7.0 起 `ndkVersion` 从 `libs.versions.libcxx` 派生（30.0.16248370）；**CI 必须显式安装该 NDK**（runner 预装版本未必匹配），我们的 `build-apk.yml` 已加 Read/Cache/Install NDK 三步
- scrcpy-server 二进制不进 git（只有 `.gitkeep`），构建时按 SHA256 下载；v5.0 起 gradle 任务会自动清理旧版本文件
