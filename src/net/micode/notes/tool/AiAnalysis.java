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
import java.util.Collections;
import java.util.List;

/**
 * M6：一次便签分析的结果。不可变，在线与离线路径共用。
 */
public final class AiAnalysis {
    private final String mSummary;
    private final List<String> mTodos;
    private final boolean mFromAi;

    public AiAnalysis(String summary, List<String> todos, boolean fromAi) {
        mSummary = summary == null ? "" : summary;
        List<String> copy = new ArrayList<String>();
        if (todos != null) {
            for (String todo : todos) {
                if (todo != null && todo.trim().length() > 0) {
                    copy.add(todo.trim());
                }
            }
        }
        mTodos = Collections.unmodifiableList(copy);
        mFromAi = fromAi;
    }

    public String getSummary() {
        return mSummary;
    }

    public List<String> getTodos() {
        return mTodos;
    }

    public boolean isFromAi() {
        return mFromAi;
    }

    public boolean isEmpty() {
        return mSummary.trim().length() == 0 && mTodos.isEmpty();
    }
}
