"""Crop the agent screenshots to the parts worth showing in the README.

Keeps the app's own chrome (status bar) and removes the parts that are just
scroll position. Run from the repo root:

    python tools/crop_screenshots.py
"""
import pathlib
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install pillow")

ROOT = pathlib.Path(__file__).resolve().parents[1]
SHOTS = ROOT / "docs" / "screenshots"

# (source, output, (left, top, right, bottom) as fractions of the image)
JOBS = [
    # Bottom part of the scroll: the goal field and the Mic / Play / Stop row.
    ("02-goal-and-controls.png", "02-goal-and-controls.png", (0.0, 0.60, 1.0, 0.90)),
]


def main() -> None:
    for src, dst, box in JOBS:
        spath = SHOTS / src
        if not spath.exists():
            print(f"skip {src} (missing)")
            continue
        im = Image.open(spath)
        w, h = im.size
        crop = im.crop((int(box[0] * w), int(box[1] * h), int(box[2] * w), int(box[3] * h)))
        crop.save(SHOTS / dst, optimize=True)
        print(f"{src} -> {dst}  {crop.size[0]}x{crop.size[1]}  {(spath.stat().st_size/1024):.0f} KB")


if __name__ == "__main__":
    main()
