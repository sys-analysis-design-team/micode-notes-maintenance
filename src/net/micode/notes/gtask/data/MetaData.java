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

package net.micode.notes.gtask.data;

import android.database.Cursor;
import android.util.Log;

import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * 远端“meta 任务”：把本地便签的完整 JSON 藏在一个特殊任务的 notes 字段里。
 *
 * <p>职责：保存/恢复本地 note 的主键与正文行 id，使同步时能把远端任务
 * 精确映射回本地行，并检测 id 复用冲突。它不是用户可见的任务，
 * 名称固定为 {@link GTaskStringUtils#META_NOTE_NAME}，内容为序列化 meta JSON
 * 加一个 meta_gid 字段指回所属任务。</p>
 *
 * <p>架构位置：gtask.data 包，继承 {@link Task}。GTaskManager 在每次推送后
 * 调用 {@link #setMeta(String, JSONObject)} 刷新；拉取时通过
 * {@link Task#setMetaInfo(MetaData)} 把 notes 解析回本地 JSON。</p>
 *
 * <p>关键协作类：{@link Task}（复用其创建/更新 action 与 notes 字段）；
 * {@link net.micode.notes.tool.GTaskStringUtils}（meta 协议常量）。</p>
 */
public class MetaData extends Task {
    private final static String TAG = MetaData.class.getSimpleName();

    // meta_gid 的解析结果：该 meta 对应的远端任务 gid；解析失败或尚未解析时为 null。
    private String mRelatedGid = null;

    /**
     * 写入 meta 内容：把关联 gid 塞进 meta JSON，整体序列化进 notes 字段。
     *
     * <p>同时把名称设为固定标识，便于远端识别这类“不可见的系统任务”。
     * 写入 meta_gid 失败只记日志，notes 仍保存原始字符串。</p>
     *
     * @param gid      关联的远端任务 gid
     * @param metaInfo 本地便签 meta JSON（会被原地修改，写入 meta_gid）
     */
    public void setMeta(String gid, JSONObject metaInfo) {
        try {
            metaInfo.put(GTaskStringUtils.META_HEAD_GTASK_ID, gid);
        } catch (JSONException e) {
            Log.e(TAG, "failed to put related gid");
        }
        setNotes(metaInfo.toString());
        setName(GTaskStringUtils.META_NOTE_NAME);
    }

    /**
     * 返回解析出的关联 gid（仅由远端 JSON 的 meta_gid 设置）。
     *
     * <p>注意：{@link #setMeta(String, JSONObject)} 只把 gid 写进 notes 字符串，
     * 不会更新本字段；它只在 {@link #setContentByRemoteJSON(JSONObject)} 解析
     * 远端 notes 时被赋值。</p>
     */
    public String getRelatedGid() {
        return mRelatedGid;
    }

    /**
     * meta 任务只要 notes 非 null 就值得保存；名称固定、内容可以是空 JSON。
     */
    @Override
    public boolean isWorthSaving() {
        return getNotes() != null;
    }

    /**
     * 从远端 JSON 恢复 meta：先走父类字段解析，再从 notes 中取出关联 gid。
     *
     * <p>notes 为空或不是合法 JSON 时 mRelatedGid 置 null（该 meta 无法关联任务），
     * 不抛异常以免中断同步。</p>
     */
    @Override
    public void setContentByRemoteJSON(JSONObject js) {
        super.setContentByRemoteJSON(js);
        if (getNotes() != null) {
            try {
                JSONObject metaInfo = new JSONObject(getNotes().trim());
                mRelatedGid = metaInfo.getString(GTaskStringUtils.META_HEAD_GTASK_ID);
            } catch (JSONException e) {
                Log.w(TAG, "failed to get related gid");
                mRelatedGid = null;
            }
        }
    }

    /**
     * 禁止调用：meta 任务不参与本地到远端的常规内容转换。
     *
     * <p>若被调用说明同步代码把 MetaData 当普通 Task 处理。这里抛 Error 而非
     * RuntimeException，是为了让误用不能被普通的 catch(Exception) 吞掉。</p>
     */
    @Override
    public void setContentByLocalJSON(JSONObject js) {
        // this function should not be called
        throw new IllegalAccessError("MetaData:setContentByLocalJSON should not be called");
    }

    /**
     * 禁止调用：meta 内容不是用户便签，不能导回本地数据库。
     *
     * @throws IllegalAccessError 任何调用都视为同步流程错误
     */
    @Override
    public JSONObject getLocalJSONFromContent() {
        throw new IllegalAccessError("MetaData:getLocalJSONFromContent should not be called");
    }

    /**
     * 禁止调用：meta 任务不按内容做差异决策，其增删由 GTaskManager 单独管理。
     *
     * @throws IllegalAccessError 任何调用都视为同步流程错误
     */
    @Override
    public int getSyncAction(Cursor c) {
        throw new IllegalAccessError("MetaData:getSyncAction should not be called");
    }

}
