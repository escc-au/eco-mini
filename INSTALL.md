# Installing Eco Mini

Three parts: (1) publish the model files on Hugging Face, (2) put the APK on the phone,
(3) run the first-time download.

The APK is `app/build/outputs/apk/release/app-release.apk` (a copy is in `release/Eco-Mini-0.0.3.1.apk`).

**Updating from an earlier build (Eco 1.0, Eco Mini 0.0.3):** just install the new APK over it
(step 2 below, without uninstalling). It has the same app ID and signing key and a higher version code, so the
downloaded model files are kept. The app is now called **Eco Mini**. Its version number reads
0.0.3.1, lower than the old "1.0" label, but Android only compares the internal version code (now 3),
so it installs as an update.

---

## 1. Prepare the Hugging Face repo

The app downloads from `https://huggingface.co/escc/eco-4b-gguf/resolve/main/` by default
(you can change it in Settings → Download address). The repo must be **public** and hold
exactly three files at its top level:

| File | What it is |
|---|---|
| `eco-4b-Q6_K.gguf` | the Eco model (about 3.3 GB) |
| `eco-index.sqlite` | the knowledge index (see "Index format" in README.md) |
| `manifest.json` | the list of files with sizes and checksums |

### Make `manifest.json`

From the folder with the two files:

```sh
python3 tools/make_manifest.py --version 1 \
    --model eco-4b-Q6_K.gguf --index eco-index.sqlite > manifest.json
```

It looks like this:

```json
{
  "version": "1",
  "files": [
    {"name": "eco-4b-Q6_K.gguf", "role": "model", "size": 3306234912, "sha256": "…64 hex characters…"},
    {"name": "eco-index.sqlite", "role": "index", "size": 412345678, "sha256": "…"}
  ]
}
```

Rules the app enforces: exactly one `model` and one `index`; plain file names (no folders);
`size` in bytes; lowercase SHA-256. The app rejects a model whose GGUF architecture isn't
`qwen3`, and an index whose `meta` table doesn't say `embed_model=bge-small-en-v1.5`, `dim=384`,
`quant=int8` and has a `version`.

### Upload

```sh
pip install -U huggingface_hub
hf auth login                      # paste a token with write access
hf repo create escc/eco-4b-gguf --repo-type model     # skip if it exists; public by default
hf upload escc/eco-4b-gguf eco-4b-Q6_K.gguf eco-4b-Q6_K.gguf
hf upload escc/eco-4b-gguf eco-index.sqlite eco-index.sqlite
hf upload escc/eco-4b-gguf manifest.json manifest.json   # last, after the big files
```

Check it in a browser, or:

```sh
curl -sL https://huggingface.co/escc/eco-4b-gguf/resolve/main/manifest.json
```

### Publishing new files later

Upload the new files, then a new `manifest.json` with a higher `--version`. In the app,
Settings → **Check for updated files** compares the version, shows the size, and downloads only
files whose checksum changed. No app update is needed.

---

## 2. Put the APK on the phone

1. Copy `Eco-Mini-0.0.3.1.apk` to the phone, any way you like: USB cable (the phone appears as a drive;
   put it in `Download`), a cloud drive, or email it to yourself.
2. On the phone, open the **Files** app (or the app you received it in) and tap the APK.
3. Android says installing from this source isn't allowed. Tap **Settings**, turn on
   **Allow from this source**, then go back.
   (Elsewhere: Settings → Apps → Special app access → Install unknown apps → pick the app → Allow.)
4. Tap **Install**. If Play Protect warns about an unknown app, choose **More details →
   Install anyway**.
5. Afterwards you can turn "Allow from this source" off again.

**Updates:** a newer APK signed with the same key installs over the old one and keeps the
downloaded files. An APK signed with a different key (for example F-Droid's own build) needs the
old app uninstalled first, which deletes the downloaded files.

---

## 3. First run

1. Open **Eco Mini**. The setup screen explains what will be downloaded.
2. Tap **Check download size**. This is the first time the app goes online: it reads
   `manifest.json` (a few hundred bytes).
3. It shows the total size (about 3.5 GB). Leave **Wi-Fi only** on unless you mean to use
   mobile data, then tap **Download**.
4. The download runs in the background with a notification; you can leave the app. Without
   Wi-Fi it waits and says "Waiting for Wi-Fi".
5. When it finishes, Eco checks each file's SHA-256 (about a minute for 3.3 GB) and its contents.
   A damaged file is deleted and you are offered **Try again**.
6. "Getting Eco ready…" appears while the model loads (about 17 s on the GPU, longer on the
   CPU). Then the chat opens.

**No internet on the phone?** Download the three files on a computer, copy the `.gguf` and
`.sqlite` to the phone, then in Eco Mini tap the gear → **Use files on this phone** and choose each one.
The model is read where it is (don't delete it); the index is copied into the app.

---

## Signing key (back it up)

The release key was generated on your computer, outside the repo:

- Keystore: `~/android-keys/eco-release.jks` (alias `eco`)
- Passwords: `~/android-keys/keystore.properties`

**Back up both files somewhere safe and private** (for example an encrypted USB stick or a password
manager). Without them you can't publish updates that install over this version. They are not in
Dropbox or in git.

Build a signed APK with:

```sh
export JAVA_HOME=~/android-dev/jdk17 ANDROID_HOME=~/android-dev/sdk
./gradlew -Peco.signing.properties=$HOME/android-keys/keystore.properties assembleRelease
```

The toolchain (JDK 17, Android SDK, NDK r27c, CMake) is in `~/android-dev/`.

---

## What to check on the phone

I couldn't test on a device, so please check these by hand. If something goes wrong, the most
useful information is `adb logcat -s EcoNative EcoEngine llama` (if you ever connect the phone)
or a screenshot of Settings.

**Install and setup**
1. The APK installs over the old version without uninstalling, the launcher shows **Eco Mini**
   with a blue leaf icon, and it goes straight to the chat (no new download).
2. Nothing downloads until you tap the buttons.
3. "Check download size" shows about 3.5 GB and the two file sizes.
4. With Wi-Fi only on and Wi-Fi off, the download waits ("Waiting for Wi-Fi") and continues when
   Wi-Fi returns.
5. Leave the app or turn the screen off mid-download: it continues, and progress is right when
   you come back.
6. After the download: "Checking…" and then "Getting Eco ready…" appear, and the chat opens.

**Chat**
7. Ask "What is ocean acidification?" (if the index covers it). The answer streams word by word
   and is 2–3 warm sentences.
8. Ask something the index can't cover (e.g. "Who won the 1998 World Cup?"). Expect
   "I don't have reliable information on that…".
9. Ask a follow-up that only makes sense with history ("Why does that harm oysters?"). The
   answer should be about the earlier topic.
10. Tap **Stop** while an answer streams: it stops within a second. Send another question: it works.
11. Tap **+** (new chat): the conversation clears.
12. Non-English characters and emoji in answers show correctly (no `�`).

**Backends** (Settings → Engine → "In use")
13. OnePlus 13: shows `GPU (OpenCL: …Adreno…)`. Answers should match the CPU's (turn on
    Force CPU, ask the same question, compare; then turn it off).
14. Pixel 8a (and any phone with under 12 GB): Settings shows `CPU, 5 threads · 2,048-token context ·
    weights read from file (low-memory phone)` and "(low-memory mode)" next to Memory; there is no
    Force CPU switch. **Main check for this version: the app is no longer killed** while loading or
    answering, including after a few questions in a row. If you can run `adb logcat -s EcoEngine`, it
    logs the backend, `n_ctx=2048` and `repack=false`.
14a. With several heavy apps open, Eco Mini may say "Not enough free memory, close other apps and try
    again" instead of loading. Close some apps and tap Try again: it loads.
15. If the app ever crashes while "Getting Eco ready…", reopen it: it should start on the CPU and
    Settings should say "The GPU stopped the app last time".
16. Rough speed on CPU: you should see the first words within ~30 s of asking, then several words
    per second. Much slower suggests the wrong CPU variant was chosen.

**Lifecycle**
17. Switch to other heavy apps for a while and come back. Either the chat is still ready, or
    "Getting Eco ready…" appears briefly and it recovers. No crash.
18. Swipe the app away from recents and reopen: no crash report, it loads again.
19. Rotate the phone during an answer: the conversation survives.

**Settings**
20. "Check for updated files" says your files are up to date.
21. "Use files on this phone": choosing a wrong file (e.g. a photo) shows a clear error and Eco
    keeps working with the old files.
22. "Delete downloaded files" asks for confirmation; going back afterwards shows the setup
    screen. (Do this last: you'll need to download again.)
23. Dark mode: black background, white text, blue accents; your messages in dark blue bubbles.
24. Settings → About shows "Version 0.0.3.1 (3)".

**Saved chats**
25. Ask two questions, then open the menu (☰, top left): the chat is listed, titled with the first
    question (cut to about 40 characters), with the date.
26. Tap **+** for a new chat, ask something, then reopen the first chat from the menu: the whole
    conversation is there. Ask a follow-up: it continues that conversation (answers use its history).
27. Close the app mid-answer (swipe it away), reopen the chat: everything up to about two seconds
    before closing is there.
28. A very long chat (20+ questions) still answers new questions; the whole conversation stays
    visible and saved even though only the recent part is sent to the model.
29. Long-press a chat in the list: a confirmation appears; **Delete** removes it, **Cancel** keeps it.
30. Settings → **Delete all chats** asks first, then empties the list.
31. Chats survive restarting the phone.
32. Reinstalling the APK over the app keeps chats; uninstalling deletes them (they are stored only in
    the app and are never backed up).
