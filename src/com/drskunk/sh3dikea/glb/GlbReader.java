package com.drskunk.sh3dikea.glb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Splits a GLB v2 container into its JSON and BIN chunks.
 *
 * GLB layout (little-endian):
 *   header:   uint32 magic = 0x46546C67 ("glTF"), uint32 version, uint32 length
 *   chunk 0:  uint32 length, uint32 type = 0x4E4F534A ("JSON"), bytes (UTF-8)
 *   chunk 1:  uint32 length, uint32 type = 0x004E4942 ("BIN\0"),  bytes  (optional)
 */
public final class GlbReader {

    private static final int MAGIC_GLTF = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN  = 0x004E4942;

    public final String json;
    public final byte[] bin;

    private GlbReader(String json, byte[] bin) {
        this.json = json;
        this.bin = bin;
    }

    public static GlbReader read(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.remaining() < 12) throw new IOException("File too short to be a GLB");
        int magic = bb.getInt();
        int version = bb.getInt();
        int length = bb.getInt();
        if (magic != MAGIC_GLTF) {
            throw new IOException("Not a GLB file (bad magic 0x" + Integer.toHexString(magic) + ")");
        }
        if (version != 2) {
            throw new IOException("Unsupported GLB version: " + version);
        }
        if (length > data.length) {
            throw new IOException("GLB header reports length " + length + " > data length " + data.length);
        }

        // First chunk MUST be JSON.
        int jsonLen = bb.getInt();
        int jsonType = bb.getInt();
        if (jsonType != CHUNK_JSON) {
            throw new IOException("First GLB chunk is not JSON (type 0x" + Integer.toHexString(jsonType) + ")");
        }
        if (jsonLen < 0 || bb.remaining() < jsonLen) {
            throw new IOException("Truncated GLB JSON chunk");
        }
        byte[] jsonBytes = new byte[jsonLen];
        bb.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();

        // Optional BIN chunk.
        byte[] bin = new byte[0];
        if (bb.remaining() >= 8) {
            int binLen = bb.getInt();
            int binType = bb.getInt();
            if (binType == CHUNK_BIN) {
                if (binLen < 0 || bb.remaining() < binLen) {
                    throw new IOException("Truncated GLB BIN chunk");
                }
                bin = new byte[binLen];
                bb.get(bin);
            }
            // Other chunk types (e.g. extensions) are ignored.
        }

        return new GlbReader(json, bin);
    }
}
