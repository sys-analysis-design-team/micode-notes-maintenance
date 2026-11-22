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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * M6：解析大模型返回的 JSON。
 *
 * <p>模型经常在 JSON 外包一层 Markdown 代码块或说明文字，这里先截取第一个
 * <code>{</code> 到最后一个 <code>}</code>，再做严格字段校验。解析失败一律抛
 * {@link AiFormatException}，由上层退回离线分析。</p>
 */
public final class AiResultParser {
    private static final int MAX_SUMMARY_LENGTH = 200;
    private static final int MAX_TODOS = 5;
    private static final int MAX_TODO_LENGTH = 80;

    private AiResultParser() {
    }

    public static AiAnalysis parse(String raw) throws AiFormatException {
        if (raw == null || raw.trim().length() == 0) {
            throw new AiFormatException("模型返回为空");
        }
        String json = extractJsonObject(raw);
        JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (JSONException e) {
            throw new AiFormatException("模型返回不是合法 JSON", e);
        }
        String summary = object.optString("summary", "").trim();
        if (summary.length() == 0) {
            throw new AiFormatException("模型返回缺少摘要");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_SUMMARY_LENGTH) + "…";
        }
        List<String> todos = new ArrayList<String>();
        JSONArray array = object.optJSONArray("todos");
        if (array != null) {
            HashSet<String> seen = new HashSet<String>();
            for (int i = 0; i < array.length() && todos.size() < MAX_TODOS; i++) {
                String todo = cleanTodo(array.optString(i, ""));
                if (todo.length() == 0 || !seen.add(todo)) {
                    continue;
                }
                if (todo.length() > MAX_TODO_LENGTH) {
                    todo = todo.substring(0, MAX_TODO_LENGTH) + "…";
                }
                todos.add(todo);
            }
        }
        return new AiAnalysis(summary, todos, true);
    }

    private static String extractJsonObject(String raw) throws AiFormatException {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiFormatException("模型返回中没有 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    private static String cleanTodo(String value) {
        String todo = value == null ? "" : value.trim();
        while (todo.length() > 0) {
            char first = todo.charAt(0);
            if (first == '-' || first == '•' || first == '□' || first == '√'
                    || first == '*' || first == ' ') {
                todo = todo.substring(1).trim();
                continue;
            }
            break;
        }
        // 去掉「1.」「2、」这类序号。
        int index = 0;
        while (index < todo.length() && Character.isDigit(todo.charAt(index))) {
            index++;
        }
        if (index > 0 && index < todo.length()) {
            char mark = todo.charAt(index);
            if (mark == '.' || mark == '、' || mark == ')' || mark == '）') {
                todo = todo.substring(index + 1).trim();
            }
        }
        return todo;
    }
}
