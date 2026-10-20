package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.tools.OperatingSystem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * Tiny logger that appends to {@code IkeaBrowser/debug.log} alongside the
 * cache. We use this rather than {@code System.err} because Sweet Home 3D's
 * macOS app bundle swallows stderr; a file in a known location is easy to
 * point a user at when something goes wrong.
 *
 * WARN and ERROR entries are always written so users can attach the log to
 * a bug report; the file is only created once something actually fails.
 * INFO entries are written only in debug builds ({@code make debug} bakes
 * {@code debug=true} into {@code build.properties}) or when the system
 * property {@code ikeabrowser.debug=true} is set.
 */
public final class IkeaLog {

    private static final long MAX_BYTES = 1024 * 1024;

    private static final Properties BUILD = readBuildProperties();

    private static final boolean DEBUG =
            "true".equalsIgnoreCase(System.getProperty("ikeabrowser.debug"))
                    || "true".equalsIgnoreCase(BUILD.getProperty("debug", "false"));

    private static File logFile;
    private static boolean headerWritten;

    private static Properties readBuildProperties() {
        Properties p = new Properties();
        try (InputStream in = IkeaLog.class.getResourceAsStream(
                "/com/drskunk/sh3dikea/build.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // defaults
        }
        return p;
    }

    public static boolean isDebugEnabled() { return DEBUG; }

    public static String pluginVersion() { return BUILD.getProperty("version", "unknown"); }

    /** Location of the log file (it may not exist yet if nothing was logged). */
    public static synchronized File logFile() {
        if (logFile == null) logFile = resolveLogFile();
        return logFile;
    }

    private static File resolveLogFile() {
        try {
            return new File(new File(OperatingSystem.getDefaultApplicationFolder(), "IkeaBrowser"),
                    "debug.log");
        } catch (Throwable t) {
            return new File(System.getProperty("user.home"), "ikeabrowser-debug.log");
        }
    }

    public static void info(String msg) {
        if (DEBUG) write("INFO ", msg, null);
    }

    public static void warn(String msg, Throwable t) {
        write("WARN ", msg, t);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    /** One-line description of the runtime, handy at the top of bug reports. */
    public static String environment() {
        String sandbox = "none";
        if (System.getenv("FLATPAK_ID") != null || new File("/.flatpak-info").exists()) {
            sandbox = "flatpak (" + System.getenv("FLATPAK_ID") + ")";
        } else if (System.getenv("SNAP") != null) {
            sandbox = "snap (" + System.getenv("SNAP_NAME") + ")";
        }
        return "plugin=" + pluginVersion()
                + ", os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + ", arch=" + System.getProperty("os.arch")
                + ", java=" + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")"
                + ", sandbox=" + sandbox;
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        File file = logFile();
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            rotateIfNeeded(file);
            try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                if (!headerWritten) {
                    headerWritten = true;
                    pw.println("[" + ts + "] INFO  Session start: " + environment());
                }
                pw.println("[" + ts + "] " + level + " " + msg);
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    pw.println(sw.toString().trim());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private static void rotateIfNeeded(File file) {
        if (file.length() < MAX_BYTES) return;
        File old = new File(file.getParentFile(), file.getName() + ".1");
        if (old.exists()) old.delete();
        file.renameTo(old);
    }

    private IkeaLog() {}
}
