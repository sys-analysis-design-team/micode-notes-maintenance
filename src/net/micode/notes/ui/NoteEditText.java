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

package net.micode.notes.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.widget.EditText;

import net.micode.notes.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 便签编辑页清单模式下的单行输入框。
 *
 * <p>职责：在普通 EditText 之上补三类跨行编辑语义——“回车在光标处拆行”、
 * “行首退格删除本行并并入上一行”、“内容有无变化时通知外层显示/隐藏勾选框”；
 * 自身不维护行列表。另在选中内容恰好是一个链接时，向上下文菜单追加“打开链接”入口。</p>
 *
 * <p>架构位置：ui 包的自定义控件。编辑页进入清单模式后，每个清单行动态 inflate 一个本控件，
 * 通过 {@link OnTextViewChangeListener} 与 Activity 协作；行下标由 Activity 在增删行时统一重排。</p>
 *
 * <p>关键协作类：{@link NoteEditActivity}（实现监听器接口、持有行列表）、
 * {@link URLSpan}（链接识别与跳转）。</p>
 */
public class NoteEditText extends EditText {
    private static final String TAG = "NoteEditText";
    // 当前行在清单中的下标；Activity 增删行后会对所有行重新 setIndex
    private int mIndex;
    // 记录 DEL 按下、文本尚未删除时的光标位置，供 onKeyUp 判断是否属于“行首退格”
    private int mSelectionStartBeforeDelete;

    // 上下文菜单可识别的链接协议前缀；未命中的协议统一落到“其它链接”文案
    private static final String SCHEME_TEL = "tel:" ;
    private static final String SCHEME_HTTP = "http:" ;
    private static final String SCHEME_EMAIL = "mailto:" ;

    // 协议前缀 → 菜单文案资源；静态初始化一次，避免每次弹菜单时重复分配
    private static final Map<String, Integer> sSchemaActionResMap = new HashMap<String, Integer>();
    static {
        sSchemaActionResMap.put(SCHEME_TEL, R.string.note_link_tel);
        sSchemaActionResMap.put(SCHEME_HTTP, R.string.note_link_web);
        sSchemaActionResMap.put(SCHEME_EMAIL, R.string.note_link_email);
    }

    /**
     * Call by the {@link NoteEditActivity} to delete or add edit text
     *
     * <p>中文：由 {@link NoteEditActivity} 实现并注入。跨行操作（回车拆行、行首退格删行、
     * 空行勾选框显隐）通过该接口交回 Activity 处理，输入框本身不持有行列表。</p>
     */
    public interface OnTextViewChangeListener {
        /**
         * Delete current edit text when {@link KeyEvent#KEYCODE_DEL} happens
         * and the text is null
         *
         * <p>中文：只在光标位于行首且本行不是第一行时触发。Activity 负责移除本行、
         * 把 text 并入上一行，并把焦点与光标放到合并后的行尾。</p>
         *
         * @param index 被删除行在清单中的下标
         * @param text 被删除行的完整文本
         */
        void onEditTextDelete(int index, String text);

        /**
         * Add edit text after current edit text when {@link KeyEvent#KEYCODE_ENTER}
         * happen
         *
         * <p>中文：光标处拆行，本行保留前半段，text 是后半段；Activity 在 index 位置
         * 插入新行并重排后续行下标。</p>
         *
         * @param index 新行在清单中的目标下标
         * @param text 从当前行拆出的后半段文本
         */
        void onEditTextEnter(int index, String text);

        /**
         * Hide or show item option when text change
         *
         * <p>中文：hasText 为 true 时显示该行的勾选框，false 时隐藏。</p>
         *
         * @param index 发生变化的行下标
         * @param hasText 该行当前是否应视为“有内容”
         */
        void onTextChange(int index, boolean hasText);
    }

    private OnTextViewChangeListener mOnTextViewChangeListener;

    /**
     * 代码方式创建单行输入框：不读 XML 属性，初始行下标为 0。
     */
    public NoteEditText(Context context) {
        super(context, null);
        mIndex = 0;
    }

    /**
     * 设置本行在清单中的下标。
     *
     * <p>只影响跨行回调里上报的位置，不改变 View 在父容器中的实际顺序；
     * Activity 必须在插入/删除行后同步重排所有行的下标，否则删行/拆行会作用到错误行。</p>
     *
     * @param index 从 0 开始的行下标
     */
    public void setIndex(int index) {
        mIndex = index;
    }

    /**
     * 注入跨行编辑回调；Activity 只设置一个监听器，后来者覆盖先前的。
     *
     * @param listener 通常就是 NoteEditActivity 本身
     */
    public void setOnTextViewChangeListener(OnTextViewChangeListener listener) {
        mOnTextViewChangeListener = listener;
    }

    /**
     * XML inflate 入口：显式以 editTextStyle 作为默认样式，
     * 保证自定义控件与普通 EditText 的主题、内边距和光标表现一致。
     */
    public NoteEditText(Context context, AttributeSet attrs) {
        super(context, attrs, android.R.attr.editTextStyle);
    }

    /**
     * 完整构造；当前没有额外的初始化逻辑（原 TODO 保留）。
     */
    public NoteEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        // TODO Auto-generated constructor stub
    }

    /**
     * 按下时先按字符布局把触摸坐标换算成文本偏移并落下光标，再把事件交给父类。
     *
     * <p>换算顺序必须是：先减内边距（getTotalPaddingLeft/Top），再加滚动量（getScrollX/Y）。
     * 缺少任一步，长文本滚动后或带内边距时算出的偏移都会偏。只处理 ACTION_DOWN，
     * 长按选择、拖拽滚动等仍交给 super，避免抢占系统手势判定。</p>
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:

                // 触摸坐标是 View 坐标系，文本布局坐标还需要扣除内边距并叠加滚动偏移
                int x = (int) event.getX();
                int y = (int) event.getY();
                x -= getTotalPaddingLeft();
                y -= getTotalPaddingTop();
                x += getScrollX();
                y += getScrollY();

                // 垂直方向先定位行，水平方向再定位行内字符偏移；setSelection 只落光标，
                // 不消费事件，后续 super 仍会按系统逻辑处理触摸
                Layout layout = getLayout();
                int line = layout.getLineForVertical(y);
                int off = layout.getOffsetForHorizontal(line, x);
                Selection.setSelection(getText(), off);
                break;
        }

        return super.onTouchEvent(event);
    }

    /**
     * 按键按下阶段只做两件事：拦下“有监听器时的回车”，以及记录 DEL 前的光标位置。
     *
     * <p>回车分支返回 false 且不再调用 super，等价于跳过系统默认的“插入换行”，
     * 真正的拆行动作留到 onKeyUp 完成；DEL 分支此时文本尚未删除，正好记录删除前的位置，
     * 供 onKeyUp 判断是否属于行首退格。</p>
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                if (mOnTextViewChangeListener != null) {
                    // 不消费按键也不走默认处理：拆行交给 onKeyUp，避免本行先被插入换行
                    return false;
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                // 先记位置，等 onKeyUp 时再判断是否需要跨行删除
                mSelectionStartBeforeDelete = getSelectionStart();
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 完成两类跨行编辑：行首 DEL 删除本行、ENTER 在光标处拆行。
     *
     * <p>DEL 用 onKeyDown 记录的光标位置判断：只有在行首（0）且本行不是第一行时，
     * 才通知 Activity 删除本行并把内容并入上一行，同时返回 true 消费事件。
     * ENTER 以当前光标为界：前半段留在本行，后半段交给 Activity 新建下一行；
     * 行下标的后移由 Activity 统一重排。</p>
     *
     * <p>未注入监听器时（例如普通单行编辑场景）只打印日志，按键继续走系统默认处理。</p>
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_DEL:
                if (mOnTextViewChangeListener != null) {
                    // 行首删除且不是第一行：本行已无内容可删，转为“删除整行并并入上一行”
                    if (0 == mSelectionStartBeforeDelete && mIndex != 0) {
                        mOnTextViewChangeListener.onEditTextDelete(mIndex, getText().toString());
                        return true;
                    }
                } else {
                    Log.d(TAG, "OnTextViewChangeListener was not seted");
                }
                break;
            case KeyEvent.KEYCODE_ENTER:
                if (mOnTextViewChangeListener != null) {
                    // 光标处拆行：本行保留前半段，后半段作为新行内容
                    int selectionStart = getSelectionStart();
                    String text = getText().subSequence(selectionStart, length()).toString();
                    setText(getText().subSequence(0, selectionStart));
                    mOnTextViewChangeListener.onEditTextEnter(mIndex + 1, text);
                } else {
                    Log.d(TAG, "OnTextViewChangeListener was not seted");
                }
                break;
            default:
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * 焦点变化时通知外层“本行是否有内容”，用于显示/隐藏清单勾选框。
     *
     * <p>判断规则：只有“失焦且文本为空”才视为无内容；聚焦中的空行按有内容处理，
     * 这样正在编辑的新行仍保留勾选框。必须先发业务通知再调用 super，
     * 保证系统的焦点簿记（无障碍、输入法）照常进行。</p>
     */
    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        if (mOnTextViewChangeListener != null) {
            // 空内容只在失焦后才隐藏操作项；聚焦中的空行仍显示，避免正在输入时控件闪动
            if (!focused && TextUtils.isEmpty(getText())) {
                mOnTextViewChangeListener.onTextChange(mIndex, false);
            } else {
                mOnTextViewChangeListener.onTextChange(mIndex, true);
            }
        }
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    /**
     * 当选区恰好覆盖一个 URLSpan 时，向上下文菜单追加“打开链接”项。
     *
     * <p>选区可能反向（selStart > selEnd），所以先取 min/max。只有单个链接时才有明确目标；
     * 多个链接或纯文本不添加自定义项，交给系统默认菜单。协议识别用 indexOf 而非 startsWith，
     * 兼容带前缀的 URL；tel:/http:/mailto: 映射到不同文案，其余落到“其它链接”。
     * 点击直接复用 {@link URLSpan#onClick}，跳转行为与正文链接一致。</p>
     */
    @Override
    protected void onCreateContextMenu(ContextMenu menu) {
        // 只有 Spannable 文本才可能带链接 span
        if (getText() instanceof Spanned) {
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            // 反向选择时 getSpans 需要有序边界
            int min = Math.min(selStart, selEnd);
            int max = Math.max(selStart, selEnd);

            // 只处理“选中内容只有一个链接”的情况，避免菜单指向不明确
            final URLSpan[] urls = ((Spanned) getText()).getSpans(min, max, URLSpan.class);
            if (urls.length == 1) {
                // 0 作为“未命中”哨兵；三个协议互斥，命中任一即可确定菜单文案
                int defaultResId = 0;
                for(String schema: sSchemaActionResMap.keySet()) {
                    if(urls[0].getURL().indexOf(schema) >= 0) {
                        defaultResId = sSchemaActionResMap.get(schema);
                        break;
                    }
                }

                // 未知协议（如 https 之外的 scheme）统一显示“其它链接”，仍可点击跳转
                if (defaultResId == 0) {
                    defaultResId = R.string.note_link_other;
                }

                // 自定义项先加入，系统复制/粘贴项随后由 super 追加；ID 固定为 0，
                // 因为菜单关闭后不需要再引用，点击行为直接绑定到选中的那个 URLSpan
                menu.add(0, 0, 0, defaultResId).setOnMenuItemClickListener(
                        new OnMenuItemClickListener() {
                            public boolean onMenuItemClick(MenuItem item) {
                                // goto a new intent
                                urls[0].onClick(NoteEditText.this);
                                return true;
                            }
                        });
            }
        }
        super.onCreateContextMenu(menu);
    }
}
