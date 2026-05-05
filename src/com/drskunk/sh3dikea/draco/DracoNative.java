package com.drskunk.sh3dikea.draco;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Thin wrapper around the bundled JNI library that calls libdraco.
 *
 * The library is shipped inside this jar under
 * {@code /com/drskunk/sh3dikea/draco/native/<os>-<arch>/<libname>}, extracted
 * to a temp file the first time {@link #isAvailable()} is queried, and loaded
 * via {@link System#load(String)}. Loading is best-effort: if no binary
 * matches the current platform, the plugin falls back to placeholder boxes.
 */
public final class DracoNative {

    private static final Object LOCK = new Object();
    private static Boolean available; // null = not yet attempted
    private static String loadError;

    /**
     * Returns true if libdraco was successfully loaded on this platform.
     * The first call performs the load (idempotent thereafter).
     */
    public static boolean isAvailable() {
        synchronized (LOCK) {
            if (available != null) return available;
            try {
                tryLoad();
                available = Boolean.TRUE;
            } catch (Throwable t) {
                loadError = t.getMessage();
                available = Boolean.FALSE;
            }
            return available;
        }
    }

    /** A short human-readable description of why loading failed. */
    public static String getLoadError() {
        synchronized (LOCK) { return loadError; }
    }

    private static void tryLoad() throws IOException {
        String os = detectOs();
        String arch = detectArch();
        if (os == null || arch == null) {
            throw new IOException("Unsupported platform: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        }
        String libFile = libName(os);
        String resourcePath = "/com/drskunk/sh3dikea/draco/native/" + os + "-" + arch + "/" + libFile;

        try (InputStream in = DracoNative.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("No bundled Draco library for " + os + "-" + arch
                        + " (looked for " + resourcePath + ")");
            }
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "sh3d-ikea-draco");
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw new IOException("Cannot create temp dir " + tempDir);
            }
            File extracted = new File(tempDir, libFile);
            // Always overwrite — handles upgrades cleanly.
            File tmp = File.createTempFile("dracojni-", ".tmp", tempDir);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            Files.move(tmp.toPath(), extracted.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // System.load needs an absolute path.
            System.load(extracted.getAbsolutePath());
        }
    }

    private static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix"))    return "linux";
        if (os.contains("win"))                          return "windows";
        return null;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        return null;
    }

    private static String libName(String os) {
        switch (os) {
            case "macos":   return "libdracojni.dylib";
            case "linux":   return "libdracojni.so";
            case "windows": return "dracojni.dll";
            default: throw new IllegalArgumentException("Unknown os: " + os);
        }
    }

    /**
     * Decode a Draco-compressed buffer. {@code uniqueIds} lists the glTF
     * {@code unique_id} values for the attributes to extract (in any order);
     * pass -1 in a slot to leave it absent in the result.
     *
     * Returns a packed little-endian byte buffer; see {@link DracoMesh#parse}.
     * Throws {@link RuntimeException} if libdraco rejects the buffer.
     */
    public static native byte[] decode(byte[] dracoData, int[] uniqueIds);

    private DracoNative() {}
}
