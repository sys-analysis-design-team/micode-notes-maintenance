
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;

import net.micode.notes.R;
import net.micode.notes.ui.NotesListActivity;
import net.micode.notes.ui.NotesPreferenceActivity;


/**
 * 同步任务的后台执行器：把 {@link GTaskManager#sync} 包进 AsyncTask，
 * 负责进度通知与完成回调。
 *
 * <p>职责：在后台线程执行同步，把进度字符串切回主线程更新通知栏并向
 * {@link GTaskSyncService} 转发广播；结束后按结果码展示成功/网络失败/
 * 内部错误/已取消四种通知，并回调监听者。</p>
 *
 * <p>架构位置：gtask.remote 包中连接 Service 与 Manager 的中间层，由
 * {@link GTaskSyncService} 创建，一个实例对应一次同步。</p>
 *
 * <p>关键协作类：{@link GTaskManager}（同步逻辑与结果码）、
 * {@link GTaskSyncService}（进度广播）、{@code NotesPreferenceActivity}
 * （账户名与上次同步时间）。</p>
 *
 * <p>线程边界：doInBackground 在后台线程，onProgressUpdate / onPostExecute
 * 在主线程；OnCompleteListener 被额外放到新线程里调用，实现方不能假设
 * 自己在主线程。</p>
 */
public class GTaskASyncTask extends AsyncTask<Void, String, Integer> {

    /** 同步通知的固定 id：始终覆盖同一条通知，不在通知栏堆积。 */
    private static int GTASK_SYNC_NOTIFICATION_ID = 5234235;

    /** Android 8.0+ 的通知渠道 id，渠道名复用应用名。 */
    private static final String SYNC_CHANNEL_ID = "gtask_sync";

    /**
     * 同步结束回调。
     *
     * <p>由 onPostExecute 中新建的线程调用，实现方需自行切回主线程再操作界面。</p>
     */
    public interface OnCompleteListener {
        void onComplete();
    }

    /** 创建本任务的上下文，通常是 GTaskSyncService。 */
    private Context mContext;

    /** 系统通知服务，用于展示同步进度与结果。 */
    private NotificationManager mNotifiManager;

    /** 同步器单例；构造时取得，实际同步在 doInBackground 中执行。 */
    private GTaskManager mTaskManager;

    /** 完成回调，可为 null。 */
    private OnCompleteListener mOnCompleteListener;

    /**
     * 绑定上下文并取到同步器单例。
     *
     * @param context 通常是 GTaskSyncService；用于取通知服务和判断是否回发广播
     * @param listener 完成回调，可为 null
     *
     * <p>副作用：获取系统 NotificationManager；不发起任何网络请求。</p>
     */
    public GTaskASyncTask(Context context, OnCompleteListener listener) {
        mContext = context;
        mOnCompleteListener = listener;
        mNotifiManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mTaskManager = GTaskManager.getInstance();
    }

    /**
     * 请求取消同步，转发给 {@link GTaskManager}。
     *
     * <p>只置标志位；后台的 doInBackground 会在下一个检查点退出，已经发出
     * 的网络请求不会被强行中断。</p>
     */
    public void cancelSync() {
        mTaskManager.cancelSync();
    }

    /**
     * 发布一条进度文案（AsyncTask 的 publishProgress 包装）。
     *
     * <p>方法名沿用历史拼写 “Progess”，调用方已固定，改名会破坏现有调用。</p>
     *
     * @param message 展示在通知栏的进度文本
     *
     * <p>副作用：进度经 AsyncTask 切回主线程，由 onProgressUpdate 处理。</p>
     */
    public void publishProgess(String message) {
        publishProgress(new String[] {
            message
        });
    }

    /**
     * 更新同步状态通知。
     *
     * @param tickerId 状态栏文案资源 id，同时决定点击通知的跳转页面：
     *                 成功进便签列表，其余进设置页（便于修正账户）
     * @param content 通知正文，通常是当前进度或错误说明
     *
     * <p>副作用：Android 8.0+ 会创建/复用通知渠道；以固定 id 覆盖更新通知。</p>
     */
    private void showNotification(int tickerId, String content) {
        // Android 8.0+ 必须先创建通知渠道，否则通知不会展示。
        // IMPORTANCE_LOW 表示不发声、不弹悬浮窗，避免每次同步打扰用户。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(SYNC_CHANNEL_ID,
                    mContext.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            mNotifiManager.createNotificationChannel(channel);
        }

        PendingIntent pendingIntent;
        if (tickerId != R.string.ticker_success) {
            // 失败/取消时跳到设置页：用户最可能需要检查账户、网络或重新登录。
            pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(mContext,
                    NotesPreferenceActivity.class), PendingIntent.FLAG_IMMUTABLE);
        } else {
            // 成功时跳到便签列表，直接看到同步后的内容。
            // FLAG_IMMUTABLE 是 Android 12+ 的强制要求，缺少会抛异常。
            pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(mContext,
                    NotesListActivity.class), PendingIntent.FLAG_IMMUTABLE);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(mContext, SYNC_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(mContext);
        }
        builder.setSmallIcon(R.drawable.notification)
                .setTicker(mContext.getString(tickerId))
                .setContentTitle(mContext.getString(R.string.app_name))
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());
        mNotifiManager.notify(GTASK_SYNC_NOTIFICATION_ID, builder.build());
    }

    /**
     * 后台线程入口：先发布“正在登录”提示，再执行完整同步。
     *
     * @param unused AsyncTask 规定的可变参数，本任务不需要输入
     * @return {@link GTaskManager} 的结果码，供 onPostExecute 选择通知文案
     *
     * <p>异常：sync 内部已捕获所有异常，这里不会向外抛。</p>
     */
    @Override
    protected Integer doInBackground(Void... unused) {
        publishProgess(mContext.getString(R.string.sync_progress_login, NotesPreferenceActivity
                .getSyncAccountName(mContext)));
        return mTaskManager.sync(mContext, this);
    }

    /**
     * 主线程接收进度：刷新通知栏，并把进度转发给 Service 广播。
     *
     * @param progress 至少含一个元素的进度文案数组，来自 publishProgess
     *
     * <p>只有上下文是 GTaskSyncService 时才广播，便于本类在其它场景复用。</p>
     */
    @Override
    protected void onProgressUpdate(String... progress) {
        showNotification(R.string.ticker_syncing, progress[0]);
        if (mContext instanceof GTaskSyncService) {
            ((GTaskSyncService) mContext).sendBroadcast(progress[0]);
        }
    }

    /**
     * 同步结束：按结果码展示对应通知，并触发完成回调。
     *
     * <p>成功时记录本次同步时间到偏好设置。回调放到新线程执行，避免监听者在
     * onPostExecute（主线程）里直接 stopSelf 或做耗时操作造成重入；
     * 调用方必须自行切回主线程再更新界面。</p>
     *
     * @param result {@link GTaskManager} 的结果码；未知码不展示通知
     */
    @Override
    protected void onPostExecute(Integer result) {
        // 四种结果码对应四种用户可见状态；STATE_SYNC_IN_PROGRESS 等其它码
        // 理论上不会从 sync 返回，这里不处理。
        if (result == GTaskManager.STATE_SUCCESS) {
            showNotification(R.string.ticker_success, mContext.getString(
                    R.string.success_sync_account, mTaskManager.getSyncAccount()));
            // 记录成功时间，设置页用它显示“上次同步”。
            NotesPreferenceActivity.setLastSyncTime(mContext, System.currentTimeMillis());
        } else if (result == GTaskManager.STATE_NETWORK_ERROR) {
            showNotification(R.string.ticker_fail, mContext.getString(R.string.error_sync_network));
        } else if (result == GTaskManager.STATE_INTERNAL_ERROR) {
            showNotification(R.string.ticker_fail, mContext.getString(R.string.error_sync_internal));
        } else if (result == GTaskManager.STATE_SYNC_CANCELLED) {
            showNotification(R.string.ticker_cancel, mContext
                    .getString(R.string.error_sync_cancelled));
        }
        if (mOnCompleteListener != null) {
            // 回调必须离开主线程：GTaskSyncService 的 onComplete 会 stopSelf，
            // 在 onPostExecute 里同步调用容易造成生命周期重入。
            new Thread(new Runnable() {

                public void run() {
                    mOnCompleteListener.onComplete();
                }
            }).start();
        }
    }
}
