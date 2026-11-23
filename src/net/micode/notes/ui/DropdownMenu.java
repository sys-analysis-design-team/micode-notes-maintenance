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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;

import net.micode.notes.R;

/**
 * 把 Button + PopupMenu 封装成下拉菜单：按钮作为锚点，点击弹出菜单。
 *
 * <p>职责：初始化按钮外观、创建并 inflate 菜单、把按钮点击转发为 show()；
 * 菜单项点击监听、菜单项查找与按钮标题设置都原样委托给 PopupMenu/Menu。
 * 不持有业务状态，也不主动更新菜单项的勾选/标题——由调用方通过 findItem 完成。</p>
 *
 * <p>架构位置：ui 包的轻量 UI 封装，当前用于便签列表多选 ActionMode 的自定义标题栏：
 * 按钮显示“已选 N 项”，菜单提供全选/取消全选。</p>
 *
 * <p>关键协作类：{@link PopupMenu}（真正的菜单实现）、
 * {@link NotesListActivity}（设置监听并维护菜单状态）。</p>
 */
public class DropdownMenu {
    // 菜单锚点与展示控件；标题通过 setTitle 写回按钮文字
    private Button mButton;
    private PopupMenu mPopupMenu;
    private Menu mMenu;

    /**
     * 绑定按钮并创建下拉菜单。
     *
     * <p>按钮背景固定替换为下拉箭头图标；PopupMenu 以按钮为锚点，菜单资源由调用方传入。
     * 构造函数只建立结构，不改变任何菜单项状态。</p>
     *
     * @param context 用于创建 PopupMenu 与取主题
     * @param button 锚点按钮，构造后其背景与点击行为会被接管
     * @param menuId 菜单资源 ID
     */
    public DropdownMenu(Context context, Button button, int menuId) {
        mButton = button;
        // 按钮外观由本类接管，调用方不必再设置背景
        mButton.setBackgroundResource(R.drawable.dropdown_icon);
        // 锚定到按钮：show() 时菜单在按钮附近弹出
        mPopupMenu = new PopupMenu(context, mButton);
        mMenu = mPopupMenu.getMenu();
        // 菜单只 inflate 一次；后续通过 findItem 复用同一 Menu 对象
        mPopupMenu.getMenuInflater().inflate(menuId, mMenu);
        mButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // PopupMenu 会复用已 inflate 的菜单，每次点击直接 show 即可
                mPopupMenu.show();
            }
        });
    }

    /**
     * 设置菜单项点击监听，内部判空后原样委托给 PopupMenu。
     *
     * @param listener 菜单项点击回调；可重复设置，后设置的覆盖先前的
     */
    public void setOnDropdownMenuItemClickListener(OnMenuItemClickListener listener) {
        if (mPopupMenu != null) {
            mPopupMenu.setOnMenuItemClickListener(listener);
        }
    }

    /**
     * 按资源 ID 查找菜单项，供调用方更新标题或勾选状态。
     *
     * @param id 菜单项 ID
     * @return 菜单项；不存在时返回 null，调用方必须判空
     */
    public MenuItem findItem(int id) {
        return mMenu.findItem(id);
    }

    /**
     * 设置按钮文字（当前用于显示“已选 N 项”），不改变菜单内容。
     *
     * @param title 按钮显示文本
     */
    public void setTitle(CharSequence title) {
        mButton.setText(title);
    }
}
