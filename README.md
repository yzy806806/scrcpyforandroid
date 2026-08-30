# quic-tunnel

QUIC 隧道,用于 ScrcpyForAndroid fork 的远程 adb 控制。

## 架构

```
小米 app (客户端)                   被控设备 (服务端)
本地 TCP listener (127.0.0.1)      tunnel-server (监听 :22289/udp)
    ↓ adb 连接                      ↓ PSK 认证
QUIC stream (TLS 1.3 加密)  ←→    转发到 127.0.0.1:5555 (adbd)
```

- 协议:QUIC(quic-go),UDP 传输,自带 TLS 1.3 + 可靠传输 + 流复用 + 拥塞控制
- 认证:预共享密钥(PSK),通过 QUIC stream 发送 `AUTH:<key>`,对端验证
- 不占用 VpnService,与 V2Ray 等 VPN 共存

## 目录

- `quictunnel.go` — 隧道核心:`StartClient` / `StartServer`,gomobile bind 导出
- `cmd/main.go` — 服务端 CLI(编译为 `tunnel-server-quic`,部署到被控设备)

## 构建

**服务端二进制**(部署到 OnePlus/Redmi 等 root 设备):

```bash
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -ldflags="-s -w" -o tunnel-server-quic ./cmd
```

**客户端 AAR**(gomobile bind,供 Android app 集成):

```bash
gomobile bind -target=android/arm64 -o libquictunnel.aar .
```

## 密钥

PSK 从 `/data/local/tmp/tunnel-key` 读取(64 字符 hex),或用 `TUNNEL_KEY` 环境变量。

服务端运行:

```bash
./tunnel-server-quic -mode server -listen :22289 -target 127.0.0.1:5555
```

## 关联仓库

- [yzy806806/scrcpyforandroid](https://github.com/yzy806806/scrcpyforandroid) — 使用本库的 Android app fork
