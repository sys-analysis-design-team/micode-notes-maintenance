# 代码标注规范（子任务执行手册）

> 用途：给并行标注任务提供统一口径。任务书要求「阅读开源代码并对代码进行标注（即注释）」，
> 报告需按包/类统计「标注的代码行数」。

## 一、绝对约束

1. **只加注释，不改任何代码**：不改变量名、不改逻辑、不调整顺序、不删除既有注释、不加 import。
2. 注释一律使用简体中文；保留原有英文注释不动。
3. Javadoc 块（`/** ... */`）必须写在 `@Override`、`@SuppressWarnings` 等注解**之前**，缩进与成员一致。
4. 用 Edit 工具分段插入注释，禁止用 Write 重写整个文件（避免代码被无意改动）。
5. 不要格式化代码、不要统一空格、不要动行尾。
6. 文件编码 UTF-8（保持原样）。

## 二、四级标注口径

### 1. 类级（每个类必做）

类声明前加 Javadoc，三段式：

```
/**
 * 一句话职责。
 *
 * <p>职责：该类做什么、不做什么。</p>
 *
 * <p>架构位置：属于哪个包、在数据流中的位置、被谁调用/调用谁。</p>
 *
 * <p>关键协作类：{@link 类名}（为什么协作）。</p>
 */
```

### 2. 方法级（data / model / gtask.data / gtask.remote 全部方法；其他包 public 方法与关键私有方法）

方法签名前加 Javadoc：

```
/**
 * 一句话说明。
 *
 * @param xxx 含义与合法取值
 * @return 返回什么；什么情况返回 null/空
 *
 * <p>副作用：读写数据库、发通知、改静态状态、耗时 IO 等。</p>
 * <p>异常：抛什么、捕获什么、为什么。</p>
 */
```

### 3. 块级（数据库、Provider、WorkingNote、同步逻辑为主）

在逻辑块上方用 `//` 说明**为什么这样做**（而不是代码在做什么），例如事务边界、
游标关闭顺序、软删除标记、ID 映射、协议字段来源。

### 4. 语句级（样板类精读）

只对隐含约束/边界加行尾或上置 `//`：例如「null 表示未关联文件夹」「-3 是回收站
文件夹 ID」「此处必须先判空再 moveToFirst」。不做逐行翻译。

## 三、深度分档

| 档位 | 类 | 要求 |
|---|---|---|
| 深 | NotesDatabaseHelper、WorkingNote、SqlNote、GTaskManager | 类级 + 全部方法 + 关键块 + 语句级边界 |
| 中 | SqlData、Task、TaskList、Node、MetaData、Note、GTaskClient、GTaskSyncService、DateTimePicker、NoteItemData、NoteEditText、NotesListAdapter、NotesListItem、ResourceParser、NoteWidgetProvider | 类级 + 全部方法 + 关键块 |
| 浅 | 两个异常类、DropdownMenu、FoldersListAdapter、DateTimePickerDialog、NoteWidgetProvider_2x/_4x | 类级 + 主要方法 |

## 四、完成标准

- 每个类至少有类级 Javadoc。
- 深/中档类的每个方法都有方法级 Javadoc 或上置 `//` 说明。
- 注释能回答「为什么/边界在哪」，不是把方法名翻译一遍。
- 改完自检：`git diff <文件>` 中新增行全部以 `+` 开头且为注释，没有删除代码行。
