# Illustrations des manettes en pixel art, fond transparent (291 x 306) : dessinées sur une grille de
# 97 x 102 puis agrandies x3. Les boutons des manettes de consoles sont placés sur les repères de la
# table PAD_SPOTS (PadArt.kt, même table que padconfig.cpp) : les repères restent justes.
#
#   python generate_pads.py            -> desktop/native/player/pads/*.png (moteur), desktop/src/renderer/pads/*.webp
#                                         (fiche du jeu, Windows) et pad_*.webp (Android)
#   python generate_pads.py --preview  -> planche de contrôle (fonds clair et sombre) dans ce dossier
import os
import re
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps

W, H, S = 97, 102, 3
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
PAD_ART = os.path.join(ROOT, "android", "core", "src", "main", "java", "com", "romcloud", "app", "libretro", "PadArt.kt")
ANDROID_RES = os.path.join(ROOT, "android", "core", "src", "main", "res", "drawable-nodpi")
RENDERER = os.path.join(ROOT, "desktop", "src", "renderer", "pads")

INK = (16, 16, 28)
WHITE = (255, 255, 255)
SHADE = (24, 26, 52)
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(base):
    """Teintes d'une matière, de la plus claire (reflet) à la plus sombre (bas)."""
    return [mix(base, WHITE, 0.45), mix(base, WHITE, 0.2), base, mix(base, SHADE, 0.18), mix(base, SHADE, 0.34), mix(base, SHADE, 0.5)]


def outline_of(base):
    return mix(base, INK, 0.78)


def spots():
    """Repères (pixels de l'image) de chaque manette de console, lus dans PadArt.kt, en cases de la grille."""
    text = open(PAD_ART, encoding="utf-8").read()
    table = {}
    for name, body in re.findall(r'"(\w+)" to mapOf\((.*?)\),?\n', text):
        table[name] = {k: ((int(x) - 1) / 3, (int(y) - 1) / 3, int(r) / 3) for k, x, y, r in re.findall(r'"([\w+-]+)" to Spot\((\d+), (\d+), (\d+)\)', body)}
    return table


class Pad:
    def __init__(self):
        self.im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)

    # --- formes -----------------------------------------------------------------------------
    @staticmethod
    def mask(*shapes):
        """Masque d'une forme : ("e", x0, y0, x1, y1) ellipse, ("r", ..., rayon) rectangle arrondi,
        ("p", [points]) polygone ; une forme préfixée de « - » est retirée."""
        m = Image.new("L", (W, H), 0)
        dm = ImageDraw.Draw(m)
        for s in shapes:
            kind, fill = (s[0][1:], 0) if s[0].startswith("-") else (s[0], 255)
            if kind == "e":
                dm.ellipse(s[1:5], fill=fill)
            elif kind == "r":
                dm.rounded_rectangle(s[1:5], radius=s[5] if len(s) > 5 else 0, fill=fill)
            elif kind == "p":
                dm.polygon(s[1], fill=fill)
        return m

    @staticmethod
    def symmetric(mask):
        """Forme rendue symétrique (moitié gauche reproduite en miroir à droite)."""
        return ImageChops.lighter(mask, ImageOps.mirror(mask))

    @staticmethod
    def circle(cx, cy, r):
        return ("e", cx - r, cy - r, cx + r, cy + r)

    # --- remplissages -----------------------------------------------------------------------
    def shade(self, mask, colors, top=None, bottom=None, rim=True, flip=False):
        """Dégradé vertical tramé (clair en haut, sombre en bas ; [flip] : creux, l'inverse) ;
        liseré clair en haut et sombre en bas ([rim])."""
        box = mask.getbbox()
        if not box:
            return
        top = box[1] if top is None else top
        bottom = box[3] - 1 if bottom is None else bottom
        px, mk = self.im.load(), mask.load()
        inside = lambda x, y: 0 <= x < W and 0 <= y < H and mk[x, y]
        n = len(colors)
        for y in range(box[1], box[3]):
            t = min(max((y - top) / max(bottom - top, 1), 0), 1)
            if flip:
                t = 1 - t
            for x in range(box[0], box[2]):
                if not mk[x, y]:
                    continue
                level = 1 + t * (n - 2.01)
                i = int(level) + (1 if (level % 1) * 16 > BAYER[y % 4][x % 4] + 0.5 else 0)
                i = min(max(i, 1), n - 1)
                if rim and not inside(x, y - 1):
                    i = n - 1 if flip else 0
                elif rim and not inside(x, y + 1):
                    i = 0 if flip else n - 1
                px[x, y] = (*colors[i], 255)

    def part(self, mask, base, top=None, bottom=None, line=None, shadow=False, rim=True, flip=False):
        """Pièce de la manette : contour, ombre portée ([shadow]), matière dégradée."""
        edge = mask.filter(ImageFilter.MaxFilter(3))
        if shadow:
            self.blend((10, 12, 30, 70), ImageChops.subtract(ImageChops.offset(edge, 0, 3), edge))
        self.im.paste((*(line or outline_of(base)), 255), (0, 0), edge)
        self.shade(mask, ramp(base), top, bottom, rim, flip)

    def blend(self, color, mask):
        """Couleur translucide posée par-dessus (ombres) : le dessin dessous reste opaque."""
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        layer.paste(color, (0, 0), mask)
        self.im.alpha_composite(layer)

    def well(self, mask, base):
        """Creux (cuvette d'une croix, d'un stick) : ombre en haut, bord clair en bas."""
        self.shade(mask, ramp(mix(base, SHADE, 0.25)), rim=True, flip=True)

    def px(self, points, color):
        self.d.point([(round(x), round(y)) for x, y in points], fill=(*color, 255))

    def line(self, x0, y0, x1, y1, color):
        self.d.line((round(x0), round(y0), round(x1), round(y1)), fill=(*color, 255))

    def rect(self, x0, y0, x1, y1, color, r=0):
        self.d.rounded_rectangle((round(x0), round(y0), round(x1), round(y1)), radius=r, fill=(*color, 255))

    # --- éléments ---------------------------------------------------------------------------
    def button(self, cx, cy, r, base, line=None, mark=None):
        """Bouton rond bombé : reflet en haut à gauche, ombre dessous ; [mark] : petit signe dessus."""
        self.blend((*mix(base, INK, 0.85), 110), self.mask(("e", cx - r - 1, cy - r + 1, cx + r + 1, cy + r + 2)))
        self.part(self.mask(self.circle(cx, cy, r)), base, line=line)
        hi = mix(base, WHITE, 0.7)
        self.px([(cx - r * 0.45, cy - r * 0.55), (cx - r * 0.6, cy - r * 0.3)], hi)
        if mark:
            self.px([(cx + dx, cy + dy) for dx, dy in MARKS[mark[0]]], mark[1])

    def dpad(self, cx, cy, arm, half, base, well=None):
        """Croix directionnelle bombée (bras de longueur [arm], de demi-largeur [half])."""
        if well:
            self.well(self.mask(self.circle(cx, cy, arm + well)), well_base(base))
        m = self.mask(("r", cx - half, cy - arm, cx + half, cy + arm, 1), ("r", cx - arm, cy - half, cx + arm, cy + half, 1))
        self.part(m, base)
        dark = mix(base, SHADE, 0.6)
        self.px([(cx, cy)], dark)

    def pill(self, cx, cy, w, h, base, slant=0):
        """Petit bouton allongé (SELECT, START) ; [slant] : incliné."""
        if slant:
            pts = [(cx - w / 2, cy + slant - h / 2), (cx + w / 2, cy - slant - h / 2), (cx + w / 2, cy - slant + h / 2), (cx - w / 2, cy + slant + h / 2)]
            self.part(self.mask(("p", pts)), base)
        else:
            self.part(self.mask(("r", cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2)), base)

    def stick(self, cx, cy, r, base, ring=None):
        """Stick analogique vu de dessus : socle sombre, chapeau concave."""
        self.part(self.mask(self.circle(cx, cy, r + 1)), ring or mix(base, SHADE, 0.55))
        self.part(self.mask(self.circle(cx, cy - 0.5, r - 0.5)), base)
        if r > 2.5:
            self.shade(self.mask(self.circle(cx, cy - 0.5, r - 2)), ramp(base), rim=False, flip=True)

    def bumper(self, x0, y0, x1, y1, base, r=2):
        """Gâchette ou bouton de tranche (dessiné avant le corps, qui le recouvre en partie)."""
        self.part(self.mask(("r", x0, y0, x1, y1, r)), base)

    def save(self, path):
        big = self.im.resize((W * S, H * S), Image.NEAREST)
        if path.endswith(".webp"):
            big.save(path, lossless=True, quality=100, method=6)
        else:
            big.save(path, optimize=True)


def well_base(base):
    return mix(base, SHADE, 0.35)


# Petits signes (décalages autour du centre d'un bouton) : symboles PlayStation, A de la GameCube.
MARKS = {
    "tri": [(0, -1), (-1, 1), (1, 1), (0, 1), (-1, 0), (1, 0)],
    "cir": [(-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1)],
    "crs": [(-1, -1), (1, -1), (0, 0), (-1, 1), (1, 1)],
    "sqr": [(-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1)],
    "dot": [(0, 0)],
    "A": [(0, -1), (-1, 0), (1, 0), (-1, 1), (0, 0), (1, 1)],
}


# =====================================================================================================
# Manettes des consoles (boutons sur les repères)
# =====================================================================================================

def nes(sp):
    p = Pad()
    grey, face, red = (206, 206, 210), (40, 40, 44), (214, 40, 46)
    p.part(p.mask(("r", 10, 36, 86, 67, 2)), grey, shadow=True)
    p.rect(13, 39, 83, 64, face, 1)
    p.rect(13, 39, 83, 39, mix(face, WHITE, 0.25))
    # Bandes grises du centre.
    for y in (43, 46, 49):
        p.rect(36, y, 56, y + 1, (168, 168, 172))
    p.rect(36, 51, 56, 58, (150, 150, 156), 1)
    p.dpad(*sp["up"][:1], (sp["up"][1] + sp["down"][1]) / 2, 5, 1.6, (34, 34, 38))
    for k in ("select", "start"):
        p.pill(sp[k][0], sp[k][1], 5, 2, (40, 40, 44))
    for k in ("b", "a"):
        x, y, _ = sp[k]
        p.rect(x - 4, y - 4, x + 4, y + 4, (200, 200, 204), 1)
        p.button(x, y, 3, red)
    p.line(62, 42, 76, 42, red)
    return p


def famicom(sp):
    p = Pad()
    red, gold, cream = (198, 32, 44), (196, 164, 92), (232, 214, 170)
    p.part(p.mask(("r", 9, 33, 88, 66, 3)), red, shadow=True)
    p.part(p.mask(("p", [(14, 39), (40, 39), (44, 44), (83, 44), (83, 62), (14, 62)])), cream, line=mix(gold, INK, 0.4))
    # Marques I / II (boutons de tranche L / R).
    for k in ("l", "r"):
        x, y, _ = sp[k]
        p.rect(x - 2, y - 2, x + 2, y + 2, gold, 1)
        p.line(x, y - 1, x, y + 1, (90, 20, 26))
    for y in (47, 50):
        p.rect(36, y, 56, y + 1, gold)
    p.dpad(sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2, 5, 1.6, (38, 30, 32))
    for k in ("select", "start"):
        p.pill(sp[k][0], sp[k][1], 5, 2, (150, 30, 40))
    for k in ("b", "a"):
        x, y, _ = sp[k]
        p.button(x, y, 3.3, (176, 30, 40))
    return p


def snes(sp):
    p = Pad()
    grey, dark = (198, 198, 206), (150, 150, 162)
    for k in ("l", "r"):
        x, y, _ = sp[k]
        p.bumper(x - 8, y - 2, x + 8, y + 5, (160, 160, 172), r=3)
    body = p.mask(("e", 11, 36, 45, 67), ("e", 52, 35, 86, 67), ("r", 26, 38, 70, 64, 6))
    p.part(body, grey, shadow=True)
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.dpad(dx, dy, 5, 1.7, (52, 52, 60), well=2)
    # Cuvette inclinée des boutons.
    cx = (sp["y"][0] + sp["a"][0]) / 2
    cy = (sp["x"][1] + sp["b"][1]) / 2
    p.well(p.mask(("p", [(cx - 12, cy + 3), (cx + 3, cy - 12), (cx + 12, cy - 3), (cx - 3, cy + 12)]), ("e", cx - 9, cy - 9, cx + 9, cy + 9)), dark)
    colors = {"x": (126, 108, 196), "y": (126, 108, 196), "a": (80, 66, 150), "b": (80, 66, 150)}
    for k, c in colors.items():
        p.button(sp[k][0], sp[k][1], 2.7, c)
    for k in ("select", "start"):
        p.pill(sp[k][0], sp[k][1], 5, 2, (96, 96, 108), slant=1)
    p.line(38, 42, 58, 42, (110, 110, 124))
    return p


def n64(sp):
    p = Pad()
    grey = (196, 198, 206)
    for k in ("l", "r"):
        x, y, _ = sp[k]
        p.bumper(x - 9, y - 2, x + 9, y + 4, (170, 172, 182), r=3)
    body = p.mask(("e", 9, 27, 47, 57), ("e", 50, 27, 88, 57), ("r", 26, 27, 72, 52, 8),
                  ("e", 11, 44, 31, 80), ("e", 66, 44, 86, 80), ("e", 39, 46, 59, 80))
    p.part(body, grey, shadow=True)
    # Gâchette Z sous la poignée du milieu.
    zx, zy, _ = sp["l2"]
    p.part(p.mask(("e", zx - 3, zy - 3, zx + 3, zy + 4)), (120, 122, 134))
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.dpad(dx, dy, 4.5, 1.5, (70, 72, 82), well=2)
    sx, sy, _ = sp["ls"]
    p.well(p.mask(p.circle(sx, sy, 5)), grey)
    p.stick(sx, sy, 3.3, (170, 172, 182))
    p.button(*sp["start"][:2], 2.2, (200, 40, 40))
    p.button(*sp["b"][:2], 3, (44, 84, 196))
    p.button(*sp["y"][:2], 2.6, (44, 156, 72))
    for k in ("ry-", "ry+", "rx-", "rx+"):
        p.button(sp[k][0], sp[k][1], 1.9, (240, 196, 40))
    p.rect(40, 30, 57, 31, (150, 152, 164))
    return p


def playstation(sp, base, dark, wide=False):
    """Manette PlayStation (première et DualShock) : poignées, boutons de tranche empilés."""
    p = Pad()
    for k, k2 in (("l", "l2"), ("r", "r2")):
        x, y, _ = sp[k2]
        p.bumper(x - 6, y - 2, x + 6, y + 3, mix(base, SHADE, 0.3), r=2)
        x, y, _ = sp[k]
        p.bumper(x - 7, y - 2, x + 7, y + 3, mix(base, WHITE, 0.08), r=1)
    lx, rx = sp["up"][0], (sp["x"][0] + sp["b"][0]) / 2
    top = sp["l"][1] + 1
    body = p.mask(("r", lx - 15, top, rx + 15, top + 25, 8),
                  ("e", lx - 13, top + 10, lx + 7, top + 47), ("e", rx - 7, top + 10, rx + 13, top + 47))
    if wide:
        body = ImageChops.lighter(body, p.mask(("r", lx + 2, top + 14, rx - 2, top + 32, 8)))
    p.part(body, base, shadow=True)
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.well(p.mask(p.circle(dx, dy, 7.5)), base)
    for k in ("up", "down", "left", "right"):
        x, y, _ = sp[k]
        p.part(p.mask(p.circle(x, y, 2.3)), dark)
    bx, by = (sp["y"][0] + sp["a"][0]) / 2, (sp["x"][1] + sp["b"][1]) / 2
    p.well(p.mask(p.circle(bx, by, 9)), base)
    r = sp["x"][2] * 0.95
    p.button(*sp["x"][:2], r, dark, mark=("tri", (60, 200, 150)))
    p.button(*sp["a"][:2], r, dark, mark=("cir", (230, 80, 90)))
    p.button(*sp["b"][:2], r, dark, mark=("crs", (110, 150, 240)))
    p.button(*sp["y"][:2], r, dark, mark=("sqr", (230, 120, 200)))
    for k in ("select", "start"):
        p.pill(sp[k][0], sp[k][1], 4, 1.6, mix(dark, SHADE, 0.3))
    return p


def psx(sp):
    return playstation(sp, (176, 178, 188), (140, 142, 154))


def ps2(sp):
    p = playstation(sp, (44, 46, 58), (34, 36, 46), wide=True)
    for k in ("ls", "rs"):
        x, y, r = sp[k]
        p.stick(x, y, r - 0.8, (40, 42, 52))
    p.button(48.5, 50, 1.4, (200, 40, 50))
    return p


def gamecube(sp):
    p = Pad()
    indigo = (78, 70, 170)
    for k in ("l2", "r2"):
        x, y, _ = sp[k]
        p.bumper(x - 7, y - 3, x + 7, y + 4, (150, 146, 200), r=3)
    zx, zy, _ = sp["r"]
    p.bumper(zx - 5, zy - 2, zx + 5, zy + 2, (120, 70, 200), r=1)
    body = p.mask(("r", 12, 29, 87, 54, 10), ("e", 14, 40, 36, 80), ("e", 64, 40, 86, 80), ("e", 32, 42, 68, 64))
    p.part(body, indigo, shadow=True)
    sx, sy, sr = sp["ls"]
    p.well(p.mask(p.circle(sx, sy, sr + 2.5)), indigo)
    p.stick(sx, sy, sr - 0.3, (150, 150, 168))
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.dpad(dx, dy, 3.6, 1.2, (150, 150, 168), well=1.5)
    cx, cy, cr = sp["rs"]
    p.well(p.mask(p.circle(cx, cy, cr + 2)), indigo)
    p.stick(cx, cy, cr - 1, (240, 200, 40))
    p.button(*sp["start"][:2], 1.6, (190, 190, 204))
    p.well(p.mask(p.circle(*sp["a"][:2], 7.5), p.circle(*sp["x"][:2], 3.5), p.circle(*sp["y"][:2], 3.5)), indigo)
    p.button(*sp["a"][:2], 3.3, (48, 176, 110), mark=("A", (210, 250, 220)))
    p.button(*sp["b"][:2], 2.3, (210, 50, 60))
    for k in ("x", "y"):
        p.part(p.mask(("e", sp[k][0] - 2.2, sp[k][1] - 2.6, sp[k][0] + 2.2, sp[k][1] + 2.6)), (200, 200, 214))
    return p


def genesis(sp):
    p = Pad()
    black = (52, 56, 76)
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    body = ImageChops.subtract(ImageChops.lighter(p.symmetric(p.mask(("e", 8, 31, 46, 69))), p.mask(("r", 18, 28, 78, 58, 12))), p.mask(("e", 26, 57, 70, 85)))
    p.part(body, black, shadow=True)
    p.dpad(dx, dy, 4.8, 1.7, (78, 82, 104), well=3)
    p.rect(38, 33, 58, 37, (196, 200, 216), 2)
    p.line(40, 35, 56, 35, (60, 64, 84))
    p.pill(*sp["start"][:2], 6, 2, (30, 32, 46))
    for k in ("y", "b", "a"):
        p.button(*sp[k][:2], 3, (54, 30, 40))
    for y in (34, 37):
        p.line(66, y + 1, 72, y - 1, (190, 196, 214))
    return p


def master(sp):
    p = Pad()
    black = (44, 44, 52)
    p.part(p.mask(("r", 11, 35, 89, 66, 3)), black, shadow=True)
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.rect(dx - 9, dy - 9, dx + 9, dy + 9, (200, 40, 50), 1)
    p.rect(dx - 8, dy - 8, dx + 8, dy + 8, (60, 60, 70), 1)
    p.well(p.mask(("r", dx - 7, dy - 7, dx + 7, dy + 7, 1)), black)
    p.dpad(dx, dy, 5, 2, (78, 78, 92))
    p.part(p.mask(p.circle(dx, dy, 2.2)), (70, 70, 82))
    for k in ("b", "a"):
        x, y, r = sp[k]
        p.rect(x - 5, y - 9, x + 5, y - 8, (200, 40, 50))
        p.button(x, y, r - 0.6, (36, 36, 44))
    p.rect(44, 46, 55, 49, (200, 200, 210))
    p.line(45, 47, 54, 47, (60, 60, 70))
    return p


def saturn(sp):
    p = Pad()
    grey = (60, 62, 74)
    for k in ("l2", "r2"):
        x, y, _ = sp[k]
        p.bumper(x - 9, y - 2, x + 9, y + 4, (90, 92, 106), r=3)
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    body = ImageChops.subtract(ImageChops.lighter(p.symmetric(p.mask(("e", 6, 30, 44, 70))), p.mask(("r", 18, 30, 78, 60, 12))), p.mask(("e", 30, 61, 66, 84)))
    p.part(body, grey, shadow=True)
    p.dpad(dx, dy, 4.8, 1.7, (86, 88, 106), well=3)
    p.pill(*sp["start"][:2], 5, 2, (40, 42, 52))
    for k in ("b", "a", "r"):
        p.button(*sp[k][:2], 2.7, (40, 42, 54))
    for k in ("y", "x", "l"):
        p.button(*sp[k][:2], 2.2, (40, 42, 54))
    p.rect(42, 36, 54, 38, (170, 174, 190), 1)
    return p


def dreamcast(sp):
    p = Pad()
    white, grey = (230, 230, 234), (196, 198, 206)
    for k in ("l2", "r2"):
        x, y, _ = sp[k]
        p.bumper(x - 6, y - 3, x + 6, y + 5, (186, 188, 198), r=3)
    # Un seul corps : épaules larges, deux poignées évasées vers le bas, bord droit entre elles.
    outline = [(32, 27), (14, 27.5), (8.5, 30), (6, 36), (5.5, 46), (7, 55), (10.5, 63), (14.5, 71), (18, 78), (20.5, 81.5),
               (24, 82.5), (29, 81), (33, 77), (38, 74.5), (44, 74), (53, 74), (59, 74.5), (64, 77), (68, 81), (73, 82.5),
               (76.5, 81.5), (79, 78), (82.5, 71), (86.5, 63), (90, 55), (91.5, 46), (91, 36), (88.5, 30), (83, 27.5), (65, 27)]
    p.part(p.mask(("p", outline), ("e", 31, 19, 66, 44)), white, top=20, bottom=120, shadow=True)
    # Traits de la coque : arc autour des commandes, lignes vers les poignées.
    line = (208, 210, 218)
    p.d.arc((14, 4, 83, 70), 30, 150, fill=(*line, 255))
    for x0, y0, x1, y1 in ((30, 63, 22, 78), (64, 63, 72, 78)):
        p.line(x0, y0, x1, y1, line)
    # Emplacement de la carte mémoire (VMU) et son écran.
    p.part(p.mask(("r", 35, 21, 62, 57, 10)), grey)
    p.part(p.mask(("r", 39, 29, 58, 47, 2)), (150, 154, 164))
    p.rect(40, 30, 57, 46, (176, 214, 186), 1)
    p.rect(40, 30, 57, 30, (206, 232, 212))
    for y in (34, 37, 40, 43):
        p.line(43, y, 54, y, (70, 96, 80))
    # Logo : petite spirale bleue.
    p.d.arc((45.5, 21, 51.5, 27), 200, 520, fill=(50, 100, 200, 255))
    p.px([(48, 24), (49, 24), (49, 23)], (50, 100, 200))
    sx, sy, sr = sp["ls"]
    p.well(p.mask(p.circle(sx, sy, sr + 1.5)), white)
    p.stick(sx, sy, sr - 0.6, (204, 204, 212))
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.dpad(dx, dy, 4.2, 1.5, (176, 178, 190))
    sx, sy, _ = sp["start"]
    p.part(p.mask(("p", [(sx, sy - 2.5), (sx - 3, sy + 2), (sx + 3, sy + 2)])), (176, 178, 190))
    # Y en haut, X à gauche, B à droite, A en bas.
    colors = {"x": (110, 200, 150), "y": (236, 206, 40), "a": (50, 120, 220), "b": (214, 44, 50)}
    for k, c in colors.items():
        p.button(*sp[k][:2], 2.7, c)
    return p


def pcengine(sp):
    p = Pad()
    black = (40, 40, 48)
    p.part(p.mask(("r", 8, 44, 89, 73, 4), ("r", 34, 38, 62, 50, 4)), black, shadow=True)
    p.rect(14, 47, 30, 51, (200, 40, 40), 1)
    p.rect(66, 45, 82, 48, (60, 60, 70), 1)
    for x in (69, 79):
        p.rect(x - 1, 49, x + 1, 52, (220, 190, 60))
    dx, dy = sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2
    p.dpad(dx, dy, 4.8, 1.6, (76, 76, 88), well=2.5)
    for k in ("select", "start"):
        p.pill(*sp[k][:2], 5, 2, (70, 70, 80))
    for k in ("b", "a"):
        p.button(*sp[k][:2], 3.2, (56, 56, 66))
    p.px([(66, 63), (67, 63), (75, 63), (76, 63)], (220, 190, 60))
    return p


def neogeo(sp):
    p = Pad()
    black = (40, 42, 54)
    body = p.mask(("e", 8, 30, 48, 70), ("e", 52, 30, 90, 68), ("r", 26, 34, 72, 64, 10))
    p.part(body, black, shadow=True)
    cx, cy = sp["up"][0], sp["left"][1]
    p.well(p.mask(p.circle(cx, cy, 11.5)), black)
    p.part(p.mask(p.circle(cx, cy, 8)), (54, 56, 70))
    p.stick(cx, cy, 4, (60, 62, 76))
    for k in ("up", "down", "left", "right"):
        x, y, _ = sp[k]
        p.px([(x, y)], (150, 152, 170))
    colors = {"b": (210, 50, 50), "a": (240, 200, 50), "y": (60, 180, 80), "x": (60, 150, 220)}
    for k, c in colors.items():
        p.button(*sp[k][:2], 2.9, c)
    for k in ("start", "select"):
        p.pill(*sp[k][:2], 4, 2, (70, 72, 88))
    p.rect(44, 38, 56, 40, (150, 152, 170), 1)
    return p


def atari2600(sp):
    p = Pad()
    black = (40, 40, 46)
    cx, cy = sp["up"][0], sp["left"][1]
    p.part(p.mask(("r", cx - 18, cy - 13, cx + 18, cy + 25, 5)), mix(black, SHADE, 0.3), shadow=True)
    p.part(p.mask(("r", cx - 18, cy - 17, cx + 18, cy + 20, 5)), black)
    p.well(p.mask(p.circle(cx, cy, 9)), black)
    p.part(p.mask(p.circle(cx, cy, 6.5)), (54, 54, 62))
    for k in ("up", "down", "left", "right"):
        x, y, _ = sp[k]
        p.px([(x, y)], (110, 110, 120))
    # Manche.
    p.part(p.mask(("r", cx - 2, cy - 27, cx + 2, cy - 1, 2)), (30, 30, 36))
    p.part(p.mask(("e", cx - 3, cy - 30, cx + 3, cy - 23)), (34, 34, 40))
    p.button(*sp["b"][:2], 3.2, (210, 40, 40))
    return p


def cross_of(sp):
    """Centre de la croix (ou du stick) d'après les repères haut / bas / gauche, et demi-écart."""
    return sp["up"][0], (sp["up"][1] + sp["down"][1]) / 2, (sp["down"][1] - sp["up"][1]) / 2


def screen(p, x0, y0, x1, y1, bezel, glass, r=2):
    """Écran d'une console portable : cadre, vitre, reflet en haut à gauche."""
    p.part(p.mask(("r", x0, y0, x1, y1, r)), bezel)
    p.rect(x0 + 3, y0 + 3, x1 - 3, y1 - 3, mix(glass, INK, 0.5))
    p.rect(x0 + 4, y0 + 4, x1 - 4, y1 - 4, glass)
    p.line(x0 + 5, y0 + 5, x0 + 9, y0 + 5, mix(glass, WHITE, 0.4))
    p.line(x0 + 5, y0 + 6, x0 + 6, y0 + 6, mix(glass, WHITE, 0.4))


def gameboy(sp):
    p = Pad()
    shell = (206, 206, 198)
    # Coque verticale, coin bas droit très arrondi.
    p.part(p.mask(("r", 28, 18, 68, 82, 3), ("-r", 56, 70, 72, 86), ("e", 40, 58, 68, 82)), shell, shadow=True, top=18, bottom=110)
    p.part(p.mask(("r", 31, 21, 65, 48, 2), ("-r", 56, 40, 70, 52), ("e", 44, 32, 65, 48)), (92, 94, 110))
    p.rect(37, 25, 59, 43, (150, 172, 60), 1)
    p.rect(37, 25, 59, 25, (178, 198, 96))
    p.px([(33, 34)], (200, 40, 50))
    p.line(33, 22, 46, 22, (60, 62, 74))
    dx, dy, half = cross_of(sp)
    p.dpad(dx, dy, half + 1.6, 1.4, (40, 40, 46), well=1.5)
    for k in ("b", "a"):
        p.button(*sp[k][:2], 3, (160, 30, 80))
    for k in ("select", "start"):
        p.pill(*sp[k][:2], 4.5, 1.6, (140, 140, 140), slant=1)
    # Grille du haut-parleur.
    for i in range(5):
        p.line(57 + i * 2, 76, 60 + i * 2, 70, (150, 150, 146))
    return p


def gba(sp):
    p = Pad()
    purple = (88, 70, 160)
    for k in ("l", "r"):
        x, y, _ = sp[k]
        p.bumper(x - 6, y - 2, x + 6, y + 4, mix(purple, SHADE, 0.3), r=3)
    p.part(p.mask(("e", 6, 35, 34, 72), ("e", 62, 35, 90, 72), ("r", 18, 35, 78, 70, 6)), purple, shadow=True)
    screen(p, 30, 37, 66, 66, (38, 36, 56), (130, 160, 150))
    dx, dy, half = cross_of(sp)
    p.dpad(dx, dy, half + 1.6, 1.4, (50, 48, 64), well=1.5)
    for k in ("b", "a"):
        p.button(*sp[k][:2], 3, (190, 190, 204))
    for k in ("start", "select"):
        p.button(*sp[k][:2], 1.3, (40, 38, 56))
    for i in range(3):
        p.px([(78 + i * 2, 62 + i), (80 + i * 2, 62 + i)], mix(purple, SHADE, 0.5))
    return p


def psp(sp):
    p = Pad()
    black = (44, 46, 56)
    for k in ("l", "r"):
        x, y, _ = sp[k]
        p.bumper(x - 7, y - 2, x + 7, y + 3, (70, 72, 84), r=2)
    p.part(p.mask(("r", 4, 33, 92, 69, 15)), black, shadow=True)
    screen(p, 22, 34, 74, 67, (24, 26, 32), (40, 60, 110), r=1)
    for k in ("up", "down", "left", "right"):
        x, y, _ = sp[k]
        p.part(p.mask(p.circle(x, y, 1.6)), (90, 92, 106))
    sx, sy, sr = sp["ls"]
    p.well(p.mask(p.circle(sx, sy, sr)), black)
    p.stick(sx, sy, sr - 0.8, (120, 122, 136))
    face = (70, 72, 86)
    p.button(*sp["x"][:2], 1.9, face, mark=("dot", (60, 200, 150)))
    p.button(*sp["a"][:2], 1.9, face, mark=("dot", (230, 80, 90)))
    p.button(*sp["b"][:2], 1.9, face, mark=("dot", (110, 150, 240)))
    p.button(*sp["y"][:2], 1.9, face, mark=("dot", (230, 120, 200)))
    for k in ("select", "start"):
        p.pill(*sp[k][:2], 3, 1.2, (90, 92, 106))
    p.pill(30, sp["select"][1], 3, 1.2, (90, 92, 106))
    return p


def ngp(sp):
    p = Pad()
    shell = (178, 194, 210)
    p.part(p.mask(("r", 8, 31, 88, 70, 16)), shell, shadow=True)
    screen(p, 31, 33, 65, 66, (60, 64, 80), (176, 196, 120))
    dx, dy, half = cross_of(sp)
    # Stick à microcontacts : socle rond, tête en croix.
    p.well(p.mask(p.circle(dx, dy, half + 3)), shell)
    p.part(p.mask(p.circle(dx, dy, half + 1)), (60, 62, 74))
    p.part(p.mask(p.circle(dx, dy - 0.5, half - 1)), (90, 92, 106))
    for k in ("b", "a"):
        p.button(*sp[k][:2], 3, (60, 62, 74))
    p.button(*sp["start"][:2], 1.3, (60, 62, 74))
    p.rect(13, 36, 18, 37, (210, 60, 60))
    return p


def arcade_panel(sp, panel, top, bottom):
    """Panneau de borne d'arcade : stick à boule, deux rangées de trois boutons, pièce et start."""
    p = Pad()
    p.part(p.mask(("p", [(8, 32), (89, 32), (93, 74), (4, 74)])), mix(panel, SHADE, 0.3), shadow=True)
    p.part(p.mask(("p", [(9, 33), (88, 33), (91, 69), (6, 69)])), panel)
    p.rect(9, 33, 88, 35, mix(panel, WHITE, 0.25))
    dx, dy, half = cross_of(sp)
    # Stick vu de dessus : cache-poussière, ombre de la boule, boule.
    p.part(p.mask(p.circle(dx, dy, half + 2)), (30, 30, 36))
    p.blend((0, 0, 0, 90), p.mask(p.circle(dx + 1, dy + 1.5, 4.4)))
    p.part(p.mask(p.circle(dx, dy - 0.5, 4.4)), (214, 50, 50))
    for keys, colors in (top, bottom):
        for k, c in zip(keys, colors):
            p.button(*sp[k][:2], 3, c)
    for k in ("start", "select"):
        p.button(*sp[k][:2], 1.8, (230, 230, 236))
    return p


def cps(sp):
    colors = [(60, 120, 220), (240, 200, 50), (214, 50, 50)]
    return arcade_panel(sp, (40, 60, 120), (("y", "x", "l"), colors), (("b", "a", "r"), colors))


def arcade(sp):
    return arcade_panel(sp, (44, 44, 52), (("x", "l", "r"), [(60, 120, 220), (230, 230, 236), (230, 120, 40)]),
                        (("b", "a", "y"), [(214, 50, 50), (240, 200, 50), (60, 180, 80)]))


def gx4000(sp):
    p = Pad()
    grey = (70, 72, 84)
    p.part(p.mask(("p", [(14, 37), (82, 37), (88, 46), (88, 66), (8, 66), (8, 46)])), grey, shadow=True)
    p.rect(14, 39, 82, 40, (200, 50, 60))
    dx, dy, half = cross_of(sp)
    p.dpad(dx, dy, half + 1.6, 1.6, (40, 42, 50), well=2)
    for k in ("b", "a"):
        x, y, r = sp[k]
        p.well(p.mask(p.circle(x, y, r + 1.2)), grey)
        p.button(x, y, r - 0.3, (200, 50, 60))
    p.pill(48, 50, 6, 2, (40, 42, 50))
    return p


def atari7800(sp):
    p = Pad()
    black = (48, 46, 50)
    p.part(p.mask(("r", 12, 36, 84, 64, 4)), black, shadow=True)
    p.rect(15, 39, 81, 41, (150, 110, 70))
    dx, dy, half = cross_of(sp)
    p.dpad(dx, dy, half + 1.6, 1.5, (90, 88, 94), well=2)
    for k in ("b", "a"):
        p.button(*sp[k][:2], 3.2, (220, 120, 40))
    return p


def gamegear(sp):
    p = Pad()
    black = (52, 54, 62)
    # Coque large aux bords arrondis, plus haute aux extrémités (prise en main).
    p.part(ImageChops.lighter(p.symmetric(p.mask(("e", 4, 31, 34, 73))), p.mask(("r", 14, 33, 82, 71, 6))), black, shadow=True)
    screen(p, 28, 34, 68, 68, (34, 36, 42), (70, 100, 130))
    p.line(32, 66, 40, 66, (110, 114, 130))
    dx, dy, half = cross_of(sp)
    p.dpad(dx, dy, half + 1.6, 1.5, (78, 80, 94), well=2.5)
    for k in ("b", "a"):
        x, y, r = sp[k]
        p.well(p.mask(p.circle(x, y, r + 1.4)), black)
        p.button(x, y, r, (40, 42, 52))
    p.button(*sp["start"][:2], 1.8, (60, 110, 210))
    # Haut-parleur en bas à droite.
    for i in range(4):
        for j in range(3):
            p.px([(76 + i * 2, 63 + j * 2)], (30, 32, 38))
    return p


def lynx(sp):
    p = Pad()
    black = (50, 52, 60)
    p.part(p.mask(("r", 4, 32, 92, 72, 12)), black, shadow=True)
    screen(p, 26, 34, 66, 70, (30, 32, 38), (60, 110, 120), r=1)
    dx, dy, half = cross_of(sp)
    p.dpad(dx, dy, half + 1.6, 1.4, (34, 36, 44), well=2)
    for k in ("b", "a"):
        p.button(*sp[k][:2], 3, (34, 36, 44))
    for k in ("l", "start", "r"):
        p.button(*sp[k][:2], 1.4, (100, 104, 116))
    p.rect(8, 64, 18, 66, (230, 160, 40), 1)
    return p


# =====================================================================================================
# Manettes branchées (sans repères)
# =====================================================================================================

def ps3(_):
    sp = SPOTS["ps2"]
    p = playstation(sp, (40, 42, 52), (30, 32, 42), wide=True)
    for k in ("ls", "rs"):
        x, y, r = sp[k]
        p.stick(x, y, r - 0.8, (36, 38, 48))
    p.button(48.5, 49, 1.8, (60, 62, 74))
    for i in range(4):
        p.px([(41 + i * 2, 33)], (200, 60, 60) if i == 0 else (80, 80, 90))
    return p


def dualshock4(white=False):
    p = Pad()
    base = (226, 228, 236) if white else (40, 44, 58)
    for x in (26, 70):
        p.bumper(x - 7, 27, x + 7, 33, mix(base, SHADE, 0.25), r=2)
    body = p.mask(("r", 12, 30, 85, 56, 9), ("e", 8, 38, 34, 82), ("e", 63, 38, 89, 82), ("r", 26, 44, 71, 62, 8))
    p.part(body, base, shadow=True)
    if white:
        # DualSense : poignées et centre sombres, coques blanches.
        p.shade(p.mask(("r", 30, 44, 67, 62, 6)), ramp((44, 46, 60)))
    # Pavé tactile.
    p.part(p.mask(("r", 34, 30, 63, 43, 3)), mix(base, SHADE, 0.12))
    if not white:
        p.line(36, 31, 61, 31, (90, 110, 200))
    dx, dy = 25, 44
    p.well(p.mask(p.circle(dx, dy, 7)), base)
    for ox, oy in ((0, -4), (0, 4), (-4, 0), (4, 0)):
        p.part(p.mask(p.circle(dx + ox, dy + oy, 2)), mix(base, SHADE, 0.3))
    bx, by = 72, 44
    p.well(p.mask(p.circle(bx, by, 8)), base)
    face = mix(base, SHADE, 0.35)
    p.button(bx, by - 4.3, 2.4, face, mark=("tri", (60, 200, 150)))
    p.button(bx + 4.3, by, 2.4, face, mark=("cir", (230, 80, 90)))
    p.button(bx, by + 4.3, 2.4, face, mark=("crs", (110, 150, 240)))
    p.button(bx - 4.3, by, 2.4, face, mark=("sqr", (230, 120, 200)))
    stick = (50, 52, 64)
    p.stick(37, 56, 4.3, stick)
    p.stick(60, 56, 4.3, stick)
    p.button(48.5, 55, 1.5, (80, 82, 96))
    return p


def ps4(_):
    return dualshock4()


def ps5(_):
    return dualshock4(white=True)


def xbox(base, guide, stick, face_colors, dpad_disc=False):
    p = Pad()
    for x in (25, 72):
        p.bumper(x - 9, 26, x + 9, 32, mix(base, SHADE, 0.2), r=3)
    body = p.mask(("r", 12, 29, 85, 56, 10), ("e", 8, 36, 36, 82), ("e", 61, 36, 89, 82), ("r", 26, 40, 71, 62, 8), ("-e", 36, 66, 61, 86))
    p.part(body, base, shadow=True)
    p.part(p.mask(p.circle(48.5, 34, 3.2)), guide)
    p.px([(47.5, 33), (49.5, 35), (49.5, 33), (47.5, 35)], mix(guide, INK, 0.6))
    p.stick(24, 41, 5, stick)
    p.stick(62, 56, 5, stick)
    if dpad_disc:
        p.well(p.mask(p.circle(35, 56, 6.5)), base)
        p.part(p.mask(p.circle(35, 56, 5.5)), (150, 154, 170))
    p.dpad(35, 56, 4.5, 1.5, (60, 62, 74) if not dpad_disc else (110, 114, 130))
    for (ox, oy), (letter, color) in zip(((0, -5), (5, 0), (0, 5), (-5, 0)), face_colors):
        p.button(73 + ox, 41 + oy, 2.2, color, mark=(letter, mix(color, WHITE, 0.75)))
    for x in (42, 55):
        p.button(x, 42, 1.3, mix(base, SHADE, 0.45))
    return p


XBOX_FACE = [("dot", (230, 190, 50)), ("dot", (210, 60, 60)), ("dot", (80, 180, 80)), ("dot", (60, 120, 220))]
XBOX_DARK = [("dot", (60, 62, 74)), ("dot", (60, 62, 74)), ("dot", (60, 62, 74)), ("dot", (60, 62, 74))]


def xbox360(_):
    return xbox((228, 230, 236), (120, 200, 80), (80, 84, 96), XBOX_FACE, dpad_disc=True)


def xboxone(_):
    return xbox((230, 232, 238), (230, 232, 238), (40, 42, 52), XBOX_DARK)


def xboxseries(_):
    return xbox((44, 46, 58), (60, 62, 76), (34, 36, 46), XBOX_DARK)


def joycon(_):
    p = Pad()
    blue, red = (40, 190, 230), (240, 70, 70)
    p.part(p.mask(("r", 22, 16, 58, 88, 11), ("-r", 46, 10, 62, 94)), blue, shadow=True)
    p.part(p.mask(("r", 40, 16, 76, 88, 11), ("-r", 36, 10, 52, 94)), red, shadow=True)
    p.rect(44, 17, 45, 87, (40, 44, 56))
    p.rect(53, 17, 54, 87, (40, 44, 56))
    p.stick(33, 32, 4.4, (50, 52, 64))
    for ox, oy in ((0, -4.5), (0, 4.5), (-4.5, 0), (4.5, 0)):
        p.button(33 + ox, 53 + oy, 2, (50, 52, 64))
    p.rect(38, 22, 40, 23, (40, 44, 56))
    p.rect(35, 70, 38, 73, (50, 52, 64), 1)
    for ox, oy in ((0, -4.5), (0, 4.5), (-4.5, 0), (4.5, 0)):
        p.button(64 + ox, 33 + oy, 2, (50, 52, 64))
    p.stick(64, 54, 4.4, (50, 52, 64))
    p.px([(58, 22), (57, 23), (58, 23), (59, 23), (58, 24)], (40, 44, 56))
    p.part(p.mask(p.circle(59, 71, 1.8)), (60, 62, 74))
    return p


def keyboard(_):
    p = Pad()
    white = (226, 228, 232)
    p.part(p.mask(("r", 4, 38, 79, 64, 2)), white, shadow=True)
    key = (238, 238, 242)
    p.rect(6, 40, 77, 42, (190, 192, 200))
    for row in range(5):
        y = 44 + row * 4
        for col in range(14):
            x = 7 + col * 4
            if row == 4 and 3 <= col <= 9:
                continue
            p.rect(x, y, x + 2, y + 2, key)
            p.px([(x, y + 2), (x + 1, y + 2), (x + 2, y + 2)], (170, 172, 182))
    p.rect(7 + 3 * 4, 60, 7 + 9 * 4 + 2, 62, key)
    for col in range(4):
        for row in range(5):
            x, y = 64 + col * 3, 44 + row * 4
            p.rect(x, y, x + 1, y + 2, key)
    p.part(p.mask(("e", 83, 48, 93, 66)), white, shadow=True)
    p.line(88, 49, 88, 54, (170, 172, 182))
    return p


def wii(_):
    p = Pad()
    white = (232, 234, 240)
    p.part(p.mask(("r", 54, 14, 68, 88, 5)), white, shadow=True)
    p.dpad(61, 24, 3.5, 1.2, (170, 172, 184))
    p.button(61, 36, 3, (210, 212, 222))
    for y in (46, 50):
        p.button(61, y, 1, (150, 152, 166))
    for y in (60, 66):
        p.button(61, y, 2.2, (210, 212, 222))
    for row in range(3):
        for col in range(3):
            p.px([(58 + col * 3, 72 + row * 2)], (150, 152, 166))
    for i in range(4):
        p.px([(58 + i * 2, 84)], (80, 160, 230) if i == 0 else (180, 182, 194))
    p.part(p.mask(("e", 26, 36, 44, 58), ("r", 30, 50, 40, 78, 5)), white, shadow=True)
    p.stick(35, 45, 3.6, (200, 202, 212))
    return p


SPOTS = spots()
CONSOLES = {
    "nes": nes, "famicom": famicom, "snes": snes, "n64": n64, "psx": psx, "ps2": ps2, "gamecube": gamecube,
    "genesis": genesis, "master": master, "saturn": saturn, "dreamcast": dreamcast, "pcengine": pcengine,
    "neogeo": neogeo, "atari2600": atari2600, "gameboy": gameboy, "gba": gba, "psp": psp, "ngp": ngp, "cps": cps,
    "arcade": arcade, "gx4000": gx4000, "atari7800": atari7800, "lynx": lynx, "gamegear": gamegear,
}
OTHERS = {
    "ps3": ps3, "ps4": ps4, "ps5": ps5, "xbox360": xbox360, "xboxone": xboxone, "xboxseries": xboxseries,
    "joycon": joycon, "keyboard": keyboard, "wii": wii,
}


def render(name):
    draw = CONSOLES.get(name) or OTHERS[name]
    return draw(SPOTS.get(name))


def main():
    names = list(CONSOLES) + list(OTHERS)
    if "--preview" in sys.argv:
        cols = 6
        rows = (len(names) + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * 291, rows * 2 * 306), (0, 0, 0, 255))
        for i, name in enumerate(names):
            img = render(name).im.resize((W * S, H * S), Image.NEAREST)
            for half, bg in enumerate(((236, 236, 240, 255), (36, 38, 48, 255))):
                tile = Image.new("RGBA", (291, 306), bg)
                tile.alpha_composite(img)
                if "--spots" in sys.argv and name in SPOTS:
                    dt = ImageDraw.Draw(tile)
                    for gx, gy, gr in SPOTS[name].values():
                        x, y, r = gx * 3 + 1, gy * 3 + 1, gr * 3
                        dt.ellipse((x - r, y - r, x + r, y + r), outline=(0, 220, 255, 255))
                sheet.paste(tile, ((i % cols) * 291, ((i // cols) * 2 + half) * 306))
        sheet.save(os.path.join(HERE, "preview.png") if len(sys.argv) < 3 or sys.argv[-1].startswith("--") else sys.argv[-1])
        return
    for name in names:
        pad = render(name)
        pad.save(os.path.join(HERE, f"{name}.png"))
        pad.save(os.path.join(RENDERER, f"{name}.webp"))
        pad.save(os.path.join(ANDROID_RES, f"pad_{name}.webp"))


if __name__ == "__main__":
    main()
