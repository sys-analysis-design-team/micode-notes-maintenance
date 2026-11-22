/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * M6：离线兜底分析。
 *
 * <p>没有 Key、断网、超时或模型返回坏 JSON 时使用。规则刻意保持简单可解释：
 * 摘要取前两行有效内容，待办取未勾选清单项和含行动词的句子。</p>
 */
public final class LocalNoteAnalyzer {
    private static final int MAX_SUMMARY_LENGTH = 80;
    private static final int MAX_TODOS = 5;
    private static final int MAX_TODO_LENGTH = 80;

    private static final String[] TODO_KEYWORDS = {
            "TODO", "todo", "待办", "记得", "需要", "别忘", "买", "带", "交",
            "写", "发", "复习", "准备", "提交", "预约", "联系", "回复", "完成",
            "缴", "取", "整理", "学习", "阅读"
    };

    private LocalNoteAnalyzer() {
    }

    public static AiAnalysis analyze(String content) {
        if (content == null || content.trim().length() == 0) {
            return new AiAnalysis("", new ArrayList<String>(), false);
        }
        String[] lines = content.split("\\r?\\n");
        List<String> summaryParts = new ArrayList<String>();
        List<String> todos = new ArrayList<String>();
        HashSet<String> seenTodos = new HashSet<String>();

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.length() == 0) {
                continue;
            }
            String cleaned = cleanLine(line);
            if (cleaned.length() == 0) {
                continue;
            }
            if (summaryParts.size() < 2) {
                summaryParts.add(cleaned);
            }
            if (isChecked(line) || !isTodo(line, cleaned) || !seenTodos.add(cleaned)) {
                continue;
            }
            if (todos.size() < MAX_TODOS) {
                todos.add(truncate(cleaned, MAX_TODO_LENGTH));
            }
        }

        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < summaryParts.size(); i++) {
            if (i > 0) {
                summary.append("；");
            }
            summary.append(summaryParts.get(i));
        }
        return new AiAnalysis(truncate(summary.toString(), MAX_SUMMARY_LENGTH), todos, false);
    }

    private static boolean isChecked(String line) {
        return line.startsWith("√") || line.startsWith("- [x]") || line.startsWith("- [X]")
                || line.startsWith("[x]") || line.startsWith("[X]");
    }

    private static boolean isTodo(String line, String cleaned) {
        if (line.startsWith("□") || line.startsWith("- [ ]") || line.startsWith("[ ]")) {
            return true;
        }
        for (String keyword : TODO_KEYWORDS) {
            if (cleaned.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String cleanLine(String line) {
        String text = line.trim();
        String[] prefixes = { "□", "√", "- [ ]", "- [x]", "- [X]", "[ ]", "[x]", "[X]", "-", "*" };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length()).trim();
                    changed = true;
                }
            }
        }
        int index = 0;
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        if (index > 0 && index < text.length()) {
            char mark = text.charAt(index);
            if (mark == '.' || mark == '、' || mark == ')' || mark == '）') {
                text = text.substring(index + 1).trim();
            }
        }
        return text;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }
}
