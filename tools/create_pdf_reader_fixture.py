"""Regenerate the small, self-contained PDF used by the book reader tests (reportlab)."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("font", type=Path, help="NotoSerif-Regular.ttf")
args = parser.parse_args()
target = Path(__file__).resolve().parents[1] / "shared/src/desktopTest/resources/pdf-reader-book.pdf"
pdfmetrics.registerFont(TTFont("BookSerif", str(args.font)))
pdf = canvas.Canvas(str(target), pagesize=(600, 900), invariant=1)
pdf.setTitle("A quiet page")
pdf.setAuthor("WEX reader test fixture")
headings = ["First chapter: a comfortable reader.", "Second chapter: NEEDLE needle.", "The end: a final reader page."]
lines = [
    "A book is a place to slow down and stay for a while.",
    "The page should be clear, quiet and easy on the eyes.",
    "Turn the page when you are ready. Your place is saved.",
    "", "Chapter notes", "",
    "Use the keyboard or scroll at your own pace.",
    "Keep a bookmark for the parts worth returning to.",
    "Change the paper color to suit the evening light.",
    "", "Nothing here changes the original document.",
]
for index, heading in enumerate(headings):
    pdf.setPageSize((900, 600) if index == 1 else (600, 900))
    top = 520 if index == 1 else 800
    pdf.setFont("BookSerif", 23)
    pdf.drawString(48, top, heading)
    pdf.setFont("BookSerif", 15)
    for line in lines:
        top -= 28
        pdf.drawString(48, top - 24, line)
    pdf.showPage()
pdf.save()
print(target)
