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
           "泛读 · 标注 · 维护：39 个文件 / 10,001 行，9 项维护 + 团队新功能",
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
    d.header(s, "04 拓展", "提醒、通知与可恢复删除", "M4 提醒重构 · M9 重复提醒 · M5 回收站")
    d.picture_card(s, str(ASSETS / "shot_notification.png"), MARGIN, 1.6, w=2.1)
    d.text(s, MARGIN, 5.86, 2.5, 0.5, "M4 通知：渠道 + 动作 + 点击回到便签",
           size=10, color=MUTED, line_spacing=1.2)
    d.bullets(s, 3.55, 1.7, 4.6, 4.7, [
        ("M4 提醒重构：", "精确闹钟权限适配；通知渠道与动作按钮；开机重排全部提醒。"),
        ("M9 重复提醒：", "每天 / 每周 / 工作日三档，到点自动推进下一次；"
                    "改系统时间的补偿路径做了幂等，不会一次跳两天。"),
        ("M5 回收站：", "删除改为软删除；支持恢复、彻底删除、清空回收站。"),
        ("文件夹保护：", "删除文件夹时按层级进入回收站，恢复后回到原位置。"),
    ], size=11.5, gap=10)
    d.picture_card(s, str(ASSETS / "shot_trash.png"), 8.55, 1.6, w=2.1)
    d.text(s, 8.55, 5.86, 2.5, 0.5, "M5 回收站：恢复 / 彻底删除 / 清空",
           size=10, color=MUTED, line_spacing=1.2)
    d.footer(s)
    d.notes(s, "提醒这块的坑在 Android 12+ 的精确闹钟和 13+ 的通知权限，"
               "都做了降级路径；M9 在真机拨时钟测试时抓到一个双推进竞态——"
               "系统改时间的补偿和过期闹钟各推进一步，一次跳掉一整天，"
               "靠幂等判断修掉。回收站是软删除，不改变原有列表查询逻辑。")


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


def experience_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "04 拓展", "体验增强与团队新功能", "深色模式 / 便签置顶 / 成员分支开发中")
    d.picture_card(s, str(ASSETS / "shot_dark.png"), MARGIN, 1.68, w=2.0)
    d.text(s, MARGIN, 5.92, 2.0, 0.3, "M7 深色模式：三态切换",
           size=9, color=MUTED, line_spacing=1.2)
    d.picture_card(s, str(ASSETS / "shot_pinned.png"), 2.76, 1.68, w=2.0)
    d.text(s, 2.76, 5.92, 2.0, 0.3, "M8 置顶：图钉标记，排序靠前",
           size=9, color=MUTED, line_spacing=1.2)
    d.bullets(s, 5.02, 1.78, 2.9, 4.4, [
        ("M7 深色模式：", "跟随系统 / 浅色 / 深色三态；只改窗口与控件，便签纸保持浅色。"),
        ("M8 便签置顶：", "多选批量置顶；置顶状态只存本机，不进备份 JSON。"),
    ], size=11, gap=14)
    d.rect(s, 8.1, 1.6, 4.62, 4.9, fill=SOFT, line=LINE, radius=0.06)
    d.text(s, 8.4, 1.78, 4.0, 0.3, "团队新功能（成员分支开发中）",
           size=11.5, bold=True, color=TEAL_DARK)
    d.text(s, 8.4, 2.12, 4.0, 0.3, "两位成员各提 1–2 个，与 M1–M9 不重复",
           size=9.5, color=MUTED)
    y = 2.6
    for name in ("刘嘉", "罗奕峰"):
        d.rect(s, 8.4, y, 4.02, 1.8, fill=CARD, line=LINE, radius=0.08)
        d.text(s, 8.62, y + 0.14, 1.5, 0.3, name, size=11, bold=True, color=NAVY)
        d.chip(s, 11.15, y + 0.16, 1.05, 0.3, "待提交", size=9)
        d.text(s, 8.62, y + 0.62, 3.6, 0.95, "功能名 + 一句话说明（做什么 + 怎么做）",
               size=9.5, color=MUTED, line_spacing=1.3)
        y += 1.95
    d.footer(s)
    d.notes(s, "M7/M8 是组长这一轮加的体验项：深色模式按三态切换，但便签纸刻意保持浅色，"
               "因为纸张质感是这个产品的特征；置顶是本地 UI 状态，不进备份。"
               "右侧两位成员的新功能正在各自分支上开发，提交后把卡片换成实际功能名与一句话。"
               "【待更新】成员功能确定后，更新本页两个占位卡与第 10 页分工。")


def class_diagram_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "05 设计", "维护后架构与类设计", "新增类绿色、重构类橙色，分层不变")
    d.picture(s, str(ASSETS / "class_after.png"), 0.97, 1.68, w=11.4)
    d.text(s, MARGIN, 6.30, 12.09, 0.55,
           "新增 15 个类全部落在既有分包内：data（ProviderSql）· tool（备份、提醒、AI、重复规则）"
           "· ui（回收站、AI 配置、主题）；未引入新模块，未改动分层。"
           "M7–M9 的 ThemeHelper 与 ReminderRepeats 已并入类图。",
           size=10.5, color=MUTED, line_spacing=1.3)
    d.footer(s)
    d.notes(s, "设计原则一句话：维护不另起炉灶，在原有分包里扩展；"
               "新增类都是单一职责的小类，纯逻辑类不依赖 Android 框架，所以能单测。")


def closeout_page(d: Deck) -> None:
    s = d.slide()
    d.header(s, "05 收束", "验证与团队分工", "全部数字可追溯到脚本与扫描报告")
    stats = [("40", "条单测", "全部通过", TEAL),
             ("5.1% → 21.4%", "注释密度", "标注前后", GREEN),
             ("51 → 40", "Sonar 漏洞", "CRITICAL 2 → 0", "#B45309"),
             ("D → C", "安全评级", "修复 S5322/S5324", PURPLE),
             ("2.9% → 2.2%", "重复行密度", "新增代码无复制膨胀", TEAL)]
    x = MARGIN
    for value, label, note, color in stats:
        d.rect(s, x, 1.62, 2.3, 1.66, fill=CARD, line=LINE, radius=0.06)
        d.text(s, x + 0.22, 1.76, 2.0, 0.56, value, size=19, bold=True,
               color=color, font="Consolas")
        d.text(s, x + 0.22, 2.4, 2.0, 0.26, label, size=10.5, bold=True, color=NAVY)
        d.text(s, x + 0.22, 2.7, 2.0, 0.45, note, size=9.5, color=MUTED)
        x += 2.44
    d.rect(s, MARGIN, 3.44, 12.09, 0.86, fill=SOFT, line=LINE, radius=0.08)
    d.text(s, MARGIN + 0.3, 3.54, 3.4, 0.3, "演示：25 秒连续操作（录屏）",
           size=10.5, bold=True, color=TEAL_DARK)
    d.text(s, MARGIN + 0.3, 3.88, 11.4, 0.3,
           "新建便签 → 设提醒 → 到点通知 → 回收站恢复 → 导出/备份 → AI 摘要",
           size=10.5, color=NAVY)
    d.text(s, MARGIN, 4.52, 5.7, 0.3, "团队分工", size=11.5, bold=True,
           color=TEAL_DARK)
    d.bullets(s, MARGIN, 4.86, 5.7, 1.5, [
        ("袁晨昕（组长）：", "M1–M9 维护代码、标注、PPT 与汇报"),
        ("刘嘉：", "质量分析报告、成员分支新功能"),
        ("罗奕峰：", "UML 图、成员分支新功能"),
    ], size=10.5, gap=6)
    d.text(s, 6.6, 4.52, 6.1, 0.3, "局限与仓库", size=11.5, bold=True,
           color=TEAL_DARK)
    d.text(s, 6.6, 4.86, 6.1, 1.7,
           "gtask 退役包 36 个旧问题保留未修；Lint 剩余 178 个资源类 Warning 未逐条处理；"
           "AI 仅支持 OpenAI 兼容接口。\n"
           "9 项维护 +3,451 / −1,068 行、33 个文件；develop 领先 master 24 个提交；"
           "报告、UML、扫描原始数据随交付包提供。",
           size=9.5, color=MUTED, line_spacing=1.35)
    d.text(s, MARGIN, 6.6, 9.0, 0.35, "谢谢，请老师提问", size=12.5, color=TEAL_DARK)
    d.footer(s)
    d.notes(s, "五个数字按顺序报完就进入录屏，不逐条解释；"
               "如果老师问口径，指向 tools/ 脚本和 Sonar 的 JSON 原始输出。"
               "最后 30 秒讲分工与局限；局限主动说出来，比被问到好。"
               "【待更新】成员功能提交后，分工与数字需同步（Sonar/Lint 重扫、维护代码量重跑）。")


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
    experience_page(d)
    class_diagram_page(d)
    closeout_page(d)
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
