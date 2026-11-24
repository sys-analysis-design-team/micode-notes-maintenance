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

package net.micode.notes.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.view.MenuItem;

import net.micode.notes.R;

/**
 * 设置页。
 *
 * <p>维护说明：GTask 同步依赖的 Google Tasks 服务与旧版 Apache HttpClient 均已不可用，
 * 维护版本将同步功能整体退役（见 docs/PLAN.md 的 M1）。本页只保留外观相关设置，
 * 不再展示账号选择与同步按钮。</p>
 */
public class NotesPreferenceActivity extends PreferenceActivity {
    public static final String PREFERENCE_NAME = "notes_preferences";

    /** 旧版同步账号键；仅用于清理残留数据，不再读写其值。 */
    public static final String PREFERENCE_SYNC_ACCOUNT_NAME = "pref_key_account_name";

    public static final String PREFERENCE_LAST_SYNC_TIME = "pref_last_sync_time";

    public static final String PREFERENCE_SET_BG_COLOR_KEY = "pref_key_bg_random_appear";

    @Override
    protected void onCreate(Bundle icicle) {
        ThemeHelper.apply(this);
        super.onCreate(icicle);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        addPreferencesFromResource(R.xml.preferences);
        clearLegacySyncAccount(this);
        bindThemePreference();
    }

    /**
     * M7 深色模式：主题切换后重建设置页，让选择立即生效。
     */
    private void bindThemePreference() {
        Preference themePreference = findPreference(ThemeHelper.KEY_THEME_MODE);
        if (themePreference == null) {
            return;
        }
        themePreference.setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        // ListPreference 在回调返回后才持久化，这里先写入，保证重建时读到新值
                        ThemeHelper.setMode(NotesPreferenceActivity.this, (String) newValue);
                        recreate();
                        return true;
                    }
                });
    }

    /**
     * 同步已退役，同步模式恒为关闭。
     *
     * <p>保留原方法签名，调用方（便签列表、编辑页）无需改动即可保持本地模式语义。</p>
     */
    public static String getSyncAccountName(Context context) {
        return "";
    }

    public static void setLastSyncTime(Context context, long time) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        settings.edit().putLong(PREFERENCE_LAST_SYNC_TIME, time).apply();
    }

    public static long getLastSyncTime(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getLong(PREFERENCE_LAST_SYNC_TIME, 0);
    }

    /**
     * 清理旧版本残留的同步账号，避免升级用户仍被判定为同步模式。
     */
    private static void clearLegacySyncAccount(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        if (settings.contains(PREFERENCE_SYNC_ACCOUNT_NAME)) {
            settings.edit().remove(PREFERENCE_SYNC_ACCOUNT_NAME).apply();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            Intent intent = new Intent(this, NotesListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
