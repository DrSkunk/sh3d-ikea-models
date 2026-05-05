package com.drskunk.sh3dikea;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates a unit cube OBJ ZIP suitable for use as the {@code Content} of a
 * {@link com.eteks.sweethome3d.model.HomePieceOfFurniture}. Used when we can't
 * decode the original GLB (e.g. Draco-compressed) but have a thumbnail and
 * the official IKEA measurements.
 *
 * The cube spans -0.5..0.5 on each axis; SH3D's furniture sizing then scales
 * it to whatever width / depth / height we set on the piece. The product
 * photo is mapped onto the front face (positive Z); the other faces share
 * the same texture so the box looks right from any angle without giving the
 * user the impression of an empty rear surface.
 */
public final class PlaceholderBox {

    private static final String OBJ_NAME = "model.obj";
    private static final String MTL_NAME = "model.mtl";
    private static final String TEX_NAME = "thumbnail.jpg";

    private PlaceholderBox() {}

    /** Build a textured-cube ZIP at {@code dest} using {@code thumbnail} as the surface texture. */
    public static void build(File thumbnail, File dest) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(dest);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry(OBJ_NAME));
            writeObj(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(MTL_NAME));
            writeMtl(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
            zos.closeEntry();

            if (thumbnail != null && thumbnail.exists()) {
                zos.putNextEntry(new ZipEntry(TEX_NAME));
                zos.write(Files.readAllBytes(thumbnail.toPath()));
                zos.closeEntry();
            }
        }
    }

    private static void writeObj(Writer w) {
        PrintWriter pw = new PrintWriter(w);
        pw.println("# IKEA placeholder box (no decodable 3D model — using product photo)");
        pw.println("mtllib " + MTL_NAME);

        // 8 cube corners.
        pw.println("v -0.5 -0.5 -0.5");
        pw.println("v  0.5 -0.5 -0.5");
        pw.println("v  0.5  0.5 -0.5");
        pw.println("v -0.5  0.5 -0.5");
        pw.println("v -0.5 -0.5  0.5");
        pw.println("v  0.5 -0.5  0.5");
        pw.println("v  0.5  0.5  0.5");
        pw.println("v -0.5  0.5  0.5");

        // 4 UV corners.
        pw.println("vt 0.0 0.0");
        pw.println("vt 1.0 0.0");
        pw.println("vt 1.0 1.0");
        pw.println("vt 0.0 1.0");

        // Per-face normals.
        pw.println("vn  0  0  1"); // 1: front  (+Z)
        pw.println("vn  0  0 -1"); // 2: back   (-Z)
        pw.println("vn  1  0  0"); // 3: right  (+X)
        pw.println("vn -1  0  0"); // 4: left   (-X)
        pw.println("vn  0  1  0"); // 5: top    (+Y)
        pw.println("vn  0 -1  0"); // 6: bottom (-Y)

        pw.println("usemtl box");

        // Front (+Z): 5 6 7 8 — viewer looks from +Z, so wind CCW.
        pw.println("f 5/1/1 6/2/1 7/3/1 8/4/1");
        // Back (-Z).
        pw.println("f 2/1/2 1/2/2 4/3/2 3/4/2");
        // Right (+X).
        pw.println("f 6/1/3 2/2/3 3/3/3 7/4/3");
        // Left (-X).
        pw.println("f 1/1/4 5/2/4 8/3/4 4/4/4");
        // Top (+Y).
        pw.println("f 8/1/5 7/2/5 3/3/5 4/4/5");
        // Bottom (-Y).
        pw.println("f 1/1/6 2/2/6 6/3/6 5/4/6");
        pw.flush();
    }

    private static void writeMtl(Writer w) {
        PrintWriter pw = new PrintWriter(w);
        pw.println("newmtl box");
        pw.println("Kd 1.0 1.0 1.0");
        pw.println("Ka 0.05 0.05 0.05");
        pw.println("Ks 0.0 0.0 0.0");
        pw.println("map_Kd " + TEX_NAME);
        pw.flush();
    }
}
