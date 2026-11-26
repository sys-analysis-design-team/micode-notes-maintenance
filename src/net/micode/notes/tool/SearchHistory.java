package net.micode.notes.tool;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索历史的存取。
 *
 * <p>只存最近 {@link #MAX_SIZE} 条，最近的排最前（“按搜索顺序”即最近搜索优先）；
 * 重复关键词不新增，而是提到最前；超出上限时直接丢弃最旧的一条。
 * 用 JSON 数组序列化，关键词里出现逗号、引号也不会破坏格式。</p>
 *
 * <p>排序/截断/去重这段纯逻辑抽成静态方法，方便在 JVM 单测里覆盖。</p>
 */
public final class SearchHistory {

    /** 最多保留多少条历史。 */
    public static final int MAX_SIZE = 10;

    /** 存储键；与列表排序等偏好同放在 default SharedPreferences 里。 */
    static final String KEY = "net.micode.notes.search_history";

    private final SharedPreferences mPreferences;

    public SearchHistory(SharedPreferences preferences) {
        mPreferences = preferences;
    }

    /**
     * 当前历史，最近的在前；存储损坏时返回空列表而不是抛异常。
     */
    public List<String> list() {
        return decode(mPreferences.getString(KEY, ""));
    }

    /**
     * 记录一次搜索：去重后插到最前，并截断到上限。
     *
     * @param keyword 用户输入的关键词；空白输入直接忽略
     * @return 是否真正写入了历史
     */
    public boolean record(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        mPreferences.edit().putString(KEY, encode(insert(list(), trimmed))).apply();
        return true;
    }

    /**
     * 删除单条历史。
     */
    public void remove(String keyword) {
        List<String> history = list();
        if (history.remove(keyword)) {
            mPreferences.edit().putString(KEY, encode(history)).apply();
        }
    }

    /**
     * 清空全部历史。
     */
    public void clear() {
        mPreferences.edit().remove(KEY).apply();
    }

    /**
     * 把关键词并入历史：放到最前、去掉旧的同名项、最多保留 {@link #MAX_SIZE} 条。
     */
    static List<String> insert(List<String> existing, String keyword) {
        List<String> history = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) {
            return history;
        }
        history.add(keyword);
        if (existing == null) {
            return history;
        }
        for (String item : existing) {
            if (item == null || item.isEmpty() || item.equals(keyword)) {
                continue;
            }
            history.add(item);
            if (history.size() >= MAX_SIZE) {
                break;
            }
        }
        return history;
    }

    static String encode(List<String> history) {
        return new JSONArray(history).toString();
    }

    static List<String> decode(String raw) {
        List<String> history = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return history;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && history.size() < MAX_SIZE; i++) {
                String item = array.optString(i, "");
                if (!item.isEmpty() && !history.contains(item)) {
                    history.add(item);
                }
            }
        } catch (JSONException e) {
            // 历史数据损坏时按空处理：搜索本身不能被历史拦住
            return new ArrayList<>();
        }
        return history;
    }
}
