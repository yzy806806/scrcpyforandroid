#!/bin/bash
# CI guard: verify QUIC tunnel fork code is intact.
# Prevents upstream sync from silently dropping the fork's tunnel feature.
set -euo pipefail

echo "=== Fork guard: checking QUIC tunnel code ==="
fail=0

# 1. QuicTunnelManager must exist
if [ ! -f "app/src/main/java/io/github/miuzarte/scrcpyforandroid/nativecore/QuicTunnelManager.kt" ]; then
    echo "❌ QuicTunnelManager.kt missing"
    fail=1
else
    echo "✅ QuicTunnelManager.kt"
fi

# 2. libquictunnel.aar must be bundled
if [ ! -f "app/libs/libquictunnel.aar" ]; then
    echo "❌ libquictunnel.aar missing"
    fail=1
else
    echo "✅ libquictunnel.aar"
fi

# 3. Coordinator must reference QuicTunnelManager
if ! grep -q "QuicTunnelManager" app/src/main/java/io/github/miuzarte/scrcpyforandroid/services/DeviceAdbConnectionCoordinator.kt; then
    echo "❌ DeviceAdbConnectionCoordinator no longer uses QuicTunnelManager"
    fail=1
else
    echo "✅ DeviceAdbConnectionCoordinator uses QuicTunnelManager"
fi

# 4. AppSettings must have tunnel fields
if ! grep -q "tunnelEnabled\|TUNNEL_ENABLED" app/src/main/java/io/github/miuzarte/scrcpyforandroid/storage/AppSettings.kt; then
    echo "❌ AppSettings tunnel fields missing"
    fail=1
else
    echo "✅ AppSettings tunnel fields"
fi

# 5. SettingsScreen must have tunnel UI
if ! grep -q "tunnelHost\|pref_title_tunnel" app/src/main/java/io/github/miuzarte/scrcpyforandroid/pages/SettingsScreen.kt; then
    echo "❌ SettingsScreen tunnel UI missing"
    fail=1
else
    echo "✅ SettingsScreen tunnel UI"
fi

# 6. build.gradle.kts must reference the aar
if ! grep -q "libquictunnel.aar" app/build.gradle.kts; then
    echo "❌ build.gradle.kts missing libquictunnel.aar dependency"
    fail=1
else
    echo "✅ build.gradle.kts references libquictunnel.aar"
fi

# 7. Must NOT have regressed to old VpnService (WGTunnelManager / GoBackend)
#    Check real code (imports/class refs), not comments mentioning "no VpnService"
if grep -rn "^import.*GoBackend\|^import.*WGTunnelManager\|WGTunnelManager()" app/src/main/java/ 2>/dev/null | grep -v "^Binary"; then
    echo "❌ VpnService/WGTunnelManager regression detected"
    fail=1
else
    echo "✅ no VpnService regression"
fi

echo ""
if [ "$fail" -ne 0 ]; then
    echo "❌❌ Fork guard FAILED — tunnel code incomplete, refusing to build"
    exit 1
fi
echo "✅ Fork guard passed"
