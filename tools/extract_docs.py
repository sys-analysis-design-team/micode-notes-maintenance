# -*- coding: utf-8 -*-
"""Extract text from the four assignment files into docs/_extract/."""
import sys
from pathlib import Path

from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph
from pptx import Presentation


def iter_block_items(parent):
    from docx.oxml.ns import qn
    if hasattr(parent, "element") and hasattr(parent.element, "body"):
        parent_elm = parent.element.body
    else:
        parent_elm = parent._tc
    for child in parent_elm.iterchildren():
        if child.tag == qn('w:p'):
            yield Paragraph(child, parent)
        elif child.tag == qn('w:tbl'):
            yield Table(child, parent)


def extract_docx(path: Path) -> str:
    doc = Document(str(path))
    out = []
    for block in iter_block_items(doc):
        if isinstance(block, Paragraph):
            t = block.text.strip()
            if t:
                style = block.style.name if block.style is not None else ''
                out.append(f"[{style}] {t}")
        else:
            out.append("[TABLE]")
            for row in block.rows:
                cells = [c.text.strip().replace('\n', ' / ') for c in row.cells]
                out.append(" | ".join(cells))
            out.append("[/TABLE]")
    return "\n".join(out)


def extract_pptx(path: Path) -> str:
    prs = Presentation(str(path))
    out = []
    for i, slide in enumerate(prs.slides, 1):
        out.append(f"===== Slide {i} =====")
        for shape in slide.shapes:
            if shape.has_text_frame:
                for p in shape.text_frame.paragraphs:
                    t = "".join(r.text for r in p.runs).strip()
                    if t:
                        out.append(t)
            if shape.has_table:
                for row in shape.table.rows:
                    out.append(" | ".join(c.text.strip() for c in row.cells))
        if slide.has_notes_slide:
            notes = slide.notes_slide.notes_text_frame.text.strip()
            if notes:
                out.append(f"[NOTES] {notes}")
        out.append("")
    return "\n".join(out)


def main():
    base = Path(__file__).resolve().parent.parent
    outdir = base / "docs" / "_extract"
    outdir.mkdir(parents=True, exist_ok=True)
    jobs = [
        (Path(r"D:/Downloads/作业要求.docx"), outdir / "01_作业要求.txt", extract_docx),
        (Path(r"D:/Downloads/实践考评-阅读维护开源软件的汇报模板.pptx"), outdir / "02_汇报模板.txt", extract_pptx),
        (Path(r"D:/Downloads/文档模板-开源软件的质量分析报告文档.docx"), outdir / "03_质量分析报告模板.txt", extract_docx),
        (Path(r"D:/Downloads/文档模板-开源软件泛读、标注和维护报告文.docx"), outdir / "04_泛读标注维护报告模板.txt", extract_docx),
    ]
    for src, dst, fn in jobs:
        if not src.exists():
            print(f"MISSING {src}")
            continue
        text = fn(src)
        dst.write_text(text, encoding="utf-8")
        print(f"OK {dst.name}: {len(text)} chars, {text.count(chr(10)) + 1} lines")


if __name__ == "__main__":
    sys.exit(main())
