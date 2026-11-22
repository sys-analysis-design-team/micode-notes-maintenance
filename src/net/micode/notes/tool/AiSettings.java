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

import android.content.Context;
import android.content.SharedPreferences;

/**
 * M6：AI 接口配置。
 *
 * <p>Key 只写入应用私有的 SharedPreferences，不进入便签 SQLite 数据库，
 * 也不会出现在日志或备份 JSON 中。</p>
 */
public final class AiSettings {
    public static final String PREF_NAME = "ai_settings";
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "api_key";

    private final String mEndpoint;
    private final String mModel;
    private final String mApiKey;

    public AiSettings(String endpoint, String model, String apiKey) {
        mEndpoint = endpoint == null ? "" : endpoint.trim();
        mModel = model == null ? "" : model.trim();
        mApiKey = apiKey == null ? "" : apiKey.trim();
    }

    public static AiSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return new AiSettings(
                prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT),
                prefs.getString(KEY_MODEL, DEFAULT_MODEL),
                prefs.getString(KEY_API_KEY, ""));
    }

    public void save(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENDPOINT, mEndpoint)
                .putString(KEY_MODEL, mModel)
                .putString(KEY_API_KEY, mApiKey)
                .apply();
    }

    public static void clearApiKey(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_API_KEY)
                .apply();
    }

    public String getEndpoint() {
        return mEndpoint;
    }

    public String getModel() {
        return mModel;
    }

    public String getApiKey() {
        return mApiKey;
    }

    public boolean isConfigured() {
        return (mEndpoint.startsWith("https://") || mEndpoint.startsWith("http://"))
                && mModel.length() > 0 && mApiKey.length() > 0;
    }
}
