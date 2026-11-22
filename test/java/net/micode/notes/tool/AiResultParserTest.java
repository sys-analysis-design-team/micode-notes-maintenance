package net.micode.notes.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class AiResultParserTest {
    @Test
    public void parsesPlainJson() throws Exception {
        AiAnalysis result = AiResultParser.parse(
                "{\"summary\":\"整理购物清单\",\"todos\":[\"买牛奶\",\"买鸡蛋\"]}");
        assertTrue(result.isFromAi());
        assertEquals("整理购物清单", result.getSummary());
        assertEquals(2, result.getTodos().size());
        assertEquals("买牛奶", result.getTodos().get(0));
    }

    @Test
    public void parsesMarkdownFence() throws Exception {
        AiAnalysis result = AiResultParser.parse(
                "```json\n{\"summary\":\"准备会议\",\"todos\":[\"发议程\"]}\n```");
        assertEquals("准备会议", result.getSummary());
        assertEquals(1, result.getTodos().size());
    }

    @Test
    public void ignoresTextAroundJson() throws Exception {
        AiAnalysis result = AiResultParser.parse(
                "好的，结果如下：{\"summary\":\"复习英语\",\"todos\":[]} 希望有帮助。");
        assertEquals("复习英语", result.getSummary());
        assertTrue(result.getTodos().isEmpty());
    }

    @Test
    public void rejectsMissingSummary() {
        try {
            AiResultParser.parse("{\"todos\":[\"买牛奶\"]}");
            fail("缺少摘要应当失败");
        } catch (AiFormatException expected) {
            assertTrue(expected.getMessage().contains("摘要"));
        }
    }

    @Test
    public void rejectsMalformedJson() {
        try {
            AiResultParser.parse("{\"summary\":");
            fail("坏 JSON 应当失败");
        } catch (AiFormatException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        }
    }

    @Test
    public void capsDeduplicatesAndCleansTodos() throws Exception {
        AiAnalysis result = AiResultParser.parse(
                "{\"summary\":\"待办很多\",\"todos\":[\"- 买牛奶\",\"- 买牛奶\",\"2. 写报告\","
                        + "\"3、交作业\",\"4) 打电话\",\"5. 取快递\",\"6. 不该出现的第六条\"]}");
        List<String> todos = result.getTodos();
        assertEquals(5, todos.size());
        assertEquals("买牛奶", todos.get(0));
        assertEquals("写报告", todos.get(1));
        assertEquals("交作业", todos.get(2));
        assertEquals("打电话", todos.get(3));
        assertEquals("取快递", todos.get(4));
    }

    @Test
    public void truncatesLongSummary() throws Exception {
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            summary.append("长");
        }
        AiAnalysis result = AiResultParser.parse(
                "{\"summary\":\"" + summary + "\",\"todos\":[]}");
        assertTrue(result.getSummary().endsWith("…"));
        assertEquals(201, result.getSummary().length());
    }

    @Test
    public void missingTodosBecomesEmptyList() throws Exception {
        AiAnalysis result = AiResultParser.parse("{\"summary\":\"只有摘要\"}");
        assertFalse(result.isEmpty());
        assertTrue(result.getTodos().isEmpty());
    }
}
