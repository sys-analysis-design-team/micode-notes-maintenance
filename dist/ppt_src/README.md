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

## 页面骨架（10 页）

1. 封面
2. 背景：原始功能与核心架构（已有功能一页带过）
3. 标注：代码阅读与四级标注
4. 修复：M1 老工程现代化
5. 修复：M2/M3 数据安全与导出
6. 拓展：M4 提醒 / M9 重复提醒 / M5 回收站
7. 拓展：M6 AI 摘要
8. 拓展：M7 深色 / M8 置顶 + **团队新功能占位（刘嘉、罗奕峰两个卡）**
9. 设计：维护后类图（M7–M9 两个新类待下次渲染补入）
10. 收束：验证数字 + 团队分工 + 局限

每页都有讲稿备注，演示者视图可直接读。

**团队新功能回填点**（成员功能提交后）：
- 第 8 页右侧两个占位卡：换成实际功能名 + 一句话说明；
- 第 10 页分工：成员一行补功能名，维护代码量重跑 `tools/count_maintenance.py`；
- 若新功能新增类，重新渲染类图后替换第 9 页。

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
