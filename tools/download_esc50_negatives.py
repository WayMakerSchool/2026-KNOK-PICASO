"""Download a small, attributed ESC-50 hard-negative subset.

This is a prototype-only substitute for real apartment background recordings.
It deliberately downloads one clip per Freesound source and only a small
number of classes instead of the full ESC-50 archive.
"""

from __future__ import annotations

import argparse
import csv
import io
import textwrap
from pathlib import Path
from urllib.request import Request, urlopen


RAW_BASE = "https://raw.githubusercontent.com/karoldvl/ESC-50/master"
CLASSES = (
    "dog",
    "crying_baby",
    "door_wood_knock",
    "washing_machine",
    "water_drops",
    "keyboard_typing",
    "clapping",
    "car_horn",
)
CLIPS_PER_CLASS = 10


def download_bytes(url: str) -> bytes:
    request = Request(url, headers={"User-Agent": "KNOK-audio-dataset-preparer/1.0"})
    with urlopen(request, timeout=60) as response:
        return response.read()


def choose_rows(metadata: list[dict[str, str]]) -> list[dict[str, str]]:
    chosen: list[dict[str, str]] = []
    for category in CLASSES:
        seen_source_ids: set[str] = set()
        category_rows = [row for row in metadata if row["category"] == category]
        for row in sorted(category_rows, key=lambda item: item["filename"]):
            if row["src_file"] in seen_source_ids:
                continue
            chosen.append(row)
            seen_source_ids.add(row["src_file"])
            if len(seen_source_ids) >= CLIPS_PER_CLASS:
                break
        if len(seen_source_ids) < CLIPS_PER_CLASS:
            raise RuntimeError(f"Only found {len(seen_source_ids)} unique sources for {category}")
    return chosen


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("dataset/external/esc50_normal"),
    )
    args = parser.parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    metadata_bytes = download_bytes(f"{RAW_BASE}/meta/esc50.csv")
    metadata = list(csv.DictReader(io.StringIO(metadata_bytes.decode("utf-8"))))
    selected = choose_rows(metadata)

    selection_rows: list[dict[str, str]] = []
    for index, row in enumerate(selected):
        output_name = f"normal_or_unknown{index}.wav"
        output_path = output_dir / output_name
        source_url = f"{RAW_BASE}/audio/{row['filename']}"
        if not output_path.exists() or output_path.stat().st_size == 0:
            output_path.write_bytes(download_bytes(source_url))
        selection_rows.append(
            {
                "output_file": output_name,
                "esc50_filename": row["filename"],
                "category": row["category"],
                "fold": row["fold"],
                "target": row["target"],
                "src_file": row["src_file"],
                "source_url": source_url,
            }
        )

    with (output_dir / "selection.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(selection_rows[0].keys()))
        writer.writeheader()
        writer.writerows(selection_rows)

    license_path = output_dir / "ESC-50-LICENSE"
    if not license_path.exists():
        license_path.write_bytes(download_bytes(f"{RAW_BASE}/LICENSE"))

    readme = textwrap.dedent(
        f"""
        # ESC-50 hard negatives used by KNOK

        This directory contains {len(selection_rows)} clips from ESC-50 selected
        as temporary `normal_or_unknown` hard negatives.  They are not a
        replacement for real apartment recordings.

        Selected classes: {', '.join(CLASSES)}.

        Source: https://github.com/karoldvl/ESC-50
        Dataset license: Creative Commons Attribution-NonCommercial 3.0;
        see `ESC-50-LICENSE` and `selection.csv` for attribution metadata.
        """
    ).strip() + "\n"
    (output_dir / "README.md").write_text(readme, encoding="utf-8")

    print(f"Downloaded/verified {len(selection_rows)} ESC-50 clips")
    print(f"Output: {output_dir}")
    print(f"Classes: {', '.join(CLASSES)}")


if __name__ == "__main__":
    main()
