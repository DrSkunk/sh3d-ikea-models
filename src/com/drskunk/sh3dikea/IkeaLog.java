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
 */
public final class IkeaLog {

    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Logging is disabled in release builds so users don't accumulate a debug
     * file they never asked for. {@code make debug} bakes {@code debug=true}
     * into {@code build.properties} inside the jar; the system property
     * {@code ikeabrowser.debug=true} also enables it without a rebuild.
     */
    private static final boolean ENABLED = readDebugFlag();

    private static final File LOG_FILE = ENABLED ? resolveLogFile() : null;

    private static boolean readDebugFlag() {
        if ("true".equalsIgnoreCase(System.getProperty("ikeabrowser.debug"))) {
            return true;
        }
        try (InputStream in = IkeaLog.class.getResourceAsStream(
                "/com/drskunk/sh3dikea/build.properties")) {
            if (in == null) return false;
            Properties p = new Properties();
            p.load(in);
            return "true".equalsIgnoreCase(p.getProperty("debug", "false"));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isEnabled() { return ENABLED; }

    private static File resolveLogFile() {
        try {
            File appFolder = OperatingSystem.getDefaultApplicationFolder();
            File dir = new File(appFolder, "IkeaBrowser");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "debug.log");
        } catch (IOException e) {
            return new File(System.getProperty("user.home"), "ikeabrowser-debug.log");
        }
    }

    public static File logFile() { return LOG_FILE; }

    public static void info(String msg) {
        if (ENABLED) write("INFO ", msg, null);
    }

    public static void warn(String msg, Throwable t) {
        if (ENABLED) write("WARN ", msg, t);
    }

    public static void error(String msg, Throwable t) {
        if (ENABLED) write("ERROR", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        if (LOG_FILE == null) return;
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.print("[" + TS.format(new Date()) + "] " + level + " ");
            pw.println(msg);
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                pw.println(sw.toString().trim());
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private IkeaLog() {}
}
