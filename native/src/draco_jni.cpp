// JNI bridge to libdraco for the Sweet Home 3D IKEA Browser plugin.
//
// We expose a single entry point that decodes a Draco-compressed glTF mesh
// buffer and returns a packed byte buffer with the attributes the caller
// asked for, all coerced to float32 / uint32 so the Java side has nothing
// to switch on.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "draco/compression/decode.h"
#include "draco/core/decoder_buffer.h"
#include "draco/mesh/mesh.h"

namespace {

inline void putUint32LE(std::vector<uint8_t>& buf, uint32_t v) {
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}

inline void putFloatLE(std::vector<uint8_t>& buf, float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    putUint32LE(buf, bits);
}

void throwRuntime(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg.c_str());
    }
}

}  // namespace

extern "C" {

/*
 * Class:     com_drskunk_sh3dikea_draco_DracoNative
 * Method:    decode
 * Signature: ([B[I)[B
 *
 * Input:
 *   dracoData : raw bytes of the Draco-compressed primitive
 *   uniqueIds : glTF unique_id values for the attributes the caller wants,
 *               in the order they should appear in the output. -1 means
 *               "skip" (placeholder slot, returned with 0 components).
 *
 * Output (little-endian, packed):
 *   uint32 numPoints
 *   uint32 numFaces
 *   uint32 indices[3 * numFaces]
 *   for each uniqueId in input order:
 *     uint32 numComponents   (0 if attribute not found / disabled)
 *     numPoints * numComponents * float32 values
 *
 * Throws java.lang.RuntimeException on decode error.
 */
JNIEXPORT jbyteArray JNICALL Java_com_drskunk_sh3dikea_draco_DracoNative_decode(
        JNIEnv* env, jclass /*cls*/, jbyteArray dracoData, jintArray uniqueIds) {
    if (dracoData == nullptr) {
        throwRuntime(env, "dracoData must not be null");
        return nullptr;
    }

    jsize dataLen = env->GetArrayLength(dracoData);
    jbyte* dataPtr = env->GetByteArrayElements(dracoData, nullptr);
    if (dataPtr == nullptr) {
        throwRuntime(env, "Failed to access dracoData");
        return nullptr;
    }

    draco::DecoderBuffer buffer;
    buffer.Init(reinterpret_cast<const char*>(dataPtr), static_cast<size_t>(dataLen));

    draco::Decoder decoder;
    auto statusOrMesh = decoder.DecodeMeshFromBuffer(&buffer);
    env->ReleaseByteArrayElements(dracoData, dataPtr, JNI_ABORT);

    if (!statusOrMesh.ok()) {
        throwRuntime(env, std::string("Draco decode failed: ") +
                          statusOrMesh.status().error_msg_string());
        return nullptr;
    }
    std::unique_ptr<draco::Mesh> mesh = std::move(statusOrMesh).value();
    if (mesh == nullptr) {
        throwRuntime(env, "Draco returned null mesh");
        return nullptr;
    }

    const uint32_t numPoints = mesh->num_points();
    const uint32_t numFaces = mesh->num_faces();

    // Pull the requested unique_ids into a vector.
    std::vector<int32_t> ids;
    if (uniqueIds != nullptr) {
        jsize idsLen = env->GetArrayLength(uniqueIds);
        ids.resize(idsLen);
        env->GetIntArrayRegion(uniqueIds, 0, idsLen, reinterpret_cast<jint*>(ids.data()));
    }

    // Pre-size the output. Indices: 12 bytes per triangle. Per attribute:
    // 4 bytes header + numPoints * numComponents * 4 bytes.
    std::vector<uint8_t> out;
    out.reserve(8 + 12 * static_cast<size_t>(numFaces) +
                static_cast<size_t>(ids.size()) * 4 +
                static_cast<size_t>(numPoints) * 16 * ids.size());

    putUint32LE(out, numPoints);
    putUint32LE(out, numFaces);

    // Index buffer: each face is 3 PointIndex values.
    for (uint32_t f = 0; f < numFaces; ++f) {
        const draco::Mesh::Face& face = mesh->face(draco::FaceIndex(f));
        putUint32LE(out, face[0].value());
        putUint32LE(out, face[1].value());
        putUint32LE(out, face[2].value());
    }

    // For each requested attribute, write num_components + per-point floats.
    // Maximum components in glTF is 4 (e.g. tangents); we cap at 4.
    constexpr int kMaxComponents = 4;
    for (size_t i = 0; i < ids.size(); ++i) {
        if (ids[i] < 0) {
            putUint32LE(out, 0);
            continue;
        }
        const draco::PointAttribute* attr =
                mesh->GetAttributeByUniqueId(static_cast<uint32_t>(ids[i]));
        if (attr == nullptr) {
            putUint32LE(out, 0);
            continue;
        }
        const int numComponents = attr->num_components();
        if (numComponents <= 0 || numComponents > kMaxComponents) {
            putUint32LE(out, 0);
            continue;
        }
        putUint32LE(out, static_cast<uint32_t>(numComponents));

        float values[kMaxComponents];
        for (uint32_t p = 0; p < numPoints; ++p) {
            draco::PointIndex pi(p);
            draco::AttributeValueIndex avi = attr->mapped_index(pi);
            // ConvertValue automatically handles dequantization for quantized
            // attributes and converts whatever stored type to float32.
            if (!attr->ConvertValue<float>(avi, numComponents, values)) {
                std::memset(values, 0, sizeof(float) * numComponents);
            }
            for (int c = 0; c < numComponents; ++c) {
                putFloatLE(out, values[c]);
            }
        }
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result == nullptr) {
        throwRuntime(env, "Could not allocate result array");
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

}  // extern "C"
