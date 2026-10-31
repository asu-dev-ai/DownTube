#!/usr/bin/env python3
"""downtube - a small video/audio downloader CLI built on yt-dlp.

Examples
--------
    python downtube.py "https://youtu.be/VIDEO_ID"
    python downtube.py -q 1080 -f mp4 "URL"
    python downtube.py -q audio --audio-bitrate 320 "URL"
    python downtube.py -F "URL"
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

try:
    import yt_dlp
    from yt_dlp.utils import DownloadError
except ImportError:  # pragma: no cover - only hit on a broken install
    sys.exit(
        "yt-dlp is not installed.\n"
        "Run: python -m pip install -r requirements.txt"
    )

VIDEO_FORMATS = ("auto", "mp4", "webm", "mkv")
AUDIO_FORMATS = ("mp3", "m4a", "opus", "wav", "flac")
QUALITIES = ("best", "2160", "1440", "1080", "720", "480", "360", "240", "worst", "audio")
DEFAULT_TEMPLATE = "%(title)s [%(id)s].%(ext)s"


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def find_ffmpeg() -> str | None:
    """Return a path to an ffmpeg binary, or None."""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:  # bundled binary, installed as a regular project dependency
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def find_js_runtimes() -> dict[str, dict | None]:
    """JavaScript runtimes yt-dlp can use to solve YouTube's challenges."""
    found: dict[str, dict | None] = {}
    for name in ("deno", "node", "bun", "quickjs"):
        path = shutil.which(name)
        if path:
            found[name] = {"path": path}
    return found


def _clean_error(msg: str) -> str:
    return msg.replace("ERROR: ", "").strip()


def human_bytes(num: float | None) -> str:
    if num is None:
        return "?"
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if num < 1024 or unit == "TiB":
            return f"{num:.0f}{unit}" if unit == "B" else f"{num:.1f}{unit}"
        num /= 1024
    return "?"


def human_time(seconds: float | None) -> str:
    if seconds is None or seconds < 0:
        return "--:--"
    seconds = int(seconds)
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


def audio_codec_for(fmt: str) -> str:
    return fmt


def build_format_selector(quality: str, fmt: str) -> tuple[str, str | None]:
    """Return (yt-dlp format string, merge container or None)."""
    height = None if quality in ("best", "worst", "audio") else int(quality)
    limit = f"[height<={height}]" if height else ""

    if quality == "worst":
        return f"wv*+wa/w{limit}", None

    if fmt == "mp4":
        return (
            f"bv*{limit}[ext=mp4]+ba[ext=m4a]/bv*{limit}+ba/b{limit}/bv*+ba/b",
            "mp4",
        )
    if fmt == "webm":
        # no forced container here: if only non-webm streams exist a webm muxer
        # would reject them, so let yt-dlp pick (it keeps webm when it can)
        return (
            f"bv*{limit}[ext=webm]+ba[ext=webm]/bv*{limit}+ba/b{limit}/bv*+ba/b",
            None,
        )
    if fmt == "mkv":
        return f"bv*{limit}+ba/b{limit}/bv*+ba/b", "mkv"
    # auto: let yt-dlp pick the most compatible container
    return f"bv*{limit}+ba/b{limit}/bv*+ba/b", None


class Progress:
    """Compact single-line progress output, no colours so it works in cmd.exe."""

    def __init__(self, enabled: bool = True) -> None:
        self.enabled = enabled
        self._width = 0
        self._seen: set[str] = set()

    def _write(self, line: str) -> None:
        line = line[:200]
        pad = max(0, self._width - len(line))
        sys.stdout.write("\r" + line + " " * pad)
        sys.stdout.flush()
        self._width = len(line)

    def _finish_line(self, tail: str = "") -> None:
        sys.stdout.write("\r" + " " * self._width + "\r" + tail + "\n")
        sys.stdout.flush()
        self._width = 0

    def __call__(self, d: dict) -> None:
        if not self.enabled:
            return
        status = d.get("status")
        info = d.get("info_dict") or {}

        if status == "downloading":
            key = str(info.get("id") or "")
            if key and key not in self._seen:
                self._seen.add(key)
                title = info.get("title") or key
                self._finish_line(f"> {title}")

            done = d.get("downloaded_bytes") or 0
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total:
                pct = done / total * 100
                size = f"{human_bytes(done)} / {human_bytes(total)}"
                head = f"  {pct:5.1f}%"
            else:
                size = human_bytes(done)
                head = "   ?? %" if done else "    0%"

            speed = human_bytes(d.get("speed")) + "/s" if d.get("speed") else "--"
            eta = human_time(d.get("eta"))
            self._write(f"{head} | {size:>21} | {speed:>11} | ETA {eta}")

        elif status == "finished":
            done = d.get("downloaded_bytes") or 0
            self._finish_line(f"  100.0% | {human_bytes(done)}")
        elif status == "error":
            self._finish_line("  error")


class QuietLogger:
    """Route yt-dlp messages through our own printer so output stays tidy."""

    def __init__(self, verbose: bool) -> None:
        self.verbose = verbose
        self.errors: list[str] = []

    def debug(self, msg: str) -> None:
        if self.verbose and not msg.startswith("[debug] "):
            print(msg)

    def info(self, msg: str) -> None:
        if self.verbose:
            print(msg)

    def warning(self, msg: str) -> None:
        if not self.verbose and msg.startswith("[debug] "):
            return
        # only ffprobe (not ffmpeg) produces this one; the fallback it triggers
        # is a harmless stream-copy remux of the HLS output
        if "ffprobe not found" in msg:
            return
        print(f"warning: {msg}", file=sys.stderr)

    def error(self, msg: str) -> None:
        # yt-dlp often logs the same error twice and then raises it, so keep a
        # record and print each distinct message only once
        clean = _clean_error(msg)
        if clean in self.errors:
            return
        self.errors.append(clean)
        print(f"error: {clean}", file=sys.stderr)


# --------------------------------------------------------------------------- #
# argument parsing
# --------------------------------------------------------------------------- #
def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="downtube",
        description="Download video or audio from any site yt-dlp supports.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            '  downtube "URL"                       best video, best container\n'
            '  downtube -q 1080 -f mp4 "URL"        1080p mp4\n'
            '  downtube -q audio -f mp3 "URL"       mp3 audio\n'
            '  downtube -F "URL"                    list every available format\n'
            '  downtube --playlist -q 720 "URL"     whole playlist at 720p\n'
        ),
    )
    p.add_argument("urls", nargs="+", metavar="URL", help="video / playlist URL(s)")
    p.add_argument(
        "-q",
        "--quality",
        default="best",
        choices=QUALITIES,
        help="max video height, 'audio' for sound only, or 'best'/'worst' (default: best)",
    )
    p.add_argument(
        "-f",
        "--format",
        default="auto",
        choices=VIDEO_FORMATS + AUDIO_FORMATS,
        help="output container/codec (default: auto)",
    )
    p.add_argument(
        "-o",
        "--output",
        default="downloads",
        metavar="DIR",
        help="folder to save into (default: ./downloads)",
    )
    p.add_argument(
        "-F",
        "--list-formats",
        action="store_true",
        help="only list the available formats, do not download",
    )
    p.add_argument(
        "--audio-bitrate",
        default="192",
        choices=("64", "96", "128", "192", "256", "320"),
        help="mp3/opus bitrate in kbps (default: 192)",
    )
    p.add_argument(
        "-t",
        "--template",
        default=DEFAULT_TEMPLATE,
        metavar="TMPL",
        help=f"output filename template (default: {DEFAULT_TEMPLATE.replace('%', '%%')})",
    )
    p.add_argument("--playlist", action="store_true", help="download the whole playlist")
    p.add_argument(
        "--cookies-from-browser",
        metavar="BROWSER",
        help="load cookies from a browser, e.g. chrome, edge, firefox",
    )
    p.add_argument("--cookies", metavar="FILE", help="load cookies from a Netscape cookie file")
    p.add_argument("--no-progress", action="store_true", help="disable the progress line")
    p.add_argument("-v", "--verbose", action="store_true", help="show yt-dlp's own output")
    p.add_argument("--version", action="version", version=f"downtube (yt-dlp {yt_dlp.version.__version__})")
    return p.parse_args(argv)


# --------------------------------------------------------------------------- #
# format listing
# --------------------------------------------------------------------------- #
def list_formats(args: argparse.Namespace) -> int:
    opts = base_opts(args, ffmpeg=None)
    rc = 0
    for url in args.urls:
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
        except DownloadError as exc:
            print(f"error: {exc}", file=sys.stderr)
            rc = 1
            continue
        if info.get("_type") == "playlist":
            entries = [e for e in (info.get("entries") or []) if e]
            if not entries:
                print("error: playlist is empty or private", file=sys.stderr)
                rc = 1
                continue
            print(f"playlist: {info.get('title')} ({len(entries)} entries, showing the first)")
            info = entries[0]
            print()
        print_formats(info)
    return rc


def print_formats(info: dict) -> None:
    print(f"title : {info.get('title')}")
    print(f"site  : {info.get('extractor_key') or info.get('extractor')}")
    if info.get("duration"):
        print(f"length: {human_time(info.get('duration'))}")
    print()

    rows: list[list[str]] = []
    for f in info.get("formats") or []:
        if f.get("ext") in ("mhtml", "none"):
            continue
        if f.get("format_note") == "storyboard":
            continue
        vcodec = f.get("vcodec") or "none"
        acodec = f.get("acodec") or "none"
        kind = "audio" if vcodec == "none" else "video"
        if f.get("height"):
            res = f"{f.get('width') or '?'}x{f['height']}"
        else:
            res = "audio only" if kind == "audio" else "?"
        fps = f"{f['fps']:g}" if f.get("fps") else ""
        if f.get("filesize"):
            size = human_bytes(f["filesize"])
        elif f.get("filesize_approx"):
            size = "~" + human_bytes(f["filesize_approx"])
        else:
            size = "?"
        rows.append(
            [
                str(f.get("format_id")),
                f.get("ext") or "?",
                res,
                fps,
                vcodec.split(".")[0][:12],
                acodec.split(".")[0][:12],
                size,
                (f.get("format_note") or "")[:24],
            ]
        )

    if not rows:
        print("no downloadable formats reported")
        return

    headers = ["id", "ext", "resolution", "fps", "vcodec", "acodec", "size", "note"]
    widths = [max(len(headers[i]), *(len(r[i]) for r in rows)) for i in range(len(headers))]
    line = "  ".join(h.ljust(w) for h, w in zip(headers, widths))
    print(line)
    print("-" * len(line))
    for r in rows:
        print("  ".join(c.ljust(w) for c, w in zip(r, widths)))
    print("\nUse -q <height> to cap the resolution, or -f mp4/webm/mkv/mp3 to pick a container.")


# --------------------------------------------------------------------------- #
# yt-dlp options
# --------------------------------------------------------------------------- #
def base_opts(args: argparse.Namespace, ffmpeg: str | None) -> dict:
    opts: dict = {
        "quiet": True,
        "no_warnings": False,
        "noprogress": True,
        "logger": QuietLogger(args.verbose or False),
        "noplaylist": not getattr(args, "playlist", False),
        "ignoreerrors": False,
        "retries": 5,
        "fragment_retries": 5,
        "concurrent_fragment_downloads": 4,
        "paths": {"home": str(Path(args.output).expanduser())},
        "outtmpl": {"default": args.template},
        "windowsfilenames": True,
        "restrictfilenames": False,
        "overwrites": False,
        "continuedl": True,
    }
    if ffmpeg:
        opts["ffmpeg_location"] = ffmpeg
    runtimes = find_js_runtimes()
    if runtimes:
        opts["js_runtimes"] = runtimes
    if getattr(args, "cookies_from_browser", None):
        opts["cookiesfrombrowser"] = (args.cookies_from_browser.lower(),)
    if getattr(args, "cookies", None):
        opts["cookiefile"] = args.cookies
    return opts


def download_opts(args: argparse.Namespace, ffmpeg: str | None) -> dict:
    opts = base_opts(args, ffmpeg)

    audio_only = args.quality == "audio" or args.format in AUDIO_FORMATS
    if audio_only:
        if args.format not in ("auto", *AUDIO_FORMATS):
            raise SystemExit(
                f"error: -q audio cannot produce a {args.format} file - "
                "use an audio format such as -f mp3, or drop -f"
            )
        codec = args.format if args.format in AUDIO_FORMATS else "mp3"
        if codec in ("mp3", "opus") and not ffmpeg:
            raise SystemExit("error: converting to %s needs ffmpeg (see README)" % codec)
        postprocessor = {
            "key": "FFmpegExtractAudio",
            "preferredcodec": codec,
            "preferredquality": args.audio_bitrate if codec in ("mp3", "opus") else "0",
        }
        opts.update(
            {
                "format": "ba/b",
                "postprocessors": [postprocessor],
                "outtmpl": {"default": args.template},
            }
        )
        return opts

    selector, container = build_format_selector(args.quality, args.format)
    opts["format"] = selector
    if container:
        opts["merge_output_format"] = container
    if ffmpeg is None and container in ("mp4", "mkv"):
        print(
            "warning: ffmpeg not found - high resolution (split video+audio) downloads will fail.\n"
            "         install it with: pip install imageio-ffmpeg",
            file=sys.stderr,
        )
    return opts


# --------------------------------------------------------------------------- #
# entry point
# --------------------------------------------------------------------------- #
def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    args = parse_args(argv)
    ffmpeg = find_ffmpeg()

    if args.list_formats:
        return list_formats(args)

    Path(args.output).expanduser().mkdir(parents=True, exist_ok=True)

    try:
        opts = download_opts(args, ffmpeg)
    except SystemExit as exc:
        print(exc, file=sys.stderr)
        return 2
    # the \r progress line only makes sense on a real terminal
    show_progress = not args.no_progress and sys.stdout.isatty()
    opts["progress_hooks"] = [Progress(enabled=show_progress)]
    logger = opts["logger"]
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            rc = ydl.download(args.urls)
    except DownloadError as exc:
        msg = _clean_error(str(exc))
        if msg not in logger.errors:
            print(f"\nerror: {msg}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130

    if rc == 0:
        print(f"\nsaved to: {Path(args.output).expanduser().resolve()}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
