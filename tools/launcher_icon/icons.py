"""Launcher icon concepts for "Τι θα φάμε;".

One shape list per concept drives both the SVG preview sheet and the Android
VectorDrawable XML, so what you preview is exactly what ships.

The viewport is the 108dp adaptive-icon canvas. Launchers show the middle
72dp and may mask it to a circle, so the important shapes stay inside the
66dp safe-zone circle.

Usage:
    python tools/launcher_icon/icons.py preview build/icon-preview
    python tools/launcher_icon/icons.py export <concept-key> app/src/main/res
"""
from __future__ import annotations

import html
import math
import os
import sys
from dataclasses import dataclass, field

PAPRIKA = "#B8442E"
PAPRIKA_DARK = "#8D2E1E"
TERRACOTTA = "#D96C4B"
SAGE = "#2F6D62"
CREAM = "#FFF8F1"
OAT = "#F3E7D8"
GOLD = "#E5A63B"
PEACH = "#FFDAD0"


@dataclass
class Shape:
    d: str
    fill: str | None = None
    stroke: str | None = None
    width: float = 0
    cap: str = "round"
    join: str = "round"
    rotate: float = 0  # degrees, clockwise about the canvas centre (54, 54)
    tx: float = 0
    ty: float = 0


@dataclass
class Concept:
    key: str
    title: str
    blurb: str
    background: str
    shapes: list[Shape] = field(default_factory=list)
    mono: list[Shape] = field(default_factory=list)  # silhouette for themed icons


def circle(cx, cy, r):
    return f"M{cx - r},{cy}a{r},{r} 0 1 0 {2 * r},0a{r},{r} 0 1 0 {-2 * r},0Z"


def ellipse(cx, cy, rx, ry):
    return f"M{cx - rx},{cy}a{rx},{ry} 0 1 0 {2 * rx},0a{rx},{ry} 0 1 0 {-2 * rx},0Z"


def qmark(cx, cy, r, stem, start_deg=172):
    """A question-mark hook: arc from `start_deg` clockwise round to the bottom, then a stem."""
    a = math.radians(start_deg)
    x0, y0 = cx + r * math.cos(a), cy + r * math.sin(a)
    return f"M{x0:.2f},{y0:.2f} A{r},{r} 0 1 1 {cx},{cy + r} L{cx},{cy + r + stem}"


# ---- A: a spoon bent into a question mark (bowl on top, handle curls down) --
def spoon_question(color, dot=None):
    dot = dot or color
    return [
        Shape("M59.5,33.5 A12.5,12.5 0 0 1 55,58 L55,64", stroke=color, width=7),
        Shape(f"M{50-10.5},{31}a10.5,7.5 0 1 0 21,0a10.5,7.5 0 1 0 -21,0Z", fill=color),
        Shape(circle(55, 77, 4.6), fill=dot),
    ]


A = Concept(
    "spoon_question",
    "Bent spoon",
    "A spoon whose handle curls into a question mark; the codename is Spoon.",
    PAPRIKA,
    shapes=spoon_question(CREAM, GOLD),
    mono=spoon_question("#000"),
)

# ---- B: a plate with a question mark ----------------------------------------
def plate_question(plate, shadow, rim, mark, r=29):
    return [
        Shape(circle(54, 56.5, r), fill=shadow),
        Shape(circle(54, 54, r), fill=plate),
        Shape(circle(54, 54, r - 7), stroke=rim, width=1.8),
        Shape(qmark(54, 47.5, 9, 3.5, 168), stroke=mark, width=7.2),
        Shape(circle(54, 69, 4), fill=mark),
    ]


B = Concept(
    "plate_question",
    "Plate",
    "A cream plate with a bold question mark: what goes on the plate tonight?",
    PAPRIKA,
    shapes=plate_question(CREAM, PAPRIKA_DARK, OAT, PAPRIKA),
    mono=[Shape(circle(54, 54, 29), fill="#000")],
)

# ---- plate family: the plate carries the question, no cutlery ----------------
def tapered_qmark(cx, cy, r_out, r_in, inner_dx, inner_dy, start_deg, stem_h, stem_w):
    """A filled question-mark hook whose stroke thins towards the tail.

    The outer edge follows a circle of radius r_out; the inner edge follows a smaller
    circle whose centre is offset, so the thickness varies smoothly round the hook.
    """
    pts_out, pts_in = [], []
    steps = 28
    for i in range(steps + 1):
        a = math.radians(start_deg + (450 - start_deg) * i / steps)  # clockwise to the bottom (90°)
        pts_out.append((cx + r_out * math.cos(a), cy + r_out * math.sin(a)))
        pts_in.append((cx + inner_dx + r_in * math.cos(a), cy + inner_dy + r_in * math.sin(a)))
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts_out)
    # stem: from the bottom of the hook straight down
    bx, by = pts_out[-1]
    ix, iy = pts_in[-1]
    d += f" L{bx + stem_w / 2:.2f},{by + stem_h:.2f} L{bx - stem_w / 2:.2f},{by + stem_h:.2f}"
    d += " L" + " L".join(f"{x:.2f},{y:.2f}" for x, y in reversed(pts_in)) + "Z"
    return d


def plate(plate_color, shadow, rim, r=31, rim_r=None, well=None):
    shapes = [Shape(circle(54, 56.6, r), fill=shadow), Shape(circle(54, 54, r), fill=plate_color)]
    if well:
        shapes.append(Shape(circle(54, 54, rim_r or r - 8), fill=well))
    else:
        shapes.append(Shape(circle(54, 54, rim_r or r - 8), stroke=rim, width=1.8))
    return shapes


def stroked_mark(mark, dot=None, width=7.2):
    return [Shape(qmark(54, 47.5, 9, 3.5, 168), stroke=mark, width=width), Shape(circle(54, 69, 4), fill=dot or mark)]


P1 = Concept(
    "plate_classic", "Plate, classic",
    "Cream plate, oat rim, bold paprika question mark. The one from the place setting, alone and bigger.",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT) + stroked_mark(PAPRIKA),
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

TAPERED = tapered_qmark(54, 46.5, 12.2, 5.6, 1.2, 1.4, 165, 6.5, 6.4)
P2 = Concept(
    "plate_tapered", "Plate, tapered mark",
    "Same plate, but the question mark is drawn like type: heavy at the top, thinning into the tail.",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT) + [Shape(TAPERED, fill=PAPRIKA), Shape(circle(54, 69.5, 4.2), fill=PAPRIKA)],
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

P3 = Concept(
    "plate_gold", "Plate, gold dot",
    "The classic plate with the app's gold on the dot only: one warm accent.",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT) + stroked_mark(PAPRIKA, GOLD),
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

P4 = Concept(
    "plate_well", "Plate, two-tone well",
    "The plate's well is filled oat instead of a hairline ring, so the rim reads as a real edge.",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT, well=OAT, rim_r=23) + stroked_mark(PAPRIKA, width=7.6),
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

PLACEMAT = "M25,25 H83 A8,8 0 0 1 91,33 V75 A8,8 0 0 1 83,83 H25 A8,8 0 0 1 17,75 V33 A8,8 0 0 1 25,25 Z"
P5 = Concept(
    "plate_placemat", "Plate on a placemat",
    "A darker paprika placemat under the plate gives depth and a table without any cutlery.",
    PAPRIKA,
    shapes=[Shape(PLACEMAT, fill=PAPRIKA_DARK)] + plate(CREAM, "#6E2115", OAT, r=27, rim_r=20)
    + [Shape(qmark(54, 48, 8, 3, 168), stroke=PAPRIKA, width=6.4), Shape(circle(54, 67, 3.6), fill=PAPRIKA)],
    mono=[Shape(circle(54, 54, 30), fill="#000")],
)

def greek_mark(color, r=5.6, cy_dot=42.5, cy_head=57.5):
    """The Greek question mark «;»: a round dot over a comma drawn as one teardrop.

    The comma's head is a circle; the tail leaves it tangentially on the right, sweeps
    down and left to a point, and returns to the head's underside, so the join is invisible.
    """
    cx = 54
    head = circle(cx, cy_head, r)
    tail = (
        f"M{cx + r * 0.87:.2f},{cy_head + r * 0.5:.2f} "
        f"C{cx + r * 0.95:.2f},{cy_head + r * 1.9:.2f} {cx - r * 0.1:.2f},{cy_head + r * 2.55:.2f} "
        f"{cx - r * 0.95:.2f},{cy_head + r * 2.95:.2f} "
        f"C{cx - r * 0.35:.2f},{cy_head + r * 2.2:.2f} {cx - r * 0.35:.2f},{cy_head + r * 1.55:.2f} "
        f"{cx - r * 0.6:.2f},{cy_head + r * 0.75:.2f} Z"
    )
    return [Shape(circle(cx, cy_dot, r), fill=color), Shape(head, fill=color), Shape(tail, fill=color)]


P6 = Concept(
    "plate_greek", "Plate, Greek «;»",
    "The Greek question mark itself, exactly as the name is written: «Τι θα φάμε;».",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT, well=OAT, rim_r=23) + greek_mark(PAPRIKA),
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

P6b = Concept(
    "plate_greek_bold", "Plate, Greek «;», heavier",
    "Same mark with a larger dot and head, for more presence at 24px.",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT, well=OAT, rim_r=23) + greek_mark(PAPRIKA, r=6.4, cy_dot=41.5, cy_head=58),
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

P6c = Concept(
    "plate_greek_rim", "Plate, Greek «;», hairline rim",
    "The smooth mark on the classic plate with the hairline rim instead of the filled well.",
    PAPRIKA,
    shapes=plate(CREAM, PAPRIKA_DARK, OAT) + greek_mark(PAPRIKA, r=6),
    mono=[Shape(circle(54, 54, 31), fill="#000")],
)

# ---- G: question mark whose dot is a spoon bowl -----------------------------
def spoon_dot(color, dot):
    return [
        Shape(qmark(54, 44, 13, 6, 170), stroke=color, width=9),
        Shape(ellipse(54, 78, 7, 5.2), fill=dot),
    ]


G = Concept(
    "spoon_dot",
    "Spoon-bowl dot",
    "A plain bold question mark whose dot is a spoon bowl seen from above.",
    PAPRIKA,
    shapes=spoon_dot(CREAM, GOLD),
    mono=spoon_dot("#000", "#000"),
)

# ---- C: a bowl with steam, the middle wisp asks the question ----------------
BOWL = "M24,58 H84 A30,30 0 0 1 54,88 A30,30 0 0 1 24,58 Z"


def bowl_steam(bowl, steam, mark):
    return [
        Shape(BOWL, fill=bowl),
        Shape("M44,90 H64", stroke=bowl, width=5),
        Shape("M40,50 C40,44 46,44 46,38 C46,33 41,32 41,27", stroke=steam, width=4.5),
        Shape("M66,50 C66,44 72,44 72,38 C72,33 67,32 67,27", stroke=steam, width=4.5),
        Shape("M48,30 A6.5,6.5 0 1 1 54.5,36.5 L54.5,40", stroke=mark, width=4.8),
        Shape(circle(54.5, 47.5, 2.8), fill=mark),
    ]


C = Concept(
    "bowl_steam",
    "Steaming bowl",
    "A bowl on the table, steam rising, and the middle wisp becomes the question.",
    PAPRIKA,
    shapes=bowl_steam(CREAM, PEACH, GOLD),
    mono=[
        Shape(BOWL, fill="#000"),
        Shape("M44,90 H64", stroke="#000", width=5),
        Shape("M48,30 A6.5,6.5 0 1 1 54.5,36.5 L54.5,40", stroke="#000", width=4.8),
        Shape(circle(54.5, 47.5, 2.8), fill="#000"),
    ],
)

# ---- D: a speech bubble with a spoon and the question -----------------------
BUBBLE = (
    "M30,26 H78 A12,12 0 0 1 90,38 V68 A12,12 0 0 1 78,80 H48 L36,90 L38,80 H30 "
    "A12,12 0 0 1 18,68 V38 A12,12 0 0 1 30,26 Z"
)
SPOON_D = (
    "M63,33 C56,33 51.5,38.5 51.5,45 C51.5,50 54,53.5 57.3,55.2 L52.5,73 "
    "C52,75.5 53.8,77.5 56,77.5 C58.2,77.5 60,75.5 59.5,73 L62.7,55.2 "
    "C67.5,53.2 72.5,49 73,42.5 C73.5,37 69.5,33 63,33Z"
)


def bubble(bg, spoon, mark):
    return [
        Shape(BUBBLE, fill=bg),
        Shape(SPOON_D, fill=spoon),
        Shape("M33,44 A7,7 0 1 1 40,51 L40,54.5", stroke=mark, width=4.6),
        Shape(circle(40, 62, 2.9), fill=mark),
    ]


D = Concept(
    "bubble",
    "Speech bubble",
    "Someone asking «τι θα φάμε;»: a bubble holding a spoon and the question.",
    PAPRIKA,
    shapes=bubble(CREAM, PAPRIKA, GOLD),
    mono=[Shape(BUBBLE, fill="#000")],
)

# ---- E: the bent spoon, inverted onto cream ---------------------------------
E = Concept(
    "spoon_question_cream",
    "Bent spoon, cream",
    "Same mark inverted: paprika spoon on the app's cream background, gold dot.",
    CREAM,
    shapes=spoon_question(PAPRIKA, GOLD),
    mono=spoon_question("#000"),
)

# ---- F: spoon and fork ------------------------------------------------------
def cutlery(color):
    return [
        Shape("M43,52 L43,84", stroke=color, width=6),
        Shape(ellipse(43, 44, 8.5, 12), fill=color),
        Shape("M65,52 L65,84", stroke=color, width=6),
        Shape("M57,34 V46 A8,8 0 0 0 73,46 V34", stroke=color, width=4),
        Shape("M65,34 V46", stroke=color, width=4),
    ]


F = Concept(
    "cutlery",
    "Spoon and fork",
    "Spoon and fork side by side, the classic 'meal' glyph in the app's colours.",
    PAPRIKA,
    shapes=cutlery(CREAM),
    mono=cutlery("#000"),
)

# ---- solid cutlery, drawn upright on the centre line then moved into place ----
def spoon_shape(color, top, bottom, rotate=0, tx=0, ty=0):
    """Upright spoon at x=54 from `top` (bowl tip) to `bottom` (handle end)."""
    h = bottom - top
    ry = h * 0.135
    rx = ry * 0.74
    bowl_cy = top + ry
    neck = bowl_cy + ry * 0.85
    cap_r = h * 0.045
    handle = (f"M{54 - rx * 0.36:.2f},{neck:.2f} L{54 + rx * 0.36:.2f},{neck:.2f} "
              f"L{54 + cap_r:.2f},{bottom - cap_r:.2f} L{54 - cap_r:.2f},{bottom - cap_r:.2f} Z")
    kw = dict(rotate=rotate, tx=tx, ty=ty)
    return [
        Shape(ellipse(54, bowl_cy, rx, ry), fill=color, **kw),
        Shape(handle, fill=color, **kw),
        Shape(circle(54, bottom - cap_r, cap_r), fill=color, **kw),
    ]


def fork_shape(color, top, bottom, rotate=0, tx=0, ty=0):
    """Upright fork at x=54: three round tines, a tapered neck, a rounded handle."""
    h = bottom - top
    tine_w = h * 0.075
    tine_len = h * 0.19
    spread = h * 0.145
    neck_top = top + tine_len * 0.82
    neck_bottom = neck_top + h * 0.13
    cap_r = h * 0.045
    kw = dict(rotate=rotate, tx=tx, ty=ty)
    tines = [
        Shape(f"M{54 + dx:.2f},{top + tine_w / 2:.2f} V{neck_top + 1:.2f}", stroke=color, width=tine_w, **kw)
        for dx in (-spread, 0, spread)
    ]
    neck = (f"M{54 - spread - tine_w / 2:.2f},{neck_top:.2f} L{54 + spread + tine_w / 2:.2f},{neck_top:.2f} "
            f"L{54 + cap_r * 0.9:.2f},{neck_bottom:.2f} L{54 - cap_r * 0.9:.2f},{neck_bottom:.2f} Z")
    handle = (f"M{54 - cap_r * 0.9:.2f},{neck_bottom - 0.5:.2f} L{54 + cap_r * 0.9:.2f},{neck_bottom - 0.5:.2f} "
              f"L{54 + cap_r:.2f},{bottom - cap_r:.2f} L{54 - cap_r:.2f},{bottom - cap_r:.2f} Z")
    return tines + [Shape(neck, fill=color, **kw), Shape(handle, fill=color, **kw),
                    Shape(circle(54, bottom - cap_r, cap_r), fill=color, **kw)]


def setting_flank(mark_shapes, plate_r=21.5, well=True):
    """Fork left, plate centre, spoon right."""
    cutlery = fork_shape(CREAM, 39.5, 69, tx=-26.5) + spoon_shape(CREAM, 39.5, 69, tx=26.5)
    dish = plate(CREAM, PAPRIKA_DARK, OAT, r=plate_r, rim_r=plate_r - 6, well=OAT if well else None)
    return cutlery + dish + mark_shapes


def setting_crossed(mark_shapes, plate_r=22.5, well=True):
    """Spoon and fork crossed behind the plate, only their ends showing."""
    cutlery = spoon_shape(CREAM, 25, 83, rotate=-45) + fork_shape(CREAM, 25, 83, rotate=45)
    dish = plate(CREAM, PAPRIKA_DARK, OAT, r=plate_r, rim_r=plate_r - 6, well=OAT if well else None)
    return cutlery + dish + mark_shapes


MONO_FLANK = [Shape(circle(54, 54, 21.5), fill="#000")] + fork_shape("#000", 39.5, 69, tx=-26.5) + spoon_shape("#000", 39.5, 69, tx=26.5)
MONO_CROSSED = spoon_shape("#000", 25, 83, rotate=-45) + fork_shape("#000", 25, 83, rotate=45) + [Shape(circle(54, 54, 22.5), fill="#000")]

S1 = Concept(
    "setting_greek", "Setting, Greek «;»",
    "Fork, plate, spoon laid for dinner; the plate carries the Greek question mark.",
    PAPRIKA,
    shapes=setting_flank(greek_mark(PAPRIKA, r=4.9, cy_dot=44.3, cy_head=57.2), plate_r=22),
    mono=MONO_FLANK,
)

S2 = Concept(
    "crossed_greek", "Crossed cutlery, Greek «;»",
    "Spoon and fork crossed behind the plate, only their ends showing: the classic dinner sign.",
    PAPRIKA,
    shapes=setting_crossed(greek_mark(PAPRIKA, r=4.6, cy_dot=44.5, cy_head=56.5)),
    mono=MONO_CROSSED,
)

S3 = Concept(
    "setting_greek_rim", "Setting, Greek «;», hairline rim",
    "The flanked setting on the classic plate with a hairline rim.",
    PAPRIKA,
    shapes=setting_flank(greek_mark(PAPRIKA, r=4.4, cy_dot=45, cy_head=56.5), well=False),
    mono=MONO_FLANK,
)

S4 = Concept(
    "crossed_greek_rim", "Crossed cutlery, hairline rim",
    "Crossed cutlery behind the classic plate with a hairline rim.",
    PAPRIKA,
    shapes=setting_crossed(greek_mark(PAPRIKA, r=4.6, cy_dot=44.5, cy_head=56.5), well=False),
    mono=MONO_CROSSED,
)

# ---- the first place setting: stroked cutlery, hairline-rim plate, «?» ----
# cutlery sits 27 units either side of the plate centre, leaving clear air around the plate
FORK_HANDLE, FORK_TINES, FORK_MID = "M27,54 V68", "M22.6,41 V48 A4.4,4.4 0 0 0 31.4,48 V41", "M27,41 V49"
SPOON_HANDLE = "M81,54 V68"
SPOON_BOWL = ellipse(81, 45.5, 5.4, 8)


def outline_cutlery(color):
    return [
        Shape(FORK_HANDLE, stroke=color, width=4.6),
        Shape(FORK_TINES, stroke=color, width=3),
        Shape(FORK_MID, stroke=color, width=3),
        Shape(SPOON_HANDLE, stroke=color, width=4.6),
        Shape(SPOON_BOWL, fill=color),
    ]


def first_plate(r=20.5):
    return [
        Shape(circle(54, 56, r), fill=PAPRIKA_DARK),
        Shape(circle(54, 54, r), fill=CREAM),
        Shape(circle(54, 54, r - 5), stroke=OAT, width=1.6),
    ]


FIRST_MARK = [Shape(qmark(54, 49.8, 6.6, 2.4, 168), stroke=PAPRIKA, width=5.7), Shape(circle(54, 65, 3.1), fill=PAPRIKA)]
MONO_FIRST = outline_cutlery("#000") + [Shape(circle(54, 54, 20.5), fill="#000")]

O1 = Concept(
    "place_setting", "First setting, «?»",
    "The very first place setting: outline fork and spoon, hairline-rim plate, question mark.",
    PAPRIKA,
    shapes=outline_cutlery(CREAM) + first_plate() + FIRST_MARK,
    mono=MONO_FIRST,
)

O2 = Concept(
    "place_setting_greek", "First setting, Greek «;»",
    "The first cutlery and plate, with the smooth Greek question mark instead of «?».",
    PAPRIKA,
    shapes=outline_cutlery(CREAM) + first_plate() + greek_mark(PAPRIKA, r=4.6, cy_dot=45, cy_head=57),
    mono=MONO_FIRST,
)

O3 = Concept(
    "setting_question", "Current setting, «?»",
    "The solid cutlery and two-tone plate from the current icon, with «?» instead of «;».",
    PAPRIKA,
    shapes=setting_flank([Shape(qmark(54, 49.5, 7.2, 2.5, 168), stroke=PAPRIKA, width=6.2), Shape(circle(54, 66, 3.5), fill=PAPRIKA)], plate_r=22),
    mono=MONO_FLANK,
)

CONCEPTS = [O1, S1, O2, O3]

MASKS = {
    "circle": '<circle cx="54" cy="54" r="36"/>',
    "squircle": (
        '<path d="M54,18 C77,18 90,31 90,54 C90,77 77,90 54,90 '
        'C31,90 18,77 18,54 C18,31 31,18 54,18Z"/>'
    ),
    "rounded": '<rect x="18" y="18" width="72" height="72" rx="15"/>',
    "none": '<rect width="108" height="108"/>',
}


# ---- emitters ---------------------------------------------------------------
def svg_shape(s: Shape, color_override: str | None = None) -> str:
    fill = s.fill and (color_override or s.fill)
    stroke = s.stroke and (color_override or s.stroke)
    attrs = [f'd="{s.d}"', f'fill="{fill or "none"}"']
    if stroke:
        attrs += [
            f'stroke="{stroke}"',
            f'stroke-width="{s.width}"',
            f'stroke-linecap="{s.cap}"',
            f'stroke-linejoin="{s.join}"',
        ]
    if s.rotate or s.tx or s.ty:
        attrs.append(f'transform="translate({s.tx} {s.ty}) rotate({s.rotate} 54 54)"')
    return "<path " + " ".join(attrs) + "/>"


def svg(concept: Concept, mask: str = "circle", mono: bool = False, size: int = 96) -> str:
    """Render the 108 canvas cropped to the 72dp visible area, like a launcher."""
    cid = f"clip-{concept.key}-{mask}-{int(mono)}-{size}"
    shapes = concept.mono if mono else concept.shapes
    bg = "#e4e1dc" if mono else concept.background
    body = "".join(svg_shape(s, "#3a3a3a" if mono else None) for s in shapes)
    return (
        f'<svg width="{size}" height="{size}" viewBox="18 18 72 72" '
        f'xmlns="http://www.w3.org/2000/svg">'
        f'<defs><clipPath id="{cid}">{MASKS[mask]}</clipPath></defs>'
        f'<g clip-path="url(#{cid})"><rect width="108" height="108" fill="{bg}"/>{body}</g></svg>'
    )


def vector_xml(shapes: list[Shape], color_override: str | None = None) -> str:
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    for s in shapes:
        grouped = bool(s.rotate or s.tx or s.ty)
        pad = "        " if grouped else "    "
        if grouped:
            out.append("    <group")
            out.append('        android:pivotX="54"')
            out.append('        android:pivotY="54"')
            out.append(f'        android:rotation="{s.rotate}"')
            out.append(f'        android:translateX="{s.tx}"')
            out.append(f'        android:translateY="{s.ty}">')
        out.append(f"{pad}<path")
        if s.fill:
            out.append(f'{pad}    android:fillColor="{color_override or s.fill}"')
        if s.stroke:
            out.append(f'{pad}    android:strokeColor="{color_override or s.stroke}"')
            out.append(f'{pad}    android:strokeWidth="{s.width}"')
            out.append(f'{pad}    android:strokeLineCap="{s.cap}"')
            out.append(f'{pad}    android:strokeLineJoin="{s.join}"')
        out.append(f'{pad}    android:pathData="{s.d}" />')
        if grouped:
            out.append("    </group>")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def preview_html() -> str:
    cards = []
    for c in CONCEPTS:
        masks = "".join(svg(c, m, size=112) for m in ("circle", "squircle", "rounded"))
        small = "".join(svg(c, "circle", size=s) for s in (48, 32, 24))
        mono = svg(c, "circle", mono=True, size=64)
        cards.append(
            f'<section class="card"><h2>{html.escape(c.title)} <code>{c.key}</code></h2>'
            f"<p>{html.escape(c.blurb)}</p>"
            f'<div class="row">{masks}<div class="small">{small}</div>{mono}</div></section>'
        )
    css = (
        "body{margin:0;padding:28px;font:14px/1.45 system-ui,sans-serif;background:#f5efe8;color:#2d2520}"
        "h1{font:700 26px/1.2 Georgia,serif;margin:0 0 4px}"
        "h2{font:700 17px/1.2 Georgia,serif;margin:0 0 4px}"
        "h2 code{font:12px monospace;color:#89736a;margin-left:8px}"
        "p{margin:0 0 12px;color:#5c4d45}"
        ".grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;max-width:1400px}"
        ".card{background:#fffbf7;border:1px solid #eadfd4;border-radius:16px;padding:18px 20px}"
        ".row{display:flex;align-items:center;gap:18px}"
        ".small{display:flex;align-items:center;gap:10px;padding:0 6px}"
        "svg{flex:none;display:block}"
    )
    return (
        '<!doctype html><meta charset="utf-8"><title>Launcher concepts</title>'
        f"<style>{css}</style><h1>Τι θα φάμε; — launcher concepts</h1>"
        "<p>Each row: circle / squircle / rounded masks, then 48-32-24px, then the Android 13 themed layer.</p>"
        f'<div class="grid">{"".join(cards)}</div>'
    )


def export(concept: Concept, res_dir: str) -> None:
    def write(rel: str, text: str) -> None:
        path = os.path.join(res_dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote", path)

    write("drawable/ic_launcher_foreground.xml", vector_xml(concept.shapes))
    write("drawable/ic_launcher_monochrome.xml", vector_xml(concept.mono, "#000000"))
    write(
        "values/ic_launcher_background.xml",
        "<resources>\n"
        f'    <color name="ic_launcher_background">{concept.background}</color>\n'
        "</resources>\n",
    )


def main(argv: list[str]) -> None:
    command = argv[1] if len(argv) > 1 else "preview"
    if command == "preview":
        out = argv[2] if len(argv) > 2 else "build/icon-preview"
        os.makedirs(out, exist_ok=True)
        path = os.path.join(out, "preview.html")
        with open(path, "w", encoding="utf-8") as f:
            f.write(preview_html())
        print("wrote", path)
    elif command == "export":
        key, res_dir = argv[2], argv[3]
        concept = next(c for c in CONCEPTS if c.key == key)
        export(concept, res_dir)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
