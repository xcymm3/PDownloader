"""Generate original, network-independent debug fixtures. Requires imageio-ffmpeg."""
from pathlib import Path
import subprocess
import imageio_ffmpeg

root = Path(__file__).resolve().parents[1] / "app/src/debug/assets/media-fixture"
root.mkdir(parents=True, exist_ok=True)
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

def run(*args):
    subprocess.run([ffmpeg, "-y", *map(str, args)], check=True)

run("-f", "lavfi", "-i", "testsrc2=size=160x288:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "4", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "10", "-c:a", "aac", "-movflags", "+faststart", root / "sample.mp4")
run("-i", root / "sample.mp4", "-c", "copy", "-hls_time", "1", "-hls_list_size", "0", "-hls_segment_filename", root / "segment-%03d.ts", "-f", "hls", root / "media.m3u8")
run("-i", root / "sample.mp4", "-c", "copy", "-hls_time", "1", "-hls_list_size", "0", "-hls_segment_type", "fmp4", "-hls_flags", "single_file", "-hls_segment_filename", root / "single.mp4", "-f", "hls", root / "single.m3u8")
