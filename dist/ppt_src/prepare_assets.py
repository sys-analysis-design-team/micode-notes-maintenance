"""把 logs/ 下的原始截图裁切并做成白卡，供 build.py 使用。"""

from pathlib import Path

from office_kit.deck.images import card_to_file, crop_to_file
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
LOGS = ROOT / "logs"
ASSETS = Path(__file__).resolve().parent / "assets"
ASSETS.mkdir(exist_ok=True)

# 手机截图：去掉状态栏与底部导航条
PHONE_CROP = (0, 110, 1080, 2290)

SHOTS = {
    "m1_final_list.png": "shot_list.png",
    "app_editor_typed.png": "shot_editor.png",
    "m4_notification.png": "shot_notification.png",
    "m5_trash_list.png": "shot_trash.png",
    "m6_ai_result.png": "shot_ai.png",
    "m7_dark_list.png": "shot_dark.png",
    "m8_pinned_list.png": "shot_pinned.png",
}


def main() -> None:
    for src, dst in SHOTS.items():
        raw = ASSETS / ("raw_" + dst)
        crop_to_file(str(LOGS / src), PHONE_CROP, str(raw))
        card_to_file(str(raw), str(ASSETS / dst))
        print("card:", dst)
    # 用例图与包图按原样复制（PNG 已是成品）
    for name in ("use_case_original.png", "package_original.png", "class_after.png"):
        im = Image.open(ROOT / "docs" / "uml" / name)
        im.save(ASSETS / name)
        print("uml:", name, im.size)


if __name__ == "__main__":
    main()
