# DownTube

A free Snaptube-style video/audio downloader with **no ads, no account, no tracking**.
Two parts:

| Part | What it is |
| --- | --- |
| [`android/`](android) | **Android app** — paste a link, pick a quality, download to `Downloads/downtube` |
| [`downtube.py`](downtube.py) | Optional command line version for a PC |

MIT licensed — use it, fork it, change it, share it.

## Android app

### Install an APK

1. Open the [**Actions**](../../actions/workflows/android.yml) tab.
2. Pick the newest green "build apk" run.
3. Download **`downtube-release-apk`** (or `downtube-debug-apk`) from *Artifacts*.
4. Unzip, copy the `.apk` to your phone, tap it and allow *Install unknown apps*.

Tagged builds (`v1.0`, `v1.1`, …) also show up under **Releases** with both APKs attached.

### What it does

- Paste a link, or press **Share → downtube** from YouTube / Chrome and the link is filled in
- Quality presets: **Best / 1080p / 720p / 480p / 360p / MP3 (audio only)**
- Live progress with ETA, and a **Cancel** button
- Finished files land in **`Downloads/downtube`**, so the gallery and file manager see them
- Works with everything yt-dlp supports (YouTube, Instagram, Facebook, TikTok, Vimeo, …)

### Build it yourself on GitHub

Push to `main` (or run the workflow manually) and the APK is built on GitHub's runners —
nothing to install locally:

- **debug** and **release** APKs are built in the same run
- both are uploaded as workflow artifacts
- pushing a tag like `v1.0` publishes a GitHub release with both APKs

The release APK is signed with the debug keystore so it installs without any secrets.
That is fine for sideloading — it is **not** a Play Store signing setup.

### Apps under the hood

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) + Python + ffmpeg, bundled through
  [youtubedl-android](https://github.com/yausername/youtubedl-android) (0.18.1)
- Kotlin, Material 3, min Android 10 (API 29), arm64 + armeabi-v7a

### Known limitations

- Downloads run inside the app, so if Android kills the app mid-download it stops
  (a foreground service is the next step)
- The bundled yt-dlp ages: when YouTube changes something, the library has to be updated
  (`youtubedl-android` version bump in `android/app/build.gradle.kts`)

## PC command line (optional)

```bash
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt

./downtube.cmd "URL"                                # best quality
./downtube.cmd -q 1080 -f mp4 "URL"                 # 1080p mp4
./downtube.cmd -q audio -f mp3 --audio-bitrate 320 "URL"
./downtube.cmd -F "URL"                             # list available formats
```

| Flag | Meaning |
| --- | --- |
| `-q, --quality` | `best` (default), `2160`/`1440`/`1080`/`720`/`480`/`360`/`240`, `worst`, `audio` |
| `-f, --format` | `auto` (default), `mp4`, `webm`, `mkv`, or audio: `mp3`, `m4a`, `opus`, `wav`, `flac` |
| `-o, --output` | folder to save into (default `./downloads`) |
| `-F, --list-formats` | print every available format and exit |
| `--audio-bitrate` | `64` … `320` kbps for mp3/opus (default `192`) |
| `--playlist` | download the whole playlist |
| `--cookies-from-browser` | `chrome`, `edge`, `firefox`, … for bot-checked pages |

If YouTube answers with `Sign in to confirm you're not a bot`, pass cookies:
`./downtube.cmd --cookies-from-browser chrome "URL"`. yt-dlp goes stale every few weeks —
update it with `.venv/Scripts/python.exe -m pip install -U yt-dlp`.

## Legal

Only download what you have the right to download. Downloading copyrighted material
usually breaks the site's terms of service and can be illegal where you live.
