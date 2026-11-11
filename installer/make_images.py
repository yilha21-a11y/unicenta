"""Regenere les images de l'assistant Inno Setup a partir des logos OLPOS.
Usage (racine du depot) : python installer/make_images.py   (Pillow requis)"""
from PIL import Image

side = Image.open('olpos_installer_side.png').convert('RGB')
logo = Image.open('olpos.png').convert('RGBA')


def wizard(w, h, name):
    bg = Image.new('RGB', (w, h), side.getpixel((2, 2)))
    s = side.resize((int(side.width * h / side.height), h), Image.LANCZOS)
    bg.paste(s, ((w - s.width) // 2, 0))
    bg.save(name)


def small(n, name):
    bg = Image.new('RGB', (n, n), (255, 255, 255))
    l = logo.resize((n, n), Image.LANCZOS)
    bg.paste(l, (0, 0), l)
    bg.save(name)


wizard(164, 314, 'installer/wizard.bmp')
wizard(328, 628, 'installer/wizard-2x.bmp')
small(55, 'installer/wizard-small.bmp')
small(110, 'installer/wizard-small-2x.bmp')
