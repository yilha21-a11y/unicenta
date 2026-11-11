"""Applique l'identite OLPOS aux images d'uniCenta 5.0.

Sources : branding/source/ (logos et icones OLPOS repris de l'ancienne version)
et les images olpos_* deja presentes dans src/main/resources/com/unicenta/images/.

Les fichiers d'uniCenta qui portent le logo uniCenta sont remplaces par des
images OLPOS de MEMES dimensions (le code et les scripts SQL d'uniCenta les
referencent par leur nom). Necessite Pillow.

Usage (depuis la racine du projet) : python branding/apply_olpos_5.py
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'branding', 'source')
IMG = os.path.join(ROOT, 'src', 'main', 'resources', 'com', 'unicenta', 'images')
TPL = os.path.join(ROOT, 'src', 'main', 'resources', 'com', 'unicenta', 'pos', 'templates')
OTHER = os.path.join(ROOT, 'src', 'other', 'images')

NAVY = (5, 28, 74)
WHITE = (255, 255, 255)


def resize(img, size):
    return img.convert('RGBa').resize(size, Image.LANCZOS).convert('RGBA')


def fit(img, max_w, max_h):
    r = min(max_w / img.width, max_h / img.height)
    return resize(img, (max(1, round(img.width * r)), max(1, round(img.height * r))))


def canvas(size, bg=(0, 0, 0, 0)):
    return Image.new('RGBA', size, bg)


def centered(img, size, bg=(0, 0, 0, 0), margin=0):
    c = canvas(size, bg)
    f = fit(img, size[0] - 2 * margin, size[1] - 2 * margin)
    c.alpha_composite(f, ((size[0] - f.width) // 2, (size[1] - f.height) // 2))
    return c


def save(img, path, mode=None, **kw):
    if mode:
        if mode == 'RGB' and img.mode == 'RGBA':
            bg = Image.new('RGB', img.size, WHITE)
            bg.paste(img, mask=img.split()[3])
            img = bg
        else:
            img = img.convert(mode)
    img.save(path, **kw)
    print('ecrit', os.path.relpath(path, ROOT), img.size)


logo = Image.open(os.path.join(IMG, 'olpos_logo.png')).convert('RGBA')        # 410 x 128
icon = Image.open(os.path.join(IMG, 'olpos_icon_256.png')).convert('RGBA')    # symbole
small = Image.open(os.path.join(IMG, 'olpos_logo_small.png')).convert('RGBA')

# Icone des fenetres (48x48, RGB comme l'original)
save(centered(icon, (48, 48), (255, 255, 255, 255)), os.path.join(IMG, 'favicon.png'), 'RGB')
# Logo de l'ecran de connexion (410x289) : logo OLPOS centre
for name in ('logo.png', 'unicenta.png'):
    save(centered(logo, (410, 289), margin=10), os.path.join(IMG, name))
# Barre de titre (103x34)
save(centered(small, (103, 34), margin=3), os.path.join(IMG, 'poweredby_uni.png'))
# Outil de configuration (100x100)
save(centered(icon, (100, 100), margin=4), os.path.join(IMG, 'uniCenta_logo_vert_100.png'))
# Icone d'application (377x377) et petit logo de fenetre (38x38)
save(centered(icon, (377, 377), margin=8), os.path.join(IMG, 'unicentaopos.png'))
save(centered(icon, (38, 38), (255, 255, 255, 255), margin=1), os.path.join(IMG, 'window.logo.png'), 'RGB')
# Logo d'EXEMPLE imprime sur les tickets d'une base neuve (a remplacer par celui du restaurant)
sample = Image.open(os.path.join(SRC, 'printer.ticket.logo.png')).convert('RGBA')
save(centered(sample, (100, 100), (255, 255, 255, 255)), os.path.join(IMG, 'printer.ticket.logo.jpg'), 'RGB',
     quality=92)
# Logo d'exemple (png) utilise par la migration des bases existantes
save(sample, os.path.join(TPL, 'printer.ticket.logo.png'))
# Ecran de demarrage et icone du programme
splash = Image.open(os.path.join(SRC, 'olpos_splash.png')).convert('RGBA')
save(centered(splash, (500, 352), (255, 255, 255, 255)), os.path.join(IMG, 'uniCenta_splash_dark.png'), 'RGB')
save(centered(splash, (500, 352), (255, 255, 255, 255)), os.path.join(OTHER, 'uniCenta_splash_dark.png'), 'RGB')
save(centered(icon, (377, 377), margin=8), os.path.join(OTHER, 'unicentaopos.png'))
with open(os.path.join(SRC, 'olpos.ico'), 'rb') as f:
    data = f.read()
with open(os.path.join(OTHER, 'unicentaopos.ico'), 'wb') as f:
    f.write(data)
print('ecrit', os.path.relpath(os.path.join(OTHER, 'unicentaopos.ico'), ROOT))
