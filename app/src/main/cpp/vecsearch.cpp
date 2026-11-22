// Brute-force int8 vector search for the Eco index.
// Each stored vector is int8[dim] with a float scale; the real vector is emb * scale.
// Score = dot(query, emb) * scale. Self-contained (no Android deps) so unit tests can
// build it for the host.
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

namespace {

struct Index {
    int dim;
    std::vector<int8_t> embs;
    std::vector<float> scales;
    std::vector<int64_t> ids;
};

inline float dot_i8(const int8_t *e, const float *q, int dim) {
    // 16 independent accumulators let the compiler vectorise without -ffast-math.
    float acc[16] = {0};
    int d = 0;
    for (; d + 16 <= dim; d += 16)
        for (int j = 0; j < 16; ++j) acc[j] += float(e[d + j]) * q[d + j];
    float s = 0;
    for (int j = 0; j < 16; ++j) s += acc[j];
    for (; d < dim; ++d) s += float(e[d]) * q[d];
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_escc_ecomini_engine_VectorIndex_nativeCreate(JNIEnv *, jclass, jint dim, jint capacity) {
    auto *ix = new (std::nothrow) Index();
    if (!ix) return 0;
    ix->dim = dim;
    try {
        ix->embs.reserve(size_t(capacity) * dim);
        ix->scales.reserve(capacity);
        ix->ids.reserve(capacity);
    } catch (...) {
        delete ix;
        return 0;
    }
    return reinterpret_cast<jlong>(ix);
}

JNIEXPORT jboolean JNICALL
Java_org_escc_ecomini_engine_VectorIndex_nativeAdd(JNIEnv *env, jclass, jlong h, jbyteArray embs,
                                                   jfloatArray scales, jlongArray ids, jint n) {
    auto *ix = reinterpret_cast<Index *>(h);
    if (!ix || n <= 0) return JNI_FALSE;
    if (env->GetArrayLength(embs) < jsize(n) * ix->dim || env->GetArrayLength(scales) < n ||
        env->GetArrayLength(ids) < n)
        return JNI_FALSE;
    try {
        size_t off = ix->embs.size();
        ix->embs.resize(off + size_t(n) * ix->dim);
        env->GetByteArrayRegion(embs, 0, n * ix->dim, reinterpret_cast<jbyte *>(ix->embs.data() + off));
        size_t so = ix->scales.size();
        ix->scales.resize(so + n);
        env->GetFloatArrayRegion(scales, 0, n, ix->scales.data() + so);
        size_t io = ix->ids.size();
        ix->ids.resize(io + n);
        env->GetLongArrayRegion(ids, 0, n, reinterpret_cast<jlong *>(ix->ids.data() + io));
    } catch (...) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_org_escc_ecomini_engine_VectorIndex_nativeSize(JNIEnv *, jclass, jlong h) {
    auto *ix = reinterpret_cast<Index *>(h);
    return ix ? jint(ix->ids.size()) : 0;
}

JNIEXPORT jlongArray JNICALL
Java_org_escc_ecomini_engine_VectorIndex_nativeSearch(JNIEnv *env, jclass, jlong h, jfloatArray query, jint k) {
    auto *ix = reinterpret_cast<Index *>(h);
    if (!ix || k <= 0 || env->GetArrayLength(query) != ix->dim) return env->NewLongArray(0);
    std::vector<float> q(ix->dim);
    env->GetFloatArrayRegion(query, 0, ix->dim, q.data());

    const size_t n = ix->ids.size();
    const int kk = int(std::min<size_t>(size_t(k), n));
    std::vector<std::pair<float, size_t>> top;  // kept sorted, best first
    top.reserve(kk + 1);
    for (size_t i = 0; i < n; ++i) {
        float s = dot_i8(ix->embs.data() + i * ix->dim, q.data(), ix->dim) * ix->scales[i];
        if (int(top.size()) < kk || s > top.back().first) {
            auto pos = std::upper_bound(top.begin(), top.end(), s,
                                        [](float v, const std::pair<float, size_t> &p) { return v > p.first; });
            top.insert(pos, {s, i});
            if (int(top.size()) > kk) top.pop_back();
        }
    }
    jlongArray out = env->NewLongArray(jsize(top.size()));
    std::vector<jlong> res(top.size());
    for (size_t i = 0; i < top.size(); ++i) res[i] = ix->ids[top[i].second];
    env->SetLongArrayRegion(out, 0, jsize(res.size()), res.data());
    return out;
}

JNIEXPORT void JNICALL
Java_org_escc_ecomini_engine_VectorIndex_nativeFree(JNIEnv *, jclass, jlong h) {
    delete reinterpret_cast<Index *>(h);
}

}  // extern "C"
