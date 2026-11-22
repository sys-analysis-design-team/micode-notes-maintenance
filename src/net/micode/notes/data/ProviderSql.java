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

package net.micode.notes.data;

import java.util.List;

/**
 * 组装 ContentProvider 的 WHERE 片段与绑定参数。
 *
 * <p>不依赖 Android API，方便在 JVM 上做单元测试；所有外部值都只进入参数列表，
 * 不拼进 SQL 文本。</p>
 */
final class ProviderSql {

    private ProviderSql() {
    }

    /**
     * 用括号追加调用方条件，保证 "A AND B OR C" 不会改变原有优先级。
     */
    static String appendSelection(String base, String selection) {
        return isEmpty(selection) ? base : base + " AND (" + selection + ')';
    }

    /**
     * 单条记录的条件：主键使用 {@code ?} 占位符。
     */
    static String buildItemSelection(String idColumn, String selection) {
        return appendSelection(idColumn + "=?", selection);
    }

    /**
     * 把主键值放到参数列表首位，再拼接调用方参数。
     */
    static String[] mergeArgs(String id, String[] selectionArgs) {
        if (selectionArgs == null || selectionArgs.length == 0) {
            return new String[] { id };
        }
        String[] merged = new String[selectionArgs.length + 1];
        merged[0] = id;
        System.arraycopy(selectionArgs, 0, merged, 1, selectionArgs.length);
        return merged;
    }

    /**
     * 生成版本号自增语句，并把需要绑定的值写入 {@code outArgs}。
     *
     * <p>调用方参数只以 {@code ?} 的形式出现，不会再被替换进 SQL 文本。</p>
     */
    static String buildVersionIncrementSql(String table, String idColumn, String versionColumn,
            long id, String selection, String[] selectionArgs, List<String> outArgs) {
        StringBuilder sql = new StringBuilder(120);
        sql.append("UPDATE ").append(table)
                .append(" SET ").append(versionColumn)
                .append('=').append(versionColumn).append("+1");

        if (id > 0) {
            sql.append(" WHERE ").append(idColumn).append("=?");
            outArgs.add(String.valueOf(id));
            if (!isEmpty(selection)) {
                sql.append(" AND (").append(selection).append(')');
                addArgs(outArgs, selectionArgs);
            }
        } else if (!isEmpty(selection)) {
            sql.append(" WHERE (").append(selection).append(')');
            addArgs(outArgs, selectionArgs);
        }
        return sql.toString();
    }

    private static void addArgs(List<String> target, String[] values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            target.add(value);
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
