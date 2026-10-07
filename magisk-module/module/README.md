# Magisk module — QUIC tunnel + display holder

On-device half of the multi-app session mode. Two resident components, both
started and supervised by the module's `service.sh`:

| Component | What it does |
|---|---|
| `tunnel-server` | QUIC (TLS 1.3 + PSK) tunnel, forwards authenticated connections to `127.0.0.1:5555` (adbd) |
| `display-holder` | `app_process` Java process that creates and **holds** virtual displays so apps keep running after the controller disconnects |

## Why a holder is needed

A virtual display dies with the process that created it. If the scrcpy client
created the display, disconnecting destroys it and the apps on it are paused —
so "run apps in the background" cannot be built on the client's lifetime.
`display-holder` is a separate resident process that owns the displays; the
client only *attaches* to them (`--display-id`).

## Verified on-device constraints

These are measurements from the target device (Android 16), not guesses. They
shape the implementation, so read them before changing flags:

1. **A real output surface is required.** With a `null` surface the display is
   created but `dumpsys display` reports `state OFF`, SurfaceFlinger does not
   compose it, and mirroring yields 0 frames. The holder therefore attaches an
   `ImageReader` surface and drains it (`acquireLatestImage` + `close`) to keep
   the producer from stalling.
2. **`FLAG_DEVICE_DISPLAY_GROUP` is required for mirroring.** With
   `FLAG_OWN_DISPLAY_GROUP` the display is independent and `state ON`, and apps
   keep running, but SurfaceFlinger cannot mirror it — the controller sees
   nothing. Device display group is therefore the default.
3. **Consequence of (2): the device must stay awake.** The display follows the
   main display's power state. When the phone dozes, the display goes `OFF` and
   the apps on it freeze (measured: game CPU time stops advancing). Hence
   `svc power stayon` / `stay_on_while_plugged_in` while in use. Keep the phone
   on a charger; screen brightness can be at minimum.
4. **Four concurrent displays work**, each with its own hardware encoder
   (measured: all four sessions used `c2.mtk.avc.encoder`, ~33% total CPU).
5. **Virtual displays must use the device's native size and density.** Changing
   them makes some apps misbehave. Transfer resolution is a separate knob
   (the controller's `--max-size`).

## Layout

```
module/
├── module.prop          module metadata (id must stay `tunnel_server` for in-place upgrade)
├── customize.sh         install script (permissions, generates the PSK if absent)
├── service.sh           starts + supervises both components
├── uninstall.sh         stops them and removes runtime files
├── build.sh             builds everything into a flashable zip
├── build-holder.sh      builds display-holder.jar (javac + d8)
└── holder/src/          DisplayHolder.java + the scrcpy-derived wrappers it needs
```

## Building

Requires go 1.21+, a JDK, and the Android SDK (for `d8` and `android.jar`).

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk bash module/build.sh
# -> build/tunnel_server_v<version>.zip
```

## Installing

```bash
# copy the zip to the device, then
magisk --install-module /data/local/tmp/tunnel_server_v<version>.zip
# reboot to let service.sh run
```

Or unpack it manually into `/data/adb/modules/tunnel_server/` (keep the layout)
and run `service.sh`.

## Controlling the holder

State and commands are files, so anything that can run `adb shell` can drive it
(no persistent connection needed). The holder makes them world-readable/writable
so the `shell` user can use them.

```bash
# status
cat /data/local/tmp/display-holder/state.json

# commands (one per line, appended)
echo 'create 0'              >> /data/local/tmp/display-holder/cmd
echo 'launch 0 com.foo.bar'  >> /data/local/tmp/display-holder/cmd
echo 'kill 0'                >> /data/local/tmp/display-holder/cmd
echo 'quit'                  >> /data/local/tmp/display-holder/cmd
```

| Command | Effect |
|---|---|
| `create <slot>` | create the slot's virtual display (idempotent) |
| `launch <slot> <pkg>` | start the app on that display (creates it first if needed) |
| `kill <slot>` | force-stop the app and destroy the display |
| `destroy <slot>` | destroy the display only |
| `status` | rewrite `state.json` |
| `quit` | release everything and exit |

Slots are recorded in `desired`, so when the holder is restarted (crash or
reboot) it rebuilds the displays and relaunches the apps by itself.

## Security notes

- PSK lives in `/data/local/tmp/tunnel-key` (generated on install, `0600`).
  Never commit a real key.
- `service.sh` keeps adbd's TCP port reachable **from loopback only**; external
  traffic to 5555 is dropped.
- The holder creates displays owned by `com.android.shell`; it needs root or
  shell privileges and a running Android runtime environment (`app_process`
  requires `ANDROID_DATA` etc., which `service.sh` exports explicitly).

## Credits

Virtual display creation follows scrcpy's proven approach
(`wrappers/DisplayManager.createNewVirtualDisplay` + `FakeContext` +
`Workarounds`), adapted so the display outlives any client.
