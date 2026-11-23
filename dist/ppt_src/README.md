# 小米便签开源软件维护 — PPT 工程

脚本式构建，产物是可编辑 PPTX：文字是文字，图形是原生形状，只有截图是图片。

## 目录

```
build.py            页面脚本（改这里）
assets/             截图与封面素材（cover_bg.png / screenshot_card.png ...）
小米便签开源软件维护.pptx        构建产物
```

## 用法

```bash
python build.py                  # 生成 小米便签开源软件维护.pptx
officegen review 小米便签开源软件维护.pptx --pdf --images renders --contact renders/contact.png
```

## 页面骨架

封面 → 提纲 → 章节页 → 内容页（要点 / 三卡 / 数据 / 截图）→ 结尾。
每页都有讲稿备注，演示者视图可直接读。

## 截图加工

```python
from office_kit.deck.images import crop_to_file, card_to_file, cover_background_to_file

crop_to_file("raw/home.png", (8, 40, 1032, 580), "assets/home.png")   # 去掉标题栏
card_to_file("assets/home.png", "assets/screenshot_card.png")          # 白卡 + 阴影
cover_background_to_file("assets/cover_bg.png", photo="raw/dashboard.png")
```

## 交付前检查

1. `officegen review 小米便签开源软件维护.pptx --pdf` —— errors 必须为 0；
2. 逐页目检 `renders/`，重点看截图是否被裁掉内容、文字是否压线；
3. `officegen qa 小米便签开源软件维护.pptx --pdf` —— 结构、越界、溢出；
4. 清理文档属性（metadata.clean_pptx_properties），再导出 PDF 随附。
