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

package net.micode.notes.tool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * M6：OpenAI 兼容的 Chat Completions 客户端。
 *
 * <p>刻意只依赖 HttpURLConnection 与 org.json，不引入第三方网络库；读超时 30 秒，
 * 失败由调用方转离线规则。日志与异常信息中不包含 API Key。</p>
 */
public final class AiClient {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_CONTENT_LENGTH = 4000;
    private static final int MAX_RESPONSE_LENGTH = 64 * 1024;

    private static final String SYSTEM_PROMPT =
            "你是便签整理助手。请只输出一个 JSON 对象，不要输出 Markdown 代码块或任何解释。"
            + "格式：{\"summary\":\"一句话摘要\",\"todos\":[\"待办1\",\"待办2\"]}。"
            + "摘要用中文，不超过 80 字；待办最多 5 条，没有待办时返回空数组。";

    private AiClient() {
    }

    public static AiAnalysis analyze(AiSettings settings, String noteContent)
            throws IOException, AiFormatException {
        if (!settings.isConfigured()) {
            throw new IOException("AI 未配置");
        }
        String content = noteContent == null ? "" : noteContent.trim();
        if (content.length() == 0) {
            throw new IOException("便签内容为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("model", settings.getModel());
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            payload.put("messages", messages);
            payload.put("temperature", 0.2);
            payload.put("max_tokens", 500);
            payload.put("stream", false);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(settings.getEndpoint());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + settings.getApiKey());

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + shorten(response, 200));
            }
            return AiResultParser.parse(extractMessageContent(response));
        } catch (JSONException e) {
            throw new AiFormatException("响应结构不符合 Chat Completions", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String extractMessageContent(String response) throws JSONException {
        JSONObject root = new JSONObject(response);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new JSONException("响应缺少 choices");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null || !message.has("content")) {
            throw new JSONException("响应缺少 message.content");
        }
        return message.optString("content", "");
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            char[] buffer = new char[4096];
            int length;
            while ((length = reader.read(buffer)) != -1
                    && builder.length() < MAX_RESPONSE_LENGTH) {
                builder.append(buffer, 0, length);
            }
        } finally {
            reader.close();
        }
        return builder.toString();
    }

    private static String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() > maxLength
                ? oneLine.substring(0, maxLength) + "…" : oneLine;
    }
}
