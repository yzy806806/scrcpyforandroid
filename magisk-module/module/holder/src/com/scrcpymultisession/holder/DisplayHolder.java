package com.scrcpymultisession.holder;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.ImageFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DisplayHolder —— 常驻虚拟显示持有者。
 *
 * 独立于任何 scrcpy 客户端进程运行（由 Magisk 模块 service.sh 拉起），创建并持有虚拟显示，
 * 使被控端应用在主控端断开后仍然继续运行。不编码视频，开销远小于 scrcpy server。
 *
 * 用法：
 *   app_process -Djava.class.path=/data/adb/modules/tunnel_server/bin/display-holder.jar \
 *       /system/bin com.scrcpymultisession.holder.DisplayHolder \
 *       --width 1080 --height 2378 --dpi 480 --dir /data/local/tmp/display-holder
 *
 * 命令文件（追加一行执行一条）：
 *   create <slot>
 *   destroy <slot>
 *   launch <slot> <package>
 *   kill <slot>
 *   status
 *   quit
 *
 * 虚拟显示的创建方式照抄 scrcpy server（wrappers/DisplayManager.createNewVirtualDisplay +
 * FakeContext + Workarounds），这是在本机型上已被验证可用的路径。
 */
public final class DisplayHolder {

    private static final String TAG = "DisplayHolder";

    // ---------- VirtualDisplay flags（部分为 @hide，按 scrcpy 的做法自行定义）----------
    private static final int FLAG_PUBLIC = 1 << 0;
    private static final int FLAG_PRESENTATION = 1 << 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int FLAG_OWN_FOCUS = 1 << 14;
    private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;

    private static final int MAX_SLOTS = 4;

    // ---------- 配置 ----------
    private static int cfgWidth = 1080;
    private static int cfgHeight = 2378;
    private static int cfgDpi = 480;
    private static File cfgDir = new File("/data/local/tmp/display-holder");
    private static int cfgSlotCount = MAX_SLOTS;
    private static String cfgSurfaceMode = "none"; // none | reader
    private static boolean cfgDeviceDisplayGroup = true;
    private static boolean cfgRestore = true;

    // ---------- 状态 ----------
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Slot[] slots;
    private static Handler mainHandler;
    private static RandomAccessFile cmdFile;
    private static long cmdOffset;
    private static File stateFile;
    private static File desiredFile;
    private static boolean displayManagerReady;

    // 反射句柄
    private static Object displayManager;
    private static Method createVirtualDisplayMethod;

    static final class Slot {
        int index;
        int displayId = -1;
        String pkg = "";
        String error = ""; // 非空 = 最近一次 launch 失败的原因（主控端可见）
        VirtualDisplay vd;
        ImageReader reader; // surface mode = reader 时使用

        Slot(int index) {
            this.index = index;
        }

        boolean isActive() {
            return vd != null && displayId >= 0;
        }
    }

    // =====================================================================
    //  入口
    // =====================================================================
    public static void main(String[] args) {
        parseArgs(args);
        bootLogFile = new java.io.File(cfgDir, "boot.log");

        log("start pid=" + Process.myPid() + " " + cfgWidth + "x" + cfgHeight + "/" + cfgDpi
                + " slots=" + cfgSlotCount + " surface=" + cfgSurfaceMode + " dir=" + cfgDir);

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        mainHandler = new Handler(Looper.getMainLooper());

        slots = new Slot[Math.max(1, Math.min(cfgSlotCount, MAX_SLOTS))];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot(i);
        }

        if (!cfgDir.exists() && !cfgDir.mkdirs()) {
            log("FATAL 无法创建状态目录 " + cfgDir);
            System.exit(1);
        }
        relaxPermissions(cfgDir, true);
        stateFile = new File(cfgDir, "state.json");
        desiredFile = new File(cfgDir, "desired");

        try {
            Workarounds.apply();
            log("W: apply 完成");
            initDisplayManager();
            displayManagerReady = true;
            log("DisplayManager 就绪");
        } catch (Throwable t) {
            log("FATAL 初始化 DisplayManager 失败: " + t);
            t.printStackTrace(System.out);
            writeState();
            System.exit(2);
        }

        // 初始化命令文件（从末尾开始读，避免重放旧命令）
        try {
            File cf = new File(cfgDir, "cmd");
            if (!cf.exists()) {
                cf.createNewFile();
            }
            relaxPermissions(cf, false);
            cmdFile = new RandomAccessFile(cf, "r");
            cmdOffset = cmdFile.length();
        } catch (IOException e) {
            log("FATAL 无法打开命令文件: " + e);
            System.exit(3);
        }

        // 先读期望状态再写 state —— writeState 会重写 desired 文件
        final List<String[]> pendingRestore = cfgRestore ? readDesired() : new ArrayList<>();

        writeState();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("shutdown: 释放全部虚拟显示");
            for (Slot s : slots) {
                try {
                    releaseSlot(s);
                } catch (Throwable ignored) {
                }
            }
            writeState();
        }));

        Thread poller = new Thread(DisplayHolder::pollLoop, "cmd-poller");
        poller.setDaemon(true);
        poller.start();

        if (cfgRestore) {
            mainHandler.post(() -> restoreSlots(pendingRestore));
        }

        log("进入主循环");
        Looper.loop();
    }

    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            String a = args[i];
            String v = args[i + 1];
            switch (a) {
                case "--width":
                    cfgWidth = parseInt(v, cfgWidth);
                    break;
                case "--height":
                    cfgHeight = parseInt(v, cfgHeight);
                    break;
                case "--dpi":
                    cfgDpi = parseInt(v, cfgDpi);
                    break;
                case "--slots":
                    cfgSlotCount = parseInt(v, cfgSlotCount);
                    break;
                case "--dir":
                    cfgDir = new File(v);
                    break;
                case "--surface":
                    cfgSurfaceMode = v;
                    break;
                case "--restore":
                    cfgRestore = "1".equals(v) || "true".equalsIgnoreCase(v);
                    break;
                case "--device-display-group":
                    cfgDeviceDisplayGroup = "1".equals(v) || "true".equalsIgnoreCase(v);
                    break;
                default:
                    break;
            }
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // =====================================================================
    //  命令循环
    // =====================================================================
    private static void pollLoop() {
        StringBuilder partial = new StringBuilder();
        while (running.get()) {
            try {
                Thread.sleep(400);
                long len = cmdFile.length();
                if (len < cmdOffset) {
                    // 文件被截断（或重建）
                    cmdOffset = 0;
                    partial.setLength(0);
                }
                if (len == cmdOffset) {
                    continue;
                }
                cmdFile.seek(cmdOffset);
                String line;
                while ((line = cmdFile.readLine()) != null) {
                    cmdOffset = cmdFile.getFilePointer();
                    String cmd = line.trim();
                    if (cmd.isEmpty()) {
                        continue;
                    }
                    submit(cmd);
                }
                // 命令文件过大时清空，避免无限增长
                if (cmdOffset > 65536) {
                    cmdFile.close();
                    File cf = new File(cfgDir, "cmd");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cf, false)) {
                        // 截断
                    }
                    cmdFile = new RandomAccessFile(cf, "r");
                    cmdOffset = 0;
                }
            } catch (Throwable t) {
                log("poll 异常: " + t);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /** 全部命令都投递到主线程串行执行（ImageReader 回调、显示创建都依赖主 Looper）。 */
    private static void submit(String cmd) {
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                handleCommand(cmd);
            } catch (Throwable t) {
                log("命令失败 [" + cmd + "]: " + t);
                t.printStackTrace(System.out);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static void handleCommand(String cmd) throws Exception {
        String[] parts = cmd.split("\\s+");
        String op = parts[0].toLowerCase();
        log("cmd: " + cmd);

        switch (op) {
            case "create": {
                int slot = slotIndex(parts);
                ensureDisplay(slot);
                break;
            }
            case "destroy": {
                int slot = slotIndex(parts);
                releaseSlot(slots[slot]);
                break;
            }
            case "launch": {
                if (parts.length < 3) {
                    log("launch 需要 <slot> <package>");
                    break;
                }
                int slot = slotIndex(parts);
                String pkg = parts[2];
                launch(slot, pkg);
                break;
            }
            case "kill": {
                int slot = slotIndex(parts);
                killSlot(slot);
                break;
            }
            case "status":
                break;
            case "quit":
                running.set(false);
                for (Slot s : slots) {
                    releaseSlot(s);
                }
                writeState();
                log("收到 quit，退出");
                mainHandler.postDelayed(() -> System.exit(0), 300);
                break;
            default:
                log("未知命令: " + op);
        }
        writeState();
    }

    private static int slotIndex(String[] parts) {
        if (parts.length < 2) {
            throw new IllegalArgumentException("缺少 slot 参数");
        }
        int slot = parseInt(parts[1], 0);
        if (slot < 0 || slot >= slots.length) {
            throw new IllegalArgumentException("slot 越界: " + slot);
        }
        return slot;
    }

    // =====================================================================
    //  虚拟显示
    // =====================================================================
    private static void ensureDisplay(int slotIndex) throws Exception {
        Slot s = slots[slotIndex];
        if (s.isActive()) {
            return;
        }
        int flags = FLAG_PUBLIC
                | FLAG_PRESENTATION
                | FLAG_OWN_CONTENT_ONLY
                | FLAG_SUPPORTS_TOUCH
                | FLAG_ROTATES_WITH_CONTENT
                | FLAG_TRUSTED
                | FLAG_ALWAYS_UNLOCKED
                | FLAG_TOUCH_FEEDBACK_DISABLED
                | FLAG_OWN_FOCUS;
        if (cfgDeviceDisplayGroup) {
            flags |= FLAG_DEVICE_DISPLAY_GROUP;
        } else {
            flags |= FLAG_OWN_DISPLAY_GROUP;
        }
        if (Build.VERSION.SDK_INT < 33) {
            flags &= ~(FLAG_TRUSTED | FLAG_ALWAYS_UNLOCKED | FLAG_TOUCH_FEEDBACK_DISABLED | FLAG_OWN_FOCUS);
            flags |= FLAG_OWN_DISPLAY_GROUP;
        }

        Surface surface = cfgSurfaceMode.equals("reader") ? createReaderSurface(s) : null;
        String name = "holder-slot" + slotIndex;
        VirtualDisplay vd = (VirtualDisplay) createVirtualDisplayMethod.invoke(
                displayManager, name, cfgWidth, cfgHeight, cfgDpi, surface, flags);
        if (vd == null) {
            throw new IllegalStateException("createVirtualDisplay 返回 null");
        }
        s.vd = vd;
        s.displayId = vd.getDisplay().getDisplayId();
        log("slot" + slotIndex + ": 创建显示 " + cfgWidth + "x" + cfgHeight + "/" + cfgDpi
                + " id=" + s.displayId);
    }

    private static Surface createReaderSurface(Slot s) {
        if (s.reader != null) {
            return s.reader.getSurface();
        }
        // maxImages 越小越省内存；1 足够，避免堆积
        ImageReader reader = ImageReader.newInstance(cfgWidth, cfgHeight, android.graphics.PixelFormat.RGBA_8888, 2);
        // 立刻取走并关闭，保持 SurfaceFlinger 的生产者不被反压阻塞
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
            } catch (Throwable ignored) {
            } finally {
                if (img != null) {
                    img.close();
                }
            }
        }, mainHandler);
        s.reader = reader;
        return reader.getSurface();
    }

    private static void releaseSlot(Slot s) {
        if (s.vd != null) {
            try {
                s.vd.release();
            } catch (Throwable t) {
                log("release 显示失败: " + t);
            }
            s.vd = null;
        }
        if (s.reader != null) {
            try {
                s.reader.close();
            } catch (Throwable ignored) {
            }
            s.reader = null;
        }
        s.displayId = -1;
        s.pkg = "";
        log("slot" + s.index + ": 已释放");
    }

    // =====================================================================
    //  应用启停
    // =====================================================================
    private static void launch(int slotIndex, String pkg) throws Exception {
        Slot s = slots[slotIndex];
        ensureDisplay(slotIndex);
        s.pkg = pkg;

        String component = resolveLauncherComponent(pkg);
        List<String> cmd = new ArrayList<>();
        cmd.add("/system/bin/am");
        cmd.add("start");
        cmd.add("--display");
        cmd.add(String.valueOf(s.displayId));
        cmd.add("--activity-reset-task-if-needed");
        if (component != null && !component.isEmpty()) {
            cmd.add("-n");
            cmd.add(component);
        } else {
            cmd.add("-a");
            cmd.add("android.intent.action.MAIN");
            cmd.add("-c");
            cmd.add("android.intent.category.LAUNCHER");
            cmd.add("-p");
            cmd.add(pkg);
        }
        String out = exec(cmd);
        log("slot" + slotIndex + " launch " + pkg + " -> " + oneLine(out));
        s.error = "";

        // am start 失败（应用不支持虚拟显示 / 不存在）时把 slot 标成 error，
        // 让主控端立刻看到，而不是对着一个"running 但没画面"的格子等超时
        if (out != null && out.contains("Error")) {
            s.error = oneLine(out);
            writeState();
            log("slot" + slotIndex + " launch failed: " + oneLine(out));
        }
    }

    private static void killSlot(int slotIndex) throws Exception {
        Slot s = slots[slotIndex];
        String pkg = s.pkg;
        releaseSlot(s);
        if (pkg != null && !pkg.isEmpty()) {
            String out = exec(listOf("/system/bin/am", "force-stop", pkg));
            log("slot" + slotIndex + " force-stop " + pkg + " -> " + oneLine(out));
        }
    }

    private static String resolveLauncherComponent(String pkg) {
        try {
            String out = exec(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief",
                    "-c", "android.intent.category.LAUNCHER", pkg));
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.contains("/") && t.startsWith(pkg)) {
                    return t;
                }
            }
        } catch (Throwable t) {
            log("resolve-activity 失败 " + pkg + ": " + t);
        }
        return null;
    }

    private static List<String> listOf(String... items) {
        List<String> l = new ArrayList<>();
        for (String i : items) {
            l.add(i);
        }
        return l;
    }

    private static String exec(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        java.lang.Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        p.waitFor();
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').trim();
    }

    // =====================================================================
    //  状态文件
    // =====================================================================
    /**
     * 按上次的 slot 记录重建显示并重新拉起应用。
     * holder 被守护进程重启、或手机重启后，挂机位能自动恢复，不需要主控端再操作一次。
     */
    /** 读取「期望状态」文件（必须在首次 writeState 之前调用，否则会被空状态覆盖）。 */
    private static List<String[]> readDesired() {
        List<String[]> out = new ArrayList<>();
        if (desiredFile == null || !desiredFile.exists()) {
            log("restore: 无历史记录，跳过");
            return out;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(desiredFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) {
                    continue;
                }
                int slot = parseInt(p[0], -1);
                if (slot < 0 || slot >= slots.length) {
                    continue;
                }
                String pkg = p[1].trim();
                if (!pkg.isEmpty()) {
                    out.add(new String[]{String.valueOf(slot), pkg});
                }
            }
        } catch (IOException e) {
            log("restore 读取失败: " + e);
        }
        return out;
    }

    private static void restoreSlots(List<String[]> pending) {
        if (pending.isEmpty()) {
            return;
        }
        for (String[] e : pending) {
            int slot = parseInt(e[0], -1);
            String pkg = e[1];
            if (slot < 0 || slot >= slots.length) {
                continue;
            }
            try {
                log("restore: slot" + slot + " -> " + pkg);
                launch(slot, pkg);
            } catch (Throwable t) {
                log("restore slot" + slot + " 失败: " + t);
            }
        }
        writeState();
    }

    /** 记录「期望状态」（哪些 slot 应该跑哪个包），供重启后恢复。 */
    private static void writeDesired() {
        if (desiredFile == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            if (!s.pkg.isEmpty()) {
                sb.append(s.index).append(' ').append(s.pkg).append('\n');
            }
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(desiredFile, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("写 desired 失败: " + e);
        }
        relaxPermissions(desiredFile, false);
    }

    private static void writeState() {
        if (stateFile == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"pid\": ").append(Process.myPid()).append(",\n");
        sb.append("  \"width\": ").append(cfgWidth).append(",\n");
        sb.append("  \"height\": ").append(cfgHeight).append(",\n");
        sb.append("  \"dpi\": ").append(cfgDpi).append(",\n");
        sb.append("  \"slots\": [\n");
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            sb.append("    {\"slot\": ").append(s.index)
                    .append(", \"displayId\": ").append(s.displayId)
                    .append(", \"package\": \"").append(jsonEscape(s.pkg)).append("\"")
                    .append(", \"state\": \"").append(!s.error.isEmpty() ? "error" : (s.isActive() ? (s.pkg.isEmpty() ? "empty" : "running") : "empty"))
                    .append("\"")
                    .append(", \"error\": \"").append(jsonEscape(s.error)).append("\"")
                    .append("}");
            sb.append(i == slots.length - 1 ? "\n" : ",\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        File tmp = new File(cfgDir, "state.json.tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            log("写 state 失败: " + e);
            return;
        }
        File dst = new File(cfgDir, "state.json");
        if (!tmp.renameTo(dst)) {
            // rename 失败时退化为直接写
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dst, false)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log("写 state 失败(2): " + e);
            }
        }
        relaxPermissions(dst, false);
        writeDesired();
    }

    /** 让主控端（adb shell / app 进程）能读写状态与命令文件。 */
    private static void relaxPermissions(File f, boolean dir) {
        try {
            f.setReadable(true, false);
            f.setWritable(true, false);
            if (dir) {
                f.setExecutable(true, false);
            }
        } catch (Throwable t) {
            log("chmod 失败 " + f + ": " + t);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // =====================================================================
    //  DisplayManager（反射，照抄 scrcpy wrappers/DisplayManager）
    // =====================================================================
    private static void initDisplayManager() throws Exception {
        Class<?> dmClass = Class.forName("android.hardware.display.DisplayManager");
        Constructor<?> ctor = dmClass.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        displayManager = ctor.newInstance(FakeContext.get());

        createVirtualDisplayMethod = dmClass.getMethod("createVirtualDisplay",
                String.class, int.class, int.class, int.class, Surface.class, int.class);
        createVirtualDisplayMethod.setAccessible(true);
    }

    // =====================================================================
    //  Workarounds（反射，照抄 scrcpy Workarounds）
    // =====================================================================
    static final class Workarounds {
        private static Class<?> activityThreadClass;
        private static Object activityThread;

        static void apply() {
            try {
                activityThreadClass = Class.forName("android.app.ActivityThread");
                Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                activityThread = ctor.newInstance();
                log("W: ActivityThread 实例化 OK");

                Field f = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                f.set(null, activityThread);

                Field mSystemThread = activityThreadClass.getDeclaredField("mSystemThread");
                mSystemThread.setAccessible(true);
                mSystemThread.setBoolean(activityThread, true);
            } catch (Throwable t) {
                log("Workarounds: ActivityThread 初始化失败: " + t);
                return;
            }

            log("W: SDK=" + Build.VERSION.SDK_INT);
            if (Build.VERSION.SDK_INT >= 31) {
                fillConfigurationController();
            }
            if (!"ONYX".equalsIgnoreCase(Build.BRAND)) {
                fillAppInfo();
            }
            log("W: fillAppInfo/config 完成");
            fillAppContext();
            log("W: fillAppContext 完成");
        }

        static Context getSystemContext() {
            try {
                Method m = activityThreadClass.getDeclaredMethod("getSystemContext");
                m.setAccessible(true);
                return (Context) m.invoke(activityThread);
            } catch (Throwable t) {
                log("Workarounds: getSystemContext 失败: " + t);
                return null;
            }
        }

        private static void fillAppInfo() {
            try {
                Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
                Constructor<?> ctor = appBindDataClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object appBindData = ctor.newInstance();

                android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
                ai.packageName = FakeContext.PACKAGE_NAME;

                Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
                appInfoField.setAccessible(true);
                appInfoField.set(appBindData, ai);

                Field boundApp = activityThreadClass.getDeclaredField("mBoundApplication");
                boundApp.setAccessible(true);
                boundApp.set(activityThread, appBindData);
            } catch (Throwable t) {
                log("Workarounds: fillAppInfo 跳过 (" + t + ")");
            }
        }

        private static void fillAppContext() {
            try {
                android.app.Application app =
                        android.app.Instrumentation.newApplication(android.app.Application.class, FakeContext.get());
                Field f = activityThreadClass.getDeclaredField("mInitialApplication");
                f.setAccessible(true);
                f.set(activityThread, app);
            } catch (Throwable t) {
                log("Workarounds: fillAppContext 跳过 (" + t + ")");
            }
        }

        private static void fillConfigurationController() {
            try {
                Class<?> ccClass = Class.forName("android.app.ConfigurationController");
                Class<?> atInternalClass = Class.forName("android.app.ActivityThreadInternal");
                Constructor<?> ctor = ccClass.getDeclaredConstructor(atInternalClass);
                ctor.setAccessible(true);
                Object cc = ctor.newInstance(activityThread);

                Field f = activityThreadClass.getDeclaredField("mConfigurationController");
                f.setAccessible(true);
                f.set(activityThread, cc);
            } catch (Throwable t) {
                log("Workarounds: fillConfigurationController 跳过 (" + t + ")");
            }
        }
    }

    // =====================================================================
    //  FakeContext（照抄 scrcpy FakeContext，去掉 ContentResolver 相关）
    // =====================================================================
    static final class FakeContext extends ContextWrapper {
        // 包名必须与调用方 uid 匹配，否则 Android 12 的 DisplayManagerService 直接拒：
        //   java.lang.SecurityException: packageName must match the calling uid
        // 以 root(uid 0) 运行时用 "android"；以 shell(uid 2000) 运行时用
        // "com.android.shell"。按实际 uid 选，避免在部分 ROM 上建不出显示。
        static final String PACKAGE_NAME =
                android.os.Process.myUid() == 0 ? "android" : "com.android.shell";
        private static FakeContext instance;

        static synchronized FakeContext get() {
            if (instance == null) {
                instance = new FakeContext(Workarounds.getSystemContext());
            }
            return instance;
        }

        private FakeContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public AttributionSource getAttributionSource() {
            AttributionSource.Builder builder = new AttributionSource.Builder(Process.SHELL_UID);
            builder.setPackageName(PACKAGE_NAME);
            return builder.build();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            return this;
        }
    }

    // =====================================================================
    private static java.io.File bootLogFile;

    private static void log(String msg) {
        String line = "[" + TAG + "] " + msg;
        System.out.println(line);
        System.out.flush();
        // Android 12 的 app_process 不把 System.out 接到 logcat/ssh（MIUI 实测静默），
        // 同步落一份到状态目录，启动卡死时能看出卡在哪一步
        if (bootLogFile != null) {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(bootLogFile, true)) {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
        }
    }
}
