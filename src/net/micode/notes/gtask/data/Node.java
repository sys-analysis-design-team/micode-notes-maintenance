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

import org.json.JSONObject;

/**
 * 远端同步节点（任务、任务组、meta 任务）的抽象基类。
 *
 * <p>职责：统一保存 Google Tasks 协议中的公共字段（gid、name、last_modified、
 * deleted），定义创建/更新 action 的生成与内容装载/导出的抽象协议，
 * 并提供 {@link #SYNC_ACTION_*} 常量作为本地与远端差异的决策编码。</p>
 *
 * <p>架构位置：gtask.data 包的根类型。GTaskManager 的同步循环只面向 Node，
 * 具体 JSON 结构差异由 {@link Task}、{@link TaskList}、{@link MetaData} 实现。</p>
 */
public abstract class Node {
    // 同步动作编码：由子类的 getSyncAction 产生，GTaskManager.doContentSync 消费。
    // 数值本身无意义，仅作 switch 分支标识，不能持久化到数据库或协议中。
    /** 两侧都无变化，跳过。 */
    public static final int SYNC_ACTION_NONE = 0;

    /** 本地有、远端无：把本地节点推到远端（创建）。 */
    public static final int SYNC_ACTION_ADD_REMOTE = 1;

    /** 远端有、本地无：拉到本地新建便签。 */
    public static final int SYNC_ACTION_ADD_LOCAL = 2;

    /** 本地已删除：删除远端节点及其 meta。 */
    public static final int SYNC_ACTION_DEL_REMOTE = 3;

    /** 远端已删除：删除本地行，并记入待清理表。 */
    public static final int SYNC_ACTION_DEL_LOCAL = 4;

    /** 仅本地修改（或冲突时本地优先）：推送覆盖远端。 */
    public static final int SYNC_ACTION_UPDATE_REMOTE = 5;

    /** 仅远端修改：拉取覆盖本地。 */
    public static final int SYNC_ACTION_UPDATE_LOCAL = 6;

    /** 两侧都改：当前实现按本地优先更新远端。 */
    public static final int SYNC_ACTION_UPDATE_CONFLICT = 7;

    /** 数据不一致（如 gid 不匹配）：GTaskManager 抛异常终止本轮同步。 */
    public static final int SYNC_ACTION_ERROR = 8;

    // 远端全局 id；本地新建节点在创建成功前为 null。
    private String mGid;

    private String mName;

    // 远端最后修改时间（毫秒时间戳）；与本地 sync_id 比较判断远端是否变化。
    private long mLastModified;

    // 远端软删除标记；更新 action 会原样回传。
    private boolean mDeleted;

    /** 初始化为空节点：无 gid、空名称、未修改、未删除。 */
    public Node() {
        mGid = null;
        mName = "";
        mLastModified = 0;
        mDeleted = false;
    }

    /**
     * 生成远端创建该节点的 action JSON。
     *
     * @param actionId 客户端自增的 action 序号
     * @return 可直接提交给 Google Tasks 的 JSON
     */
    public abstract JSONObject getCreateAction(int actionId);

    /**
     * 生成远端更新该节点的 action JSON（改名、改内容或软删除）。
     *
     * @param actionId 客户端自增的 action 序号
     * @return 更新 action JSON
     */
    public abstract JSONObject getUpdateAction(int actionId);

    /** 用远端返回的 JSON 填充节点字段（拉取方向）；各实现自行处理缺省字段。 */
    public abstract void setContentByRemoteJSON(JSONObject js);

    /** 用本地便签/文件夹 JSON 填充节点字段（推送方向）。 */
    public abstract void setContentByLocalJSON(JSONObject js);

    /**
     * 把节点内容导出为本地 note 可用的 meta JSON。
     *
     * @return meta JSON；无有效内容时可为 null
     */
    public abstract JSONObject getLocalJSONFromContent();

    /**
     * 根据本地 note 行与远端节点状态决定同步动作。
     *
     * @param c 本地 note 行（列序见 {@link SqlNote#PROJECTION_NOTE}）
     * @return {@link #SYNC_ACTION_*} 之一
     */
    public abstract int getSyncAction(Cursor c);

    /** 设置远端 gid（创建成功后由 GTaskClient 回填）。 */
    public void setGid(String gid) {
        this.mGid = gid;
    }

    /** 设置节点名称；任务名与文件夹名都走这里。 */
    public void setName(String name) {
        this.mName = name;
    }

    /** 设置远端最后修改时间，用于与本地 sync_id 比较。 */
    public void setLastModified(long lastModified) {
        this.mLastModified = lastModified;
    }

    /** 设置远端软删除标记。 */
    public void setDeleted(boolean deleted) {
        this.mDeleted = deleted;
    }

    /** 返回远端 gid；本地新建且尚未创建成功时为 null。 */
    public String getGid() {
        return this.mGid;
    }

    /** 返回节点名称；构造器初始化为空串，正常不会为 null。 */
    public String getName() {
        return this.mName;
    }

    /** 返回远端最后修改时间；未知时为 0。 */
    public long getLastModified() {
        return this.mLastModified;
    }

    /** 返回远端软删除标记。 */
    public boolean getDeleted() {
        return this.mDeleted;
    }

}
