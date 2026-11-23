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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.gtask.data.Node;
import net.micode.notes.gtask.data.Task;
import net.micode.notes.gtask.data.TaskList;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.gtask.exception.NetworkFailureException;
import net.micode.notes.tool.GTaskStringUtils;
import net.micode.notes.ui.NotesPreferenceActivity;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;


/**
 * Google Tasks 私有 Web 接口的 HTTP 客户端与登录凭据管理器。
 *
 * <p>职责：用 Android AccountManager 取得 Google 账户的 authtoken，换取
 * mail.google.com/tasks 的会话 cookie；之后以表单方式 POST JSON 动作，
 * 完成任务的增删改查与移动。不负责业务层的同步决策（那是
 * {@link GTaskManager} 的事），也不解析节点内容（Node 子类负责）。</p>
 *
 * <p>架构位置：gtask.remote 包的下层。{@link GTaskManager} 每轮同步只通过
 * 单例取得本类，先 {@link #login(Activity)}，再调用
 * {@link #getTaskLists()} / {@link #getTaskList(String)} 拉取数据，
 * 用 {@link #addUpdateNode(Node)} 入队、{@link #commitUpdate()} 提交。</p>
 *
 * <p>关键协作类：{@link GTaskManager}（唯一调用方）、{@link Node}/{@code Task}/
 * {@code TaskList}（生成动作 JSON）、{@code NotesPreferenceActivity}（读取用户
 * 选定的同步账户名）。</p>
 *
 * <p>协议边界：这是 Google Tasks 的网页内部接口，不是公开 API。请求依赖登录
 * cookie、{@code AT: 1} 头和 {@code client_version}，任何一项变化都可能返回
 * 登录页或 400；本类按“响应解析失败即失败”处理，不做协议级重试。</p>
 */
public class GTaskClient {
    private static final String TAG = GTaskClient.class.getSimpleName();

    /** 企业域名账户的地址前缀，后面会拼接 /a/<域名>/。 */
    private static final String GTASK_URL = "https://mail.google.com/tasks/";

    /** 默认的读取地址（列表页初始化脚本）。 */
    private static final String GTASK_GET_URL = "https://mail.google.com/tasks/ig";

    /** 默认的动作提交地址。 */
    private static final String GTASK_POST_URL = "https://mail.google.com/tasks/r/ig";

    /** 进程内唯一实例；会话 cookie 保存在其 mHttpClient 中。 */
    private static GTaskClient mInstance = null;

    /** 持有 cookie 的 HTTP 客户端；每次登录成功都会重建。 */
    private DefaultHttpClient mHttpClient;

    /** 当前会话的读取地址；企业账户会改成 /a/<域名>/ig。 */
    private String mGetUrl;

    /** 当前会话的提交地址；与 mGetUrl 配套切换。 */
    private String mPostUrl;

    /** 网页端协议版本号，从登录页脚本的 "_setup" 数据中解析，请求必须携带。 */
    private long mClientVersion;

    /** 是否已有可用会话；false 时所有读写方法都会拒绝执行。 */
    private boolean mLoggedin;

    /** 上次登录时间，用于 5 分钟会话复用判断。 */
    private long mLastLoginTime;

    /** 动作 ID 计数器；服务端按 ID 与结果数组对应。 */
    private int mActionId;

    /** 登录成功的 Google 账户；未登录时为 null。 */
    private Account mAccount;

    /** 待提交的更新动作队列；commitUpdate 时一次性发出。 */
    private JSONArray mUpdateArray;

    /**
     * 私有构造：只初始化字段，不建立连接。
     *
     * <p>mActionId 从 1 开始，保证动作 ID 非零；mClientVersion 初始为 -1，
     * 登录成功后由 loginGtask 写入服务端返回的真实版本号。</p>
     */
    private GTaskClient() {
        mHttpClient = null;
        mGetUrl = GTASK_GET_URL;
        mPostUrl = GTASK_POST_URL;
        mClientVersion = -1;
        mLoggedin = false;
        mLastLoginTime = 0;
        mActionId = 1;
        mAccount = null;
        mUpdateArray = null;
    }

    /**
     * 获取进程内单例。
     *
     * <p>加锁只防止首次并发创建；会话状态本身不是线程安全的，调用方应保证
     * 同步流程串行执行。</p>
     *
     * @return 全局唯一的 GTaskClient
     */
    public static synchronized GTaskClient getInstance() {
        if (mInstance == null) {
            mInstance = new GTaskClient();
        }
        return mInstance;
    }

    /**
     * 确保当前持有一个有效的 Google Tasks 会话。
     *
     * <p>复用条件：距上次登录不足 5 分钟且账户未切换。服务端 cookie 的真实
     * 过期时间无法读取，5 分钟是保守假设；账户切换后必须重新取 token，
     * 否则会用上一个账户的 cookie 读写错误的数据。</p>
     *
     * @param activity 用于 AccountManager 弹出授权/选择账户界面
     * @return 登录成功返回 true；没有 Google 账户、token 获取失败或两次
     *         HTTP 登录都失败时返回 false
     *
     * <p>副作用：修改 mLoggedin、mLastLoginTime、mAccount、mGetUrl/mPostUrl，
     * 并重建内部 HttpClient 与 cookie 存储。</p>
     */
    public boolean login(Activity activity) {
        // we suppose that the cookie would expire after 5 minutes
        // then we need to re-login
        final long interval = 1000 * 60 * 5;
        if (mLastLoginTime + interval < System.currentTimeMillis()) {
            mLoggedin = false;
        }

        // need to re-login after account switch
        // 会话按账户隔离：设置页换了账户就必须重新登录，不能沿用旧 cookie。
        if (mLoggedin
                && !TextUtils.equals(getSyncAccount().name, NotesPreferenceActivity
                        .getSyncAccountName(activity))) {
            mLoggedin = false;
        }

        if (mLoggedin) {
            Log.d(TAG, "already logged in");
            return true;
        }

        mLastLoginTime = System.currentTimeMillis();
        String authToken = loginGoogleAccount(activity, false);
        if (authToken == null) {
            Log.e(TAG, "login google account failed");
            return false;
        }

        // login with custom domain if necessary
        // 非 gmail.com/googlemail.com 的账户是 Google Workspace 托管域，
        // 任务页在 /a/<域名>/ 下，先按企业地址尝试。
        if (!(mAccount.name.toLowerCase().endsWith("gmail.com") || mAccount.name.toLowerCase()
                .endsWith("googlemail.com"))) {
            StringBuilder url = new StringBuilder(GTASK_URL).append("a/");
            int index = mAccount.name.indexOf('@') + 1;
            String suffix = mAccount.name.substring(index);
            url.append(suffix + "/");
            mGetUrl = url.toString() + "ig";
            mPostUrl = url.toString() + "r/ig";

            if (tryToLoginGtask(activity, authToken)) {
                mLoggedin = true;
            }
        }

        // try to login with google official url
        // 企业地址失败时回退到个人版地址；两种都失败才认定登录失败。
        if (!mLoggedin) {
            mGetUrl = GTASK_GET_URL;
            mPostUrl = GTASK_POST_URL;
            if (!tryToLoginGtask(activity, authToken)) {
                return false;
            }
        }

        mLoggedin = true;
        return true;
    }

    /**
     * 从系统账户服务获取指定 Google 账户的 authtoken。
     *
     * <p>只使用用户在设置页选定的账户（按名字精确匹配），不会自动挑第一个，
     * 避免把便签同步到错误账号。token 类型固定为 {@code goanna_mobile}，
     * 这是 Google Tasks 网页端接受的类型。</p>
     *
     * @param activity 提供账户授权界面
     * @param invalidateToken true 表示先作废旧 token 再重新获取
     * @return authtoken；没有 Google 账户、账户名不匹配或获取过程抛异常时
     *         返回 null
     *
     * <p>边界：invalidateToken 分支递归调用后没有使用返回值，调用方拿到的
     * 仍是作废前的 token；因此该重试路径可能连续失败两次，并非真正的
     * “换新 token 再试”。</p>
     */
    private String loginGoogleAccount(Activity activity, boolean invalidateToken) {
        String authToken;
        AccountManager accountManager = AccountManager.get(activity);
        Account[] accounts = accountManager.getAccountsByType("com.google");

        // 设备上一个 Google 账户都没有，直接失败，不必弹授权界面。
        if (accounts.length == 0) {
            Log.e(TAG, "there is no available google account");
            return null;
        }

        // 按设置页保存的账户名精确匹配；找不到就失败，绝不退而求其次用别的账户。
        String accountName = NotesPreferenceActivity.getSyncAccountName(activity);
        Account account = null;
        for (Account a : accounts) {
            if (a.name.equals(accountName)) {
                account = a;
                break;
            }
        }
        if (account != null) {
            mAccount = account;
        } else {
            Log.e(TAG, "unable to get an account with the same name in the settings");
            return null;
        }

        // get the token now
        // getAuthToken 可能弹出授权页，因此必须传 Activity 作为上下文；
        // 缺少 Activity 时无法完成授权流程。
        AccountManagerFuture<Bundle> accountManagerFuture = accountManager.getAuthToken(account,
                "goanna_mobile", null, activity, null, null);
        try {
            Bundle authTokenBundle = accountManagerFuture.getResult();
            authToken = authTokenBundle.getString(AccountManager.KEY_AUTHTOKEN);
            if (invalidateToken) {
                // 仅在 tryToLoginGtask 重试路径上使用：作废服务端缓存后重取。
                accountManager.invalidateAuthToken("com.google", authToken);
                loginGoogleAccount(activity, false);
            }
        } catch (Exception e) {
            // 用户取消授权或账户服务异常都走这里，统一返回 null。
            Log.e(TAG, "get auth token failed");
            authToken = null;
        }

        return authToken;
    }

    /**
     * 用 authtoken 登录 Tasks 网页端；失败时作废 token 再重试一次。
     *
     * <p>第一次失败最常见的原因是 token 已被服务端吊销但本地缓存仍有效，
     * 因此强制失效后重取；第二次仍失败就认定账户或网络不可用，不再重试。</p>
     *
     * @return 两次尝试内登录成功返回 true
     */
    private boolean tryToLoginGtask(Activity activity, String authToken) {
        if (!loginGtask(authToken)) {
            // maybe the auth token is out of date, now let's invalidate the
            // token and try again
            // 第二次取 token 失败就不必再试 HTTP 登录了。
            authToken = loginGoogleAccount(activity, true);
            if (authToken == null) {
                Log.e(TAG, "login google account failed");
                return false;
            }

            if (!loginGtask(authToken)) {
                Log.e(TAG, "login gtask failed");
                return false;
            }
        }
        return true;
    }

    /**
     * 真正建立 HTTP 会话：GET 登录地址、保存 cookie、解析 client_version。
     *
     * <p>成功标准不是 HTTP 200，而是能从返回 HTML 中截出
     * {@code _setup({...})}</script> 里的 JSON 并读到版本号 {@code v}；
     * 登录页改版或要求人机验证时，这里会解析失败并返回 false。</p>
     *
     * @param authToken AccountManager 取到的 Google 凭据
     * @return 会话可用返回 true；JSON 解析失败或任何 IO 异常返回 false
     *
     * <p>副作用：重建 mHttpClient（连接超时 10 秒、读超时 15 秒）、替换
     * cookie 会话、写入 mClientVersion。</p>
     */
    private boolean loginGtask(String authToken) {
        // 超时必须有上限：同步跑在后台线程，但也可能卡住整个同步队列。
        int timeoutConnection = 10000;
        int timeoutSocket = 15000;
        HttpParams httpParameters = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(httpParameters, timeoutConnection);
        HttpConnectionParams.setSoTimeout(httpParameters, timeoutSocket);
        mHttpClient = new DefaultHttpClient(httpParameters);
        BasicCookieStore localBasicCookieStore = new BasicCookieStore();
        mHttpClient.setCookieStore(localBasicCookieStore);
        HttpProtocolParams.setUseExpectContinue(mHttpClient.getParams(), false);

        // login gtask
        try {
            String loginUrl = mGetUrl + "?auth=" + authToken;
            HttpGet httpGet = new HttpGet(loginUrl);
            HttpResponse response = null;
            response = mHttpClient.execute(httpGet);

            // get the cookie now
            // GTL 是 Tasks 的会话 cookie；缺失通常意味着未真正登录，
            // 但这里只记警告、不直接失败，让后续请求自然暴露问题。
            List<Cookie> cookies = mHttpClient.getCookieStore().getCookies();
            boolean hasAuthCookie = false;
            for (Cookie cookie : cookies) {
                if (cookie.getName().contains("GTL")) {
                    hasAuthCookie = true;
                }
            }
            if (!hasAuthCookie) {
                Log.w(TAG, "it seems that there is no auth cookie");
            }

            // get the client version
            // 网页把初始化数据塞在脚本调用 _setup(...) 中，不是标准 JSON 响应，
            // 只能按固定前后缀截取。
            String resString = getResponseContent(response.getEntity());
            String jsBegin = "_setup(";
            String jsEnd = ")}</script>";
            int begin = resString.indexOf(jsBegin);
            int end = resString.lastIndexOf(jsEnd);
            String jsString = null;
            if (begin != -1 && end != -1 && begin < end) {
                jsString = resString.substring(begin + jsBegin.length(), end);
            }
            JSONObject js = new JSONObject(jsString);
            mClientVersion = js.getLong("v");
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            // simply catch all exceptions
            Log.e(TAG, "httpget gtask_url failed");
            return false;
        }

        return true;
    }

    /**
     * 分配下一个动作 ID。
     *
     * <p>服务端用该 ID 与响应 results 数组按顺序对应；进程内单调递增即可。
     * 非线程安全，但同步只在单个后台任务中串行调用。</p>
     *
     * @return 新的动作 ID
     */
    private int getActionId() {
        return mActionId++;
    }

    /**
     * 构造带固定协议头的 POST 请求。
     *
     * <p>{@code AT: 1} 是网页端标记已认证请求的自定义头；缺了它服务端会
     * 返回登录页而不是 JSON。请求体必须是 UTF-8 表单编码。</p>
     *
     * @return 尚未设置实体的 HttpPost，提交地址取当前会话的 mPostUrl
     */
    private HttpPost createHttpPost() {
        HttpPost httpPost = new HttpPost(mPostUrl);
        httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        httpPost.setHeader("AT", "1");
        return httpPost;
    }

    /**
     * 读取响应体并按 Content-Encoding 解压。
     *
     * <p>服务端可能返回 gzip 或 raw deflate，Apache HttpClient 不会自动解压，
     * 必须手动包一层流，否则拿到二进制乱码。deflate 用
     * {@code new Inflater(true)} 对应 nowrap 的原始格式。</p>
     *
     * @return 响应文本；按行拼接且不保留换行，够用于 JSON/HTML 片段解析
     * @throws IOException 读流失败，调用方按网络错误处理
     */
    private String getResponseContent(HttpEntity entity) throws IOException {
        String contentEncoding = null;
        if (entity.getContentEncoding() != null) {
            contentEncoding = entity.getContentEncoding().getValue();
            Log.d(TAG, "encoding: " + contentEncoding);
        }

        InputStream input = entity.getContent();
        if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
            input = new GZIPInputStream(entity.getContent());
        } else if (contentEncoding != null && contentEncoding.equalsIgnoreCase("deflate")) {
            Inflater inflater = new Inflater(true);
            input = new InflaterInputStream(entity.getContent(), inflater);
        }

        try {
            InputStreamReader isr = new InputStreamReader(input);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder sb = new StringBuilder();

            while (true) {
                String buff = br.readLine();
                if (buff == null) {
                    return sb.toString();
                }
                sb = sb.append(buff);
            }
        } finally {
            input.close();
        }
    }

    /**
     * 提交一个动作 JSON 并解析 JSON 响应。
     *
     * <p>错误分类决定上层能否重试：HTTP/IO 异常抛
     * {@link NetworkFailureException}（可重试）；响应不是合法 JSON 或内部状态
     * 未登录抛 {@link ActionFailureException}（重试无意义）。</p>
     *
     * @param js 完整请求体，通常包含 action_list 与 client_version
     * @return 服务端响应 JSON
     *
     * <p>异常：未登录抛 ActionFailureException；网络失败抛
     * NetworkFailureException；响应解析失败抛 ActionFailureException。</p>
     */
    private JSONObject postRequest(JSONObject js) throws NetworkFailureException {
        // 未登录是调用顺序错误，不是网络问题，因此抛 ActionFailureException。
        if (!mLoggedin) {
            Log.e(TAG, "please login first");
            throw new ActionFailureException("not logged in");
        }

        HttpPost httpPost = createHttpPost();
        try {
            LinkedList<BasicNameValuePair> list = new LinkedList<BasicNameValuePair>();
            list.add(new BasicNameValuePair("r", js.toString()));
            UrlEncodedFormEntity entity = new UrlEncodedFormEntity(list, "UTF-8");
            httpPost.setEntity(entity);

            // execute the post
            HttpResponse response = mHttpClient.execute(httpPost);
            String jsString = getResponseContent(response.getEntity());
            return new JSONObject(jsString);

        } catch (ClientProtocolException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("postRequest failed");
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("postRequest failed");
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("unable to convert response content to jsonobject");
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("error occurs when posting request");
        }
    }

    /**
     * 在远端创建一条任务并回填服务端分配的 gid。
     *
     * <p>先 commitUpdate 清空积压的更新队列，保持“旧更新先于新建”的提交顺序；
     * 否则积压动作会拖到创建之后才发出。</p>
     *
     * @param task 待创建任务；成功后其 gid 被写入
     *
     * <p>副作用：可能先提交待处理更新、发一次 HTTP POST、修改 task 的 gid。</p>
     * <p>异常：网络失败抛 NetworkFailureException；响应结构异常抛
     * ActionFailureException。</p>
     */
    public void createTask(Task task) throws NetworkFailureException {
        commitUpdate();
        try {
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();

            // action_list
            actionList.put(task.getCreateAction(getActionId()));
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // client_version
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // post
            JSONObject jsResponse = postRequest(jsPost);
            JSONObject jsResult = (JSONObject) jsResponse.getJSONArray(
                    GTaskStringUtils.GTASK_JSON_RESULTS).get(0);
            task.setGid(jsResult.getString(GTaskStringUtils.GTASK_JSON_NEW_ID));

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("create task: handing jsonobject failed");
        }
    }

    /**
     * 在远端创建一个任务列表并回填服务端分配的 gid。
     *
     * <p>列表是任务挂载的容器，必须先拿到 gid 才能创建子任务；同样先 flush
     * 积压的更新，保持提交顺序。</p>
     *
     * @param tasklist 待创建列表；成功后其 gid 被写入
     *
     * <p>副作用：可能先提交待处理更新、发一次 HTTP POST、修改列表的 gid。</p>
     * <p>异常：网络失败抛 NetworkFailureException；响应结构异常抛
     * ActionFailureException。</p>
     */
    public void createTaskList(TaskList tasklist) throws NetworkFailureException {
        commitUpdate();
        try {
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();

            // action_list
            actionList.put(tasklist.getCreateAction(getActionId()));
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // client version
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // post
            JSONObject jsResponse = postRequest(jsPost);
            JSONObject jsResult = (JSONObject) jsResponse.getJSONArray(
                    GTaskStringUtils.GTASK_JSON_RESULTS).get(0);
            tasklist.setGid(jsResult.getString(GTaskStringUtils.GTASK_JSON_NEW_ID));

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("create tasklist: handing jsonobject failed");
        }
    }

    /**
     * 把累积的更新动作一次性提交给服务端。
     *
     * <p>没有待提交动作时是空操作。成功后会清空队列；失败时异常向上抛且
     * 队列保留，调用方若选择重试可以重新提交，本类自身不做重试。</p>
     *
     * <p>副作用：发 HTTP POST；成功清空 mUpdateArray。</p>
     */
    public void commitUpdate() throws NetworkFailureException {
        if (mUpdateArray != null) {
            try {
                JSONObject jsPost = new JSONObject();

                // action_list
                jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, mUpdateArray);

                // client_version
                jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

                postRequest(jsPost);
                mUpdateArray = null;
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
                e.printStackTrace();
                throw new ActionFailureException("commit update: handing jsonobject failed");
            }
        }
    }

    /**
     * 把节点的更新动作加入批量队列。
     *
     * <p>服务端对单次请求的动作数量有限制，队列超过 10 条就先提交一次，
     * 把大同步拆成多个请求。节点为 null 时静默忽略。</p>
     *
     * @param node 待更新节点；其 getUpdateAction 在此刻求值并固化进队列
     *
     * <p>副作用：可能触发一次 commitUpdate 网络请求。</p>
     */
    public void addUpdateNode(Node node) throws NetworkFailureException {
        if (node != null) {
            // too many update items may result in an error
            // set max to 10 items
            // 阈值写成 > 10，实际队列可到 11 条才触发提交，属于原实现的
            // 保守上限，不要按“严格 10 条”理解。
            if (mUpdateArray != null && mUpdateArray.length() > 10) {
                commitUpdate();
            }

            if (mUpdateArray == null)
                mUpdateArray = new JSONArray();
            mUpdateArray.put(node.getUpdateAction(getActionId()));
        }
    }

    /**
     * 在远端移动任务（同列表内换序或跨列表换父）。
     *
     * <p>协议约定：同列表内移动且不是第一个时才带 prior_sibling_id；
     * 只有跨列表移动才带 dest_list，其余场景应省略这些字段。</p>
     *
     * @param task 被移动任务
     * @param preParent 原父列表
     * @param curParent 目标父列表
     *
     * <p>副作用：先提交积压更新，再单独发一次移动请求（移动不能混在普通
     * 更新队列里）。</p>
     */
    public void moveTask(Task task, TaskList preParent, TaskList curParent)
            throws NetworkFailureException {
        commitUpdate();
        try {
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();
            JSONObject action = new JSONObject();

            // action_list
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_MOVE);
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, getActionId());
            action.put(GTaskStringUtils.GTASK_JSON_ID, task.getGid());
            if (preParent == curParent && task.getPriorSibling() != null) {
                // put prioring_sibing_id only if moving within the tasklist and
                // it is not the first one
                // prior_sibling_id 决定插到哪个兄弟节点之后；移到首位时没有
                // 前驱，无法表达插入位置，必须省略该字段。
                action.put(GTaskStringUtils.GTASK_JSON_PRIOR_SIBLING_ID, task.getPriorSibling());
            }
            action.put(GTaskStringUtils.GTASK_JSON_SOURCE_LIST, preParent.getGid());
            action.put(GTaskStringUtils.GTASK_JSON_DEST_PARENT, curParent.getGid());
            if (preParent != curParent) {
                // put the dest_list only if moving between tasklists
                // 只有跨列表才需要 dest_list，同列表换序应省略。
                action.put(GTaskStringUtils.GTASK_JSON_DEST_LIST, curParent.getGid());
            }
            actionList.put(action);
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // client_version
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            postRequest(jsPost);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("move task: handing jsonobject failed");
        }
    }

    /**
     * 在远端删除节点。
     *
     * <p>协议没有独立删除动作，而是把节点标记 deleted 后发一次 update；
     * 删除前先提交积压更新，避免更新一个即将删除的节点。</p>
     *
     * @param node 待删除节点；本方法会修改它的 deleted 状态
     *
     * <p>副作用：修改 node 的 deleted 标志、发 HTTP POST、清空更新队列。</p>
     */
    public void deleteNode(Node node) throws NetworkFailureException {
        commitUpdate();
        try {
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();

            // action_list
            node.setDeleted(true);
            actionList.put(node.getUpdateAction(getActionId()));
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // client_version
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            postRequest(jsPost);
            // 删除是独立请求，成功后把队列置空，防止删除动作被重复提交。
            mUpdateArray = null;
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("delete node: handing jsonobject failed");
        }
    }

    /**
     * 拉取账户下全部任务列表。
     *
     * <p>这是 GET 请求，返回的是网页初始化脚本而不是 JSON API 响应，
     * 所以要先截取 {@code _setup(...)} 再解析。</p>
     *
     * @return 列表 JSON 数组，元素含 id/name 等字段
     *
     * <p>异常：未登录抛 ActionFailureException；HTTP 失败抛
     * NetworkFailureException；脚本结构变化导致解析失败抛 ActionFailureException。</p>
     */
    public JSONArray getTaskLists() throws NetworkFailureException {
        if (!mLoggedin) {
            Log.e(TAG, "please login first");
            throw new ActionFailureException("not logged in");
        }

        try {
            HttpGet httpGet = new HttpGet(mGetUrl);
            HttpResponse response = null;
            response = mHttpClient.execute(httpGet);

            // get the task list
            String resString = getResponseContent(response.getEntity());
            String jsBegin = "_setup(";
            String jsEnd = ")}</script>";
            int begin = resString.indexOf(jsBegin);
            int end = resString.lastIndexOf(jsEnd);
            String jsString = null;
            if (begin != -1 && end != -1 && begin < end) {
                jsString = resString.substring(begin + jsBegin.length(), end);
            }
            JSONObject js = new JSONObject(jsString);
            return js.getJSONObject("t").getJSONArray(GTaskStringUtils.GTASK_JSON_LISTS);
        } catch (ClientProtocolException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("gettasklists: httpget failed");
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("gettasklists: httpget failed");
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("get task lists: handing jasonobject failed");
        }
    }

    /**
     * 拉取指定列表下的全部任务。
     *
     * <p>使用 get_all 动作且 get_deleted=false，服务端不会返回已删除任务；
     * 本地只能靠 gid 对不上来推断“远端已删除”。提交前先 flush 积压更新，
     * 保证读到的内容包含本地刚做的修改。</p>
     *
     * @param listGid 目标列表的远端 id
     * @return 任务 JSON 数组
     *
     * <p>异常：网络失败抛 NetworkFailureException；响应解析失败抛
     * ActionFailureException。</p>
     */
    public JSONArray getTaskList(String listGid) throws NetworkFailureException {
        commitUpdate();
        try {
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();
            JSONObject action = new JSONObject();

            // action_list
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_GETALL);
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, getActionId());
            action.put(GTaskStringUtils.GTASK_JSON_LIST_ID, listGid);
            action.put(GTaskStringUtils.GTASK_JSON_GET_DELETED, false);
            actionList.put(action);
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // client_version
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            JSONObject jsResponse = postRequest(jsPost);
            return jsResponse.getJSONArray(GTaskStringUtils.GTASK_JSON_TASKS);
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("get task list: handing jsonobject failed");
        }
    }

    /**
     * 返回最近一次登录使用的 Google 账户。
     *
     * @return 账户对象；尚未成功登录时为 null，调用方需自行判空
     */
    public Account getSyncAccount() {
        return mAccount;
    }

    /**
     * 丢弃尚未提交的更新队列。
     *
     * <p>每轮同步开始时调用，防止上一轮失败残留的动作在新一轮被误提交
     * （那时节点状态可能已经变化）。</p>
     */
    public void resetUpdateArray() {
        mUpdateArray = null;
    }
}
