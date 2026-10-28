#!/usr/bin/env python3
"""Extract genuine preview media from a rendered PadNote demo, without altering its UI."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):
            h.update(block)
    return h.hexdigest()


def run(args: list[str]) -> None:
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors='replace')
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {args[0]}\n{result.stdout}")


def main() -> int:
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--video',required=True,type=Path,help='rendered real-screen recording MP4')
    ap.add_argument('--ffmpeg',required=True,type=Path,help='explicit FFmpeg executable')
    ap.add_argument('--poster',required=True,type=Path,help='PNG output path, preferably source/docs/media/')
    ap.add_argument('--poster-at',required=True,type=float,help='root-reviewed timestamp in seconds')
    ap.add_argument('--gif',type=Path,help='optional short GIF output path')
    ap.add_argument('--gif-start',type=float,help='root-reviewed GIF start timestamp in seconds')
    ap.add_argument('--gif-seconds',type=float,default=6.0,help='GIF duration (2..8 seconds)')
    ap.add_argument('--manifest',type=Path,help='JSON receipt path; defaults beside poster')
    args=ap.parse_args()
    video=args.video.resolve(); ffmpeg=args.ffmpeg.resolve()
    if not video.is_file() or not ffmpeg.is_file(): raise FileNotFoundError('video or explicit FFmpeg path missing')
    if args.poster_at < 0: raise ValueError('--poster-at must be nonnegative')
    args.poster.parent.mkdir(parents=True,exist_ok=True)
    run([str(ffmpeg),'-hide_banner','-y','-ss',f'{args.poster_at:.3f}','-i',str(video),'-frames:v','1','-update','1',str(args.poster)])
    outputs=[{'path':str(args.poster),'sha256':sha256(args.poster),'bytes':args.poster.stat().st_size}]
    if args.gif:
        if args.gif_start is None or args.gif_start < 0 or not 2 <= args.gif_seconds <= 8:
            raise ValueError('GIF requires --gif-start and --gif-seconds in 2..8')
        args.gif.parent.mkdir(parents=True,exist_ok=True)
        filt='fps=8,scale=720:-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer'
        run([str(ffmpeg),'-hide_banner','-y','-ss',f'{args.gif_start:.3f}','-t',f'{args.gif_seconds:.3f}','-i',str(video),'-vf',filt,'-loop','0',str(args.gif)])
        outputs.append({'path':str(args.gif),'sha256':sha256(args.gif),'bytes':args.gif.stat().st_size})
    manifest=args.manifest or args.poster.with_suffix('.media.json')
    manifest.parent.mkdir(parents=True,exist_ok=True)
    manifest.write_text(json.dumps({'source_video':str(video),'source_video_sha256':sha256(video),'poster_at_seconds':args.poster_at,'outputs':outputs},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(manifest)
    for out in outputs: print(f"{out['path']}  {out['bytes']} bytes  sha256:{out['sha256']}")
    return 0

if __name__=='__main__':
    try: raise SystemExit(main())
    except Exception as exc:
        print(f'preview extraction failed: {exc}',file=sys.stderr)
        raise SystemExit(2)
