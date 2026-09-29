"""Render a standalone animated preview of the fly-brain routing simulation.

This is not the solver and not the connectome runner. It consumes the replay
and sidecar brain events, then produces a shareable GIF that shows the intended
3D interaction without requiring the browser to do inference.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
REPLAY_PATH = ROOT / "examples" / "ui_demo.json"
EVENTS_PATH = ROOT / "examples" / "ui_demo.events.ndjson"
OUT_PATH = ROOT / "output" / "fly_heatnet_3d_simulation.gif"

W, H = 960, 540
BG = (15, 20, 23)
GRID = (34, 43, 46)
TEXT = (238, 242, 232)
MUTED = (152, 166, 158)
GREEN = (104, 211, 145)
YELLOW = (247, 201, 107)
RED = (255, 116, 108)
BLUE = (99, 133, 157)
PINK = (244, 111, 159)


def load_font(size: int, bold: bool = False):
    candidates = [
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


FONT_12 = load_font(12)
FONT_14 = load_font(14)
FONT_18 = load_font(18, True)
FONT_28 = load_font(28, True)


def parse_events() -> dict[tuple[int, str], dict]:
    events = {}
    for line in EVENTS_PATH.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        event = json.loads(line)
        if event.get("type") == "brain_phase":
            events[(event["step_index"], event["phase"])] = event
    return events


def bounds(replay):
    coords = []

    def walk(value):
        if isinstance(value, list) and len(value) >= 2 and all(isinstance(x, (int, float)) for x in value[:2]):
            coords.append((value[0], value[1]))
            return
        if isinstance(value, list):
            for item in value:
                walk(item)

    for collection_name in ("input", "initial"):
        for feature in replay[collection_name]["features"]:
            walk(feature.get("geometry", {}).get("coordinates"))
    for step in replay["steps"]:
        for feature in step["after"]["features"] + step["candidate"]["features"]:
            walk(feature.get("geometry", {}).get("coordinates"))
    xs = [x for x, _ in coords]
    ys = [y for _, y in coords]
    return min(xs), min(ys), max(xs), max(ys)


def make_projector(replay):
    min_x, min_y, max_x, max_y = bounds(replay)
    span_x = max(max_x - min_x, 1)
    span_y = max(max_y - min_y, 1)
    scale = min(620 / span_x, 330 / span_y)

    def project(coord, lift=0.0):
        x, y = coord
        sx = 205 + (x - min_x) * scale
        sy = 420 - (y - min_y) * scale
        # Simple oblique 3D lift.
        return sx + lift * 0.42, sy - lift

    return project


def line_points(coords, project, lift=0.0):
    return [project(coord, lift) for coord in coords]


def lerp(a, b, t):
    return a + (b - a) * t


def path_point(coords, progress):
    if len(coords) < 2:
        return coords[0]
    lengths = []
    total = 0.0
    for a, b in zip(coords, coords[1:]):
        total += math.dist(a, b)
        lengths.append(total)
    target = progress * total
    prev = 0.0
    for index, length in enumerate(lengths):
        if target <= length:
            local = (target - prev) / max(length - prev, 1e-9)
            ax, ay = coords[index]
            bx, by = coords[index + 1]
            return lerp(ax, bx, local), lerp(ay, by, local)
        prev = length
    return coords[-1]


def draw_polyline(draw, points, fill, width=5, joint="curve"):
    if len(points) >= 2:
        draw.line(points, fill=fill, width=width, joint=joint)


def draw_brain(draw, active, pulse):
    cx, cy = 126, 140
    draw.ellipse((cx - 56, cy - 44, cx + 56, cy + 44), fill=(28, 65, 54), outline=GREEN, width=2)
    draw.text((58, 48), "fly brain", fill=GREEN, font=FONT_18)
    active_map = {item["id"]: item["activation"] for item in active}
    positions = []
    for i in range(28):
        theta = i * 2.399
        rx = 44 + (i % 5) * 2
        ry = 30 + (i % 4) * 2
        x = cx + math.cos(theta) * rx
        y = cy + math.sin(theta) * ry
        positions.append((x, y))
    for i in range(0, 26, 2):
        draw.line((positions[i], positions[(i + 5) % 28]), fill=(45, 103, 82), width=1)
    for i, (x, y) in enumerate(positions):
        nid = f"FLY-N{i:02d}"
        a = active_map.get(nid, 0.0)
        color = YELLOW if a > 0.62 else GREEN if a > 0.25 else (67, 80, 90)
        r = 3 + a * 8 + pulse * a * 2
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def draw_fly(draw, x, y, angle, wing):
    body = (240, 244, 236)
    outline = (9, 13, 14)
    # Wings
    draw.ellipse((x - 34, y - 26 - wing, x - 3, y - 4 + wing), fill=(117, 220, 181, 92), outline=GREEN, width=1)
    draw.ellipse((x + 3, y - 26 + wing, x + 34, y - 4 - wing), fill=(117, 220, 181, 92), outline=GREEN, width=1)
    # Body/head
    draw.ellipse((x - 13, y - 12, x + 13, y + 20), fill=body, outline=outline, width=2)
    draw.ellipse((x - 9, y - 26, x + 9, y - 9), fill=body, outline=outline, width=2)
    draw.ellipse((x - 6, y - 23, x - 1, y - 18), fill=outline)
    draw.ellipse((x + 1, y - 23, x + 6, y - 18), fill=outline)
    # Legs / stylus
    draw.line((x - 10, y + 14, x - 26, y + 30), fill=body, width=2)
    draw.line((x + 10, y + 14, x + 24, y + 30), fill=body, width=2)
    draw.line((x, y + 18, x + math.cos(angle) * 22, y + 18 + math.sin(angle) * 22), fill=YELLOW, width=3)


def draw_ui(draw, step, phase, active):
    draw.rectangle((690, 30, 930, 510), fill=(24, 29, 32), outline=(52, 58, 62), width=1)
    draw.text((712, 54), "phase", fill=MUTED, font=FONT_12)
    draw.text((712, 72), phase.upper(), fill=GREEN if phase != "reject" else RED, font=FONT_28)
    draw.text((712, 122), step["operation"], fill=TEXT, font=FONT_18)
    draw.text((712, 148), step["status"], fill=GREEN if step["status"] == "committed" else RED, font=FONT_14)
    draw.text((712, 192), "active neurons", fill=MUTED, font=FONT_12)
    y = 214
    for item in active[:8]:
        label = f'{item["id"]} {round(item["activation"] * 100):02d}%'
        draw.text((712, y), label, fill=YELLOW if item["activation"] > 0.62 else GREEN, font=FONT_14)
        y += 24
    draw.text((712, 450), "browser = visualization only", fill=MUTED, font=FONT_12)
    draw.text((712, 470), "sidecar events drive neurons", fill=MUTED, font=FONT_12)


def phase_at(local):
    if local < 0.22:
        return "scan", 0.0
    if local < 0.44:
        return "decide", 0.0
    if local < 0.84:
        return "draw", (local - 0.44) / 0.40
    return "commit", 1.0


def render():
    replay = json.loads(REPLAY_PATH.read_text(encoding="utf-8"))
    events = parse_events()
    project = make_projector(replay)
    frames = []
    prior_features = replay["initial"]["features"]

    for step_index, step in enumerate(replay["steps"], start=1):
        candidate = next(f for f in step["candidate"]["features"] if f["geometry"]["type"] == "LineString")
        cand_coords = candidate["geometry"]["coordinates"]
        for frame_index in range(22):
            local = frame_index / 21
            phase, draw_progress = phase_at(local)
            if phase == "commit" and step["status"] == "rejected":
                phase = "reject"
            active = events.get((step_index, phase), events.get((step_index, "commit"), {})).get("active_neurons", [])
            image = Image.new("RGB", (W, H), BG)
            draw = ImageDraw.Draw(image, "RGBA")

            for gx in range(180, 660, 55):
                draw.line((gx, 80, gx - 130, 450), fill=GRID, width=1)
            for gy in range(120, 470, 44):
                draw.line((110, gy, 670, gy - 14), fill=GRID, width=1)

            draw.text((42, 24), "Fly Heatnet 3D Simulation", fill=TEXT, font=FONT_28)
            draw.text((44, 58), "brain sidecar -> active neurons -> fly draws checked heat route", fill=MUTED, font=FONT_14)

            draw_brain(draw, active, math.sin(frame_index * 0.8) * 0.5 + 0.5)

            # Existing route already accepted before this step.
            for feature in prior_features:
                if feature["geometry"]["type"] == "LineString":
                    pts = line_points(feature["geometry"]["coordinates"], project, 8)
                    draw_polyline(draw, pts, (*GREEN, 180), width=9)
                    draw_polyline(draw, pts, (*GREEN, 255), width=4)

            cand_visible = phase in {"decide", "draw", "commit", "reject"}
            if cand_visible:
                visible_count = max(2, int(2 + (len(cand_coords) - 1) * draw_progress))
                visible_coords = cand_coords[:visible_count]
                if phase == "draw" and draw_progress < 1:
                    end = path_point(cand_coords, draw_progress)
                    visible_coords = cand_coords[: max(1, visible_count - 1)] + [list(end)]
                color = RED if phase == "reject" else YELLOW
                pts = line_points(visible_coords, project, 14)
                draw_polyline(draw, pts, (*color, 210), width=11)
                draw_polyline(draw, pts, (*color, 255), width=5)

            fly_progress = min(1.0, max(0.0, (local - 0.18) / 0.62))
            fly_coord = path_point(cand_coords, fly_progress)
            fx, fy = project(fly_coord, 42)
            draw.line((126, 140, fx, fy), fill=(104, 211, 145, 85), width=2)
            draw_fly(draw, fx, fy, -0.7, math.sin(frame_index * 1.7) * 5)

            if phase in {"commit", "reject"}:
                text = "CHECKER ACCEPTED" if step["status"] == "committed" else "CHECKER REJECTED"
                color = GREEN if step["status"] == "committed" else RED
                draw.rounded_rectangle((286, 92, 560, 132), radius=8, fill=(17, 21, 23, 220), outline=color, width=2)
                draw.text((304, 102), text, fill=color, font=FONT_18)

            draw_ui(draw, step, phase, active)
            frames.append(image)

        prior_features = step["after"]["features"]

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(
        OUT_PATH,
        save_all=True,
        append_images=frames[1:],
        duration=70,
        loop=0,
        optimize=False,
    )
    print(f"wrote {OUT_PATH}")
    print(f"frames {len(frames)}")


if __name__ == "__main__":
    render()

