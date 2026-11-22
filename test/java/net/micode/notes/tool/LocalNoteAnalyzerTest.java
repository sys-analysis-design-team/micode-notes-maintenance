package net.micode.notes.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class LocalNoteAnalyzerTest {
    @Test
    public void extractsUncheckedChecklistItems() {
        AiAnalysis result = LocalNoteAnalyzer.analyze("□ 买牛奶\n√ 已完成\n□ 交作业");
        assertEquals(2, result.getTodos().size());
        assertEquals("买牛奶", result.getTodos().get(0));
        assertEquals("交作业", result.getTodos().get(1));
        assertEquals("买牛奶；已完成", result.getSummary());
    }

    @Test
    public void extractsKeywordSentences() {
        AiAnalysis result = LocalNoteAnalyzer.analyze(
                "明天记得带 U 盘\n需要给导师发邮件\n购物清单：牛奶、鸡蛋、面包");
        List<String> todos = result.getTodos();
        assertEquals(2, todos.size());
        assertEquals("明天记得带 U 盘", todos.get(0));
        assertEquals("需要给导师发邮件", todos.get(1));
    }

    @Test
    public void buildsSummaryFromFirstTwoNonEmptyLines() {
        AiAnalysis result = LocalNoteAnalyzer.analyze("第一行\n\n第二行\n第三行");
        assertEquals("第一行；第二行", result.getSummary());
    }

    @Test
    public void emptyContentProducesEmptyAnalysis() {
        AiAnalysis result = LocalNoteAnalyzer.analyze("  \n\t ");
        assertTrue(result.isEmpty());
        assertFalse(result.isFromAi());
    }

    @Test
    public void ignoresChecklistMarkersOnly() {
        AiAnalysis result = LocalNoteAnalyzer.analyze("□\n- [ ]\n√");
        assertTrue(result.isEmpty());
    }

    @Test
    public void capsTodosAtFive() {
        AiAnalysis result = LocalNoteAnalyzer.analyze(
                "□ 一\n□ 二\n□ 三\n□ 四\n□ 五\n□ 六");
        assertEquals(5, result.getTodos().size());
        assertEquals("五", result.getTodos().get(4));
    }

    @Test
    public void truncatesLongTodo() {
        StringBuilder line = new StringBuilder("□ ");
        for (int i = 0; i < 100; i++) {
            line.append("长");
        }
        AiAnalysis result = LocalNoteAnalyzer.analyze(line.toString());
        String todo = result.getTodos().get(0);
        assertEquals(81, todo.length());
        assertTrue(todo.endsWith("…"));
    }

    @Test
    public void ignoresPlainInventoryLine() {
        AiAnalysis result = LocalNoteAnalyzer.analyze("购物清单：牛奶、鸡蛋、面包");
        assertTrue(result.getTodos().isEmpty());
        assertEquals("购物清单：牛奶、鸡蛋、面包", result.getSummary());
    }
}
