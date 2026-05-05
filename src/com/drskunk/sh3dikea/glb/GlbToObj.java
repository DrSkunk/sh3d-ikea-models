package com.drskunk.sh3dikea.glb;

import com.drskunk.sh3dikea.json.Json;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Converts a binary glTF (GLB) v2 model into a ZIP bundle containing an
 * OBJ + MTL + extracted texture images. Sweet Home 3D's OBJLoader can read
 * an OBJ from inside a ZIP via a {@code jar:file:...!/model.obj} URL.
 *
 * Scope (good enough for IKEA's catalog models):
 *   - meshes with POSITION, NORMAL (optional), TEXCOORD_0 (optional)
 *   - indexed and non-indexed primitives
 *   - primitive modes: TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN
 *   - node TRS or matrix transforms, recursively composed
 *   - PBR baseColorFactor and baseColorTexture (PNG / JPEG)
 *
 * Not handled:
 *   - skins, animations, morph targets
 *   - lines, points
 *   - sparse accessors
 *   - external (non-GLB) buffers / image URIs
 *   - KTX2 / Draco — IKEA's models are uncompressed glTF
 */
public final class GlbToObj {

    private static final int CT_BYTE           = 5120;
    private static final int CT_UNSIGNED_BYTE  = 5121;
    private static final int CT_SHORT          = 5122;
    private static final int CT_UNSIGNED_SHORT = 5123;
    private static final int CT_UNSIGNED_INT   = 5125;
    private static final int CT_FLOAT          = 5126;

    private static final int MODE_TRIANGLES      = 4;
    private static final int MODE_TRIANGLE_STRIP = 5;
    private static final int MODE_TRIANGLE_FAN   = 6;

    private static final String OBJ_NAME = "model.obj";
    private static final String MTL_NAME = "model.mtl";

    /** Result of a conversion: bounds of the produced mesh, in glTF units (meters). */
    public static final class Result {
        public final float minX, minY, minZ, maxX, maxY, maxZ;
        public Result(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
        public float widthMeters()  { return Math.max(0, maxX - minX); }
        public float heightMeters() { return Math.max(0, maxY - minY); }
        public float depthMeters()  { return Math.max(0, maxZ - minZ); }
    }

    /** Thrown when the GLB uses an extension we can't decode (e.g. Draco). */
    public static final class UnsupportedExtensionException extends IOException {
        public UnsupportedExtensionException(String message) { super(message); }
    }

    /** Convert a GLB and write a ZIP bundle to {@code dest}. */
    public static Result convert(byte[] glb, File dest) throws IOException {
        GlbReader gr = GlbReader.read(glb);
        Map<String, Object> root = Json.asObject(Json.parse(gr.json));

        // We support uncompressed glTF and KHR_draco_mesh_compression (when
        // the bundled JNI library loaded). Anything else is fatal.
        List<Object> required = Json.asArray(root.get("extensionsRequired"));
        if (required != null) {
            for (Object o : required) {
                String ext = Json.asString(o);
                if (ext == null) continue;
                if (ext.equals("KHR_draco_mesh_compression")) {
                    if (!com.drskunk.sh3dikea.draco.DracoNative.isAvailable()) {
                        throw new UnsupportedExtensionException(
                                "Model is Draco-compressed and the bundled Draco "
                                        + "library could not be loaded: "
                                        + com.drskunk.sh3dikea.draco.DracoNative.getLoadError());
                    }
                    continue;
                }
                if (ext.equals("EXT_meshopt_compression")) {
                    throw new UnsupportedExtensionException(
                            "Model uses EXT_meshopt_compression which is not supported.");
                }
                // EXT_texture_webp etc. only affect material/texture assignment;
                // OBJ/MTL has no concept of texture format requirements, and our
                // own image decode is best-effort, so we let those slide.
            }
        }

        Doc doc = new Doc(root, gr.bin);
        Mesh out = new Mesh();
        for (int rootNode : doc.sceneRootNodes()) {
            walk(doc, rootNode, IDENTITY, out);
        }

        // Assemble texture entries (for materials referenced by output mesh).
        Map<Integer, ImageEntry> images = new HashMap<>();
        for (MaterialOut m : out.materials.values()) {
            if (m.baseColorImageIndex != null) {
                images.computeIfAbsent(m.baseColorImageIndex, k -> doc.extractImage(k));
            }
        }

        try (FileOutputStream fos = new FileOutputStream(dest);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry(OBJ_NAME));
            writeObj(out, new OutputStreamWriter(zos, StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(MTL_NAME));
            writeMtl(out, images, new OutputStreamWriter(zos, StandardCharsets.UTF_8));
            zos.closeEntry();

            for (Map.Entry<Integer, ImageEntry> e : images.entrySet()) {
                ImageEntry ie = e.getValue();
                if (ie == null) continue;
                zos.putNextEntry(new ZipEntry(ie.fileName));
                zos.write(ie.data);
                zos.closeEntry();
            }
        }

        return out.bounds();
    }

    // ---------------------------------------------------------------- writers

    private static void writeObj(Mesh m, Writer w) throws IOException {
        PrintWriter pw = new PrintWriter(w);
        pw.println("# Converted from glTF/GLB by sh3d-ikea-models");
        pw.println("mtllib " + MTL_NAME);

        for (float[] p : m.positions) {
            pw.printf(Locale.US, "v %.6f %.6f %.6f%n", p[0], p[1], p[2]);
        }
        for (float[] t : m.texcoords) {
            // OBJ V is bottom-up; glTF V is top-down. Flip.
            pw.printf(Locale.US, "vt %.6f %.6f%n", t[0], 1.0f - t[1]);
        }
        for (float[] n : m.normals) {
            pw.printf(Locale.US, "vn %.6f %.6f %.6f%n", n[0], n[1], n[2]);
        }

        boolean hasNormals = !m.normals.isEmpty();
        boolean hasTex = !m.texcoords.isEmpty();
        String currentMaterial = null;

        for (FaceGroup fg : m.faceGroups) {
            if (currentMaterial == null || !currentMaterial.equals(fg.material)) {
                currentMaterial = fg.material;
                pw.println("usemtl " + currentMaterial);
            }
            for (int[] tri : fg.tris) {
                pw.print("f");
                for (int idx : tri) {
                    int v = idx + 1;
                    if (hasTex && hasNormals) pw.print(" " + v + "/" + v + "/" + v);
                    else if (hasTex)          pw.print(" " + v + "/" + v);
                    else if (hasNormals)      pw.print(" " + v + "//" + v);
                    else                      pw.print(" " + v);
                }
                pw.println();
            }
        }
        pw.flush();
    }

    private static void writeMtl(Mesh m, Map<Integer, ImageEntry> images, Writer w) throws IOException {
        PrintWriter pw = new PrintWriter(w);
        pw.println("# Converted from glTF/GLB by sh3d-ikea-models");
        for (MaterialOut mat : m.materials.values()) {
            pw.println("newmtl " + mat.name);
            pw.printf(Locale.US, "Kd %.4f %.4f %.4f%n",
                    mat.baseColor[0], mat.baseColor[1], mat.baseColor[2]);
            pw.printf(Locale.US, "Ka %.4f %.4f %.4f%n", 0.05f, 0.05f, 0.05f);
            pw.println("Ks 0.0000 0.0000 0.0000");
            // Alpha < 1 marks the material as transparent.
            if (mat.baseColor[3] < 0.999f) {
                pw.printf(Locale.US, "d %.4f%n", mat.baseColor[3]);
                pw.printf(Locale.US, "Tr %.4f%n", 1.0f - mat.baseColor[3]);
            }
            if (mat.baseColorImageIndex != null) {
                ImageEntry ie = images.get(mat.baseColorImageIndex);
                if (ie != null) {
                    pw.println("map_Kd " + ie.fileName);
                }
            }
            pw.println();
        }
        pw.flush();
    }

    // ---------------------------------------------------------------- walking

    /** Accumulate the world-space transform for each node and emit primitives. */
    private static void walk(Doc doc, int nodeIndex, float[] parent, Mesh out) {
        Map<String, Object> node = doc.node(nodeIndex);
        float[] local = doc.nodeLocalMatrix(node);
        float[] world = mul(parent, local);

        Integer mesh = optInt(node.get("mesh"));
        if (mesh != null) {
            emitMesh(doc, mesh, world, out);
        }

        List<Object> children = Json.asArray(node.get("children"));
        if (children != null) {
            for (Object c : children) {
                int idx = Json.asInt(c, -1);
                if (idx >= 0) walk(doc, idx, world, out);
            }
        }
    }

    private static void emitMesh(Doc doc, int meshIndex, float[] world, Mesh out) {
        Map<String, Object> mesh = doc.mesh(meshIndex);
        List<Object> primitives = Json.asArray(mesh.get("primitives"));
        if (primitives == null) return;
        // Inverse-transpose for normals — strip translation, invert+transpose rotation/scale.
        float[] normalMatrix = inverseTransposeUpper3x3(world);

        for (Object po : primitives) {
            Map<String, Object> prim = Json.asObject(po);
            if (prim == null) continue;
            int mode = Json.asInt(prim.get("mode"), MODE_TRIANGLES);
            if (mode != MODE_TRIANGLES && mode != MODE_TRIANGLE_STRIP && mode != MODE_TRIANGLE_FAN) {
                continue; // skip lines, points, etc.
            }

            Map<String, Object> attrs = Json.asObject(prim.get("attributes"));
            if (attrs == null) continue;

            PrimitiveData data = readPrimitive(doc, prim, attrs);
            if (data == null || data.positions.length == 0) continue;

            int[] triIndices = expandToTriangles(data.indices, mode);

            Integer matIdx = optInt(prim.get("material"));
            String matName = out.ensureMaterial(doc, matIdx);

            int posBase = out.positions.size();

            for (float[] p : data.positions) {
                out.positions.add(transformPoint(world, p));
            }
            if (data.normals != null) {
                for (float[] n : data.normals) {
                    out.normals.add(normalize(transformDir(normalMatrix, n)));
                }
            }
            if (data.uvs != null) {
                for (float[] t : data.uvs) {
                    out.texcoords.add(t);
                }
            }

            // glTF guarantees one accessor per attribute, indexed by vertex,
            // so positions/normals/uvs must be the same length. If a primitive
            // breaks that assumption we drop it rather than emit garbage.
            if (data.normals != null && data.normals.length != data.positions.length) continue;
            if (data.uvs != null && data.uvs.length != data.positions.length) continue;

            FaceGroup fg = new FaceGroup(matName);
            for (int i = 0; i + 2 < triIndices.length; i += 3) {
                fg.tris.add(new int[]{
                        posBase + triIndices[i],
                        posBase + triIndices[i + 1],
                        posBase + triIndices[i + 2],
                });
            }
            out.faceGroups.add(fg);
        }
    }

    /** Parallel arrays for one primitive's vertex / index data. */
    private static final class PrimitiveData {
        float[][] positions;
        float[][] normals;   // may be null
        float[][] uvs;       // may be null
        int[] indices;       // never null; sequential if absent
    }

    private static PrimitiveData readPrimitive(Doc doc, Map<String, Object> prim, Map<String, Object> attrs) {
        Map<String, Object> ext = Json.asObject(prim.get("extensions"));
        Map<String, Object> draco = ext == null ? null : Json.asObject(ext.get("KHR_draco_mesh_compression"));
        if (draco != null) {
            return readDracoPrimitive(doc, draco);
        }
        return readClassicalPrimitive(doc, prim, attrs);
    }

    private static PrimitiveData readClassicalPrimitive(Doc doc, Map<String, Object> prim, Map<String, Object> attrs) {
        Integer posAcc = optInt(attrs.get("POSITION"));
        if (posAcc == null) return null;
        PrimitiveData d = new PrimitiveData();
        d.positions = doc.readVec3(posAcc);
        Integer normAcc = optInt(attrs.get("NORMAL"));
        if (normAcc != null) d.normals = doc.readVec3(normAcc);
        Integer uvAcc = optInt(attrs.get("TEXCOORD_0"));
        if (uvAcc != null) d.uvs = doc.readVec2(uvAcc);
        Integer idxAcc = optInt(prim.get("indices"));
        d.indices = idxAcc != null ? doc.readIndices(idxAcc) : sequential(d.positions.length);
        return d;
    }

    private static PrimitiveData readDracoPrimitive(Doc doc, Map<String, Object> draco) {
        Integer bvIdx = optInt(draco.get("bufferView"));
        if (bvIdx == null) return null;
        Map<String, Object> bv = doc.bufferView(bvIdx);
        int bvOffset = Json.asInt(bv.get("byteOffset"), 0);
        int bvLength = Json.asInt(bv.get("byteLength"), 0);
        byte[] dracoData = new byte[bvLength];
        System.arraycopy(doc.bin, bvOffset, dracoData, 0, bvLength);

        Map<String, Object> dracoAttrs = Json.asObject(draco.get("attributes"));
        if (dracoAttrs == null) return null;

        // Always request POSITION, NORMAL, TEXCOORD_0 in this fixed order so
        // the parsing on the Java side stays predictable.
        int[] uniqueIds = new int[]{
                Json.asInt(dracoAttrs.get("POSITION"), -1),
                Json.asInt(dracoAttrs.get("NORMAL"), -1),
                Json.asInt(dracoAttrs.get("TEXCOORD_0"), -1),
        };
        if (uniqueIds[0] < 0) return null; // no positions = nothing to draw

        byte[] packed = com.drskunk.sh3dikea.draco.DracoNative.decode(dracoData, uniqueIds);
        com.drskunk.sh3dikea.draco.DracoMesh dm =
                com.drskunk.sh3dikea.draco.DracoMesh.parse(packed, uniqueIds);

        PrimitiveData d = new PrimitiveData();
        d.positions = unflatten3(dm.attributes.get(uniqueIds[0]), dm.numPoints);
        if (uniqueIds[1] >= 0 && dm.attributes.containsKey(uniqueIds[1])) {
            d.normals = unflatten3(dm.attributes.get(uniqueIds[1]), dm.numPoints);
        }
        if (uniqueIds[2] >= 0 && dm.attributes.containsKey(uniqueIds[2])) {
            d.uvs = unflatten2(dm.attributes.get(uniqueIds[2]), dm.numPoints);
        }
        d.indices = dm.indices;
        return d;
    }

    private static float[][] unflatten3(float[] flat, int count) {
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = flat[i * 3];
            out[i][1] = flat[i * 3 + 1];
            out[i][2] = flat[i * 3 + 2];
        }
        return out;
    }

    private static float[][] unflatten2(float[] flat, int count) {
        float[][] out = new float[count][2];
        for (int i = 0; i < count; i++) {
            out[i][0] = flat[i * 2];
            out[i][1] = flat[i * 2 + 1];
        }
        return out;
    }

    // ----------------------------------------------------------- math helpers

    private static final float[] IDENTITY = new float[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    /** Column-major 4x4 matrix multiply: result = a * b. */
    private static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float v = 0;
                for (int k = 0; k < 4; k++) v += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = v;
            }
        }
        return r;
    }

    private static float[] transformPoint(float[] m, float[] p) {
        float x = m[0] * p[0] + m[4] * p[1] + m[8]  * p[2] + m[12];
        float y = m[1] * p[0] + m[5] * p[1] + m[9]  * p[2] + m[13];
        float z = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14];
        return new float[]{x, y, z};
    }

    /** Apply 3x3 (column-major, packed in 16-float matrix) to a direction. */
    private static float[] transformDir(float[] m, float[] d) {
        float x = m[0] * d[0] + m[4] * d[1] + m[8]  * d[2];
        float y = m[1] * d[0] + m[5] * d[1] + m[9]  * d[2];
        float z = m[2] * d[0] + m[6] * d[1] + m[10] * d[2];
        return new float[]{x, y, z};
    }

    private static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-8f) return new float[]{0, 0, 1};
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    /** Build inverse-transpose of the upper-left 3x3 of a column-major 4x4. */
    private static float[] inverseTransposeUpper3x3(float[] m) {
        // Extract 3x3 in column-major.
        float a00 = m[0],  a01 = m[4],  a02 = m[8];
        float a10 = m[1],  a11 = m[5],  a12 = m[9];
        float a20 = m[2],  a21 = m[6],  a22 = m[10];

        float c00 = a11 * a22 - a12 * a21;
        float c01 = a12 * a20 - a10 * a22;
        float c02 = a10 * a21 - a11 * a20;
        float c10 = a02 * a21 - a01 * a22;
        float c11 = a00 * a22 - a02 * a20;
        float c12 = a01 * a20 - a00 * a21;
        float c20 = a01 * a12 - a02 * a11;
        float c21 = a02 * a10 - a00 * a12;
        float c22 = a00 * a11 - a01 * a10;

        float det = a00 * c00 + a01 * c01 + a02 * c02;
        if (Math.abs(det) < 1e-12f) {
            return IDENTITY.clone();
        }
        float invDet = 1.0f / det;

        // Inverse = adj / det, where adj is transpose of cofactor.
        // Inverse transpose = cofactor / det.
        float[] r = new float[16];
        r[0]  = c00 * invDet; r[1]  = c01 * invDet; r[2]  = c02 * invDet;
        r[4]  = c10 * invDet; r[5]  = c11 * invDet; r[6]  = c12 * invDet;
        r[8]  = c20 * invDet; r[9]  = c21 * invDet; r[10] = c22 * invDet;
        r[15] = 1.0f;
        return r;
    }

    private static int[] sequential(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = i;
        return r;
    }

    private static int[] expandToTriangles(int[] indices, int mode) {
        if (mode == MODE_TRIANGLES) return indices;
        List<Integer> out = new ArrayList<>();
        if (mode == MODE_TRIANGLE_STRIP) {
            for (int i = 0; i + 2 < indices.length; i++) {
                if ((i & 1) == 0) {
                    out.add(indices[i]);
                    out.add(indices[i + 1]);
                    out.add(indices[i + 2]);
                } else {
                    out.add(indices[i]);
                    out.add(indices[i + 2]);
                    out.add(indices[i + 1]);
                }
            }
        } else if (mode == MODE_TRIANGLE_FAN) {
            for (int i = 1; i + 1 < indices.length; i++) {
                out.add(indices[0]);
                out.add(indices[i]);
                out.add(indices[i + 1]);
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static Integer optInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        return null;
    }

    // ---------------------------------------------------------------- types

    private static final class Mesh {
        final List<float[]> positions = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();
        final List<float[]> texcoords = new ArrayList<>();
        final List<FaceGroup> faceGroups = new ArrayList<>();
        final Map<String, MaterialOut> materials = new java.util.LinkedHashMap<>();

        Result bounds() {
            if (positions.isEmpty()) return new Result(0, 0, 0, 0, 0, 0);
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (float[] p : positions) {
                if (p[0] < minX) minX = p[0]; if (p[0] > maxX) maxX = p[0];
                if (p[1] < minY) minY = p[1]; if (p[1] > maxY) maxY = p[1];
                if (p[2] < minZ) minZ = p[2]; if (p[2] > maxZ) maxZ = p[2];
            }
            return new Result(minX, minY, minZ, maxX, maxY, maxZ);
        }

        String ensureMaterial(Doc doc, Integer matIdx) {
            if (matIdx == null) {
                if (!materials.containsKey("default")) {
                    MaterialOut m = new MaterialOut("default");
                    m.baseColor = new float[]{0.8f, 0.8f, 0.8f, 1.0f};
                    materials.put("default", m);
                }
                return "default";
            }
            Map<String, Object> mat = doc.material(matIdx);
            String rawName = Json.asString(mat.get("name"));
            String name = sanitize(rawName != null ? rawName : ("material_" + matIdx));
            // Disambiguate clashes from sanitisation.
            String unique = name;
            int n = 1;
            while (materials.containsKey(unique)
                    && !Integer.valueOf(matIdx).equals(materials.get(unique).sourceIndex)) {
                unique = name + "_" + (n++);
            }
            if (!materials.containsKey(unique)) {
                MaterialOut m = new MaterialOut(unique);
                m.sourceIndex = matIdx;
                Map<String, Object> pbr = Json.asObject(mat.get("pbrMetallicRoughness"));
                if (pbr != null) {
                    List<Object> bcf = Json.asArray(pbr.get("baseColorFactor"));
                    if (bcf != null && bcf.size() >= 3) {
                        m.baseColor = new float[]{
                                (float) Json.asDouble(bcf.get(0), 1),
                                (float) Json.asDouble(bcf.get(1), 1),
                                (float) Json.asDouble(bcf.get(2), 1),
                                bcf.size() >= 4 ? (float) Json.asDouble(bcf.get(3), 1) : 1f,
                        };
                    } else {
                        m.baseColor = new float[]{1, 1, 1, 1};
                    }
                    Map<String, Object> bct = Json.asObject(pbr.get("baseColorTexture"));
                    if (bct != null) {
                        Integer texIdx = optInt(bct.get("index"));
                        if (texIdx != null) {
                            Integer imgIdx = doc.textureSourceIndex(texIdx);
                            if (imgIdx != null) m.baseColorImageIndex = imgIdx;
                        }
                    }
                } else {
                    m.baseColor = new float[]{0.8f, 0.8f, 0.8f, 1.0f};
                }
                materials.put(unique, m);
            }
            return unique;
        }

        private static String sanitize(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
                else sb.append('_');
            }
            String r = sb.toString();
            if (r.isEmpty()) r = "material";
            return r;
        }
    }

    private static final class FaceGroup {
        final String material;
        final List<int[]> tris = new ArrayList<>();
        FaceGroup(String material) { this.material = material; }
    }

    private static final class MaterialOut {
        final String name;
        Integer sourceIndex;
        float[] baseColor = new float[]{0.8f, 0.8f, 0.8f, 1.0f};
        Integer baseColorImageIndex; // index into glTF images[]
        MaterialOut(String name) { this.name = name; }
    }

    static final class ImageEntry {
        final String fileName;
        final byte[] data;
        ImageEntry(String fileName, byte[] data) { this.fileName = fileName; this.data = data; }
    }

    // ---------------------------------------------------------------- doc

    private static final class Doc {
        final Map<String, Object> root;
        final byte[] bin;
        final List<Object> nodes;
        final List<Object> meshes;
        final List<Object> bufferViews;
        final List<Object> accessors;
        final List<Object> materials;
        final List<Object> textures;
        final List<Object> images;
        final List<Object> scenes;

        Doc(Map<String, Object> root, byte[] bin) {
            this.root = root;
            this.bin = bin;
            this.nodes       = listOrEmpty(root.get("nodes"));
            this.meshes      = listOrEmpty(root.get("meshes"));
            this.bufferViews = listOrEmpty(root.get("bufferViews"));
            this.accessors   = listOrEmpty(root.get("accessors"));
            this.materials   = listOrEmpty(root.get("materials"));
            this.textures    = listOrEmpty(root.get("textures"));
            this.images      = listOrEmpty(root.get("images"));
            this.scenes      = listOrEmpty(root.get("scenes"));
        }

        private static List<Object> listOrEmpty(Object o) {
            List<Object> l = Json.asArray(o);
            return l == null ? new ArrayList<>() : l;
        }

        Map<String, Object> node(int i)     { return Json.asObject(nodes.get(i)); }
        Map<String, Object> mesh(int i)     { return Json.asObject(meshes.get(i)); }
        Map<String, Object> material(int i) { return Json.asObject(materials.get(i)); }
        Map<String, Object> bufferView(int i){return Json.asObject(bufferViews.get(i)); }
        Map<String, Object> accessor(int i) { return Json.asObject(accessors.get(i)); }

        List<Integer> sceneRootNodes() {
            int sceneIdx = Json.asInt(root.get("scene"), 0);
            if (sceneIdx < 0 || sceneIdx >= scenes.size()) sceneIdx = 0;
            List<Integer> out = new ArrayList<>();
            if (scenes.isEmpty()) {
                // Fall back to node 0 if no scene block.
                if (!nodes.isEmpty()) out.add(0);
                return out;
            }
            Map<String, Object> scene = Json.asObject(scenes.get(sceneIdx));
            List<Object> ns = Json.asArray(scene.get("nodes"));
            if (ns != null) for (Object o : ns) out.add(Json.asInt(o, -1));
            return out;
        }

        Integer textureSourceIndex(int textureIndex) {
            if (textureIndex < 0 || textureIndex >= textures.size()) return null;
            Map<String, Object> t = Json.asObject(textures.get(textureIndex));
            Integer src = optInt(t.get("source"));
            if (src != null) return src;
            // Textures shipped via EXT_texture_webp put the source inside
            // the extension block instead of at the top level.
            Map<String, Object> ext = Json.asObject(t.get("extensions"));
            if (ext != null) {
                Map<String, Object> webp = Json.asObject(ext.get("EXT_texture_webp"));
                if (webp != null) {
                    Integer s = optInt(webp.get("source"));
                    if (s != null) return s;
                }
            }
            return null;
        }

        ImageEntry extractImage(int imageIndex) {
            if (imageIndex < 0 || imageIndex >= images.size()) return null;
            Map<String, Object> img = Json.asObject(images.get(imageIndex));
            String mime = Json.asString(img.get("mimeType"));
            Integer bv = optInt(img.get("bufferView"));
            byte[] data;
            if (bv != null) {
                data = readBufferView(bv);
            } else {
                // We don't fetch external image URIs in this MVP.
                return null;
            }

            // PNG / JPEG pass through unchanged — every Java ImageIO can read
            // those, and Sweet Home 3D's MTL loader treats them as Texture2Ds.
            if ("image/png".equals(mime) || sniff(data, "png")) {
                return new ImageEntry("tex_" + imageIndex + ".png", data);
            }
            if ("image/jpeg".equals(mime) || sniff(data, "jpg")) {
                return new ImageEntry("tex_" + imageIndex + ".jpg", data);
            }

            // WebP / anything else — try to transcode through ImageIO. If the
            // host JRE bundles a WebP reader (recent JDKs on macOS do, and
            // TwelveMonkeys-augmented setups always do), we can save it as PNG
            // and Sweet Home 3D will happily load that. If decode fails, drop
            // the texture; the material will just render with its solid Kd.
            try {
                java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(data));
                if (decoded != null) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    if (javax.imageio.ImageIO.write(decoded, "png", baos)) {
                        return new ImageEntry("tex_" + imageIndex + ".png", baos.toByteArray());
                    }
                }
            } catch (Exception ignored) {
                // fall through and drop the texture
            }
            return null;
        }

        private static boolean sniff(byte[] d, String kind) {
            if ("png".equals(kind)) {
                return d.length >= 4
                        && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
            }
            if ("jpg".equals(kind)) {
                return d.length >= 3
                        && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
            }
            return false;
        }

        // ---- accessor reads ----------------------------------------------

        float[][] readVec3(int accessorIndex) {
            Map<String, Object> a = accessor(accessorIndex);
            int componentType = Json.asInt(a.get("componentType"), CT_FLOAT);
            if (componentType != CT_FLOAT) {
                throw new IllegalStateException("Unsupported vec3 componentType: " + componentType);
            }
            int count = Json.asInt(a.get("count"), 0);
            ByteBuffer src = accessorBuffer(a, 12);
            float[][] out = new float[count][3];
            for (int i = 0; i < count; i++) {
                out[i][0] = src.getFloat();
                out[i][1] = src.getFloat();
                out[i][2] = src.getFloat();
            }
            return out;
        }

        float[][] readVec2(int accessorIndex) {
            Map<String, Object> a = accessor(accessorIndex);
            int componentType = Json.asInt(a.get("componentType"), CT_FLOAT);
            int count = Json.asInt(a.get("count"), 0);
            float[][] out = new float[count][2];
            int stride = computeStride(a, componentSize(componentType) * 2);
            ByteBuffer src = accessorBuffer(a, stride);
            for (int i = 0; i < count; i++) {
                int basePos = src.position();
                out[i][0] = readNormalized(src, componentType);
                out[i][1] = readNormalized(src, componentType);
                src.position(basePos + stride);
            }
            return out;
        }

        int[] readIndices(int accessorIndex) {
            Map<String, Object> a = accessor(accessorIndex);
            int componentType = Json.asInt(a.get("componentType"), CT_UNSIGNED_SHORT);
            int count = Json.asInt(a.get("count"), 0);
            int compSize = componentSize(componentType);
            ByteBuffer src = accessorBuffer(a, compSize);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                switch (componentType) {
                    case CT_UNSIGNED_BYTE:  out[i] = src.get() & 0xFF; break;
                    case CT_UNSIGNED_SHORT: out[i] = src.getShort() & 0xFFFF; break;
                    case CT_UNSIGNED_INT:   out[i] = src.getInt(); break;
                    default: throw new IllegalStateException("Bad index componentType: " + componentType);
                }
            }
            return out;
        }

        private int computeStride(Map<String, Object> accessor, int defaultStride) {
            Integer bv = optInt(accessor.get("bufferView"));
            if (bv == null) return defaultStride;
            Map<String, Object> view = bufferView(bv);
            Integer s = optInt(view.get("byteStride"));
            return s != null && s > 0 ? s : defaultStride;
        }

        private static float readNormalized(ByteBuffer bb, int componentType) {
            switch (componentType) {
                case CT_FLOAT:          return bb.getFloat();
                case CT_UNSIGNED_BYTE:  return (bb.get() & 0xFF) / 255.0f;
                case CT_UNSIGNED_SHORT: return (bb.getShort() & 0xFFFF) / 65535.0f;
                case CT_BYTE:           return Math.max(bb.get() / 127.0f, -1.0f);
                case CT_SHORT:          return Math.max(bb.getShort() / 32767.0f, -1.0f);
                default: throw new IllegalStateException("Bad componentType: " + componentType);
            }
        }

        private static int componentSize(int componentType) {
            switch (componentType) {
                case CT_BYTE: case CT_UNSIGNED_BYTE: return 1;
                case CT_SHORT: case CT_UNSIGNED_SHORT: return 2;
                case CT_UNSIGNED_INT: case CT_FLOAT: return 4;
                default: throw new IllegalStateException("Bad componentType: " + componentType);
            }
        }

        private ByteBuffer accessorBuffer(Map<String, Object> accessor, int stride) {
            Integer bvIdx = optInt(accessor.get("bufferView"));
            int accOffset = Json.asInt(accessor.get("byteOffset"), 0);
            if (bvIdx == null) {
                throw new IllegalStateException("Sparse / no-bufferView accessors not supported");
            }
            Map<String, Object> view = bufferView(bvIdx);
            int viewOffset = Json.asInt(view.get("byteOffset"), 0);
            int viewLength = Json.asInt(view.get("byteLength"), 0);
            int start = viewOffset + accOffset;
            int end = viewOffset + viewLength;
            if (start < 0 || end > bin.length) {
                throw new IllegalStateException("BufferView out of bounds");
            }
            ByteBuffer bb = ByteBuffer.wrap(bin, start, end - start).slice();
            bb.order(ByteOrder.LITTLE_ENDIAN);
            return bb;
        }

        private byte[] readBufferView(int bvIndex) {
            Map<String, Object> view = bufferView(bvIndex);
            int offset = Json.asInt(view.get("byteOffset"), 0);
            int length = Json.asInt(view.get("byteLength"), 0);
            byte[] out = new byte[length];
            System.arraycopy(bin, offset, out, 0, length);
            return out;
        }

        // ---- node transforms --------------------------------------------

        float[] nodeLocalMatrix(Map<String, Object> node) {
            List<Object> matrix = Json.asArray(node.get("matrix"));
            if (matrix != null && matrix.size() == 16) {
                float[] m = new float[16];
                for (int i = 0; i < 16; i++) m[i] = (float) Json.asDouble(matrix.get(i), 0);
                return m;
            }
            float[] t = readVec3OrZero(node.get("translation"));
            float[] r = readQuatOrIdentity(node.get("rotation"));
            float[] s = readVec3OrOne(node.get("scale"));
            return composeTRS(t, r, s);
        }

        private static float[] readVec3OrZero(Object o) {
            float[] r = {0, 0, 0};
            List<Object> l = Json.asArray(o);
            if (l != null && l.size() == 3) {
                r[0] = (float) Json.asDouble(l.get(0), 0);
                r[1] = (float) Json.asDouble(l.get(1), 0);
                r[2] = (float) Json.asDouble(l.get(2), 0);
            }
            return r;
        }

        private static float[] readVec3OrOne(Object o) {
            float[] r = {1, 1, 1};
            List<Object> l = Json.asArray(o);
            if (l != null && l.size() == 3) {
                r[0] = (float) Json.asDouble(l.get(0), 1);
                r[1] = (float) Json.asDouble(l.get(1), 1);
                r[2] = (float) Json.asDouble(l.get(2), 1);
            }
            return r;
        }

        private static float[] readQuatOrIdentity(Object o) {
            float[] r = {0, 0, 0, 1};
            List<Object> l = Json.asArray(o);
            if (l != null && l.size() == 4) {
                r[0] = (float) Json.asDouble(l.get(0), 0);
                r[1] = (float) Json.asDouble(l.get(1), 0);
                r[2] = (float) Json.asDouble(l.get(2), 0);
                r[3] = (float) Json.asDouble(l.get(3), 1);
            }
            return r;
        }

        private static float[] composeTRS(float[] t, float[] q, float[] s) {
            float x = q[0], y = q[1], z = q[2], w = q[3];
            float xx = x * x, yy = y * y, zz = z * z;
            float xy = x * y, xz = x * z, yz = y * z;
            float wx = w * x, wy = w * y, wz = w * z;

            // Column-major rotation * scale.
            float r00 = (1 - 2 * (yy + zz)) * s[0];
            float r10 = (2 * (xy + wz))     * s[0];
            float r20 = (2 * (xz - wy))     * s[0];

            float r01 = (2 * (xy - wz))     * s[1];
            float r11 = (1 - 2 * (xx + zz)) * s[1];
            float r21 = (2 * (yz + wx))     * s[1];

            float r02 = (2 * (xz + wy))     * s[2];
            float r12 = (2 * (yz - wx))     * s[2];
            float r22 = (1 - 2 * (xx + yy)) * s[2];

            return new float[]{
                    r00, r10, r20, 0,
                    r01, r11, r21, 0,
                    r02, r12, r22, 0,
                    t[0], t[1], t[2], 1,
            };
        }
    }

    private GlbToObj() {}
}
