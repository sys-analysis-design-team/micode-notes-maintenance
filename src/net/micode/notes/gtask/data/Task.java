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
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * 远端“任务”（Google Tasks 中的单条 task）的同步模型，对应本地一条便签。
 *
 * <p>职责：把本地便签内容映射成远端任务的 name/notes，生成创建/更新 action JSON，
 * 并根据本地 note 行与远端 last_modified 判断该执行哪种同步动作。
 * 它不直接访问数据库（只接收 Cursor 做判断），网络动作由 GTaskClient 完成。</p>
 *
 * <p>架构位置：gtask.data 包，继承 {@link Node}。GTaskManager 初始化远端列表时
 * 构造 Task 并挂到 {@link TaskList} 下；本地查询结果与远端节点按 gid 配对后，
 * 调用 {@link #getSyncAction(Cursor)} 决定推送、拉取还是冲突处理。</p>
 *
 * <p>关键协作类：{@link TaskList}（父分组，决定 index 与前驱兄弟）；
 * {@link MetaData}（把本地 note JSON 存在特殊任务的 notes 里，供 {@link #setMetaInfo} 恢复）；
 * {@link net.micode.notes.tool.GTaskStringUtils}（协议字段名常量）。</p>
 */
public class Task extends Node {
    private static final String TAG = Task.class.getSimpleName();

    // 远端完成状态；本地便签模型没有对应列，仅作协议字段保存。
    private boolean mCompleted;

    // 远端任务的 notes 字段；普通任务为空，MetaData 任务用它承载本地 note JSON。
    private String mNotes;

    // 从远端 meta 任务 notes 解析出的本地 note JSON 缓存；null 表示远端没有可用 meta。
    private JSONObject mMetaInfo;

    // 同组中排在自己前面的任务；首个任务为 null，创建 action 用它声明顺序。
    private Task mPriorSibling;

    // 所属远端任务组；创建任务时必须非 null，用于取 gid 与 index。
    private TaskList mParent;

    /** 构造空任务：字段全为空，等待 setContentByRemoteJSON 或 setContentByLocalJSON 填充。 */
    public Task() {
        super();
        mCompleted = false;
        mNotes = null;
        mPriorSibling = null;
        mParent = null;
        mMetaInfo = null;
    }

    /**
     * 生成远端“创建任务”的 action JSON。
     *
     * <p>字段来源：name/notes 取本对象；parent_id/list_id 都取父 {@link TaskList}
     * 的 gid（Google Tasks 中 task 的父即 list）；index 用父列表中的当前下标；
     * prior_sibling_id 仅在存在前驱兄弟时输出，首个任务省略该字段。</p>
     *
     * @param actionId 客户端自增的 action 序号，服务端按序执行
     * @return 可直接提交的 action JSON
     *
     * <p>副作用：无（只读对象状态）。</p>
     * <p>异常：JSON 字段类型异常时转抛 {@link ActionFailureException}，同步中止。</p>
     */
    public JSONObject getCreateAction(int actionId) {
        JSONObject js = new JSONObject();

        try {
            // action_type
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_CREATE);

            // action_id
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, actionId);

            // index
            // 下标基于父列表当前顺序；若任务未挂到父列表，getChildTaskIndex 返回 -1，
            // 服务端可能拒绝，因此创建前必须 addChildTask。
            js.put(GTaskStringUtils.GTASK_JSON_INDEX, mParent.getChildTaskIndex(this));

            // entity_delta
            // creator_id 用字符串 "null" 表示当前登录用户，这是协议要求，
            // 不能省略成 JSON null（否则字段会被移除）。
            JSONObject entity = new JSONObject();
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            entity.put(GTaskStringUtils.GTASK_JSON_CREATOR_ID, "null");
            entity.put(GTaskStringUtils.GTASK_JSON_ENTITY_TYPE,
                    GTaskStringUtils.GTASK_JSON_TYPE_TASK);
            if (getNotes() != null) {
                entity.put(GTaskStringUtils.GTASK_JSON_NOTES, getNotes());
            }
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

            // parent_id
            js.put(GTaskStringUtils.GTASK_JSON_PARENT_ID, mParent.getGid());

            // dest_parent_type
            js.put(GTaskStringUtils.GTASK_JSON_DEST_PARENT_TYPE,
                    GTaskStringUtils.GTASK_JSON_TYPE_GROUP);

            // list_id
            js.put(GTaskStringUtils.GTASK_JSON_LIST_ID, mParent.getGid());

            // prior_sibling_id
            // 前驱兄弟只用于排序提示；为 null 时省略字段，服务端按 index 插入。
            if (mPriorSibling != null) {
                js.put(GTaskStringUtils.GTASK_JSON_PRIOR_SIBLING_ID, mPriorSibling.getGid());
            }

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate task-create jsonobject");
        }

        return js;
    }

    /**
     * 生成远端“更新任务”的 action JSON。
     *
     * <p>只携带 name、notes 与 deleted：完成状态等字段不参与本地→远端同步；
     * notes 为 null 时省略，避免把 null 写成字符串。</p>
     *
     * @param actionId action 序号
     * @return 更新 action JSON
     *
     * <p>异常：序列化失败抛 {@link ActionFailureException}。</p>
     */
    public JSONObject getUpdateAction(int actionId) {
        JSONObject js = new JSONObject();

        try {
            // action_type
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_UPDATE);

            // action_id
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, actionId);

            // id
            js.put(GTaskStringUtils.GTASK_JSON_ID, getGid());

            // entity_delta
            JSONObject entity = new JSONObject();
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            if (getNotes() != null) {
                entity.put(GTaskStringUtils.GTASK_JSON_NOTES, getNotes());
            }
            entity.put(GTaskStringUtils.GTASK_JSON_DELETED, getDeleted());
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate task-update jsonobject");
        }

        return js;
    }

    /**
     * 用远端返回的任务 JSON 填充本对象（拉取方向）。
     *
     * <p>所有字段都是可选的：远端没返回的字段保持原值，因此可安全用于增量响应。
     * id 映射为 gid，last_modified 作为后续冲突判断的水位。</p>
     *
     * @param js 远端任务 JSON；null 时直接返回（容错）
     *
     * <p>异常：字段存在但类型不对时抛 {@link ActionFailureException}。</p>
     */
    public void setContentByRemoteJSON(JSONObject js) {
        if (js != null) {
            try {
                // id
                if (js.has(GTaskStringUtils.GTASK_JSON_ID)) {
                    setGid(js.getString(GTaskStringUtils.GTASK_JSON_ID));
                }

                // last_modified
                if (js.has(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED)) {
                    setLastModified(js.getLong(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED));
                }

                // name
                if (js.has(GTaskStringUtils.GTASK_JSON_NAME)) {
                    setName(js.getString(GTaskStringUtils.GTASK_JSON_NAME));
                }

                // notes
                if (js.has(GTaskStringUtils.GTASK_JSON_NOTES)) {
                    setNotes(js.getString(GTaskStringUtils.GTASK_JSON_NOTES));
                }

                // deleted
                if (js.has(GTaskStringUtils.GTASK_JSON_DELETED)) {
                    setDeleted(js.getBoolean(GTaskStringUtils.GTASK_JSON_DELETED));
                }

                // completed
                if (js.has(GTaskStringUtils.GTASK_JSON_COMPLETED)) {
                    setCompleted(js.getBoolean(GTaskStringUtils.GTASK_JSON_COMPLETED));
                }
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
                e.printStackTrace();
                throw new ActionFailureException("fail to get task content from jsonobject");
            }
        }
    }

    /**
     * 用本地便签 meta JSON 填充任务的 name（推送方向）。
     *
     * <p>只取 mime 为 text_note 的 data 行内容作为任务名；本地正文整体由
     * {@link MetaData} 承载，不在这里设置。类型不是普通便签时忽略。</p>
     *
     * @param js 本地 meta JSON（含 meta_note 与 meta_data）
     *
     * <p>异常：JSON 缺失/类型错误记日志后吞掉；注意这里只告警没有 return，
     * 传入 null 会在下方 getJSONObject 处抛 NullPointerException。</p>
     */
    public void setContentByLocalJSON(JSONObject js) {
        if (js == null || !js.has(GTaskStringUtils.META_HEAD_NOTE)
                || !js.has(GTaskStringUtils.META_HEAD_DATA)) {
            Log.w(TAG, "setContentByLocalJSON: nothing is avaiable");
        }

        try {
            JSONObject note = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
            JSONArray dataArray = js.getJSONArray(GTaskStringUtils.META_HEAD_DATA);

            if (note.getInt(NoteColumns.TYPE) != Notes.TYPE_NOTE) {
                Log.e(TAG, "invalid type");
                return;
            }

            // 便签可能有多种 data 行（文本/通话记录），远端任务名只取第一条文本行。
            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject data = dataArray.getJSONObject(i);
                if (TextUtils.equals(data.getString(DataColumns.MIME_TYPE), DataConstants.NOTE)) {
                    setName(data.getString(DataColumns.CONTENT));
                    break;
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }
    }

    /**
     * 把任务内容导出为本地便签可用的 meta JSON（远端到本地方向）。
     *
     * <p>两种情形：没有 mMetaInfo 的任务（如网页端新建）只有 name，临时拼一个
     * 最小 meta；有 mMetaInfo 的任务（此前同步过）在其结构上更新正文内容后返回。
     * 任务名与 meta 都为空时无法构造有效便签。</p>
     *
     * @return meta JSON；空任务或序列化失败时返回 null
     *
     * <p>副作用：有 meta 时原地修改 mMetaInfo 中的 data 内容（返回同一对象引用），
     * 调用方应只读使用。</p>
     */
    public JSONObject getLocalJSONFromContent() {
        String name = getName();
        try {
            if (mMetaInfo == null) {
                // new task created from web
                // 远端新建的任务没有本地 meta：用任务名构造最小便签（只有正文首行）。
                if (name == null) {
                    Log.w(TAG, "the note seems to be an empty one");
                    return null;
                }

                JSONObject js = new JSONObject();
                JSONObject note = new JSONObject();
                JSONArray dataArray = new JSONArray();
                JSONObject data = new JSONObject();
                data.put(DataColumns.CONTENT, name);
                dataArray.put(data);
                js.put(GTaskStringUtils.META_HEAD_DATA, dataArray);
                note.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
                js.put(GTaskStringUtils.META_HEAD_NOTE, note);
                return js;
            } else {
                // synced task
                // 已同步任务：复用 meta 里的 note/data 结构，只把文本内容替换为最新任务名。
                JSONObject note = mMetaInfo.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
                JSONArray dataArray = mMetaInfo.getJSONArray(GTaskStringUtils.META_HEAD_DATA);

                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject data = dataArray.getJSONObject(i);
                    if (TextUtils.equals(data.getString(DataColumns.MIME_TYPE), DataConstants.NOTE)) {
                        data.put(DataColumns.CONTENT, getName());
                        break;
                    }
                }

                note.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
                return mMetaInfo;
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 从远端 meta 任务恢复本地 note JSON 缓存。
     *
     * <p>meta 任务的 notes 字段就是上次推送的便签 JSON；解析失败视为没有 meta
     * （对象按“网页端新建任务”处理），不抛异常以免阻断整轮同步。</p>
     *
     * @param metaData 远端 meta 任务；null 或 notes 为空时不改变 mMetaInfo
     */
    public void setMetaInfo(MetaData metaData) {
        if (metaData != null && metaData.getNotes() != null) {
            try {
                mMetaInfo = new JSONObject(metaData.getNotes());
            } catch (JSONException e) {
                Log.w(TAG, e.toString());
                mMetaInfo = null;
            }
        }
    }

    /**
     * 根据本地 note 行与远端任务状态决定同步动作。
     *
     * <p>决策顺序：meta 缺失/无本地 id/id 不匹配都优先“以远端为准重建本地”；
     * 本地未修改时比较 sync_id 与远端 last_modified 判断是否拉取；本地已修改时
     * 先校验 gid 是否仍对应，再比较时间戳区分“仅本地改”与“双方都改”的冲突。</p>
     *
     * @param c 当前便签在 note 表中的行（列序见 {@link SqlNote#PROJECTION_NOTE}）
     * @return {@link Node#SYNC_ACTION_*} 之一；任何异常返回 SYNC_ACTION_ERROR
     *
     * <p>异常：内部捕获所有异常并记日志，最终统一返回错误动作。</p>
     */
    public int getSyncAction(Cursor c) {
        try {
            JSONObject noteInfo = null;
            if (mMetaInfo != null && mMetaInfo.has(GTaskStringUtils.META_HEAD_NOTE)) {
                noteInfo = mMetaInfo.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
            }

            if (noteInfo == null) {
                // 远端 meta 被删/损坏：无法确认远端内容与本地 id 的映射，
                // 按“需要把本地内容重新推到远端”处理。
                Log.w(TAG, "it seems that note meta has been deleted");
                return SYNC_ACTION_UPDATE_REMOTE;
            }

            if (!noteInfo.has(NoteColumns.ID)) {
                // meta 存在但缺本地 id：说明远端数据不完整，宁可拉回本地重建也不信任。
                Log.w(TAG, "remote note id seems to be deleted");
                return SYNC_ACTION_UPDATE_LOCAL;
            }

            // validate the note id now
            // 远端 meta 指向另一条本地便签（常见于 id 复用或导入冲突），以远端为准覆盖本地映射。
            if (c.getLong(SqlNote.ID_COLUMN) != noteInfo.getLong(NoteColumns.ID)) {
                Log.w(TAG, "note id doesn't match");
                return SYNC_ACTION_UPDATE_LOCAL;
            }

            if (c.getInt(SqlNote.LOCAL_MODIFIED_COLUMN) == 0) {
                // there is no local update
                // 本地没有未推送修改：只需判断远端是否变过。
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // no update both side
                    // sync_id 是上次同步记录的远端 last_modified，相等说明两侧都没变。
                    return SYNC_ACTION_NONE;
                } else {
                    // apply remote to local
                    return SYNC_ACTION_UPDATE_LOCAL;
                }
            } else {
                // validate gtask id
                // 本地有未推送修改，先确认远端对象仍是同一条（gid 一致）。
                if (!c.getString(SqlNote.GTASK_ID_COLUMN).equals(getGid())) {
                    Log.e(TAG, "gtask id doesn't match");
                    return SYNC_ACTION_ERROR;
                }
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // local modification only
                    return SYNC_ACTION_UPDATE_REMOTE;
                } else {
                    // 双方都改过：返回冲突，由 GTaskManager 决定合并策略（当前实现本地优先）。
                    return SYNC_ACTION_UPDATE_CONFLICT;
                }
            }
        } catch (Exception e) {
            // 字段缺失/类型错误都收敛为 ERROR，避免带着错误假设继续同步。
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }

        return SYNC_ACTION_ERROR;
    }

    /**
     * 判断该远端任务是否值得在本地落库。
     *
     * <p>有 meta、或名称/备注任一非空白即保留；用于过滤远端列表中的空壳任务，
     * 避免生成无内容便签。</p>
     */
    public boolean isWorthSaving() {
        return mMetaInfo != null || (getName() != null && getName().trim().length() > 0)
                || (getNotes() != null && getNotes().trim().length() > 0);
    }

    /** 设置远端完成状态（仅协议字段，不映射本地数据库）。 */
    public void setCompleted(boolean completed) {
        this.mCompleted = completed;
    }

    /** 设置远端 notes 字段；MetaData 用它承载序列化后的本地便签 JSON。 */
    public void setNotes(String notes) {
        this.mNotes = notes;
    }

    /** 设置同组前驱任务，仅影响后续创建 action 的 prior_sibling_id。 */
    public void setPriorSibling(Task priorSibling) {
        this.mPriorSibling = priorSibling;
    }

    /** 绑定所属任务组；创建任务或计算 index 前必须设置。 */
    public void setParent(TaskList parent) {
        this.mParent = parent;
    }

    /** 返回远端完成状态；当前版本只透传，不参与本地便签展示。 */
    public boolean getCompleted() {
        return this.mCompleted;
    }

    /** 返回远端 notes 原文；可能为 null（远端未提供）。 */
    public String getNotes() {
        return this.mNotes;
    }

    /** 返回同组前驱任务；首个任务或未挂载时为 null。 */
    public Task getPriorSibling() {
        return this.mPriorSibling;
    }

    /** 返回所属任务组；未挂载时为 null。 */
    public TaskList getParent() {
        return this.mParent;
    }

}
