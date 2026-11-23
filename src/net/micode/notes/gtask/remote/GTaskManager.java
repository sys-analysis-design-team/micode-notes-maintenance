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

package net.micode.notes.gtask.remote;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.data.MetaData;
import net.micode.notes.gtask.data.Node;
import net.micode.notes.gtask.data.SqlNote;
import net.micode.notes.gtask.data.Task;
import net.micode.notes.gtask.data.TaskList;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.gtask.exception.NetworkFailureException;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;


/**
 * 便签与 Google Tasks 之间的同步总控。
 *
 * <p>职责：驱动一次完整同步——登录、拉取远端任务列表、按本地数据库逐条比对、
 * 决定增删改方向、提交远端变更、回写本地同步 ID。本类不直接发 HTTP
 * （交给 {@link GTaskClient}），也不解析具体节点 JSON（交给 Node 子类）。</p>
 *
 * <p>架构位置：位于 gtask.remote 包，是同步链路的中枢。上层由
 * {@link GTaskASyncTask} 在后台线程调用 {@link #sync(Context, GTaskASyncTask)}；
 * 下层通过 {@link GTaskClient} 访问 mail.google.com/tasks 的私有 Web 接口，
 * 并把结果写回 notes 内容提供者中的 SQLite 表。</p>
 *
 * <p>关键协作类：{@link GTaskClient}（登录与 HTTP）、{@code TaskList}/{@code Task}
 * （远端节点模型）、{@code MetaData}（用隐藏任务保存每条便签的完整 JSON）、
 * {@code SqlNote}（本地记录与 ID 映射）。</p>
 *
 * <p>并发边界：单例但非线程安全。{@link #sync} 用 {@code mSyncing} 做互斥，
 * {@code mCancelled} 是跨线程取消标志，只在各步骤之间检查，无法中断正在进行的
 * HTTP 请求或数据库操作。</p>
 */
public class GTaskManager {
    private static final String TAG = GTaskManager.class.getSimpleName();

    /** 同步结果：全部步骤完成且未被取消。 */
    public static final int STATE_SUCCESS = 0;

    /** 同步结果：网络不可达、HTTP 失败或登录失败，通常可重试。 */
    public static final int STATE_NETWORK_ERROR = 1;

    /** 同步结果：JSON 结构异常、gid 映射缺失等逻辑错误，重试也不会成功。 */
    public static final int STATE_INTERNAL_ERROR = 2;

    /** 同步结果：已有同步在进行，本次请求被直接忽略，不排队。 */
    public static final int STATE_SYNC_IN_PROGRESS = 3;

    /** 同步结果：用户在同步途中取消，可能只完成了前几个步骤。 */
    public static final int STATE_SYNC_CANCELLED = 4;

    /** 进程内唯一实例；各容器跨轮复用，每轮同步开始时清空。 */
    private static GTaskManager mInstance = null;

    /** 取 Google authtoken 所需的 Activity，由界面在发起同步前注入。 */
    private Activity mActivity;

    /** 当前轮次的上下文，用来拿 ContentResolver。 */
    private Context mContext;

    /** 当前轮次的内容解析器；sync 开始时刷新。 */
    private ContentResolver mContentResolver;

    /** 同步互斥标志：true 时新的 sync 请求直接返回“进行中”。 */
    private boolean mSyncing;

    /** 跨线程取消标志；只在步骤边界检查，不能打断进行中的 IO。 */
    private boolean mCancelled;

    /** 远端文件夹快照：gid 到 TaskList，供按名称匹配和挂载子任务。 */
    private HashMap<String, TaskList> mGTaskListHashMap;

    /** 远端节点快照：gid 到 Node；已与本地配对的节点会被移除，剩下的即远端独有。 */
    private HashMap<String, Node> mGTaskHashMap;

    /** 元数据索引：便签 gid 到 MetaData，用来判断远端内容版本与本地主键。 */
    private HashMap<String, MetaData> mMetaHashMap;

    /** 远端隐藏的 meta 文件夹；不存在时会在第一轮同步中创建。 */
    private TaskList mMetaList;

    /** 本地回收站中待批量删除的记录 id；同步末尾统一清理。 */
    private HashSet<Long> mLocalDeleteIdMap;

    /** 本轮 gid 到本地 id 的映射，用于给新建本地记录找父目录。 */
    private HashMap<String, Long> mGidToNid;

    /** 本轮本地 id 到 gid 的映射，用于给本地记录找父任务列表。 */
    private HashMap<Long, String> mNidToGid;

    /**
     * 私有构造：只初始化容器与状态标志，不做任何 IO。
     *
     * <p>所有映射在每轮 {@link #sync} 开始时清空复用，避免上一轮的 gid
     * 残留被误判成“远端已删除”。</p>
     */
    private GTaskManager() {
        mSyncing = false;
        mCancelled = false;
        mGTaskListHashMap = new HashMap<String, TaskList>();
        mGTaskHashMap = new HashMap<String, Node>();
        mMetaHashMap = new HashMap<String, MetaData>();
        mMetaList = null;
        mLocalDeleteIdMap = new HashSet<Long>();
        mGidToNid = new HashMap<String, Long>();
        mNidToGid = new HashMap<Long, String>();
    }

    /**
     * 获取进程内单例。
     *
     * <p>加锁只防止多个入口并发首次创建；返回后实例字段本身仍不是线程安全的。</p>
     *
     * @return 全局唯一的 GTaskManager
     */
    public static synchronized GTaskManager getInstance() {
        if (mInstance == null) {
            mInstance = new GTaskManager();
        }
        return mInstance;
    }

    /**
     * 记录发起同步的 Activity，供获取 Google authtoken 时使用。
     *
     * <p>必须在首次 {@link #sync} 前调用；{@code GTaskSyncService.startSync(Activity)}
     * 是当前唯一注入点，因此从非 Activity 上下文发起的同步会因无法取 token 而失败。</p>
     *
     * @param activity 已在前台的 Activity，不能为 null
     */
    public synchronized void setActivityContext(Activity activity) {
        // used for getting authtoken
        mActivity = activity;
    }

    /**
     * 执行一次完整同步，是整条链路唯一入口（运行在后台线程）。
     *
     * <p>流程：清空上一轮状态 → 登录 Google Tasks → 拉取远端任务列表与任务
     * （{@link #initGTaskList()}）→ 按本地数据库做增删改决策（{@link #syncContent()}）
     * → 提交远端变更并刷新本地 sync_id。</p>
     *
     * @param context 用于获取 ContentResolver 的上下文，通常是 GTaskSyncService
     * @param asyncTask 用于向界面发布进度文案，不能为 null
     * @return {@link #STATE_SUCCESS}、{@link #STATE_NETWORK_ERROR}、
     *         {@link #STATE_INTERNAL_ERROR}、{@link #STATE_SYNC_IN_PROGRESS}
     *         或 {@link #STATE_SYNC_CANCELLED}
     *
     * <p>副作用：读写本地便签库、重建内存映射、向 Google 提交新增/修改/删除。</p>
     * <p>异常：内部不向外抛异常；网络异常归为网络错误，JSON/状态不一致归为内部错误。</p>
     */
    public int sync(Context context, GTaskASyncTask asyncTask) {
        if (mSyncing) {
            // 同一时刻只允许一个同步任务；重复触发直接返回，不排队等待。
            Log.d(TAG, "Sync is in progress");
            return STATE_SYNC_IN_PROGRESS;
        }
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mSyncing = true;
        mCancelled = false;
        // 每轮都从空映射开始，防止旧 gid 残留被当成“远端已删除”。
        mGTaskListHashMap.clear();
        mGTaskHashMap.clear();
        mMetaHashMap.clear();
        mLocalDeleteIdMap.clear();
        mGidToNid.clear();
        mNidToGid.clear();

        try {
            GTaskClient client = GTaskClient.getInstance();
            client.resetUpdateArray();

            // login google task
            if (!mCancelled) {
                // 登录返回 false 表示没有 Google 账户、token 失效或 HTTP 不通，
                // 统一按网络失败上报，让界面提示用户检查账户与网络。
                if (!client.login(mActivity)) {
                    throw new NetworkFailureException("login google task failed");
                }
            }

            // get the task list from google
            asyncTask.publishProgess(mContext.getString(R.string.sync_progress_init_list));
            // 先把远端全量快照装进内存，后面的比对不再逐条请求服务端。
            initGTaskList();

            // do content sync work
            asyncTask.publishProgess(mContext.getString(R.string.sync_progress_syncing));
            syncContent();
        } catch (NetworkFailureException e) {
            // 可重试：断网、超时、cookie 失效等外部原因。
            Log.e(TAG, e.toString());
            return STATE_NETWORK_ERROR;
        } catch (ActionFailureException e) {
            // 不可重试：JSON 结构不符、gid 映射缺失等逻辑错误。
            Log.e(TAG, e.toString());
            return STATE_INTERNAL_ERROR;
        } catch (Exception e) {
            // 兜底：未预期异常不能让后台线程直接崩溃，转成内部错误码。
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return STATE_INTERNAL_ERROR;
        } finally {
            // 无论成功失败都释放快照并复位互斥标志，否则后续同步永远返回“进行中”。
            mGTaskListHashMap.clear();
            mGTaskHashMap.clear();
            mMetaHashMap.clear();
            mLocalDeleteIdMap.clear();
            mGidToNid.clear();
            mNidToGid.clear();
            mSyncing = false;
        }

        // 主体流程没有抛异常时，再由取消标志决定是“完成”还是“用户取消”。
        return mCancelled ? STATE_SYNC_CANCELLED : STATE_SUCCESS;
    }

    /**
     * 拉取远端全部任务列表与任务，构建本轮同步的内存快照。
     *
     * <p>先定位或创建隐藏的 meta 文件夹（{@code MIUI/meta}），再加载所有带
     * {@code MIUI/} 前缀的文件夹及其任务。只有 {@code isWorthSaving()} 为 true
     * 的节点才进入映射，空任务视为已删除残留而丢弃。</p>
     *
     * @throws NetworkFailureException HTTP 请求失败时抛出；JSON 解析失败会被
     *         包装成 ActionFailureException
     *
     * <p>副作用：可能向远端新建 meta 文件夹；填充 mGTaskListHashMap /
     * mGTaskHashMap / mMetaHashMap。</p>
     */
    private void initGTaskList() throws NetworkFailureException {
        if (mCancelled)
            return;
        GTaskClient client = GTaskClient.getInstance();
        try {
            JSONArray jsTaskLists = client.getTaskLists();

            // init meta list first
            // meta 必须最先加载：普通任务的比对依赖 MetaData 中的本地主键。
            mMetaList = null;
            for (int i = 0; i < jsTaskLists.length(); i++) {
                JSONObject object = jsTaskLists.getJSONObject(i);
                String gid = object.getString(GTaskStringUtils.GTASK_JSON_ID);
                String name = object.getString(GTaskStringUtils.GTASK_JSON_NAME);

                if (name
                        .equals(GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_META)) {
                    // 只认名字完全匹配的 meta 文件夹，避免把用户自建的
                    // “MIUI/meta 备份”之类列表误当元数据仓库。
                    mMetaList = new TaskList();
                    mMetaList.setContentByRemoteJSON(object);

                    // load meta data
                    JSONArray jsMetas = client.getTaskList(gid);
                    for (int j = 0; j < jsMetas.length(); j++) {
                        object = (JSONObject) jsMetas.getJSONObject(j);
                        MetaData metaData = new MetaData();
                        metaData.setContentByRemoteJSON(object);
                        // notes 为空的元数据任务没有关联价值，直接丢弃。
                        if (metaData.isWorthSaving()) {
                            mMetaList.addChildTask(metaData);
                            if (metaData.getGid() != null) {
                                // 以“被描述的便签 gid”为键，而不是元数据任务自己的 gid。
                                mMetaHashMap.put(metaData.getRelatedGid(), metaData);
                            }
                        }
                    }
                }
            }

            // create meta list if not existed
            // 首次使用或用户手工删掉了 meta 文件夹时，远端没有地方存元数据，
            // 这里补建一个，否则新建的便签在下一轮会被误判为远端已删除。
            if (mMetaList == null) {
                mMetaList = new TaskList();
                mMetaList.setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX
                        + GTaskStringUtils.FOLDER_META);
                GTaskClient.getInstance().createTaskList(mMetaList);
            }

            // init task list
            for (int i = 0; i < jsTaskLists.length(); i++) {
                JSONObject object = jsTaskLists.getJSONObject(i);
                String gid = object.getString(GTaskStringUtils.GTASK_JSON_ID);
                String name = object.getString(GTaskStringUtils.GTASK_JSON_NAME);

                if (name.startsWith(GTaskStringUtils.MIUI_FOLDER_PREFFIX)
                        && !name.equals(GTaskStringUtils.MIUI_FOLDER_PREFFIX
                                + GTaskStringUtils.FOLDER_META)) {
                    // 只接管 MIUI/ 前缀的列表，用户自己的 Google Tasks 列表不碰；
                    // meta 已在第一轮单独处理，这里跳过。
                    TaskList tasklist = new TaskList();
                    tasklist.setContentByRemoteJSON(object);
                    mGTaskListHashMap.put(gid, tasklist);
                    mGTaskHashMap.put(gid, tasklist);

                    // load tasks
                    JSONArray jsTasks = client.getTaskList(gid);
                    for (int j = 0; j < jsTasks.length(); j++) {
                        object = (JSONObject) jsTasks.getJSONObject(j);
                        gid = object.getString(GTaskStringUtils.GTASK_JSON_ID);
                        Task task = new Task();
                        task.setContentByRemoteJSON(object);
                        if (task.isWorthSaving()) {
                            // 把同一 gid 的元数据挂到任务上，比对时才能拿到
                            // 远端内容对应的本地主键和修改时间。
                            task.setMetaInfo(mMetaHashMap.get(gid));
                            tasklist.addChildTask(task);
                            mGTaskHashMap.put(gid, task);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            // 服务端 JSON 结构变化属于协议层错误，重试无法修复，记为内部错误。
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("initGTaskList: handing JSONObject failed");
        }
    }

    /**
     * 以本地数据库为基准，与 {@link #initGTaskList()} 得到的远端快照逐条比对并执行同步动作。
     *
     * <p>处理顺序有语义依赖：先处理回收站中的本地删除（要删远端），再同步文件夹
     * （任务必须挂在已存在的列表上），然后同步便签正文，最后把远端剩余节点补到本地。
     * 每一步都会检查 {@code mCancelled}，所以取消后可能只完成前半段。</p>
     *
     * <p>副作用：写本地库、发远端请求、批量删除本地回收站记录、刷新 sync_id。</p>
     */
    private void syncContent() throws NetworkFailureException {
        int syncType;
        Cursor c = null;
        String gid;
        Node node;

        mLocalDeleteIdMap.clear();

        if (mCancelled) {
            return;
        }

        // for local deleted note
        // 回收站里的记录代表用户已在本地删除，远端对应节点也要删掉；
        // 同时把本地 id 记入集合，最后统一做一次批量删除。
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type<>? AND parent_id=?)", new String[] {
                            String.valueOf(Notes.TYPE_SYSTEM), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, null);
            if (c != null) {
                while (c.moveToNext()) {
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    node = mGTaskHashMap.get(gid);
                    // 远端还有对应节点才需要发删除；node 为空说明从未同步或已删过。
                    if (node != null) {
                        mGTaskHashMap.remove(gid);
                        doContentSync(Node.SYNC_ACTION_DEL_REMOTE, node, c);
                    }

                    // 无论有无 gid 都登记：回收站记录最终都要从本地库清除。
                    mLocalDeleteIdMap.add(c.getLong(SqlNote.ID_COLUMN));
                }
            } else {
                Log.w(TAG, "failed to query trash folder");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // sync folder first
        // 文件夹必须先同步：新建便签要用 mNidToGid 找到父列表的远端 gid，
        // 父列表没同步过就会因为映射缺失而抛内部错误。
        syncFolder();

        // for note existing in database
        // 回收站中的便签已在上一段处理，这里只取正常位置的便签正文。
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type=? AND parent_id<>?)", new String[] {
                            String.valueOf(Notes.TYPE_NOTE), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, NoteColumns.TYPE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        // 两边都存在：先建立双向映射，再让节点按 sync_id /
                        // local_modified / lastModified 判定谁更新。
                        mGTaskHashMap.remove(gid);
                        mGidToNid.put(gid, c.getLong(SqlNote.ID_COLUMN));
                        mNidToGid.put(c.getLong(SqlNote.ID_COLUMN), gid);
                        syncType = node.getSyncAction(c);
                    } else {
                        // 远端快照里找不到对应 gid：gid 为空是本地新建；
                        // 否则说明远端被删除（服务端不返回已删除任务）。
                        if (c.getString(SqlNote.GTASK_ID_COLUMN).trim().length() == 0) {
                            // local add
                            syncType = Node.SYNC_ACTION_ADD_REMOTE;
                        } else {
                            // remote delete
                            syncType = Node.SYNC_ACTION_DEL_LOCAL;
                        }
                    }
                    doContentSync(syncType, node, c);
                }
            } else {
                Log.w(TAG, "failed to query existing note in database");
            }

        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // go through remaining items
        // 此时仍留在快照里的节点 = 远端存在、本地没有对应记录，需要补建到本地。
        Iterator<Map.Entry<String, Node>> iter = mGTaskHashMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Node> entry = iter.next();
            node = entry.getValue();
            doContentSync(Node.SYNC_ACTION_ADD_LOCAL, node, null);
        }

        // mCancelled can be set by another thread, so we neet to check one by
        // one
        // clear local delete table
        // 取消后不做破坏性清理，避免“远端已删、本地还没删”的半同步状态被固化。
        if (!mCancelled) {
            if (!DataUtils.batchDeleteNotes(mContentResolver, mLocalDeleteIdMap)) {
                throw new ActionFailureException("failed to batch-delete local deleted notes");
            }
        }

        // refresh local sync id
        // 先 flush 批量更新队列，再重新拉一次远端，才能拿到新建条目的 gid
        // 和最新 lastModified 写回 sync_id。
        if (!mCancelled) {
            GTaskClient.getInstance().commitUpdate();
            refreshLocalSyncId();
        }

    }

    /**
     * 同步文件夹层：两个固定的系统文件夹（根目录、通话记录）以及用户自建文件夹。
     *
     * <p>系统文件夹在本地 ID 固定，只允许改远端名称，不能删了重建；用户文件夹
     * 先按 gid 与远端列表配对，本地新建时再按名称去重，配不上的按新增/删除处理。</p>
     *
     * <p>副作用：可能创建、改名或删除远端列表，并在末尾提交一次批量更新。</p>
     */
    private void syncFolder() throws NetworkFailureException {
        Cursor c = null;
        String gid;
        Node node;
        int syncType;

        if (mCancelled) {
            return;
        }

        // for root folder
        // 根目录在本地是固定行（ID_ROOT_FOLDER），远端名固定为 MIUI/default，
        // 名称不一致时只改名，绝不重建；删除列表会连带删掉其下全部远端任务。
        try {
            c = mContentResolver.query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI,
                    Notes.ID_ROOT_FOLDER), SqlNote.PROJECTION_NOTE, null, null, null);
            if (c != null) {
                c.moveToNext();
                gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                node = mGTaskHashMap.get(gid);
                if (node != null) {
                    mGTaskHashMap.remove(gid);
                    mGidToNid.put(gid, (long) Notes.ID_ROOT_FOLDER);
                    mNidToGid.put((long) Notes.ID_ROOT_FOLDER, gid);
                    // for system folder, only update remote name if necessary
                    if (!node.getName().equals(
                            GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT))
                        doContentSync(Node.SYNC_ACTION_UPDATE_REMOTE, node, c);
                } else {
                    // 这里 node 为 null 是有意为之：addRemoteNode 的文件夹分支
                    // 只读游标、不读 node，靠本地记录重建远端列表。
                    doContentSync(Node.SYNC_ACTION_ADD_REMOTE, node, c);
                }
            } else {
                Log.w(TAG, "failed to query root folder");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // for call-note folder
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE, "(_id=?)",
                    new String[] {
                        String.valueOf(Notes.ID_CALL_RECORD_FOLDER)
                    }, null);
            if (c != null) {
                if (c.moveToNext()) {
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        mGTaskHashMap.remove(gid);
                        mGidToNid.put(gid, (long) Notes.ID_CALL_RECORD_FOLDER);
                        mNidToGid.put((long) Notes.ID_CALL_RECORD_FOLDER, gid);
                        // for system folder, only update remote name if
                        // necessary
                        // 通话记录目录同样是本地固定行，只允许改远端显示名。
                        if (!node.getName().equals(
                                GTaskStringUtils.MIUI_FOLDER_PREFFIX
                                        + GTaskStringUtils.FOLDER_CALL_NOTE))
                            doContentSync(Node.SYNC_ACTION_UPDATE_REMOTE, node, c);
                    } else {
                        // 同根目录：node 为空走文件夹新建分支，不读取 node。
                        doContentSync(Node.SYNC_ACTION_ADD_REMOTE, node, c);
                    }
                }
            } else {
                Log.w(TAG, "failed to query call note folder");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // for local existing folders
        // 用户自建文件夹：按 gid 配对，配不上再按“有无 gid”区分新建还是远端删除。
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type=? AND parent_id<>?)", new String[] {
                            String.valueOf(Notes.TYPE_FOLDER), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, NoteColumns.TYPE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        mGTaskHashMap.remove(gid);
                        mGidToNid.put(gid, c.getLong(SqlNote.ID_COLUMN));
                        mNidToGid.put(c.getLong(SqlNote.ID_COLUMN), gid);
                        syncType = node.getSyncAction(c);
                    } else {
                        if (c.getString(SqlNote.GTASK_ID_COLUMN).trim().length() == 0) {
                            // local add
                            syncType = Node.SYNC_ACTION_ADD_REMOTE;
                        } else {
                            // remote delete
                            syncType = Node.SYNC_ACTION_DEL_LOCAL;
                        }
                    }
                    doContentSync(syncType, node, c);
                }
            } else {
                Log.w(TAG, "failed to query existing folder");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // for remote add folders
        // 快照里仍未被本地认领的列表 = 远端新建，需要在本地补建对应文件夹。
        // 系统文件夹已在上面处理并从快照移除，这里不会重复创建。
        Iterator<Map.Entry<String, TaskList>> iter = mGTaskListHashMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, TaskList> entry = iter.next();
            gid = entry.getKey();
            node = entry.getValue();
            if (mGTaskHashMap.containsKey(gid)) {
                mGTaskHashMap.remove(gid);
                doContentSync(Node.SYNC_ACTION_ADD_LOCAL, node, null);
            }
        }

        if (!mCancelled)
            GTaskClient.getInstance().commitUpdate();
    }

    /**
     * 按同步动作码分派到具体处理函数，是所有增删改的唯一出口。
     *
     * @param syncType {@code Node.SYNC_ACTION_*} 常量
     * @param node 远端节点；本地新增（ADD_REMOTE）时可能为 null，
     *             但该分支的文件夹路径不会读取它
     * @param c 当前本地记录游标；远端新增（ADD_LOCAL）时传 null
     *
     * <p>异常：动作码为 ERROR 或越界时抛 ActionFailureException，整轮同步判为
     * 内部错误并中止，避免带着未知状态继续写数据。</p>
     */
    private void doContentSync(int syncType, Node node, Cursor c) throws NetworkFailureException {
        if (mCancelled) {
            return;
        }

        MetaData meta;
        switch (syncType) {
            case Node.SYNC_ACTION_ADD_LOCAL:
                // 远端有、本地没有：在本地补建便签或文件夹。
                addLocalNode(node);
                break;
            case Node.SYNC_ACTION_ADD_REMOTE:
                // 本地有、远端没有且从未同步过：在远端创建。
                addRemoteNode(node, c);
                break;
            case Node.SYNC_ACTION_DEL_LOCAL:
                // 远端已删除：先删对应的元数据任务，再把本地记录放进待删集合。
                // 这里不立即删本地行，是为了和回收站清理合并成一次批量操作。
                meta = mMetaHashMap.get(c.getString(SqlNote.GTASK_ID_COLUMN));
                if (meta != null) {
                    GTaskClient.getInstance().deleteNode(meta);
                }
                mLocalDeleteIdMap.add(c.getLong(SqlNote.ID_COLUMN));
                break;
            case Node.SYNC_ACTION_DEL_REMOTE:
                // 本地已删除：元数据和节点本身都要从远端删除，顺序不能反，
                // 元数据里还引用着该节点的 gid。
                meta = mMetaHashMap.get(node.getGid());
                if (meta != null) {
                    GTaskClient.getInstance().deleteNode(meta);
                }
                GTaskClient.getInstance().deleteNode(node);
                break;
            case Node.SYNC_ACTION_UPDATE_LOCAL:
                // 只有远端变化：用远端内容覆盖本地。
                updateLocalNode(node, c);
                break;
            case Node.SYNC_ACTION_UPDATE_REMOTE:
                // 只有本地变化：把本地内容推送到远端。
                updateRemoteNode(node, c);
                break;
            case Node.SYNC_ACTION_UPDATE_CONFLICT:
                // merging both modifications maybe a good idea
                // right now just use local update simply
                // 双方都改过时当前策略是“本地覆盖远端”，不做三路合并；
                // 冲突期间在另一台设备上的编辑会丢失，这是已知取舍。
                updateRemoteNode(node, c);
                break;
            case Node.SYNC_ACTION_NONE:
                // 双方 lastModified 一致，本轮无需任何操作。
                break;
            case Node.SYNC_ACTION_ERROR:
            default:
                // 元数据缺失或 gid 对不上，属于数据不一致；静默跳过会让错误
                // 一直残留，因此直接中止整轮同步。
                throw new ActionFailureException("unkown sync action type");
        }
    }

    /**
     * 把远端节点落地成本地记录（远端新增 → 本地）。
     *
     * <p>文件夹按名字映射到两个固定系统目录，其余挂到根目录；便签要先把远端
     * JSON 里的数据库主键剥掉，避免与本地已有记录撞 ID。</p>
     *
     * @param node 远端新增的 TaskList 或 Task
     *
     * <p>副作用：插入便签与正文数据、写入 gtask_id、创建或更新元数据。</p>
     * <p>异常：找不到父列表的本地 id 时抛 ActionFailureException，因为无法
     * 确定挂载位置，继续创建会产生孤儿记录。</p>
     */
    private void addLocalNode(Node node) throws NetworkFailureException {
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote;
        if (node instanceof TaskList) {
            // 系统文件夹在本地已有固定行，直接复用，不能再 insert 一条；
            // 其余远端列表按普通文件夹挂在根目录下。
            if (node.getName().equals(
                    GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT)) {
                sqlNote = new SqlNote(mContext, Notes.ID_ROOT_FOLDER);
            } else if (node.getName().equals(
                    GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_CALL_NOTE)) {
                sqlNote = new SqlNote(mContext, Notes.ID_CALL_RECORD_FOLDER);
            } else {
                sqlNote = new SqlNote(mContext);
                sqlNote.setContent(node.getLocalJSONFromContent());
                sqlNote.setParentId(Notes.ID_ROOT_FOLDER);
            }
        } else {
            sqlNote = new SqlNote(mContext);
            JSONObject js = node.getLocalJSONFromContent();
            try {
                if (js.has(GTaskStringUtils.META_HEAD_NOTE)) {
                    JSONObject note = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
                    if (note.has(NoteColumns.ID)) {
                        long id = note.getLong(NoteColumns.ID);
                        if (DataUtils.existInNoteDatabase(mContentResolver, id)) {
                            // the id is not available, have to create a new one
                            // 远端 JSON 里的 id 是原设备的自增主键，在本机可能
                            // 已被别的便签占用；删掉该字段让 SQLite 重新分配。
                            note.remove(NoteColumns.ID);
                        }
                    }
                }

                if (js.has(GTaskStringUtils.META_HEAD_DATA)) {
                    JSONArray dataArray = js.getJSONArray(GTaskStringUtils.META_HEAD_DATA);
                    for (int i = 0; i < dataArray.length(); i++) {
                        JSONObject data = dataArray.getJSONObject(i);
                        if (data.has(DataColumns.ID)) {
                            long dataId = data.getLong(DataColumns.ID);
                            if (DataUtils.existInDataDatabase(mContentResolver, dataId)) {
                                // the data id is not available, have to create
                                // a new one
                                // 正文数据行同理，主键冲突会让插入失败或覆盖他行。
                                data.remove(DataColumns.ID);
                            }
                        }
                    }

                }
            } catch (JSONException e) {
                Log.w(TAG, e.toString());
                e.printStackTrace();
            }
            sqlNote.setContent(js);

            // 父列表必须先由 syncFolder 落地并写入映射，否则不知道该挂到哪个本地目录。
            Long parentId = mGidToNid.get(((Task) node).getParent().getGid());
            if (parentId == null) {
                Log.e(TAG, "cannot find task's parent id locally");
                throw new ActionFailureException("cannot add local node");
            }
            sqlNote.setParentId(parentId.longValue());
        }

        // create the local node
        // 新建记录不做版本校验（还没有版本概念），commit 后 sqlNote 才拿到自增 id。
        sqlNote.setGtaskId(node.getGid());
        sqlNote.commit(false);

        // update gid-nid mapping
        // 立刻回填双向映射，后续同轮的子任务/子便签要靠它找父目录。
        mGidToNid.put(node.getGid(), sqlNote.getId());
        mNidToGid.put(sqlNote.getId(), node.getGid());

        // update meta
        // 本地新记录也要生成元数据，否则下次同步无法判断远端内容对应哪条本地行。
        updateRemoteMeta(node.getGid(), sqlNote);
    }

    /**
     * 用远端内容覆盖本地记录（远端较新 → 本地）。
     *
     * @param node 远端节点
     * @param c 命中的本地记录游标
     *
     * <p>副作用：更新便签内容与父目录、刷新元数据。{@code commit(true)} 带版本
     * 校验，如果同步期间用户又改了这条便签，本次覆盖会被跳过并只记警告日志，
     * 不会回滚已写入的其它记录。</p>
     * <p>异常：父列表的本地 id 缺失时抛 ActionFailureException。</p>
     */
    private void updateLocalNode(Node node, Cursor c) throws NetworkFailureException {
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote;
        // update the note locally
        sqlNote = new SqlNote(mContext, c);
        sqlNote.setContent(node.getLocalJSONFromContent());

        // 便签按父列表 gid 找本地目录；文件夹本身没有父级，固定挂根目录。
        Long parentId = (node instanceof Task) ? mGidToNid.get(((Task) node).getParent().getGid())
                : new Long(Notes.ID_ROOT_FOLDER);
        if (parentId == null) {
            Log.e(TAG, "cannot find task's parent id locally");
            throw new ActionFailureException("cannot update local node");
        }
        sqlNote.setParentId(parentId.longValue());
        sqlNote.commit(true);

        // update meta info
        // 覆盖本地后元数据里的 note id 等字段也要同步刷新，保持两边一致。
        updateRemoteMeta(node.getGid(), sqlNote);
    }

    /**
     * 把本地记录推到远端（本地新增 → 远端）。
     *
     * <p>便签需要先解析父文件夹的 gid；文件夹则先按名称去重，因为远端可能
     * 已有同名列表（例如上次同步只完成了一半），直接创建会出现两个同名文件夹。</p>
     *
     * @param node 通常为 null：本地新增没有远端节点，新建的远端节点由局部
     *             变量 n 承载；文件夹路径完全不读该参数
     * @param c 本地新增记录的游标
     *
     * <p>副作用：创建远端任务/列表、回填 gtask_id、清除本地修改标志。</p>
     * <p>异常：父文件夹 gid 缺失时抛 ActionFailureException。</p>
     */
    private void addRemoteNode(Node node, Cursor c) throws NetworkFailureException {
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote = new SqlNote(mContext, c);
        Node n;

        // update remotely
        if (sqlNote.isNoteType()) {
            // 便签本体先转成远端 JSON，再挂到父列表并立即创建（创建需要服务端
            // 分配 gid，不能延迟到批量提交）。
            Task task = new Task();
            task.setContentByLocalJSON(sqlNote.getContent());

            // 父目录必须已在本轮 syncFolder 中建立映射，否则无法确定位置。
            String parentGid = mNidToGid.get(sqlNote.getParentId());
            if (parentGid == null) {
                Log.e(TAG, "cannot find task's parent tasklist");
                throw new ActionFailureException("cannot add remote task");
            }
            mGTaskListHashMap.get(parentGid).addChildTask(task);

            GTaskClient.getInstance().createTask(task);
            n = (Node) task;

            // add meta
            // 创建成功后 task 才有 gid，元数据必须以新 gid 为键写入。
            updateRemoteMeta(task.getGid(), sqlNote);
        } else {
            TaskList tasklist = null;

            // we need to skip folder if it has already existed
            // 文件夹去重键是“MIUI/ + 本地名称”，系统目录用固定名，
            // 普通目录用 snippet；与 addLocalNode 解析系统目录的规则保持一致。
            String folderName = GTaskStringUtils.MIUI_FOLDER_PREFFIX;
            if (sqlNote.getId() == Notes.ID_ROOT_FOLDER)
                folderName += GTaskStringUtils.FOLDER_DEFAULT;
            else if (sqlNote.getId() == Notes.ID_CALL_RECORD_FOLDER)
                folderName += GTaskStringUtils.FOLDER_CALL_NOTE;
            else
                folderName += sqlNote.getSnippet();

            // 在远端快照里找同名列表；命中后还要把它从“远端独有”集合移除，
            // 否则本轮末尾会被误当成远端新增再在本地建一份。
            Iterator<Map.Entry<String, TaskList>> iter = mGTaskListHashMap.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, TaskList> entry = iter.next();
                String gid = entry.getKey();
                TaskList list = entry.getValue();

                if (list.getName().equals(folderName)) {
                    tasklist = list;
                    if (mGTaskHashMap.containsKey(gid)) {
                        mGTaskHashMap.remove(gid);
                    }
                    break;
                }
            }

            // no match we can add now
            if (tasklist == null) {
                // 远端确实没有同名列表才创建；成功后放进快照，供同轮后续
                // 便签挂载使用。
                tasklist = new TaskList();
                tasklist.setContentByLocalJSON(sqlNote.getContent());
                GTaskClient.getInstance().createTaskList(tasklist);
                mGTaskListHashMap.put(tasklist.getGid(), tasklist);
            }
            n = (Node) tasklist;
        }

        // update local note
        // 第一次写入远端 gid；第二次清掉 local_modified，避免下一轮把这条
        // 记录再次当成“本地有修改”而重复上传。
        sqlNote.setGtaskId(n.getGid());
        sqlNote.commit(false);
        sqlNote.resetLocalModified();
        sqlNote.commit(true);

        // gid-id mapping
        mGidToNid.put(n.getGid(), sqlNote.getId());
        mNidToGid.put(sqlNote.getId(), n.getGid());
    }

    /**
     * 用本地内容覆盖远端（本地较新 → 远端）。
     *
     * @param node 远端节点
     * @param c 本地记录游标
     *
     * <p>副作用：把更新动作放入批量队列、刷新元数据、必要时移动远端任务、
     * 清除本地修改标志。真正的网络提交发生在稍后的 commitUpdate。</p>
     * <p>异常：父列表 gid 缺失时抛 ActionFailureException。</p>
     */
    private void updateRemoteNode(Node node, Cursor c) throws NetworkFailureException {
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote = new SqlNote(mContext, c);

        // update remotely
        // 内容更新进队列，由 commitUpdate 合并成一次请求；元数据必须同步
        // 刷新，否则下一轮比对会拿到旧的 note id 与修改时间。
        node.setContentByLocalJSON(sqlNote.getContent());
        GTaskClient.getInstance().addUpdateNode(node);

        // update meta
        updateRemoteMeta(node.getGid(), sqlNote);

        // move task if necessary
        // 父目录改变不能靠普通 update 表达，必须单独发 move 动作；
        // 先比较对象引用即可，同一轮快照里每个列表只有一个实例。
        if (sqlNote.isNoteType()) {
            Task task = (Task) node;
            TaskList preParentList = task.getParent();

            // 新父目录必须已同步过，否则不知道该移到哪个远端列表。
            String curParentGid = mNidToGid.get(sqlNote.getParentId());
            if (curParentGid == null) {
                Log.e(TAG, "cannot find task's parent tasklist");
                throw new ActionFailureException("cannot update remote task");
            }
            TaskList curParentList = mGTaskListHashMap.get(curParentGid);

            if (preParentList != curParentList) {
                // 先在内存快照里移动，保持本轮后续判断与远端一致，再发请求。
                preParentList.removeChildTask(task);
                curParentList.addChildTask(task);
                GTaskClient.getInstance().moveTask(task, preParentList, curParentList);
            }
        }

        // clear local modified flag
        // 带版本校验地清除修改标记；若同步期间用户又编辑，update 会被跳过，
        // 只留下警告日志，下一轮会再次检测到本地修改。
        sqlNote.resetLocalModified();
        sqlNote.commit(true);
    }

    /**
     * 创建或更新某条便签对应的元数据任务。
     *
     * <p>元数据是隐藏在 meta 列表中的普通任务，notes 字段保存整条便签的
     * JSON 快照，并带 {@code gtask_id} 指回所属便签。下次同步靠它判断
     * “远端内容是否被修改”以及远端条目的本地主键。</p>
     *
     * @param gid 便签的远端 gid
     * @param sqlNote 本地记录；不是便签类型（如文件夹）时直接跳过
     *
     * <p>副作用：可能创建远端任务或将更新动作入队。</p>
     */
    private void updateRemoteMeta(String gid, SqlNote sqlNote) throws NetworkFailureException {
        // 文件夹没有正文，不需要元数据；只有便签才维护 JSON 快照。
        if (sqlNote != null && sqlNote.isNoteType()) {
            MetaData metaData = mMetaHashMap.get(gid);
            if (metaData != null) {
                // 已有元数据：原地更新内容并排队，由 commitUpdate 统一提交。
                metaData.setMeta(gid, sqlNote.getContent());
                GTaskClient.getInstance().addUpdateNode(metaData);
            } else {
                // 首次出现：必须立即创建，服务端分配 gid 后下一轮才能按 gid 索引。
                metaData = new MetaData();
                metaData.setMeta(gid, sqlNote.getContent());
                mMetaList.addChildTask(metaData);
                mMetaHashMap.put(gid, metaData);
                GTaskClient.getInstance().createTask(metaData);
            }
        }
    }

    /**
     * 同步提交成功后，用服务端最新状态回填本地 {@code sync_id}。
     *
     * <p>{@code sync_id} 保存上次同步时远端节点的 lastModified，是下次判断
     * “远端是否变化”的唯一依据；不刷新会导致每轮都把未变化的条目重同步一遍。</p>
     *
     * <p>为拿到新建/移动后的 gid 与 lastModified，这里重新拉一次完整远端列表。</p>
     *
     * <p>异常：本地条目在最新远端快照中找不到对应 gid（常见于新建后未成功
     * 回填）时抛 ActionFailureException——说明前面的同步逻辑漏处理了记录，
     * 继续运行会造成数据不一致。</p>
     */
    private void refreshLocalSyncId() throws NetworkFailureException {
        if (mCancelled) {
            return;
        }

        // get the latest gtask list
        // 必须丢弃旧快照：新建条目的 gid 和 lastModified 只有重新拉取才拿得到。
        mGTaskHashMap.clear();
        mGTaskListHashMap.clear();
        mMetaHashMap.clear();
        initGTaskList();

        Cursor c = null;
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type<>? AND parent_id<>?)", new String[] {
                            String.valueOf(Notes.TYPE_SYSTEM), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, NoteColumns.TYPE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    String gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    Node node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        mGTaskHashMap.remove(gid);
                        // 用远端 lastModified 作为下次比对的基准时间戳。
                        ContentValues values = new ContentValues();
                        values.put(NoteColumns.SYNC_ID, node.getLastModified());
                        mContentResolver.update(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI,
                                c.getLong(SqlNote.ID_COLUMN)), values, null, null);
                    } else {
                        // 刚同步完就找不到 gid，说明创建/映射环节有遗漏，
                        // 不能静默继续，否则这条记录以后每轮都会“复活”。
                        Log.e(TAG, "something is missed");
                        throw new ActionFailureException(
                                "some local items don't have gid after sync");
                    }
                }
            } else {
                Log.w(TAG, "failed to query local note to refresh sync id");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }
    }

    /**
     * 返回当前登录的 Google 账户名，用于通知栏与设置页展示。
     *
     * @return 账户名；若从未成功登录（{@code mAccount} 为 null）调用处会触发
     *         空指针，因此只能在同步成功后使用
     */
    public String getSyncAccount() {
        return GTaskClient.getInstance().getSyncAccount().name;
    }

    /**
     * 请求取消当前同步（可从任意线程调用）。
     *
     * <p>只置标志位，不中断正在执行的 HTTP 或数据库操作；各步骤在边界处检查
     * {@code mCancelled}，因此取消是“尽快停止”而不是“立即停止”。</p>
     */
    public void cancelSync() {
        mCancelled = true;
    }
}
