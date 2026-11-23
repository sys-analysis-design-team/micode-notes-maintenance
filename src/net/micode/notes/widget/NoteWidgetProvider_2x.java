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
 * 2x 尺寸的桌面便签微件。
 *
 * <p>职责：只做三件事——指定布局 widget_2x、把颜色索引映射到 2x 背景资源、
 * 声明尺寸类型为 {@link Notes#TYPE_WIDGET_2X}；刷新与删除逻辑全部继承自
 * {@link NoteWidgetProvider}。</p>
 *
 * <p>架构位置：widget 层。在 AndroidManifest 中注册为独立的 AppWidgetProvider，
 * 系统按微件尺寸把更新广播分发到这里；App 内 Activity 也会直接向本类发送
 * ACTION_APPWIDGET_UPDATE 来刷新绑定的微件。</p>
 */
public class NoteWidgetProvider_2x extends NoteWidgetProvider {
    /**
     * 系统周期更新或收到广播时的刷新入口，委托基类处理。
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.update(context, appWidgetManager, appWidgetIds);
    }

    /**
     * 2x 微件布局。
     */
    @Override
    protected int getLayoutId() {
        return R.layout.widget_2x;
    }

    /**
     * 颜色索引换 2x 微件背景 drawable。
     */
    @Override
    protected int getBgResourceId(int bgId) {
        return ResourceParser.WidgetBgResources.getWidget2xBgResource(bgId);
    }

    /**
     * 尺寸类型常量，写入启动编辑页的 extra 供其识别绑定关系。
     */
    @Override
    protected int getWidgetType() {
        return Notes.TYPE_WIDGET_2X;
    }
}
