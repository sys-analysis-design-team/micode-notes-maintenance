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

package net.micode.notes.widget;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;
import android.widget.RemoteViews;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NoteEditActivity;
import net.micode.notes.ui.NotesListActivity;

/**
 * 桌面便签微件的通用基类：把一条便签的摘要与点击跳转渲染成 RemoteViews。
 *
 * <p>职责：处理微件的删除与刷新。刷新时按 widgetId 找到绑定的便签，取摘要与背景色，
 * 组装 RemoteViews 交回 AppWidgetManager；没有绑定便签（未创建、已移动或已进回收站）时
 * 显示“没有关联内容”并点击进入新建/编辑页。不负责建立绑定关系——绑定由编辑页保存时
 * 写回 note 表。</p>
 *
 * <p>架构位置：widget 层。App 内 Activity 通过向具体子类（{@link NoteWidgetProvider_2x} /
 * {@link NoteWidgetProvider_4x}）发送 ACTION_APPWIDGET_UPDATE 广播间接触发刷新；
 * 系统在微件被添加或周期更新时调用 onUpdate。两个子类只提供布局、背景资源与尺寸类型。</p>
 *
 * <p>关键协作类：{@link NoteEditActivity}（点击微件后的编辑入口）、
 * {@link NotesListActivity}（访客模式的列表入口）、
 * {@link ResourceParser.WidgetBgResources}（微件背景换算）。</p>
 */
public abstract class NoteWidgetProvider extends AppWidgetProvider {
    // 只需 ID（跳转）、背景色（渲染）、摘要（显示）三列，避免拉整行
    public static final String [] PROJECTION = new String [] {
        NoteColumns.ID,
        NoteColumns.BG_COLOR_ID,
        NoteColumns.SNIPPET
    };

    // 投影列下标，顺序必须与 PROJECTION 一致
    public static final int COLUMN_ID           = 0;
    public static final int COLUMN_BG_COLOR_ID  = 1;
    public static final int COLUMN_SNIPPET      = 2;

    private static final String TAG = "NoteWidgetProvider";

    /**
     * 微件被移除时回调：把绑定了这些微件 ID 的便签的 widget_id 清成 INVALID，解除关联。
     *
     * <p>只清绑定关系，便签本身保留；直接按 widget_id 批量 update，不先查再逐条改，
     * 即使便签已被移到别处也只需一条 SQL。</p>
     *
     * @param appWidgetIds 被移除的微件 ID 数组；空数组时循环不执行
     */
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        ContentValues values = new ContentValues();
        values.put(NoteColumns.WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        for (int i = 0; i < appWidgetIds.length; i++) {
            context.getContentResolver().update(Notes.CONTENT_NOTE_URI,
                    values,
                    NoteColumns.WIDGET_ID + "=?",
                    new String[] { String.valueOf(appWidgetIds[i])});
        }
    }

    /**
     * 查询绑定到指定微件的便签行。
     *
     * <p>条件排除回收站文件夹（-3）：便签被移入回收站后按“微件没有内容”处理，
     * 不再显示已删除的摘要。查询不区分 widget_type，同一 widgetId 理论上只应查到一条；
     * 出现多条由 update 按数据异常处理。</p>
     *
     * @return 游标由调用方负责关闭；无匹配时通常返回非 null 的空游标
     */
    private Cursor getNoteWidgetInfo(Context context, int widgetId) {
        return context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                PROJECTION,
                NoteColumns.WIDGET_ID + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(widgetId), String.valueOf(Notes.ID_TRASH_FOLER) },
                null);
    }

    /**
     * 子类 onUpdate 的统一入口；固定以非访客模式刷新。
     */
    protected void update(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        update(context, appWidgetManager, appWidgetIds, false);
    }

    /**
     * 遍历给定的微件 ID，逐个重建 RemoteViews 并提交给 AppWidgetManager。
     *
     * @param appWidgetIds 系统或广播传入的微件 ID；INVALID_APPWIDGET_ID(0) 会被跳过
     * @param privacyMode true 时进入访客模式，只显示占位文案、点击进便签列表而不是编辑页；
     *        当前 App 内没有调用方传 true，属于历史预留分支
     *
     * <p>边界：同一 widgetId 查到多条便签时记错误并直接 return，不仅跳过当前微件，
     * 还会中断数组中剩余微件的刷新。</p>
     *
     * <p>副作用：读取 Provider、设置 PendingIntent、调用 updateAppWidget 触发桌面重绘。</p>
     */
    private void update(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds,
            boolean privacyMode) {
        for (int i = 0; i < appWidgetIds.length; i++) {
            // 0 不是有效微件 ID；系统正常情况下不会传，这里只做防御
            if (appWidgetIds[i] != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int bgId = ResourceParser.getDefaultBgId(context);
                String snippet = "";
                // 先按“编辑页”构造点击意图，查到关联便签后再改成查看模式
                Intent intent = new Intent(context, NoteEditActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra(Notes.INTENT_EXTRA_WIDGET_ID, appWidgetIds[i]);
                intent.putExtra(Notes.INTENT_EXTRA_WIDGET_TYPE, getWidgetType());

                // 一次查询同时完成“有没有绑定”判断与渲染数据获取，避免两次 Provider 往返
                Cursor c = getNoteWidgetInfo(context, appWidgetIds[i]);
                if (c != null && c.moveToFirst()) {
                    // 一个微件理论上只绑一条便签；多条属于数据异常，放弃本轮刷新
                    if (c.getCount() > 1) {
                        Log.e(TAG, "Multiple message with same widget id:" + appWidgetIds[i]);
                        c.close();
                        return;
                    }
                    snippet = c.getString(COLUMN_SNIPPET);
                    bgId = c.getInt(COLUMN_BG_COLOR_ID);
                    intent.putExtra(Intent.EXTRA_UID, c.getLong(COLUMN_ID));
                    intent.setAction(Intent.ACTION_VIEW);
                } else {
                    // 没有关联便签（未创建、已移动或已进回收站）：显示占位文案，点击进入新建
                    snippet = context.getResources().getString(R.string.widget_havenot_content);
                    intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
                }

                if (c != null) {
                    c.close();
                }

                // 每次刷新都新建 RemoteViews：布局由子类决定，背景与文案按查询结果填充
                RemoteViews rv = new RemoteViews(context.getPackageName(), getLayoutId());
                rv.setImageViewResource(R.id.widget_bg_image, getBgResourceId(bgId));
                intent.putExtra(Notes.INTENT_EXTRA_BACKGROUND_ID, bgId);
                // requestCode 用微件 ID，保证多个微件的 PendingIntent 不互相覆盖；
                // FLAG_IMMUTABLE 是 Android 12+ 对显式 PendingIntent 的强制要求
                /**
                 * Generate the pending intent to start host for the widget
                 */
                PendingIntent pendingIntent = null;
                if (privacyMode) {
                    // 访客模式不暴露便签内容，点击只进列表
                    rv.setTextViewText(R.id.widget_text,
                            context.getString(R.string.widget_under_visit_mode));
                    pendingIntent = PendingIntent.getActivity(context, appWidgetIds[i], new Intent(
                            context, NotesListActivity.class),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                } else {
                    rv.setTextViewText(R.id.widget_text, snippet);
                    pendingIntent = PendingIntent.getActivity(context, appWidgetIds[i], intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                }

                // 整个文案区域可点：有便签进编辑页，无便签进新建流程
                rv.setOnClickPendingIntent(R.id.widget_text, pendingIntent);
                appWidgetManager.updateAppWidget(appWidgetIds[i], rv);
            }
        }
    }

    /**
     * 子类提供颜色索引到微件背景 drawable 的映射。
     */
    protected abstract int getBgResourceId(int bgId);

    /**
     * 子类提供微件布局（widget_2x / widget_4x）。
     */
    protected abstract int getLayoutId();

    /**
     * 子类返回尺寸类型，写进启动编辑页的 extra，编辑页据此回写绑定关系。
     */
    protected abstract int getWidgetType();
}
