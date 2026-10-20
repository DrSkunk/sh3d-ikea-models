package com.drskunk.sh3dikea.draco;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the bundled JNI library that calls libdraco.
 *
 * The library is shipped inside this jar under
 * {@code /com/drskunk/sh3dikea/draco/native/<os>-<arch>/<libname>}, extracted
 * to a per-user temp directory (falling back to a cache directory under the
 * user's home) the first time {@link #isAvailable()} is queried, and loaded
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
                loadError = describe(t);
                available = Boolean.FALSE;
            }
            return available;
        }
    }

    /** A short human-readable description of why loading failed. */
    public static String getLoadError() {
        synchronized (LOCK) { return loadError; }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
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
        if (DracoNative.class.getResource(resourcePath) == null) {
            throw new IOException("No bundled Draco library for " + os + "-" + arch
                    + " (looked for " + resourcePath + ")");
        }

        // Try each candidate directory in turn: the temp dir can be mounted
        // noexec or owned by another user, in which case loading fails there.
        StringBuilder errors = new StringBuilder();
        for (File dir : extractionDirs(os)) {
            try {
                File extracted = extract(resourcePath, dir, libFile);
                System.load(extracted.getAbsolutePath());
                return;
            } catch (IOException | UnsatisfiedLinkError | SecurityException e) {
                if (errors.length() > 0) errors.append("; ");
                errors.append(dir).append(": ").append(describe(e));
            }
        }
        throw new IOException("Could not load " + libFile + " for " + os + "-" + arch + " (" + errors + ")");
    }

    private static List<File> extractionDirs(String os) {
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(System.getProperty("java.io.tmpdir"), "sh3d-ikea-draco-" + user));
        String home = System.getProperty("user.home");
        if (home != null) {
            if ("linux".equals(os)) {
                String xdg = System.getenv("XDG_CACHE_HOME");
                File cache = xdg != null && !xdg.isEmpty() ? new File(xdg) : new File(home, ".cache");
                dirs.add(new File(cache, "sh3d-ikea-draco"));
            } else {
                dirs.add(new File(home, ".sh3d-ikea-draco"));
            }
        }
        return dirs;
    }

    private static File extract(String resourcePath, File dir, String libFile) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory");
        }
        File extracted = new File(dir, libFile);
        // Always overwrite — handles upgrades cleanly.
        File tmp = File.createTempFile("dracojni-", ".tmp", dir);
        try (InputStream in = DracoNative.class.getResourceAsStream(resourcePath);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Resource disappeared: " + resourcePath);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        try {
            Files.move(tmp.toPath(), extracted.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmp.delete();
            // Windows can't replace a DLL that another SH3D instance has loaded.
            if (!extracted.isFile()) throw e;
        }
        return extracted;
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
