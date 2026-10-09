# quic-tunnel

QUIC tunnel + Magisk module used by the [scrcpyforandroid](https://github.com/yzy806806/scrcpyforandroid)
fork for remote adb control, plus the resident **display holder** that keeps apps
running on the controlled device after the controller disconnects.

## Architecture

```
Controller (Android app)                 Controlled device (root + Magisk)
────────────────────────                 ──────────────────────────────────
local TCP listener (127.0.0.1)           tunnel-server  (UDP, default port 22289, PSK auth)
      ↓ adb                                   ↓ forwards to 127.0.0.1:5555 (adbd)
QUIC stream (TLS 1.3)  ←──────────→      display-holder (app_process, resident)
                                              ↓ owns up to 4 virtual displays
                                         apps keep running on those displays
```

- **Transport**: QUIC (quic-go) over UDP — TLS 1.3, multiplexed streams,
  congestion control. No `VpnService`, so it coexists with VPN clients.
- **Auth**: pre-shared key sent as `AUTH:<key>` on the first stream.
- **Displays**: held by a resident `app_process` process, not by the scrcpy
  client, so the apps' lifetime is independent of the controller's.

## Layout

```
tunnel/          Go sources: client (gomobile AAR) + server CLI
module/          Magisk module: packaging, supervisor, display-holder
```

## Build

```bash
# server binary (deploy on the controlled device)
cd tunnel && CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
    go build -ldflags="-s -w" -o ../build/tunnel-server ./cmd

# controller-side AAR (integrated by the Android app)
cd tunnel && gomobile bind -target=android/arm64 -o ../build/libquictunnel.aar .

# whole Magisk module (server + holder + packaging) -> flashable zip
ANDROID_SDK_ROOT=/path/to/android-sdk bash module/build.sh
```

## Trust boundary

The command channel (`/data/local/tmp/display-holder/cmd`) and state dir are
world-writable (0666/0777) so the adb-shell user can drive them. Anything with
shell or root access on the device can therefore create displays, launch or
force-stop apps through it. This is acceptable for a personal rooting setup
(adb itself grants full device control) but the module should not be treated as
a security boundary between apps.

## Configuration

- **Tunnel port**: `22289` by default. To change it, create a `port` file in the
  installed module directory (`/data/adb/modules/tunnel_server/port`) containing
  just the port number and reboot. The controller's tunnel entry must use the
  same port.
- **PSK**: generated on install (`/data/local/tmp/tunnel-key`, mode `0600`).
  Replace it with your own value if desired; the controller must be configured
  with the same key.

## Runtime files on the device

| Path | Purpose |
|---|---|
| `/data/local/tmp/tunnel-key` | PSK (`0600`, generated on install if absent) |
| `/data/local/tmp/tunnel-server.log` | tunnel log |
| `/data/local/tmp/display-holder/state.json` | slot → display id / package (read by the controller) |
| `/data/local/tmp/display-holder/cmd` | command channel (append one command per line) |
| `/data/local/tmp/display-holder/desired` | slots to restore after a holder restart |

See [module/README.md](module/README.md) for deployment details and the verified
on-device constraints (output surface, display group, keep-awake).

## Related

- [scrcpyforandroid](https://github.com/yzy806806/scrcpyforandroid) — the controller app
- [scrcpy](https://github.com/Genymobile/scrcpy) — the virtual display approach is derived from its server
