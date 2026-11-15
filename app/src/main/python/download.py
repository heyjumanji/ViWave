import yt_dlp
import json


def download(quickjs_bin: str, video_id: str, audio_quality: str = "auto") -> str:
    # default mapping: auto -> bestaudio
    format_opt = 'bestaudio'

    # "Very High" / "High" quality: Opus (itag 251) which is ~160kbps for YouTube fallback
    if audio_quality in ("VeryHigh", "Very High", "High"):
        format_opt = '251'
    # "Medium" quality: AAC (itag 140) which is ~128kbps
    elif audio_quality == "Medium":
        format_opt = '140'
    # "Low" quality: Opus (itag 249) which is ~50kbps
    elif audio_quality == "Low":
        format_opt = '249'
    elif audio_quality == "Auto":
        format_opt = 'bestaudio'

    opts = {
        'format': format_opt,
        'js_runtimes': { "quickjs": { "path": quickjs_bin } },
        'extract_flat': False,
        'quiet': True,
        'no_warnings': True,
        'simulate': True
    }

    return json.dumps(yt_dlp.YoutubeDL(opts).extract_info(video_id, download=False), indent=4)


def upgrade(package_name):
    try:
        import ensurepip
        ensurepip.bootstrap()
    except Exception as e:
        print(f"Error running ensurepip: ${e}")

    try:
        import pip
        from pip._internal import main as pip_main

        pip_main(['install', '--upgrade', package_name])
        print(f"Successfully upgraded {package_name}")
    except Exception as e:
        print(f"Error upgrading package {package_name}: {e}")

