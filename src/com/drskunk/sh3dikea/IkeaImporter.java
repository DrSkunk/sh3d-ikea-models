package com.drskunk.sh3dikea;

import com.drskunk.sh3dikea.glb.GlbToObj;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.tools.URLContent;
import com.eteks.sweethome3d.viewcontroller.FurnitureController;
import com.eteks.sweethome3d.viewcontroller.HomeController;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;

/**
 * Coordinates the heavy lifting: download GLB, convert to an OBJ ZIP (or
 * fall back to a textured-box placeholder), build a {@link HomePieceOfFurniture},
 * and add it to the home via {@link FurnitureController}.
 */
public final class IkeaImporter {

    private static final float MM_TO_CM = 0.1f;
    private static final float METERS_TO_CM = 100f;
    /** Defaults if the API doesn't report measurements: a 50 cm cube. */
    private static final float DEFAULT_SIZE_CM = 50f;
    /** SH3D rejects zero-dimension furniture; clamp small/missing values. */
    private static final float MIN_DIM_CM = 1f;

    private final IkeaApiClient api;
    private final IkeaCache cache;

    public IkeaImporter(IkeaApiClient api, IkeaCache cache) {
        this.api = api;
        this.cache = cache;
    }

    public static final class Prepared {
        public final IkeaProduct product;
        public final Content modelContent;
        public final Content iconContent;
        public final float widthCm;
        public final float depthCm;
        public final float heightCm;
        /** True when we couldn't decode the GLB and fell back to a textured box. */
        public final boolean placeholder;

        Prepared(IkeaProduct p, Content modelContent, Content iconContent,
                 float widthCm, float depthCm, float heightCm, boolean placeholder) {
            this.product = p;
            this.modelContent = modelContent;
            this.iconContent = iconContent;
            this.widthCm = widthCm;
            this.depthCm = depthCm;
            this.heightCm = heightCm;
            this.placeholder = placeholder;
        }
    }

    /**
     * Downloads + converts (or falls back) and returns everything needed to
     * place the piece. Run off the EDT — does network and file IO.
     */
    public Prepared prepare(IkeaProduct product) throws Exception {
        String itemNo = IkeaProduct.compactItemNo(product.itemNo);
        IkeaLog.info("Preparing import for " + product.name + " (" + itemNo + ")"
                + ", Draco available=" + com.drskunk.sh3dikea.draco.DracoNative.isAvailable()
                + (com.drskunk.sh3dikea.draco.DracoNative.isAvailable() ? "" :
                        " (load error: " + com.drskunk.sh3dikea.draco.DracoNative.getLoadError() + ")"));

        // Thumbnail: best-effort cache.
        File thumbFile = cache.thumbnailFile(itemNo);
        if (!thumbFile.exists() && product.mainImageUrl != null) {
            byte[] thumb = api.download(product.mainImageUrl);
            IkeaCache.writeAtomically(thumbFile, thumb);
        }

        // Always fetch the model info — we want measurements even when the
        // GLB is undecodable, and the existence check is implicit (404 if
        // there's no model).
        if (!api.modelExists(itemNo)) {
            throw new IkeaException("IKEA reports no 3D model is available for "
                    + IkeaProduct.formatItemNo(itemNo));
        }
        IkeaApiClient.ModelInfo info = api.getModelInfo(itemNo);

        File bundle = cache.objBundleFile(itemNo);
        File glb = cache.glbFile(itemNo);
        Bounds saved = readCachedBounds(itemNo);
        boolean placeholder = false;

        // Re-convert when the cache holds a placeholder but Draco is now
        // available — covers the upgrade path from the pre-JNI build.
        boolean staleCache = saved != null && saved.placeholder
                && com.drskunk.sh3dikea.draco.DracoNative.isAvailable();
        if (bundle.exists() && saved != null && !staleCache) {
            placeholder = saved.placeholder;
        } else {
            byte[] glbData = api.download(info.modelUrl);
            IkeaCache.writeAtomically(glb, glbData);
            try {
                GlbToObj.Result r = GlbToObj.convert(glbData, bundle);
                saved = new Bounds(false,
                        r.widthMeters() * METERS_TO_CM,
                        r.depthMeters() * METERS_TO_CM,
                        r.heightMeters() * METERS_TO_CM);
            } catch (GlbToObj.UnsupportedExtensionException ex) {
                // Fall back to a placeholder box; we still have the photo + measurements.
                PlaceholderBox.build(thumbFile, bundle);
                placeholder = true;
                saved = new Bounds(true, 0, 0, 0);
            }
            writeCachedBounds(itemNo, saved);
        }

        URL bundleUrl = new URL("jar:" + bundle.toURI().toURL() + "!/model.obj");
        Content modelContent = new URLContent(bundleUrl);
        Content iconContent = thumbFile.exists()
                ? new URLContent(thumbFile.toURI().toURL())
                : null;
        IkeaLog.info("Bundle URL: " + bundleUrl + " (placeholder=" + placeholder + ")");

        // Probe-load via the very same SH3D loader the placement will use.
        // If this throws, we capture the actual error in debug.log instead
        // of letting Sweet Home 3D silently substitute its red error block.
        // Uses reflection to avoid a compile-time dependency on j3dcore/vecmath jars
        // (they ship with the SH3D desktop app but not in the standalone jar used in CI).
        try {
            Class<?> mmClass = Class.forName("com.eteks.sweethome3d.j3d.ModelManager");
            Object mm = mmClass.getMethod("getInstance").invoke(null);
            Object node = mmClass.getMethod("loadModel", Content.class).invoke(mm, modelContent);
            Object size = mmClass.getMethod("getSize", Class.forName("javax.media.j3d.Node"))
                                 .invoke(mm, node);
            IkeaLog.info("Model probe-load OK: size " + size);
        } catch (Throwable t) {
            IkeaLog.error("Model probe-load FAILED for " + bundleUrl, t);
        }

        // Measurements priority: official IKEA mm > GLB-derived bounds > defaults.
        float w = pickDimension(info.widthMm,  saved.widthCm,  DEFAULT_SIZE_CM);
        float d = pickDimension(info.depthMm,  saved.depthCm,  DEFAULT_SIZE_CM);
        float h = pickDimension(info.heightMm, saved.heightCm, DEFAULT_SIZE_CM);

        return new Prepared(product, modelContent, iconContent, w, d, h, placeholder);
    }

    private static float pickDimension(Float ikeaMm, float fromBoundsCm, float fallback) {
        if (ikeaMm != null && ikeaMm > 0) return Math.max(MIN_DIM_CM, ikeaMm * MM_TO_CM);
        if (fromBoundsCm > 0) return Math.max(MIN_DIM_CM, fromBoundsCm);
        return fallback;
    }

    public HomePieceOfFurniture addToHome(HomeController homeController, Home home, Prepared p) {
        String displayName = p.product.name != null && !p.product.name.isEmpty()
                ? p.product.name
                : IkeaProduct.formatItemNo(p.product.itemNo);

        // CatalogPieceOfFurniture(name, icon, model, width, depth, height, movable, doorOrWindow)
        // — icon comes BEFORE model in this overload. Getting them backwards
        // makes Sweet Home 3D treat the JPEG thumbnail as the 3D model and
        // render the piece as its red "broken model" placeholder.
        CatalogPieceOfFurniture catalogPiece = new CatalogPieceOfFurniture(
                displayName,
                p.iconContent,
                p.modelContent,
                p.widthCm, p.depthCm, p.heightCm,
                true, false);

        HomePieceOfFurniture piece = new HomePieceOfFurniture(catalogPiece);
        piece.setName(displayName);
        String description = "IKEA item " + IkeaProduct.formatItemNo(p.product.itemNo);
        if (p.placeholder) description += " (placeholder box — full 3D model unavailable)";
        piece.setDescription(description);
        piece.setCatalogId("ikea/" + IkeaProduct.compactItemNo(p.product.itemNo));
        piece.setLevel(home.getSelectedLevel());

        Camera camera = home.getCamera();
        if (camera != null) {
            piece.setX(camera.getX());
            piece.setY(camera.getY());
        }

        FurnitureController fc = homeController.getFurnitureController();
        fc.addFurniture(Collections.singletonList(piece));
        return piece;
    }

    // --- bounds cache: persist sizes so we don't redownload on each session ---

    private static final class Bounds {
        final boolean placeholder;
        final float widthCm, depthCm, heightCm;
        Bounds(boolean placeholder, float widthCm, float depthCm, float heightCm) {
            this.placeholder = placeholder;
            this.widthCm = widthCm;
            this.depthCm = depthCm;
            this.heightCm = heightCm;
        }
    }

    private Bounds readCachedBounds(String itemNo) {
        try {
            File f = boundsFile(itemNo);
            if (!f.exists()) return null;
            String[] parts = new String(Files.readAllBytes(f.toPath()), "UTF-8").trim().split("\\s+");
            if (parts.length != 4) return null;
            return new Bounds(
                    Boolean.parseBoolean(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]));
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCachedBounds(String itemNo, Bounds b) {
        try {
            File f = boundsFile(itemNo);
            String text = b.placeholder + " " + b.widthCm + " " + b.depthCm + " " + b.heightCm;
            IkeaCache.writeAtomically(f, text.getBytes("UTF-8"));
        } catch (Exception ignored) {
            // best effort; we'll rebuild next time
        }
    }

    private File boundsFile(String itemNo) throws java.io.IOException {
        return new File(cache.getDir(itemNo), "bounds.txt");
    }

    public static final class IkeaException extends Exception {
        public IkeaException(String message) { super(message); }
    }
}
