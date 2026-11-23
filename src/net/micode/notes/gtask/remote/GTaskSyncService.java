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
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * 同步后台服务：把一次同步包装进 Service 生命周期，并提供静态启动/取消入口。
 *
 * <p>职责：接收带 action extra 的 Intent，创建并启动 {@link GTaskASyncTask}，
 * 在同步过程中向应用内广播进度，结束后停止自身。不持有业务逻辑，也不直接
 * 访问数据库或网络。</p>
 *
 * <p>架构位置：gtask.remote 包的界面侧入口。设置页/列表页通过
 * {@link #startSync(Activity)} 启动；{@link GTaskASyncTask} 把进度回传本服务，
 * 本服务再用 {@link #sendBroadcast(String)} 通知界面刷新。</p>
 *
 * <p>关键协作类：{@link GTaskASyncTask}（真正执行同步的 AsyncTask）、
 * {@link GTaskManager}（同步器单例）、{@code NotesListActivity}（监听广播）。</p>
 *
 * <p>状态边界：mSyncTask / mSyncProgress 是静态字段，等价于进程内单例；
 * 进程被杀后状态丢失，正在进行的同步也随之终止。</p>
 */
public class GTaskSyncService extends Service {
    /** Intent extra 键：本次启动要执行的动作类型。 */
    public final static String ACTION_STRING_NAME = "sync_action_type";

    /** 动作值：开始同步。 */
    public final static int ACTION_START_SYNC = 0;

    /** 动作值：请求取消当前同步。 */
    public final static int ACTION_CANCEL_SYNC = 1;

    /** 动作值：非法或缺省动作，onStartCommand 中直接忽略。 */
    public final static int ACTION_INVALID = 2;

    /** 应用内广播 action：同步状态与进度。 */
    public final static String GTASK_SERVICE_BROADCAST_NAME = "net.micode.notes.gtask.remote.gtask_sync_service";

    /** 广播 extra：布尔值，表示当前是否有同步任务。 */
    public final static String GTASK_SERVICE_BROADCAST_IS_SYNCING = "isSyncing";

    /** 广播 extra：字符串，当前进度文案。 */
    public final static String GTASK_SERVICE_BROADCAST_PROGRESS_MSG = "progressMsg";

    /** 当前同步任务；非 null 即表示同步进行中，也是 isSyncing 的判断依据。 */
    private static GTaskASyncTask mSyncTask = null;

    /** 最近一次广播的进度文案，供未及时收到广播的界面查询。 */
    private static String mSyncProgress = "";

    /**
     * 创建并启动同步任务；已有任务在跑时直接忽略。
     *
     * <p>回调里先把静态引用清空再广播，这样界面收到“结束”广播时
     * {@code isSyncing()} 已经是 false，不会出现进度条卡住。</p>
     *
     * <p>副作用：启动 AsyncTask（后台线程）、发广播、完成后 stopSelf。</p>
     */
    private void startSync() {
        // 同一时刻只保留一个同步任务；重复点击同步按钮不会排队。
        if (mSyncTask == null) {
            mSyncTask = new GTaskASyncTask(this, new GTaskASyncTask.OnCompleteListener() {
                public void onComplete() {
                    // 先清空引用再广播：sendBroadcast 用引用判断 isSyncing，
                    // 顺序反了界面会一直显示“同步中”。
                    mSyncTask = null;
                    sendBroadcast("");
                    stopSelf();
                }
            });
            // 立即广播一次“开始”，让界面在登录阶段就能显示进度条。
            sendBroadcast("");
            mSyncTask.execute();
        }
    }

    /**
     * 请求取消当前同步；没有任务时为空操作。
     *
     * <p>取消是协作式的：{@link GTaskASyncTask#cancelSync()} 只置标志，
     * 同步会在下一个检查点退出。</p>
     */
    private void cancelSync() {
        if (mSyncTask != null) {
            mSyncTask.cancelSync();
        }
    }

    /**
     * Service 创建时复位静态任务引用。
     *
     * <p>onCreate 早于 onStartCommand。同一进程内 Service 可能被销毁后重建，
     * 而静态引用仍指向旧任务，这里复位可避免 isSyncing 假阳性。</p>
     */
    @Override
    public void onCreate() {
        mSyncTask = null;
    }

    /**
     * 根据 Intent 中的 action 启动或取消同步。
     *
     * @param intent 携带 {@link #ACTION_STRING_NAME} 的启动意图
     * @param flags 系统传入的启动标志，本服务不使用
     * @param startId 本次启动的请求 id，本服务不使用
     * @return 带动作时返回 START_STICKY 保持服务存活；否则交回父类默认处理
     *
     * <p>边界：系统在资源回收后重建服务时可能传入 null Intent，此时
     * {@code intent.getExtras()} 会抛空指针；当前实现未做防护。</p>
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle bundle = intent.getExtras();
        if (bundle != null && bundle.containsKey(ACTION_STRING_NAME)) {
            switch (bundle.getInt(ACTION_STRING_NAME, ACTION_INVALID)) {
                case ACTION_START_SYNC:
                    startSync();
                    break;
                case ACTION_CANCEL_SYNC:
                    cancelSync();
                    break;
                default:
                    // 未知动作静默忽略，避免旧版本界面用新常量时误启动同步。
                    break;
            }
            return START_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * 系统内存不足时主动取消同步。
     *
     * <p>同步持有整份远端快照和大量 JSON，继续执行可能被系统直接杀掉；
     * 主动取消至少能让已提交的部分保持一致。</p>
     */
    @Override
    public void onLowMemory() {
        if (mSyncTask != null) {
            mSyncTask.cancelSync();
        }
    }

    /**
     * 本服务只支持 startService 方式，不支持绑定。
     *
     * @param intent 绑定意图，本服务忽略
     * @return 恒为 null
     */
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 广播当前同步状态与进度文案。
     *
     * @param msg 展示给用户的进度文本；空串表示“开始/结束”的状态切换
     *
     * <p>副作用：写入静态 mSyncProgress；发送应用内广播。isSyncing 取
     * {@code mSyncTask != null}，因此结束时回调必须先清引用再调用本方法。</p>
     */
    public void sendBroadcast(String msg) {
        mSyncProgress = msg;
        Intent intent = new Intent(GTASK_SERVICE_BROADCAST_NAME);
        intent.putExtra(GTASK_SERVICE_BROADCAST_IS_SYNCING, mSyncTask != null);
        intent.putExtra(GTASK_SERVICE_BROADCAST_PROGRESS_MSG, msg);
        sendBroadcast(intent);
    }

    /**
     * 从界面发起同步。
     *
     * <p>先把 Activity 注入 {@link GTaskManager}，因为登录时需要它弹出账户
     * 授权界面；再通过 startService 把请求转交给本服务，避免在 UI 线程
     * 直接做网络操作。</p>
     *
     * @param activity 发起同步的界面，同时作为 authtoken 的授权上下文
     */
    public static void startSync(Activity activity) {
        GTaskManager.getInstance().setActivityContext(activity);
        Intent intent = new Intent(activity, GTaskSyncService.class);
        intent.putExtra(GTaskSyncService.ACTION_STRING_NAME, GTaskSyncService.ACTION_START_SYNC);
        activity.startService(intent);
    }

    /**
     * 从任意上下文请求取消同步。
     *
     * <p>同样走 startService，保证取消动作与同步任务在同一进程、同一套
     * 生命周期里处理。</p>
     *
     * @param context 用于构造启动 Intent 的上下文
     */
    public static void cancelSync(Context context) {
        Intent intent = new Intent(context, GTaskSyncService.class);
        intent.putExtra(GTaskSyncService.ACTION_STRING_NAME, GTaskSyncService.ACTION_CANCEL_SYNC);
        context.startService(intent);
    }

    /**
     * 是否有同步任务在运行。
     *
     * @return 静态引用非空即为 true；跨进程或进程重启后不可靠
     */
    public static boolean isSyncing() {
        return mSyncTask != null;
    }

    /**
     * 返回最近一次广播的进度文案。
     *
     * @return 进度文本；从未同步过时为空串
     */
    public static String getProgressString() {
        return mSyncProgress;
    }
}
