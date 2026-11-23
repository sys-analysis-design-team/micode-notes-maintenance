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

package net.micode.notes.data;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Data;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import java.util.HashMap;

/**
 * 联系人查询辅助类。
 *
 * <p>职责：根据电话号码查询系统联系人库，返回对应的联系人显示名；查不到、查询异常
 * 或没有读取联系人权限时返回 null，由调用方退化为直接显示号码。</p>
 *
 * <p>架构位置：属于 data 数据访问层，不持有 Context、全部为静态方法，自身不创建线程。
 * 主要调用方是 {@link net.micode.notes.ui.NoteItemData}（通话记录列表项的标题显示）。</p>
 *
 * <p>关键协作类：{@link android.provider.ContactsContract.Data}（联系人数据表）、
 * {@link android.provider.ContactsContract.CommonDataKinds.Phone}（电话类型与号码列）、
 * {@link android.telephony.PhoneNumberUtils}（号码归一化／最小匹配串换算）。</p>
 */
public class Contact {
    // 号码 → 联系人显示名 的进程内缓存，避免列表滚动、重复来电时反复查询联系人库
    private static HashMap<String, String> sContactCache;
    private static final String TAG = "Contact";

    // 联系人查询条件模板（SQL selection 片段）：PHONE_NUMBERS_EQUAL 做号码等价匹配，
    // 忽略括号、空格、国家码等格式差异；MIMETYPE 限定为电话类型；
    // 子查询只在 phone_lookup 中 min_match 相同的记录里找 raw_contact_id，
    // 这是系统来电识别使用的同一套最小匹配规则。模板中的 '+' 是占位符，
    // 运行时会被统一替换成由待查号码换算出的最小匹配串（见 getContact）。
    private static final String CALLER_ID_SELECTION = "PHONE_NUMBERS_EQUAL(" + Phone.NUMBER
    + ",?) AND " + Data.MIMETYPE + "='" + Phone.CONTENT_ITEM_TYPE + "'"
    + " AND " + Data.RAW_CONTACT_ID + " IN "
            + "(SELECT raw_contact_id "
            + " FROM phone_lookup"
            + " WHERE min_match = '+')";

    /**
     * 根据电话号码查询联系人显示名。
     *
     * @param context 用于获取 ContentResolver 的上下文
     * @param phoneNumber 待查询的电话号码，可带国家码、空格、括号等格式，内部会归一化
     * @return 匹配到的联系人显示名；无匹配、游标读取失败或没有联系人读取权限时返回 null
     *
     * <p>副作用：首次调用时懒创建静态缓存，查询成功会写入缓存（同一号码后续直接命中）。
     * 内部捕获 {@link IndexOutOfBoundsException} 与 {@link SecurityException}，
     * 因此不会把异常抛给调用方，通话记录界面始终可以退化为显示号码。</p>
     */
    public static String getContact(Context context, String phoneNumber) {
        // 缓存懒初始化：Contact 只有静态成员，只能这样保证进程内只建一次
        if(sContactCache == null) {
            sContactCache = new HashMap<String, String>();
        }

        // 缓存命中：直接返回上一次的查询结果
        if(sContactCache.containsKey(phoneNumber)) {
            return sContactCache.get(phoneNumber);
        }

        // String.replace 是字面量全量替换：模板中两处 '+' 占位符都换成同一个最小匹配串，
        // 分别作为 PHONE_NUMBERS_EQUAL 的第二实参和 phone_lookup.min_match 的过滤值
        String selection = CALLER_ID_SELECTION.replace("+",
                PhoneNumberUtils.toCallerIDMinMatch(phoneNumber));
        Cursor cursor = null;
        try {
            // 查询联系人数据表，只取显示名列；原始号码作为 PHONE_NUMBERS_EQUAL 的 '?' 参数绑定
            cursor = context.getContentResolver().query(
                    Data.CONTENT_URI,
                    new String [] { Phone.DISPLAY_NAME },
                    selection,
                    new String[] { phoneNumber },
                    null);

            // 有结果只取第一行：一个号码理论上可能对应多个联系人，这里按系统返回顺序取首个
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                sContactCache.put(phoneNumber, name);
                return name;
            }
            Log.d(TAG, "No contact matched with number:" + phoneNumber);
            return null;
        } catch (IndexOutOfBoundsException e) {
            // 游标列索引异常（查询结果缺少预期的显示名列）时按“未找到”处理
            Log.e(TAG, " Cursor get string error " + e.toString());
            return null;
        } catch (SecurityException e) {
            // Android 6.0+ 读取联系人需要运行时权限；未授权时退化为只显示号码，不能崩溃
            Log.w(TAG, "Read contacts denied, fall back to phone number", e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
