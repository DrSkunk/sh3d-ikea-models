package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.tools.OperatingSystem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * On-disk cache for thumbnails, downloaded GLBs, and the converted OBJ
 * bundles. Layout:
 *
 *   {appdata}/IkeaBrowser/cache/{itemNo}/thumbnail.jpg
 *   {appdata}/IkeaBrowser/cache/{itemNo}/model.glb
 *   {appdata}/IkeaBrowser/cache/{itemNo}/model.zip   (OBJ + MTL + textures)
 *
 * The application folder lives next to Sweet Home 3D's own preferences
 * folder so files survive plugin upgrades and uninstalls.
 */
public final class IkeaCache {

    private final File root;

    public IkeaCache() throws IOException {
        File appFolder = OperatingSystem.getDefaultApplicationFolder();
        this.root = new File(appFolder, "IkeaBrowser/cache");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Could not create cache directory: " + root);
        }
    }

    public File getDir(String itemNo) throws IOException {
        File d = new File(root, itemNo);
        if (!d.exists() && !d.mkdirs()) {
            throw new IOException("Could not create cache directory: " + d);
        }
        return d;
    }

    public File thumbnailFile(String itemNo) throws IOException {
        return new File(getDir(itemNo), "thumbnail.jpg");
    }

    public File glbFile(String itemNo) throws IOException {
        return new File(getDir(itemNo), "model.glb");
    }

    public File objBundleFile(String itemNo) throws IOException {
        return new File(getDir(itemNo), "model.zip");
    }

    public static void writeAtomically(File dest, byte[] data) throws IOException {
        File tmp = File.createTempFile(dest.getName() + ".", ".tmp", dest.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
        }
        Files.move(tmp.toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
