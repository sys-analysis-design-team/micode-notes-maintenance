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

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.preference.PreferenceManager;

import net.micode.notes.R;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 深色模式切换（M7）。
 *
 * <p>原始工程固定使用 Theme.Holo.Light，没有任何夜间资源。本类用代码级主题切换
 * 实现三态：跟随系统 / 浅色 / 深色。每个 Activity 在 super.onCreate() 之前调用
 * {@link #apply(Activity)}，由本类决定使用 {@code NoteTheme}（浅色）还是
 * {@code NoteTheme.Dark}（深色）。</p>
 *
 * <p>「跟随系统」读取当前 Configuration 的 night 掩码；系统切换深色模式会触发
 * 配置变化并重建 Activity，因此不需要额外注册监听器。</p>
 */
public final class ThemeHelper {

    /** 设置页主题选项的键；与 res/xml/preferences.xml 中的 pref_key_theme_mode 一致。 */
    public static final String KEY_THEME_MODE = "pref_key_theme_mode";

    /** 跟随系统深浅色（默认值）。 */
    public static final String MODE_FOLLOW_SYSTEM = "follow";

    /** 固定浅色。 */
    public static final String MODE_LIGHT = "light";

    /** 固定深色。 */
    public static final String MODE_DARK = "dark";

    /**
     * 记录每个 Activity 上一次实际应用的主题资源，用于回到前台时判断是否需要重建。
     * 弱引用键保证 Activity 销毁后不阻止回收。
     */
    private static final Map<Activity, Integer> sAppliedThemes =
            Collections.synchronizedMap(new WeakHashMap<Activity, Integer>());

    private ThemeHelper() {
    }

    /**
     * 给 Activity 设置主题，必须在 {@code super.onCreate} 之前调用。
     *
     * @param activity 当前 Activity，用于读取偏好并应用主题资源
     */
    public static void apply(Activity activity) {
        int themeResId = getThemeResId(activity);
        activity.setTheme(themeResId);
        sAppliedThemes.put(activity, themeResId);
    }

    /**
     * 在 Activity#onResume 中调用：用户可能在设置页切换了主题，此时必须重建
     * 返回栈里已经存在的界面，否则旧主题的 Activity 会继续显示。
     */
    public static void recreateIfThemeChanged(Activity activity) {
        Integer appliedThemeResId = sAppliedThemes.get(activity);
        if (appliedThemeResId != null && appliedThemeResId.intValue() != getThemeResId(activity)) {
            activity.recreate();
        }
    }

    /**
     * 按当前模式返回主题资源 ID。
     */
    public static int getThemeResId(Context context) {
        return isDarkMode(context) ? R.style.NoteTheme_Dark : R.style.NoteTheme;
    }

    /**
     * 当前是否应使用深色主题。
     */
    public static boolean isDarkMode(Context context) {
        String mode = getMode(context);
        if (MODE_DARK.equals(mode)) {
            return true;
        }
        if (MODE_LIGHT.equals(mode)) {
            return false;
        }
        int nightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 读取用户选择的主题模式。
     */
    public static String getMode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(KEY_THEME_MODE, MODE_FOLLOW_SYSTEM);
    }

    /**
     * 保存用户选择的主题模式。
     */
    public static void setMode(Context context, String mode) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(KEY_THEME_MODE, mode).apply();
    }
}
