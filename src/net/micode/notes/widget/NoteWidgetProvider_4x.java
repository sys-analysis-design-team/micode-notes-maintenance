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

import android.appwidget.AppWidgetManager;
import android.content.Context;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.ResourceParser;


/**
 * 4x 尺寸的桌面便签微件。
 *
 * <p>职责：指定布局 widget_4x、把颜色索引映射到 4x 背景资源、声明尺寸类型为
 * {@link Notes#TYPE_WIDGET_4X}；其余逻辑全部继承自 {@link NoteWidgetProvider}。</p>
 *
 * <p>架构位置：widget 层。与 2x 版本并列注册为独立 AppWidgetProvider，
 * 系统与 App 内广播按尺寸选择进入本类。</p>
 */
public class NoteWidgetProvider_4x extends NoteWidgetProvider {
    /**
     * 系统周期更新或收到广播时的刷新入口，委托基类处理。
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.update(context, appWidgetManager, appWidgetIds);
    }

    /**
     * 4x 微件布局；这里没有写 @Override（基类同名抽象方法仍然被覆盖）。
     */
    protected int getLayoutId() {
        return R.layout.widget_4x;
    }

    /**
     * 颜色索引换 4x 微件背景 drawable。
     */
    @Override
    protected int getBgResourceId(int bgId) {
        return ResourceParser.WidgetBgResources.getWidget4xBgResource(bgId);
    }

    /**
     * 尺寸类型常量，写入启动编辑页的 extra 供其识别绑定关系。
     */
    @Override
    protected int getWidgetType() {
        return Notes.TYPE_WIDGET_4X;
    }
}
