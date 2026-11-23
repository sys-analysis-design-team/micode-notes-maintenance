"""小米便签开源软件维护 — 汇报 PPT 构建脚本（10 页 / 8 分钟）。

    python prepare_assets.py        # 先准备截图卡片
    python build.py                 # -> 小米便签开源软件维护.pptx
"""

from __future__ import annotations

import argparse
from pathlib import Path

from pptx.enum.shapes import MSO_SHAPE

from office_kit.deck import (
    AMBER, CARD, CYAN, GREEN, LINE, MARGIN, MUTED, NAVY, PURPLE, SOFT,
    TEAL, TEAL_DARK, WHITE, Deck,
)
from office_kit.deck.images import cover_background_to_file

HERE = Path(__file__).resolve().parent
ASSETS = HERE / "assets"
FOOTER = "MiCode/Notes · 开源软件维护实践"


def cover(d: Deck) -> None:
    s = d.slide(NAVY)
    hero = ASSETS / "cover_bg.png"
    if not hero.exists():
        cover_background_to_file(hero, size=(1920, 1080))
    s.shapes.add_picture(str(hero), 0, 0, width=d.prs.slide_width,
                        height=d.prs.slide_height)
    d.diagonal_marks(s, color=CYAN, alpha=18, x=12.05, y=0.34)
    d.text(s, MARGIN, 1.62, 8.6, 0.26, "2026 · 开源软件维护实践", size=11,
           bold=True, color=CYAN)
    d.text(s, MARGIN, 1.98, 10.5, 1.5, "小米便签开源软件维护", size=44, bold=True,
           color=WHITE, line_spacing=1.06)
    d.rect(s, MARGIN, 3.62, 1.0, 0.055, fill=TEAL, shape=MSO_SHAPE.RECTANGLE)
    d.text(s, MARGIN, 3.86, 10.5, 0.62,
           "泛读 · 标注 · 维护：39 个文件 / 10,001 行，6 项维护 + 1 项拓展",
           size=15, color="9FC7D2")
    d.text(s, MARGIN, 6.32, 6.0, 0.26, "袁晨昕 · 刘嘉 · 罗奕峰", size=11, color="7FA0B4")
    d.text(s, MARGIN, 6.62, 6.0, 0.26, "2026-12", size=11, color="5F8796")
    d.notes(s, "开场一句话：我们维护的是小米开源的便签应用 MiCode/Notes，"
               "原始工程是 Eclipse 时代的，今天讲四件事：读懂它、修好它、给它加功能、证明它真的能跑。")


def arch_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "01 背景", "原始功能与核心架构", "先看功能全貌，再看代码怎么组织")
    d.text(s, MARGIN, 1.58, 7.8, 0.28, "原始包结构：7 个包 / 39 个文件 / 10,001 行",
           size=11.5, bold=True, color=TEAL_DARK)
    d.picture(s, str(ASSETS / "package_original.png"), MARGIN, 1.94, w=7.7)
    d.text(s, MARGIN, 5.28, 7.7, 0.3, "核心数据链", size=11.5, bold=True,
           color=TEAL_DARK)
    d.text(s, MARGIN, 5.62, 7.7, 0.72,
           "UI → WorkingNote（编辑期聚合）→ Note（差量提交）→ ContentResolver\n"
           "→ NotesProvider → SQLite（触发器维护摘要与文件夹计数）",
           size=11.5, color=MUTED, line_spacing=1.4)
    d.text(s, 8.86, 1.58, 3.8, 0.28, "用例模型", size=11.5, bold=True,
           color=TEAL_DARK)
    d.picture(s, str(ASSETS / "use_case_original.png"), 8.76, 1.94, w=2.9)
    d.text(s, 8.86, 6.45, 3.8, 0.5,
           "9 组功能：便签 / 文件夹 / 搜索 / 提醒 / 微件 / 通话便签 / 导出 / 云同步 / 设置",
           size=10, color=MUTED, line_spacing=1.3)
    d.footer(s)
    d.notes(s, "这一页带过：原始应用功能完整，分层清楚；问题不在功能，在它停留在 2014 年的 Android 上。")


def annotation_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "02 标注", "代码阅读与四级标注", "把「读懂」变成可检查的产出")
    stats = [("38", "个原始类", "全部覆盖"), ("3,080", "行新增注释", "只加不改"),
             ("5,135", "行代码被覆盖", "方法级 + 块级"), ("0", "删除行", "脚本校验通过")]
    x = MARGIN
    for value, label, note in stats:
        d.rect(s, x, 1.62, 2.72, 1.72, fill=CARD, line=LINE, radius=0.06)
        d.text(s, x + 0.26, 1.8, 2.3, 0.7, value, size=28, bold=True,
               color=TEAL_DARK, font="Consolas")
        d.text(s, x + 0.26, 2.58, 2.3, 0.26, label, size=11, bold=True, color=NAVY)
        d.text(s, x + 0.26, 2.88, 2.3, 0.3, note, size=10, color=MUTED)
        x += 2.86
    d.text(s, MARGIN, 3.6, 6.6, 0.28, "四级口径（示例）", size=11.5, bold=True,
           color=TEAL_DARK)
    d.bullets(s, MARGIN, 3.94, 6.6, 2.9, [
        ("类级：", "NotesDatabaseHelper——建表、10 个触发器与 v1→v4 升级的唯一入口。"),
        ("方法级：", "WorkingNote.saveNote——返回值语义、空便签不落库、三类拦截。"),
        ("块级：", "触发器 SQL——UPDATE OF parent_id 值不变也会触发，级联删除不递归。"),
        ("语句级：", "Contact.getContact——replace 的 + 占位符、游标只取首行的原因。"),
    ], size=11, gap=8)
    d.rect(s, 7.82, 3.56, 4.85, 3.14, fill=SOFT, line=LINE, radius=0.06)
    d.text(s, 8.1, 3.74, 4.3, 0.3, "标注中发现的真实缺陷", size=11.5, bold=True,
           color=TEAL_DARK)
    d.bullets(s, 8.1, 4.08, 4.3, 2.5, [
        "TaskList.addChildTask 前驱兄弟指向自己",
        "WorkingNote.saveNote 忽略 syncNote 返回值",
        "数据库 v1 直升 v4 的升级路径不可用",
        "ResourceParser 兜底分支返回索引常量",
        "DateTimePicker 24 小时制折算依赖 mIsAm",
        "GTaskSyncService 对 null Intent 无保护",
    ], size=10, gap=5, bullet_color=AMBER)
    d.footer(s)
    d.notes(s, "强调两点：标注不只是写注释，是倒逼理解；统计是脚本算的，不是手抄。"
               "发现的缺陷只记录不改，留给维护项统一处理。")


def modernization_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "03 修复", "问题一：老工程跑不起来", "Eclipse 时代工程 → 现代 Android")
    d.bullets(s, MARGIN, 1.7, 7.3, 4.9, [
        ("没有构建脚本：", "Eclipse ADT 结构 → Gradle 9.2.1 + AGP 9 迁移，工程可构建。"),
        ("装不上：", "无 targetSdk，Android 14+ 拒绝安装；补 targetSdk 34。"),
        ("构建失败：", "带 intent-filter 的组件缺 android:exported，target 31+ 直接报错。"),
        ("运行崩溃：", "PendingIntent 未指定可变性、动态广播未设导出标志，逐处修复。"),
        ("API 已移除：", "Apache HttpClient 与 setLatestEventInfo 在新系统不存在。"),
        ("依赖已下线：", "Google Tasks 后端关闭——同步入口退役，源码保留不接入 UI。"),
    ], size=12.5, gap=11)
    d.picture_card(s, str(ASSETS / "shot_list.png"), 8.7, 1.6, w=2.1)
    d.text(s, 8.7, 5.86, 2.6, 0.3, "迁移后：Pixel_7 / Android 17 正常运行",
           size=10, color=MUTED)
    d.footer(s)
    d.notes(s, "这是六个维护项里工作量最大的一项：从装不上到能跑通，"
               "每一步都是 Android 版本演进的硬约束。")


def provider_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "03 修复", "问题二：数据安全与导出失效", "Provider 收紧 + SQL 参数化 + SAF/UTF-8")
    d.bullets(s, MARGIN, 1.7, 7.3, 4.4, [
        ("Provider 裸奔：", "未设 exported 与权限，任意应用可读写全部便签 → 收紧为应用内访问。"),
        ("SQL 拼接：", "条件用字符串拼接、replaceFirst 构造 → 抽出 ProviderSql，全部参数绑定。"),
        ("导出失效：", "写外部存储根目录 + 默认字符集 → Android 10+ 失败、中文乱码。"),
        ("M3 方案：", "SAF 选择位置 + UTF-8 导出；新增 JSON 全量备份/恢复，坏文件不改动数据。"),
    ], size=12.5, gap=11)
    d.rect(s, MARGIN, 5.0, 7.3, 1.2, fill=SOFT, line=LINE, radius=0.06)
    d.text(s, MARGIN + 0.3, 5.18, 6.7, 0.9,
           "SonarQube 两个 CRITICAL 随修复消失：\n"
           "S5322 动态广播接收器无权限 · S5324 敏感数据写外部存储",
           size=10.5, color=TEAL_DARK, line_spacing=1.35)
    d.picture_card(s, str(ASSETS / "shot_editor.png"), 8.7, 1.6, w=2.1)
    d.text(s, 8.7, 5.86, 2.6, 0.3, "编辑页：字号 / 背景色 / 提醒 / AI 入口",
           size=10, color=MUTED)
    d.footer(s)
    d.notes(s, "安全问题的展示价值在于：人工分析和 SonarQube 各自独立地指出了同样两处 CRITICAL，"
               "修完之后评级从 D 升到 C。")


def reminder_trash_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "04 拓展", "可靠提醒与可恢复删除", "两个用户每天都会碰到的功能")
    d.picture_card(s, str(ASSETS / "shot_notification.png"), MARGIN, 1.6, w=2.1)
    d.text(s, MARGIN, 5.86, 2.5, 0.5, "M4 通知：渠道 + 动作 + 点击回到便签",
           size=10, color=MUTED, line_spacing=1.2)
    d.bullets(s, 3.55, 1.7, 4.5, 4.6, [
        ("M4 提醒重构：", "精确闹钟权限适配；通知渠道与动作按钮；开机重排全部提醒。"),
        ("M5 回收站：", "删除改为软删除；支持恢复、彻底删除、清空回收站。"),
        ("文件夹保护：", "删除文件夹时按层级进入回收站，恢复后回到原位置。"),
    ], size=12, gap=12)
    d.picture_card(s, str(ASSETS / "shot_trash.png"), 8.55, 1.6, w=2.1)
    d.text(s, 8.55, 5.86, 2.5, 0.5, "M5 回收站：恢复 / 彻底删除 / 清空",
           size=10, color=MUTED, line_spacing=1.2)
    d.footer(s)
    d.notes(s, "提醒这块的坑在 Android 12+ 的精确闹钟和 13+ 的通知权限，"
               "都做了降级路径；回收站是软删除，不改变原有列表查询逻辑。")


def ai_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "04 拓展", "AI 摘要与待办提取", "真实 LLM + 离线规则兜底，确认后写入")
    d.picture_card(s, str(ASSETS / "shot_ai.png"), 2.9, 1.55, w=2.05)
    d.text(s, 2.9, 5.72, 2.5, 0.3, "分析结果：摘要 + 待办清单", size=10, color=MUTED)
    d.bullets(s, 6.0, 1.7, 6.4, 4.8, [
        ("链路：", "AiSettings 配置 → AiClient 调 OpenAI 兼容接口 → AiResultParser 解析。"),
        ("离线兜底：", "未配置或请求失败时，LocalNoteAnalyzer 用规则提取摘要与待办。"),
        ("安全：", "API Key 只存本机、不入数据库；清空入口一键移除。"),
        ("可控：", "分析结果先预览，用户确认后才写入便签正文。"),
        ("工程化：", "解析与离线规则是纯 Java 类，16 条单测覆盖格式与边界。"),
    ], size=12, gap=11)
    d.footer(s)
    d.notes(s, "这一项是加分项。重点讲三件事：可离线、可回退、确认后才落地。"
               "大模型接口是可选项，不配置也能用。")


def class_diagram_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "05 设计", "维护后架构与类设计", "新增类绿色、重构类橙色，分层不变")
    d.picture(s, str(ASSETS / "class_after.png"), MARGIN, 1.72, w=11.9)
    d.text(s, MARGIN, 6.28, 11.9, 0.55,
           "新增 13 个类全部落在既有分包内：data（ProviderSql）· tool（备份、提醒、AI）"
           "· ui（回收站、AI 配置）；未引入新模块，未改动分层。",
           size=10.5, color=MUTED, line_spacing=1.3)
    d.footer(s)
    d.notes(s, "设计原则一句话：维护不另起炉灶，在原有分包里扩展；"
               "新增类都是单一职责的小类，纯逻辑类不依赖 Android 框架，所以能单测。")


def verify_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "05 验证", "质量验证与成果演示", "全部数字可追溯到脚本与扫描报告")
    stats = [("32", "条单测", "全部通过", TEAL),
             ("5.1% → 21.1%", "注释密度", "标注前后", GREEN),
             ("51 → 40", "Sonar 漏洞", "CRITICAL 2 → 0", "#B45309"),
             ("D → C", "安全评级", "修复 S5322/S5324", PURPLE),
             ("2.9% → 2.3%", "重复行密度", "新增代码无复制膨胀", TEAL)]
    x = MARGIN
    for value, label, note, color in stats:
        d.rect(s, x, 1.86, 2.3, 2.0, fill=CARD, line=LINE, radius=0.06)
        d.text(s, x + 0.24, 2.06, 2.0, 0.62, value, size=20, bold=True,
               color=color, font="Consolas")
        d.text(s, x + 0.24, 2.78, 2.0, 0.26, label, size=11, bold=True, color=NAVY)
        d.text(s, x + 0.24, 3.08, 2.0, 0.5, note, size=10, color=MUTED)
        x += 2.44
    d.rect(s, MARGIN, 4.3, 11.9, 2.0, fill=SOFT, line=LINE, radius=0.06)
    d.text(s, MARGIN + 0.35, 4.5, 11.2, 0.3, "演示：25 秒连续操作（录屏）",
           size=11.5, bold=True, color=TEAL_DARK)
    d.text(s, MARGIN + 0.35, 4.88, 11.2, 1.3,
           "新建便签 → 设置提醒 → 到点通知 → 删除进回收站 → 恢复 → 导出/备份 → AI 摘要",
           size=12, color=NAVY)
    d.text(s, MARGIN + 0.35, 5.55, 11.2, 0.6,
           "Android Lint 0 Error / 174 Warning；截图与日志在 logs/，"
           "Sonar/Lint 原始报告在 docs/analysis/，统计脚本在 tools/，可现场复跑。",
           size=10, color=MUTED)
    d.footer(s)
    d.notes(s, "五个数字按顺序报完就进入录屏，不逐条解释；"
               "如果老师问口径，指向 tools/ 脚本和 Sonar 的 JSON 原始输出。")


def closing_page(d: Deck) -> None:
    s = d.slide(NAVY)
    d.circle(s, -2.2, 4.1, 6.8, fill=TEAL, alpha=5)
    d.text(s, MARGIN, 1.45, 8.0, 0.85, "总结与分工", size=36, bold=True, color=WHITE)
    d.rect(s, MARGIN, 2.4, 0.9, 0.055, fill=TEAL, shape=MSO_SHAPE.RECTANGLE)
    d.bullets(s, MARGIN, 2.75, 5.6, 3.6, [
        ("袁晨昕（组长）：", "架构与 M1–M3、标注、报告"),
        ("刘嘉：", "M4 提醒重构、单测与设备回归"),
        ("罗奕峰：", "M5 回收站、M6 AI 拓展、演示"),
    ], size=12.5, gap=12, bullet_color=TEAL, color="FFFFFF")
    d.text(s, 6.6, 1.5, 5.9, 0.3, "仓库与文档", size=11.5, bold=True, color=CYAN)
    d.bullets(s, 6.6, 1.9, 5.9, 3.2, [
        "master / develop / 三个成员分支，13 次提交",
        "6 项维护共 +2,836 / −1,045 行，25 个文件",
        "报告、UML、扫描原始数据随交付包提供",
    ], size=12, gap=10, bullet_color=CYAN, color="D6E8EE")
    d.text(s, 6.6, 4.6, 5.9, 1.4,
           "局限：gtask 退役包 36 个旧问题保留未修；Lint 剩余 174 个资源类 Warning "
           "未逐条处理；AI 仅支持 OpenAI 兼容接口。",
           size=10.5, color="9FC7D2", line_spacing=1.3)
    d.text(s, MARGIN, 6.55, 9.0, 0.4, "谢谢，请老师提问", size=13, color="9FC7D2")
    d.footer(s, dark=True)
    d.notes(s, "最后 30 秒讲分工与局限；局限主动说出来，比被问到好。")


def build(out: Path) -> Path:
    d = Deck(title="小米便签开源软件维护", footer_text=FOOTER,
             section_names=("背景", "标注", "修复", "拓展", "验证"))
    cover(d)
    arch_page(d)
    annotation_page(d)
    modernization_page(d)
    provider_page(d)
    reminder_trash_page(d)
    ai_page(d)
    class_diagram_page(d)
    verify_page(d)
    closing_page(d)
    path = d.save(out)
    print(f"built: {path} ({len(d.prs.slides)} slides)")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the deck")
    parser.add_argument("-o", "--output",
                        default=str(HERE / "小米便签开源软件维护.pptx"))
    args = parser.parse_args(argv)
    build(Path(args.output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
