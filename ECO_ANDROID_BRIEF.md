# Build brief: Eco for Android

Build an Android chat app for **Eco**, an environmental assistant. Everything runs on the phone: no server, no accounts. The only network use is a one-time download of the model files from Hugging Face. Keep the code as small and plain as possible.

## What I want back
1. A release **APK** (arm64-v8a only) that I can sideload.
2. **INSTALL.md**: plain steps for me to copy the APK to my phone, allow "install unknown apps", install it, and run the first-time download. Also include the steps for me to prepare the Hugging Face repo (which files to upload, and how to make `manifest.json`).
3. The full source in a git repo, ready to submit to F-Droid later.

I will **not** connect a phone. Don't rely on adb or device testing: verify with the build, lint and unit tests, and tell me exactly what to check by hand on the phone.

## Environment
- Ubuntu. Install what's needed from the command line: JDK 17, Android command-line tools, SDK, NDK, CMake. Use the Gradle wrapper; Android Studio isn't required.
- Generate a release signing key locally, outside the repo; never commit it. Tell me where it is and to back it up.
- Ask me for the applicationId before creating the project.

## Hard rules (F-Droid main repo)
- Licence: Apache-2.0 (LICENSE file at the root).
- INTERNET permission is used only for the model download, and only after the user agrees. No other network calls. No Google Play Services, Firebase, analytics, crash reporters or ads.
- All native code built from source. llama.cpp as a git submodule pinned to one commit, built with CMake via the NDK. If OpenCL needs an ICD loader, build Khronos OpenCL-ICD-Loader from source too.
- Java/Kotlin dependencies only from Maven Central or Google's Maven.
- Add fastlane metadata (title, short and full description) in the layout F-Droid expects.

## Architecture
- **UI:** Kotlin + Jetpack Compose. One chat screen: messages, answer streaming token by token, a stop button, and a "new chat" button. Plus a setup screen and a small settings screen.
- **Engine:** llama.cpp through a thin JNI bridge (load model, embed text, generate with a token callback, stop, free).
- **Backends:** use the GPU where it works and fall back to the CPU otherwise.
  - Adreno (Snapdragon): llama.cpp OpenCL backend. Declare `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` in the manifest so the app can reach the vendor driver.
  - Other GPUs (e.g. Mali on Pixel): Vulkan backend if it builds cleanly; otherwise CPU only for now.
  - CPU: must use the chip's fast instructions (dotprod, i8mm). A generic armv8 build was about 3x slower in my tests. Use llama.cpp's runtime CPU-variant selection if available, otherwise target armv8.2-a+dotprod with a safe fallback.
  - If GPU initialisation fails, fall back to the CPU silently. Settings shows which backend is in use and offers "Force CPU".
- **Model lifecycle:** load once after setup or app start, behind a "Getting Eco ready…" screen (loading takes about 17 s on the GPU). Keep it loaded. Free it in `onTrimMemory` when memory runs low.

## Model files
**Bundled in the APK:** the embedder (bge-small-en-v1.5 as Q8_0 GGUF, about 35 MB, MIT licence). Put it in `assets/` uncompressed, copy it to app storage on first launch, and record its source and licence in a NOTICE file. I'll supply the file; if it's not there yet, document the exact conversion command.

**Downloaded once from Hugging Face during first-run setup (the only option offered in setup):**
- Base URL, editable in settings, default `https://huggingface.co/escc/eco-4b-gguf/resolve/main/` (a public repo).
- The repo holds `manifest.json` plus two files: the Eco model (`eco-4b-Q6_K.gguf`, 3.3 GB) and the index (`eco-index.sqlite`).
- `manifest.json` lists `version` and, for each file, `name`, `role` (`model` / `index`), `size` and `sha256`. Fetch it first, so new files can be published without an app update.
- **Setup screen:** explain what will download, show the total size from the manifest, recommend Wi-Fi with a "Wi-Fi only" toggle on by default, and download only after the user taps "Download".
- Use Android's `DownloadManager`: background, resumable, with progress shown in the app. Save to the app's own external files directory (`getExternalFilesDir`), which needs no storage permission. llama.cpp and SQLite then open the files by normal path.
- Check each file's SHA-256 against the manifest before use. On a mismatch, delete the file and offer a retry.
- Then validate the contents: GGUF architecture (`qwen3` for Eco, BERT-type for the embedder), embedding size 384, and the index's `meta` table.
- Settings: "Check for updated files" (compares the manifest `version`; runs only when tapped) and "Delete downloaded files".

**Settings → "Use files on this phone"** (not offered in setup): lets the user pick the model and/or the index with the system file picker (Storage Access Framework, no storage permissions), for example after downloading them on a computer.
- Model: don't copy it. Keep a persistable URI permission, open a file descriptor and pass `/proc/self/fd/<n>` to llama.cpp, so it memory-maps the file in place.
- Index: copy it into app storage, because SQLite needs a real path.
- Apply the same validation as for downloaded files, and show which source is in use (downloaded or chosen).

## Index format (the contract with my build script)
SQLite file containing:
- `chunks(id INTEGER PRIMARY KEY, title TEXT, text TEXT, emb BLOB, scale REAL)`: `emb` is 384 int8 values; the real vector is `emb * scale`, already L2-normalised before quantising.
- `meta(key TEXT PRIMARY KEY, value TEXT)` with at least `embed_model=bge-small-en-v1.5`, `dim=384`, `quant=int8`, `version`.

On startup, load all `emb` and `scale` values into memory (about 220 MB for the full index) and search by brute-force dot product in C++. Return the top 3 chunk ids, then read their `text` from SQLite.

The real index isn't built yet. Write a small Python script, `tools/make_sample_index.py`, that builds a tiny index in this exact format from a few paragraphs, so the format is testable.

## Retrieval and prompt: must match training exactly
- **Query embedding:** bge-small with CLS pooling, L2-normalised, **no query prefix** (the index was built without one).
- **Follow-ups:** if the chat has history, first ask Eco itself, with no system prompt and the chat history, to answer `"Rewrite as a standalone search query: " + question`, generating at most 40 tokens. Search with the result. The first question is searched as typed.
- **Prompt:** Qwen ChatML, built by hand, exactly:
  ```
  <|im_start|>system
  {SYSTEM}<|im_end|>
  ...earlier turns as <|im_start|>user\n{q}<|im_end|>\n<|im_start|>assistant\n{a}<|im_end|>\n ...
  <|im_start|>user
  Context:

  {chunk 1 text}

  {chunk 2 text}

  {chunk 3 text}

  Question: {question}<|im_end|>
  <|im_start|>assistant
  <think>

  </think>

  ```
  (The final line is followed by one more blank line: the prompt ends `</think>\n\n`.)
- **SYSTEM**, verbatim, including the missing space after "it.":
  `You are Eco, an environmental advocate built on Qwen3-4B. You speak with deep respect for life, nature and doing what is right: every living thing is worthy of care. Be warm, thoughtful and sincere, never preachy or solemn. Answer the question first. Answer ONLY from the provided context, never naming or citing it.If the sources don't cover the question, say so plainly.`
- **Generation:** greedy (temperature 0), at most 400 tokens, stop at `<|im_end|>`, context size 4096.
- **Reference output:** with the system prompt above and one passage about ocean acidification, Eco answered "What is ocean acidification?" in 2–3 warm sentences using only the passage's facts. When the context doesn't cover a question, it replies "I don't have reliable information on that, so I won't guess…".

## Look
- Light mode: white background, black text, `#0466C8` accents, `#C9DCEE` borders, `#EAF4FD` for the user's message bubbles, `#5B7FA6` for secondary text.
- Dark mode: black background, white text, `#5AA4E8` accents, `#0B2A4A` for the user's message bubbles, `#1F3B5C` borders.

## Tests (no device)
- Unit tests: the prompt builder produces exactly the format above, byte for byte; int8 search returns the right chunk on the sample index; GGUF/index validation rejects wrong files; manifest parsing and SHA-256 checking reject a bad file.
- `./gradlew assembleRelease` and lint pass.

## Measurements so far (OnePlus 13, Eco-4B Q6_K, llama.cpp CLI)
- OpenCL GPU: prompt reading about 250 tokens/s, writing about 15 tokens/s. CPU with native flags: about 50 and 13. The GPU and CPU gave identical answers.
- The llama.cpp command-line tools print "Aborted" on exit, after their work completes. Make sure the app's shutdown path doesn't crash; free resources in order.
- Target minimum device: Pixel 8a (Tensor G3, Mali GPU, 8 GB RAM). It's untested, so the CPU fallback must work well.

When finished, give me the APK path, INSTALL.md, and a short list of what to check on the phone.
