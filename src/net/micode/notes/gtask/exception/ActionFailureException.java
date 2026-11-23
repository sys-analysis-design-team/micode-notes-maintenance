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
 * 同步动作构造或执行失败的运行时异常。
 *
 * <p>职责：在本地数据与远端协议无法对齐（JSON 结构错误、id 映射失败、
 * 创建节点失败等）时中止当前同步。继承 {@link RuntimeException} 是刻意选择：
 * 这类问题属于数据/流程错误，调用链不逐层声明，直接向上终止本轮同步。</p>
 *
 * <p>架构位置：gtask.exception 包；由 gtask.data / gtask.remote 抛出，
 * 最终由 GTaskManager 的同步入口捕获，返回不可重试的内部错误码。</p>
 */
public class ActionFailureException extends RuntimeException {
    // 序列化兼容用固定值；异常跨进程传递时保持类版本一致。
    private static final long serialVersionUID = 4425249765923293627L;

    /** 构造无消息、无原因的异常。 */
    public ActionFailureException() {
        super();
    }

    /**
     * 构造带描述信息的异常；消息用于日志定位，不建议承载用户可见文案。
     *
     * @param paramString 错误描述
     */
    public ActionFailureException(String paramString) {
        super(paramString);
    }

    /**
     * 构造带描述与根因的异常，便于保留底层解析异常栈。
     *
     * @param paramString    错误描述
     * @param paramThrowable 原始异常
     */
    public ActionFailureException(String paramString, Throwable paramThrowable) {
        super(paramString, paramThrowable);
    }
}
