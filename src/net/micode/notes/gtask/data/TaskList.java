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

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;


/**
 * 远端任务组（Google Tasks 的 group），对应本地一个文件夹。
 *
 * <p>职责：维护组内任务的顺序（子任务列表 + 前驱兄弟链），生成组的创建/更新
 * action JSON，并在本地文件夹与远端组之间转换名称与类型。</p>
 *
 * <p>架构位置：gtask.data 包，继承 {@link Node}。GTaskManager 初始化时把远端
 * 所有任务组读入，本地文件夹按名称与 gid 与之一一配对；组内 {@link Task}
 * 通过 {@link #addChildTask(Task)} 挂载，创建 action 的 index/前驱据此计算。</p>
 *
 * <p>关键协作类：{@link Task}（子任务，顺序由本类维护）；
 * {@link net.micode.notes.tool.GTaskStringUtils}（[MIUI_Notes] 前缀与系统文件夹名）。</p>
 */
public class TaskList extends Node {
    private static final String TAG = TaskList.class.getSimpleName();

    // 组在远端列表中的排序下标；仅创建 action 使用，默认 1。
    private int mIndex;

    // 组内任务，顺序即远端顺序；前驱兄弟链由增删/移动操作维护。
    private ArrayList<Task> mChildren;

    /** 构造空任务组：无子任务，下标默认 1（等待 GTaskManager 挂载时使用）。 */
    public TaskList() {
        super();
        mChildren = new ArrayList<Task>();
        mIndex = 1;
    }

    /**
     * 生成远端“创建任务组”的 action JSON。
     *
     * <p>与 Task 不同，组没有 parent_id/list_id，index 用本对象的 mIndex；
     * entity_type 固定为 group。</p>
     *
     * @param actionId action 序号
     * @return 创建 action JSON
     *
     * <p>异常：序列化失败抛 {@link ActionFailureException}。</p>
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
            js.put(GTaskStringUtils.GTASK_JSON_INDEX, mIndex);

            // entity_delta
            // 组的 entity_type 声明为 group，远端据此区分任务与分组。
            JSONObject entity = new JSONObject();
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            entity.put(GTaskStringUtils.GTASK_JSON_CREATOR_ID, "null");
            entity.put(GTaskStringUtils.GTASK_JSON_ENTITY_TYPE,
                    GTaskStringUtils.GTASK_JSON_TYPE_GROUP);
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate tasklist-create jsonobject");
        }

        return js;
    }

    /**
     * 生成远端“更新任务组”的 action JSON（改名或删除）。
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
            entity.put(GTaskStringUtils.GTASK_JSON_DELETED, getDeleted());
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate tasklist-update jsonobject");
        }

        return js;
    }

    /**
     * 用远端组 JSON 填充本对象（拉取方向）。
     *
     * <p>组只有 id、last_modified、name 三个有用字段；缺失字段保持原值。</p>
     *
     * @param js 远端组 JSON；null 时直接返回
     *
     * <p>异常：字段类型错误抛 {@link ActionFailureException}。</p>
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

            } catch (JSONException e) {
                Log.e(TAG, e.toString());
                e.printStackTrace();
                throw new ActionFailureException("fail to get tasklist content from jsonobject");
            }
        }
    }

    /**
     * 用本地文件夹 meta JSON 设置组名称（推送方向）。
     *
     * <p>普通文件夹名称加 [MIUI_Notes] 前缀；系统文件夹只有根文件夹与通话记录
     * 文件夹两种合法值，其余 id 视为非法（只记错误，不设置名称）。</p>
     *
     * @param js 本地 meta JSON（含 meta_note）
     */
    public void setContentByLocalJSON(JSONObject js) {
        if (js == null || !js.has(GTaskStringUtils.META_HEAD_NOTE)) {
            Log.w(TAG, "setContentByLocalJSON: nothing is avaiable");
        }

        try {
            JSONObject folder = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);

            if (folder.getInt(NoteColumns.TYPE) == Notes.TYPE_FOLDER) {
                String name = folder.getString(NoteColumns.SNIPPET);
                // 前缀用于在远端区分“小米便签创建的文件夹”和用户手工建的组；
                // 拉取时 getLocalJSONFromContent 会剥离它。
                setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX + name);
            } else if (folder.getInt(NoteColumns.TYPE) == Notes.TYPE_SYSTEM) {
                // 系统文件夹在本地是固定行，远端名称必须映射成约定字符串，
                // 否则下次同步无法按名称找回对应分组。
                if (folder.getLong(NoteColumns.ID) == Notes.ID_ROOT_FOLDER)
                    setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT);
                else if (folder.getLong(NoteColumns.ID) == Notes.ID_CALL_RECORD_FOLDER)
                    setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX
                            + GTaskStringUtils.FOLDER_CALL_NOTE);
                else
                    Log.e(TAG, "invalid system folder");
            } else {
                Log.e(TAG, "error type");
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }
    }

    /**
     * 把组名称转换为本地文件夹 meta JSON（远端到本地方向）。
     *
     * <p>先剥离 [MIUI_Notes] 前缀得到真实名称；名称为 Default / Call_Note
     * 的映射为系统文件夹，其余为普通文件夹。缺少前缀的名称原样保留，
     * 这样用户自己在远端建的组也能同步成本地文件夹。</p>
     *
     * @return 只含 meta_note 的 JSON；序列化失败返回 null
     */
    public JSONObject getLocalJSONFromContent() {
        try {
            JSONObject js = new JSONObject();
            JSONObject folder = new JSONObject();

            String folderName = getName();
            // 前缀是本应用写的；远端用户手工建的组没有前缀，按原名落成普通文件夹。
            if (getName().startsWith(GTaskStringUtils.MIUI_FOLDER_PREFFIX))
                folderName = folderName.substring(GTaskStringUtils.MIUI_FOLDER_PREFFIX.length(),
                        folderName.length());
            folder.put(NoteColumns.SNIPPET, folderName);
            if (folderName.equals(GTaskStringUtils.FOLDER_DEFAULT)
                    || folderName.equals(GTaskStringUtils.FOLDER_CALL_NOTE))
                folder.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
            else
                folder.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);

            js.put(GTaskStringUtils.META_HEAD_NOTE, folder);

            return js;
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 根据本地文件夹行与远端组状态决定同步动作。
     *
     * <p>与 {@link Task} 的差异：文件夹冲突没有“双方合并”的必要，且本地结构
     * 更权威，因此双方都修改时仍返回 UPDATE_REMOTE（本地覆盖远端），而不是冲突。</p>
     *
     * @param c 当前文件夹在 note 表中的行
     * @return {@link Node#SYNC_ACTION_*} 之一；异常时返回 SYNC_ACTION_ERROR
     */
    public int getSyncAction(Cursor c) {
        try {
            if (c.getInt(SqlNote.LOCAL_MODIFIED_COLUMN) == 0) {
                // there is no local update
                // 本地无未推送修改：远端时间戳未变则无事可做，变了就拉取。
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // no update both side
                    return SYNC_ACTION_NONE;
                } else {
                    // apply remote to local
                    return SYNC_ACTION_UPDATE_LOCAL;
                }
            } else {
                // validate gtask id
                if (!c.getString(SqlNote.GTASK_ID_COLUMN).equals(getGid())) {
                    Log.e(TAG, "gtask id doesn't match");
                    return SYNC_ACTION_ERROR;
                }
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // local modification only
                    return SYNC_ACTION_UPDATE_REMOTE;
                } else {
                    // for folder conflicts, just apply local modification
                    // 文件夹冲突直接以本地为准：本地文件夹承载便签归属，
                    // 远端只保存名称，覆盖远端不会丢失本地数据。
                    return SYNC_ACTION_UPDATE_REMOTE;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }

        return SYNC_ACTION_ERROR;
    }

    /** 返回组内任务数量（组不嵌套组，故不含孙级）。 */
    public int getChildTaskCount() {
        return mChildren.size();
    }

    /**
     * 把任务追加到组末尾，并设置父指针。
     *
     * <p>重复任务或 null 不加入，返回 false；成功加入返回 true。</p>
     *
     * @return 是否真的加入了列表
     */
    public boolean addChildTask(Task task) {
        boolean ret = false;
        if (task != null && !mChildren.contains(task)) {
            ret = mChildren.add(task);
            if (ret) {
                // need to set prior sibling and parent
                // 边界：add 成功后列表末尾正是 task 自身，这里取到的是自己而非“前一个任务”；
                // 本地新建任务此时 gid 为 null，按 org.json 语义该字段不会写入 action，
                // 实际排序仍由 index 决定。阅读时勿按字段名把它理解为真正的兄弟引用。
                task.setPriorSibling(mChildren.isEmpty() ? null : mChildren
                        .get(mChildren.size() - 1));
                task.setParent(this);
            }
        }
        return ret;
    }

    /**
     * 在指定位置插入任务，并修复插入点前后任务的前驱指针。
     *
     * <p>index 允许等于当前大小（追加）；越界、null、已在列表中的任务都不会插入。
     * 注意：越界返回 false，但 null/重复任务这两类“未插入”情形仍返回 true，
     * 调用方不能仅凭返回值判断列表是否真的变了。</p>
     *
     * @param task  待插入任务
     * @param index 插入位置，合法范围 [0, size]
     * @return true 表示参数被接受（不代表一定发生插入，见上）
     */
    public boolean addChildTask(Task task, int index) {
        if (index < 0 || index > mChildren.size()) {
            Log.e(TAG, "add child task: invalid index");
            return false;
        }

        int pos = mChildren.indexOf(task);
        if (task != null && pos == -1) {
            mChildren.add(index, task);

            // update the task list
            // 插入后按最终位置取前驱与后继：前驱是 index-1，后继是 index+1。
            Task preTask = null;
            Task afterTask = null;
            if (index != 0)
                preTask = mChildren.get(index - 1);
            if (index != mChildren.size() - 1)
                afterTask = mChildren.get(index + 1);

            task.setPriorSibling(preTask);
            if (afterTask != null)
                afterTask.setPriorSibling(task);
        }

        return true;
    }

    /**
     * 从组内移除任务，并清理其父/前驱指针、修复后继任务的前驱。
     *
     * <p>只解除组内关系，不删除远端对象，也不改任务内容。</p>
     *
     * @return 任务原本在列表中则为 true；不在列表中返回 false 且不做任何修改
     */
    public boolean removeChildTask(Task task) {
        boolean ret = false;
        int index = mChildren.indexOf(task);
        if (index != -1) {
            ret = mChildren.remove(task);

            if (ret) {
                // reset prior sibling and parent
                task.setPriorSibling(null);
                task.setParent(null);

                // update the task list
                // 删除后 size 已减一；index != size 等价于“被删的不是最后一个”，
                // 此时原位置上的元素就是后继，需要把它的前驱改成被删任务的前一个。
                if (index != mChildren.size()) {
                    mChildren.get(index).setPriorSibling(
                            index == 0 ? null : mChildren.get(index - 1));
                }
            }
        }
        return ret;
    }

    /**
     * 把组内任务移动到新位置（先移除再插入，前驱链随之重建）。
     *
     * <p>index 以移动前的列表为参照，且必须小于当前大小——不能通过该方法
     * 移动到“末尾之后”；pos == index 时视为已到位，直接返回 true。</p>
     *
     * @return 移动是否成功；任务不在列表中或下标越界返回 false
     */
    public boolean moveChildTask(Task task, int index) {

        if (index < 0 || index >= mChildren.size()) {
            Log.e(TAG, "move child task: invalid index");
            return false;
        }

        int pos = mChildren.indexOf(task);
        if (pos == -1) {
            Log.e(TAG, "move child task: the task should in the list");
            return false;
        }

        if (pos == index)
            return true;
        return (removeChildTask(task) && addChildTask(task, index));
    }

    /**
     * 按远端 gid 线性查找组内任务。
     *
     * <p>与 {@link #getChilTaskByGid(String)} 功能重复（历史遗留的两个版本）；
     * 组规模小，线性查找足够。</p>
     *
     * @return 匹配任务；不存在返回 null
     */
    public Task findChildTaskByGid(String gid) {
        for (int i = 0; i < mChildren.size(); i++) {
            Task t = mChildren.get(i);
            if (t.getGid().equals(gid)) {
                return t;
            }
        }
        return null;
    }

    /** 返回任务在组内的下标（创建 action 的 index 字段来源）；不在组内返回 -1。 */
    public int getChildTaskIndex(Task task) {
        return mChildren.indexOf(task);
    }

    /** 按位置取任务；越界记日志并返回 null。 */
    public Task getChildTaskByIndex(int index) {
        if (index < 0 || index >= mChildren.size()) {
            Log.e(TAG, "getTaskByIndex: invalid index");
            return null;
        }
        return mChildren.get(index);
    }

    /**
     * 按 gid 查找组内任务（方法名少了一个 d，为历史拼写）。
     *
     * @return 匹配任务；不存在返回 null
     */
    public Task getChilTaskByGid(String gid) {
        for (Task task : mChildren) {
            if (task.getGid().equals(gid))
                return task;
        }
        return null;
    }

    /** 返回内部子任务列表本身（非副本）；调用方修改会直接影响组的顺序。 */
    public ArrayList<Task> getChildTaskList() {
        return this.mChildren;
    }

    /** 设置组在远端列表中的排序下标；仅影响之后生成的创建 action。 */
    public void setIndex(int index) {
        this.mIndex = index;
    }

    /** 返回组的排序下标；同步链路当前不调用 setIndex，默认值为 1。 */
    public int getIndex() {
        return this.mIndex;
    }
}
