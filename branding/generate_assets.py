"""Genere toutes les images OLPOS a partir des deux sources fournies.

Sources (branding/source/) :
  olpos-logo.png  logo horizontal (symbole + "OLPOS"), fond transparent
  olpos-icon.png  symbole seul (fond creme opaque : il est detoure ici)

Usage (depuis la racine du projet) :  python branding/generate_assets.py
Necessite Pillow. Relancer apres tout changement de logo.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "branding", "source")
IMAGES = os.path.join(ROOT, "src-beans", "com", "openbravo", "images")
TEMPLATES = os.path.join(ROOT, "src-pos", "com", "openbravo", "pos", "templates")
WEB = os.path.join(ROOT, "src-pos", "com", "openbravo", "pos", "api", "web")
PREVIEW = os.path.join(ROOT, "branding", "generated")

NAVY = (5, 28, 74)          # #051C4A, mesure sur les images fournies
TEAL = (0, 194, 165)        # #00C2A5
WHITE = (255, 255, 255)
CREAM_BG = (254, 250, 245)  # fond de olpos-icon.png

written = []


def save(img, path, **kw):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, **kw)
    written.append(os.path.relpath(path, ROOT))


def resize(img, size):
    """Redimensionnement en alpha premultiplie : pas de halo sombre sur les bords."""
    return img.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def fit(img, max_w, max_h):
    r = min(max_w / img.width, max_h / img.height)
    return resize(img, (max(1, round(img.width * r)), max(1, round(img.height * r))))


def detour(img, bg):
    """Rend transparent un fond uni en gardant des bords lisses (demelange de l'alpha).

    Chaque pixel est vu comme a*couleur_marque + (1-a)*fond ; on estime a par
    rapport a la couleur de marque la plus proche (marine ou turquoise)."""
    img = img.convert("RGB")
    out = Image.new("RGBA", img.size)
    src = img.load()
    dst = out.load()
    palette = [NAVY, TEAL]
    for y in range(img.height):
        for x in range(img.width):
            p = src[x, y]
            best = None
            for f in palette:
                denom = sum((bg[i] - f[i]) ** 2 for i in range(3))
                a = sum((bg[i] - p[i]) * (bg[i] - f[i]) for i in range(3)) / denom
                err = sum((p[i] - (a * f[i] + (1 - a) * bg[i])) ** 2 for i in range(3))
                if best is None or err < best[0]:
                    best = (err, a)
            a = max(0.0, min(1.0, best[1]))
            if a < 0.03:
                dst[x, y] = (0, 0, 0, 0)
                continue
            if a > 0.97:
                dst[x, y] = (p[0], p[1], p[2], 255)
                continue
            c = tuple(max(0, min(255, round((p[i] - (1 - a) * bg[i]) / a))) for i in range(3))
            dst[x, y] = (c[0], c[1], c[2], round(a * 255))
    return out


def detour_with_light_screen(img, bg):
    """Detoure tout le fond (comme le logo horizontal), puis pose un aplat clair
    UNIQUEMENT sous l'"ecran" central du symbole, pour que l'icone reste lisible
    sur une barre des taches sombre (l'image fournie montre cet ecran clair).

    L'ecran communique avec l'exterieur par une fine fente : on la referme sur
    une copie reduite, puis on remplit a partir du centre de l'ecran. Les dents
    du recu et la fente restent identiques au logo."""
    rgb = img.convert("RGB")
    detoured = detour(rgb, bg)

    scale = 4
    small = detoured.split()[3].resize((rgb.width // scale, rgb.height // scale), Image.BILINEAR)
    small = small.point(lambda a: 255 if a > 100 else 0)
    closed = small.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))   # referme la fente
    region = closed.point(lambda a: 0 if a else 128)
    bbox = small.getbbox()
    seed = ((bbox[0] + bbox[2]) // 2, (bbox[1] + bbox[3]) // 2 - (bbox[3] - bbox[1]) // 10)   # centre de l'ecran
    if region.getpixel(seed) != 128:
        raise SystemExit("Centre de l'ecran du symbole introuvable : verifier olpos-icon.png")
    ImageDraw.floodfill(region, seed, 255, thresh=0)
    screen = region.point(lambda a: 255 if a == 255 else 0)
    # leger debordement sous le marine (cache par le symbole) pour eviter un liseré sombre
    screen = screen.resize(rgb.size, Image.NEAREST).filter(ImageFilter.MaxFilter(9))

    result = Image.new("RGBA", rgb.size, (0, 0, 0, 0))
    result.paste(Image.new("RGBA", rgb.size, bg + (255,)), (0, 0), screen)
    result.alpha_composite(detoured)
    return result


def square(img, margin):
    """Recadre sur le dessin et le centre dans un carre avec une marge (fraction)."""
    img = img.crop(img.getbbox())
    side = round(max(img.size) * (1 + 2 * margin))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return canvas


def on_background(img, color, size, margin):
    bg = Image.new("RGBA", (size, size), color + (255,))
    inner = round(size * (1 - 2 * margin))
    sym = fit(img, inner, inner)
    bg.alpha_composite(sym, ((size - sym.width) // 2, (size - sym.height) // 2))
    return bg


def font(size, bold=False):
    for name in (["segoeuib.ttf", "arialbd.ttf"] if bold else ["segoeui.ttf", "arial.ttf"]):
        p = os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "Fonts", name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def main():
    logo = Image.open(os.path.join(SRC, "olpos-logo.png")).convert("RGBA")
    logo = logo.crop(logo.getbbox())                     # proportions conservees
    print("Detourage de l'icone...")
    symbol = square(detour_with_light_screen(Image.open(os.path.join(SRC, "olpos-icon.png")), CREAM_BG), 0.04)
    save(symbol, os.path.join(PREVIEW, "olpos-symbol-transparent.png"))

    # ---- caisse (Swing)
    save(fit(logo, 410, 140), os.path.join(IMAGES, "olpos_logo.png"))          # ecran de connexion
    save(fit(logo, 130, 26), os.path.join(IMAGES, "olpos_logo_small.png"))     # barre de titre
    save(fit(symbol, 96, 96), os.path.join(IMAGES, "olpos_logo_config.png"))   # outil de configuration
    for s in (16, 20, 24, 32, 40, 48, 64, 128, 256):
        save(resize(symbol, (s, s)), os.path.join(IMAGES, "olpos_icon_%d.png" % s))
    save(resize(symbol, (32, 32)), os.path.join(IMAGES, "favicon.png"))        # icone des autres fenetres
    save(resize(symbol, (38, 38)), os.path.join(TEMPLATES, "window.logo.png"))
    # logo d'exemple des recus (bases neuves) : 168 x 48 comme l'original ; chaque
    # restaurant le remplace par son propre logo (Administration > Ressources)
    receipt = Image.new("RGBA", (168, 48), (0, 0, 0, 0))
    rl = fit(logo, 160, 46)
    receipt.alpha_composite(rl, ((168 - rl.width) // 2, (48 - rl.height) // 2))
    save(receipt, os.path.join(TEMPLATES, "printer.ticket.logo.png"))

    # ---- Windows : icone .ico multi-tailles, PNG, ecran de demarrage
    ico_sizes = [(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)]
    save(resize(symbol, (256, 256)), os.path.join(ROOT, "olpos.ico"), format="ICO", sizes=ico_sizes)
    save(resize(symbol, (256, 256)), os.path.join(ROOT, "olpos.png"))
    save(resize(symbol, (48, 48)), os.path.join(ROOT, "olpos_48.png"))
    save(fit(logo, 500, 222), os.path.join(ROOT, "olpos_logo_500.png"))      # installeur

    splash = Image.new("RGBA", (500, 352), WHITE + (255,))
    lg = fit(logo, 400, 140)
    splash.alpha_composite(lg, ((500 - lg.width) // 2, 70))
    d = ImageDraw.Draw(splash)
    for text, f, color, y in (("Point de vente tactile pour restaurants", font(20, True), NAVY, 228),
                              ("Version derivee d'uniCenta oPOS - Licence GPL v3", font(14), (90, 100, 120), 262)):
        w = d.textlength(text, font=f)
        d.text(((500 - w) / 2, y), text, font=f, fill=color)
    d.rectangle([0, 344, 500, 352], fill=TEAL)
    d.rectangle([0, 0, 500, 4], fill=NAVY)
    save(splash.convert("RGB"), os.path.join(ROOT, "olpos_splash.png"))

    side = Image.new("RGBA", (130, 312), WHITE + (255,))
    sm = fit(symbol, 100, 100)
    side.alpha_composite(sm, ((130 - sm.width) // 2, 40))
    d = ImageDraw.Draw(side)
    f = font(26, True)
    w = d.textlength("OLPOS", font=f)
    d.text(((130 - w) / 2, 150), "OLPOS", font=f, fill=NAVY)
    d.rectangle([0, 304, 130, 312], fill=TEAL)
    save(side.convert("RGB"), os.path.join(ROOT, "olpos_installer_side.png"))

    # ---- page web mobile
    save(resize(symbol, (32, 32)), os.path.join(WEB, "favicon-32.png"))
    save(resize(symbol, (192, 192)), os.path.join(WEB, "icon-192.png"))
    save(resize(symbol, (512, 512)), os.path.join(WEB, "icon-512.png"))
    # iPhone : l'icone d'ecran d'accueil doit etre opaque (sinon fond noir)
    save(on_background(symbol, WHITE, 180, 0.12).convert("RGB"), os.path.join(WEB, "apple-touch-icon.png"))
    save(fit(logo, 600, 200), os.path.join(WEB, "logo.png"))

    print("\n".join(written))


if __name__ == "__main__":
    sys.exit(main())
