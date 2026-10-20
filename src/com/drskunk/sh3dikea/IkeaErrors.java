package com.drskunk.sh3dikea;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Turns exceptions into messages a user can act on. */
final class IkeaErrors {

    private static final String FLATPAK_HINT =
            "Sweet Home 3D from Flathub has no network access by default. Grant it with:\n"
                    + "  flatpak override --user --share=network com.sweethome3d.Sweethome3d\n"
                    + "(or enable \"Network\" in Flatseal) and restart Sweet Home 3D.";

    /** Unwraps SwingWorker/reflection wrappers to the meaningful cause. */
    static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof java.util.concurrent.ExecutionException
                || cur instanceof java.lang.reflect.InvocationTargetException)
                && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    /** Short single-line summary, e.g. for a status bar. */
    static String summary(Throwable t) {
        Throwable c = rootCause(t);
        if (c instanceof UnknownHostException) {
            return "cannot resolve host " + c.getMessage() + " (no network access?)";
        }
        if (c instanceof SocketTimeoutException) return "connection timed out";
        if (c instanceof ConnectException || c instanceof NoRouteToHostException) {
            return "cannot connect (" + c.getMessage() + ")";
        }
        if (c instanceof SSLException) return "secure connection failed (" + c.getMessage() + ")";
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    /** Multi-line explanation with a hint at how to fix it, plus the log location. */
    static String details(Throwable t) {
        Throwable c = rootCause(t);
        StringBuilder sb = new StringBuilder(summary(c));
        if (c instanceof UnknownHostException || c instanceof ConnectException
                || c instanceof NoRouteToHostException) {
            sb.append("\n\n");
            if (isFlatpak()) {
                sb.append(FLATPAK_HINT);
            } else {
                sb.append("Check your internet connection, proxy, firewall, or DNS filter.\n")
                        .append("If you installed Sweet Home 3D with Flatpak:\n").append(FLATPAK_HINT);
            }
        } else if (c instanceof SSLException) {
            sb.append("\n\nYour Java runtime may be missing up-to-date root certificates, ")
                    .append("or a proxy is intercepting HTTPS traffic.");
        } else if (c instanceof FileNotFoundException) {
            sb.append("\n\nIKEA returned no data for this request.");
        }
        File log = IkeaLog.logFile();
        sb.append("\n\nDetails were written to:\n").append(log.getAbsolutePath());
        return sb.toString();
    }

    static boolean isFlatpak() {
        return System.getenv("FLATPAK_ID") != null || new File("/.flatpak-info").exists();
    }

    private IkeaErrors() {}
}
