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

package net.micode.notes.gtask.exception;

/**
 * 网络或登录类失败的受检异常，表示本轮同步可以稍后重试。
 *
 * <p>职责：把 HTTP 请求失败、登录失效等外部原因与内部逻辑错误区分开。
 * 继承 {@link Exception}（受检）强制同步入口显式处理：GTaskManager 捕获后
 * 返回可重试的网络错误码，而不是按数据错误处理。</p>
 *
 * <p>架构位置：gtask.exception 包；主要由 GTaskClient 的网络方法抛出，
 * GTaskManager 的同步入口统一捕获。</p>
 */
public class NetworkFailureException extends Exception {
    // 序列化兼容用固定值；异常跨进程传递时保持类版本一致。
    private static final long serialVersionUID = 2107610287180234136L;

    /** 构造无消息、无原因的异常。 */
    public NetworkFailureException() {
        super();
    }

    /**
     * 构造带描述信息的异常；消息用于日志定位，不建议承载用户可见文案。
     *
     * @param paramString 错误描述
     */
    public NetworkFailureException(String paramString) {
        super(paramString);
    }

    /**
     * 构造带描述与根因的异常，便于保留底层 IO 异常栈。
     *
     * @param paramString    错误描述
     * @param paramThrowable 原始异常
     */
    public NetworkFailureException(String paramString, Throwable paramThrowable) {
        super(paramString, paramThrowable);
    }
}
