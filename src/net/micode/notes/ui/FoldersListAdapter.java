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
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;


/**
 * 批量移动便签时的“选择目标文件夹”列表适配器。
 *
 * <p>职责：把文件夹查询结果的 ID/SNIPPET 两列渲染成列表项；根文件夹特殊处理，
 * 显示为本地化的“根目录”文案，而不是内部存储名。不负责移动逻辑本身。</p>
 *
 * <p>架构位置：ui 包的 CursorAdapter，只在 NotesListActivity 的“移动到文件夹”对话框中使用；
 * 查询条件由 Activity 构造（排除当前文件夹与回收站，视场景补上根文件夹）。</p>
 *
 * <p>关键协作类：{@link NotesListActivity}（查询与调用方）、
 * {@link Notes}（根文件夹 ID 与类型常量）。</p>
 */
public class FoldersListAdapter extends CursorAdapter {
    // 只需 ID 与显示名两列；顺序必须与下面两个下标常量一致
    public static final String [] PROJECTION = {
        NoteColumns.ID,
        NoteColumns.SNIPPET
    };

    public static final int ID_COLUMN   = 0;
    public static final int NAME_COLUMN = 1;

    /**
     * 包装一次性查询结果。Cursor 由 Activity 的查询回调提供并由其管理，适配器不负责关闭。
     *
     * @param context 用于 inflate 行视图
     * @param c 文件夹查询结果，列集合必须是 {@link #PROJECTION}
     */
    public FoldersListAdapter(Context context, Cursor c) {
        super(context, c);
        // TODO Auto-generated constructor stub
    }

    /**
     * 创建文件夹行视图；行结构只有一个名称 TextView。
     */
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return new FolderListItem(context);
    }

    /**
     * 绑定文件夹名；根文件夹（ID 0）显示为本地化的“根目录”文案，避免暴露内部名称。
     */
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof FolderListItem) {
            String folderName = (cursor.getLong(ID_COLUMN) == Notes.ID_ROOT_FOLDER) ? context
                    .getString(R.string.menu_move_parent_folder) : cursor.getString(NAME_COLUMN);
            ((FolderListItem) view).bind(folderName);
        }
    }

    /**
     * 按位置取文件夹显示名，供移动成功提示等场景复用同一套根目录文案。
     *
     * @param context 用于取本地化文案
     * @param position 列表位置
     * @return 根文件夹返回“移动到根目录”文案，其余返回 snippet 列
     */
    public String getFolderName(Context context, int position) {
        Cursor cursor = (Cursor) getItem(position);
        return (cursor.getLong(ID_COLUMN) == Notes.ID_ROOT_FOLDER) ? context
                .getString(R.string.menu_move_parent_folder) : cursor.getString(NAME_COLUMN);
    }

    /**
     * 文件夹选择列表的单行视图：只包含一个名称 TextView，仅本适配器使用。
     */
    private class FolderListItem extends LinearLayout {
        private TextView mName;

        /**
         * inflate folder_list_item 并缓存名称控件。
         */
        public FolderListItem(Context context) {
            super(context);
            inflate(context, R.layout.folder_list_item, this);
            mName = (TextView) findViewById(R.id.tv_folder_name);
        }

        /**
         * @param name 已解析好的显示名（可能是根目录文案）
         */
        public void bind(String name) {
            mName.setText(name);
        }
    }

}
