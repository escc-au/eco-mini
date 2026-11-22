# Eco Mini for Android

Eco Mini (the app) runs Eco, an environmental assistant that runs entirely on the phone: no server, no accounts,
no analytics. Its only network use is a one-time download of its model files from
Hugging Face, after the user taps **Download**. Please note the model will exceed memory on most phones with less than 12 GB of RAM. 

- Kotlin + Jetpack Compose UI: chat (with a drawer of saved chats), setup and settings screens.
- Chats are saved only on the phone, one JSON file per chat in the app's private storage
  (`chat/ChatStore.kt`). When a long chat doesn't fit the 4,096-token context, the oldest turns are
  left out of the prompt only (`chat/History.kt`); the saved chat is never changed.
- llama.cpp through a thin JNI bridge (`app/src/main/cpp/eco_jni.cpp`).
- Retrieval: bge-small-en-v1.5 (bundled, 35 MB) embeds the question; a brute-force int8
  search in C++ (`vecsearch.cpp`) finds the top 3 chunks in the SQLite index; Eco-4B answers
  from them with a hand-built Qwen ChatML prompt (`rag/Prompt.kt`).
- Backends: OpenCL on Adreno, Vulkan on other GPUs, CPU otherwise. The CPU build ships seven
  variants (armv8.0 to armv9.2 with dotprod, i8mm, SVE, SME) and loads the best one for the chip
  at runtime. If the GPU fails to load, Eco falls back to the CPU; if a GPU driver crashes the
  app, the next start uses the CPU.

See [INSTALL.md](INSTALL.md) to install the APK and publish the model files.

## Building

Requirements: JDK 17, Android SDK (platform 35), NDK 27.2.12479018 (r27c), CMake 3.31.6
(from the SDK), Python 3 and a host C/C++ compiler (`cc`/`c++`, used to build Vulkan's
shader generator and the unit-test copy of the search code).

```sh
git clone --recurse-submodules <repo-url> eco-android
cd eco-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease lintRelease
```

`assembleRelease` runs the unit tests first (`testReleaseUnitTest`) and fails if any fail.

The APK is in `app/build/outputs/apk/release/`. It is unsigned unless you pass a signing
properties file:

```sh
./gradlew -Peco.signing.properties=$HOME/android-keys/keystore.properties assembleRelease
```

The properties file holds `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Never commit it
or the keystore.

The first native build takes 10–20 minutes (llama.cpp, seven CPU variants, OpenCL and
several hundred Vulkan shaders).

## Layout

```
app/src/main/cpp/        CMake build of llama.cpp + the JNI bridge and vector search
app/src/main/java/...    engine/ (native wrappers), rag/ (prompts), files/ (download, checks), ui/
app/src/main/assets/     bge-small-en-v1.5-q8_0.gguf (stored uncompressed; see NOTICE)
app/src/test/            unit tests and the sample index
third_party/             git submodules: llama.cpp, OpenCL-Headers, OpenCL-ICD-Loader,
                         Vulkan-Headers, SPIRV-Headers (each pinned to one commit)
tools/                   make_sample_index.py, make_manifest.py
fastlane/                F-Droid store metadata
```

## Index format

SQLite with `chunks(id INTEGER PRIMARY KEY, title TEXT, text TEXT, emb BLOB, scale REAL)`,
where `emb` is 384 int8 values and the real vector is `emb * scale` (L2-normalised before
quantising), and `meta(key TEXT PRIMARY KEY, value TEXT)` with `embed_model=bge-small-en-v1.5`,
`dim=384`, `quant=int8` and `version`. `tools/make_sample_index.py` builds a small example.

## F-Droid

- Licence Apache-2.0. No Google Play Services, Firebase, analytics, crash reporting or ads.
- All native code is built from source from pinned submodules. `libOpenCL.so` is built from the
  Khronos ICD loader for linking only and excluded from the APK; at runtime the vendor driver is
  used through `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`.
  llama.cpp's prebuilt Adreno kernel binaries are disabled (`GGML_OPENCL_USE_ADRENO_BIN_KERNELS=OFF`).
- All Java/Kotlin dependencies come from Google's Maven and Maven Central.
- The APK has no dependency-info signing block (`dependenciesInfo` disabled).
- A draft build recipe is in [`docs/fdroid-metadata.yml`](docs/fdroid-metadata.yml).

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party components.
