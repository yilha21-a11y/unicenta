"""Genere docs/OLPOS_Guide_Connexion_Telephone.pdf a partir de docs/guide/guide.html.

Usage (depuis la racine du projet) :  python docs/guide/build_guide.py
Necessite Playwright pour Python et Microsoft Edge (deja present sous Windows).
"""
import os
import sys

from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "guide.html")
OUT = os.path.join(os.path.dirname(HERE), "OLPOS_Guide_Connexion_Telephone.pdf")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page()
        page.goto("file:///" + SRC.replace("\\", "/"))
        page.wait_for_load_state("networkidle")
        broken = page.evaluate("[...document.images].filter(i => !i.complete || i.naturalWidth === 0).map(i => i.src)")
        if broken:
            print("Images introuvables :", broken)
            return 1
        page.pdf(path=OUT, format="A4", print_background=True, prefer_css_page_size=True,
                 display_header_footer=True, header_template="<span></span>",
                 footer_template='<div style="font-size:8px;color:#888;width:100%;text-align:center">'
                                 'OLPOS - Guide de connexion du telephone - page <span class="pageNumber"></span>'
                                 ' / <span class="totalPages"></span></div>',
                 margin={"top": "14mm", "bottom": "16mm", "left": "14mm", "right": "14mm"})
        browser.close()
    print(OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
