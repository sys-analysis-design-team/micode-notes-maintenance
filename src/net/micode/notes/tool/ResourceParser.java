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
import android.preference.PreferenceManager;

import net.micode.notes.R;
import net.micode.notes.ui.NotesPreferenceActivity;

/**
 * 颜色/字号索引到 drawable、style 资源的转换表。
 *
 * <p>职责：把数据库里存的“颜色序号、字号序号”换成资源 ID；同时提供默认背景色策略
 * （固定黄色或每次随机）。只做映射，不持有状态，也不读写数据库。</p>
 *
 * <p>架构位置：tool 层。{@link net.micode.notes.model.WorkingNote} 用它取编辑页背景与标题背景，
 * 列表适配器用它取列表项背景，桌面 Widget 用它取微件背景，
 * {@link net.micode.notes.ui.NoteEditActivity} 用它取字号样式。</p>
 *
 * <p>关键协作类：{@link NotesPreferenceActivity}（读取“随机背景色”偏好）、
 * 各 UI 适配器与 WidgetProvider。</p>
 */
public class ResourceParser {

    // 颜色索引即数组下标，顺序必须与下面各资源数组严格一致；数据库里存的就是这些值
    public static final int YELLOW           = 0;
    public static final int BLUE             = 1;
    public static final int WHITE            = 2;
    public static final int GREEN            = 3;
    public static final int RED              = 4;

    // 默认背景色：黄色（索引 0）
    public static final int BG_DEFAULT_COLOR = YELLOW;

    // 字号档位，对应 TEXTAPPEARANCE_RESOURCES 的下标
    public static final int TEXT_SMALL       = 0;
    public static final int TEXT_MEDIUM      = 1;
    public static final int TEXT_LARGE       = 2;
    public static final int TEXT_SUPER       = 3;

    // 默认字号：中号
    public static final int BG_DEFAULT_FONT_SIZE = TEXT_MEDIUM;

    /**
     * 编辑页背景资源：正文底色与标题栏底色各一套，按颜色索引取。
     */
    public static class NoteBgResources {
        // 数组顺序 = 颜色索引（黄、蓝、白、绿、红），调用方直接拿颜色 ID 当下标
        private final static int [] BG_EDIT_RESOURCES = new int [] {
            R.drawable.edit_yellow,
            R.drawable.edit_blue,
            R.drawable.edit_white,
            R.drawable.edit_green,
            R.drawable.edit_red
        };

        private final static int [] BG_EDIT_TITLE_RESOURCES = new int [] {
            R.drawable.edit_title_yellow,
            R.drawable.edit_title_blue,
            R.drawable.edit_title_white,
            R.drawable.edit_title_green,
            R.drawable.edit_title_red
        };

        /**
         * 正文背景 drawable；id 必须是 0~4 的颜色索引，越界会数组越界。
         */
        public static int getNoteBgResource(int id) {
            return BG_EDIT_RESOURCES[id];
        }

        /**
         * 标题栏背景 drawable；越界风险同上。
         */
        public static int getNoteTitleBgResource(int id) {
            return BG_EDIT_TITLE_RESOURCES[id];
        }
    }

    /**
     * 取新建便签的默认背景色索引。
     *
     * <p>偏好开启“每次随机”时在 0~4 中等概率取一个（Math.random 返回 [0,1)，
     * 乘数组长度取整不会越界）；否则固定返回 {@link #BG_DEFAULT_COLOR}。</p>
     *
     * @param context 用于读取默认 SharedPreferences
     */
    public static int getDefaultBgId(Context context) {
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                NotesPreferenceActivity.PREFERENCE_SET_BG_COLOR_KEY, false)) {
            return (int) (Math.random() * NoteBgResources.BG_EDIT_RESOURCES.length);
        } else {
            return BG_DEFAULT_COLOR;
        }
    }

    /**
     * 列表项背景：同一条便签处于首行/中间/末行/单行时用不同圆角资源，
     * 拼起来才是一块连续的卡片。文件夹单独用一种背景。
     */
    public static class NoteItemBgResources {
        // 四套数组都按颜色索引排列，顺序与 ResourceParser.YELLOW~RED 一致
        private final static int [] BG_FIRST_RESOURCES = new int [] {
            R.drawable.list_yellow_up,
            R.drawable.list_blue_up,
            R.drawable.list_white_up,
            R.drawable.list_green_up,
            R.drawable.list_red_up
        };

        private final static int [] BG_NORMAL_RESOURCES = new int [] {
            R.drawable.list_yellow_middle,
            R.drawable.list_blue_middle,
            R.drawable.list_white_middle,
            R.drawable.list_green_middle,
            R.drawable.list_red_middle
        };

        private final static int [] BG_LAST_RESOURCES = new int [] {
            R.drawable.list_yellow_down,
            R.drawable.list_blue_down,
            R.drawable.list_white_down,
            R.drawable.list_green_down,
            R.drawable.list_red_down,
        };

        private final static int [] BG_SINGLE_RESOURCES = new int [] {
            R.drawable.list_yellow_single,
            R.drawable.list_blue_single,
            R.drawable.list_white_single,
            R.drawable.list_green_single,
            R.drawable.list_red_single
        };

        /**
         * 首行项背景（只有上圆角）。
         */
        public static int getNoteBgFirstRes(int id) {
            return BG_FIRST_RESOURCES[id];
        }

        /**
         * 末行项背景（只有下圆角）。
         */
        public static int getNoteBgLastRes(int id) {
            return BG_LAST_RESOURCES[id];
        }

        /**
         * 单行项背景（四个圆角）。
         */
        public static int getNoteBgSingleRes(int id) {
            return BG_SINGLE_RESOURCES[id];
        }

        /**
         * 中间项背景（无圆角）。
         */
        public static int getNoteBgNormalRes(int id) {
            return BG_NORMAL_RESOURCES[id];
        }

        /**
         * 文件夹统一使用的列表背景，不区分颜色。
         */
        public static int getFolderBgRes() {
            return R.drawable.list_folder;
        }
    }

    /**
     * 桌面 Widget 背景资源：2x 与 4x 两种尺寸各一套，按颜色索引取。
     */
    public static class WidgetBgResources {
        // 2x 微件背景，顺序同样对应颜色索引
        private final static int [] BG_2X_RESOURCES = new int [] {
            R.drawable.widget_2x_yellow,
            R.drawable.widget_2x_blue,
            R.drawable.widget_2x_white,
            R.drawable.widget_2x_green,
            R.drawable.widget_2x_red,
        };

        /**
         * 2x 微件背景 drawable；id 为颜色索引 0~4。
         */
        public static int getWidget2xBgResource(int id) {
            return BG_2X_RESOURCES[id];
        }

        // 4x 微件背景，顺序同上
        private final static int [] BG_4X_RESOURCES = new int [] {
            R.drawable.widget_4x_yellow,
            R.drawable.widget_4x_blue,
            R.drawable.widget_4x_white,
            R.drawable.widget_4x_green,
            R.drawable.widget_4x_red
        };

        /**
         * 4x 微件背景 drawable；id 为颜色索引 0~4。
         */
        public static int getWidget4xBgResource(int id) {
            return BG_4X_RESOURCES[id];
        }
    }

    /**
     * 正文字号样式表：索引 0~3 对应小/中/大/超大。
     */
    public static class TextAppearanceResources {
        // 顺序必须与 TEXT_SMALL~TEXT_SUPER 常量一致
        private final static int [] TEXTAPPEARANCE_RESOURCES = new int [] {
            R.style.TextAppearanceNormal,
            R.style.TextAppearanceMedium,
            R.style.TextAppearanceLarge,
            R.style.TextAppearanceSuper
        };

        /**
         * 字号索引换 TextAppearance 样式资源。
         *
         * <p>边界：偏好里可能残留旧版本的越界索引，此时退回默认字号。注意兜底分支返回的
         * 是索引常量 {@link ResourceParser#BG_DEFAULT_FONT_SIZE}（值为 1）而不是 style
         * 资源 ID；正常调用方已先把索引钳制在合法范围，该分支基本不可达。只判上界，
         * 负数仍会数组越界。</p>
         */
        public static int getTexAppearanceResource(int id) {
            /**
             * HACKME: Fix bug of store the resource id in shared preference.
             * The id may larger than the length of resources, in this case,
             * return the {@link ResourceParser#BG_DEFAULT_FONT_SIZE}
             */
            if (id >= TEXTAPPEARANCE_RESOURCES.length) {
                return BG_DEFAULT_FONT_SIZE;
            }
            return TEXTAPPEARANCE_RESOURCES[id];
        }

        /**
         * 字号档位总数，供调用方做越界钳制。
         */
        public static int getResourcesSize() {
            return TEXTAPPEARANCE_RESOURCES.length;
        }
    }
}
