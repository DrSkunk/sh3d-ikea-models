package com.drskunk.sh3dikea.draco;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed result of {@link DracoNative#decode}. The native bridge returns
 * a packed byte buffer; this class converts it into Java arrays per the
 * format documented in {@code draco_jni.cpp}.
 */
public final class DracoMesh {

    public final int numPoints;
    public final int numFaces;
    /** Flattened triangles: 3 point-indices per triangle. */
    public final int[] indices;
    /** unique_id → flattened component data, length = numPoints * numComponents. */
    public final Map<Integer, float[]> attributes;
    /** unique_id → component count (2 for vec2, 3 for vec3, 4 for vec4). */
    public final Map<Integer, Integer> numComponents;

    private DracoMesh(int numPoints, int numFaces, int[] indices,
                      Map<Integer, float[]> attributes,
                      Map<Integer, Integer> numComponents) {
        this.numPoints = numPoints;
        this.numFaces = numFaces;
        this.indices = indices;
        this.attributes = attributes;
        this.numComponents = numComponents;
    }

    /**
     * Decode the packed payload from the native bridge.
     *
     * @param packed   the byte buffer returned by {@link DracoNative#decode}
     * @param uniqueIds the same array passed to {@code decode}, in order
     */
    public static DracoMesh parse(byte[] packed, int[] uniqueIds) {
        ByteBuffer bb = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        int numPoints = bb.getInt();
        int numFaces = bb.getInt();

        int[] indices = new int[numFaces * 3];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = bb.getInt();
        }

        Map<Integer, float[]> attrs = new LinkedHashMap<>();
        Map<Integer, Integer> components = new LinkedHashMap<>();
        for (int slot = 0; slot < uniqueIds.length; slot++) {
            int comps = bb.getInt();
            if (comps == 0) continue;
            int floats = numPoints * comps;
            float[] data = new float[floats];
            for (int i = 0; i < floats; i++) data[i] = bb.getFloat();
            attrs.put(uniqueIds[slot], data);
            components.put(uniqueIds[slot], comps);
        }
        return new DracoMesh(numPoints, numFaces, indices, attrs, components);
    }
}
