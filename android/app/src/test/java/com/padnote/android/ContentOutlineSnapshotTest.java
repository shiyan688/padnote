package com.padnote.android;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JUnit contract tests; pure JVM, no Android runtime, provider or note data required. */
public final class ContentOutlineSnapshotTest {
    @Test public void longFlowIsShownOnlyAtItsPersistedStartPage() {
        ContentOutlineSnapshot snapshot = ContentOutlineSnapshot.create("大纲", 3, 0, 100, 100, 20,
                Arrays.asList(new ContentOutlineSnapshot.Flow("f", "markdown", "第二页正文", 1)),
                Collections.emptyList(), noInk());
        assertTrue(snapshot.pages.get(0).textFlows.isEmpty());
        assertEquals(1, snapshot.pages.get(1).textFlows.size());
        assertTrue(snapshot.markdown().contains("文字对象按起始页归组；长对象内容可能延伸到后续页面。"));
        assertTrue(snapshot.markdown().indexOf("第二页正文") > snapshot.markdown().indexOf("第 2 页"));
        assertFalse(snapshot.markdown().contains("第 3 页正文"));
    }

    @Test public void formulaFencePreservesExactSourceAndAvoidsCollision() {
        String source = "x ``` + y\n\\frac{a}{b}";
        ContentOutlineSnapshot snapshot = ContentOutlineSnapshot.create("公式", 1, 0, 100, 100, 10,
                Arrays.asList(new ContentOutlineSnapshot.Flow("f", "latex", source, 0)),
                Collections.emptyList(), noInk());
        assertTrue(snapshot.markdown().contains("````latex\n" + source + "\n````"));
        assertEquals("````", ContentOutlineSnapshot.codeFence("```"));
    }

    @Test public void imagesInkAndPdfAreStateOnly() {
        ContentOutlineSnapshot snapshot = ContentOutlineSnapshot.create("资料", 2, 1, 100, 100, 20,
                Collections.emptyList(), Arrays.asList(new ContentOutlineSnapshot.Image("img", 1)),
                ink(new double[][][]{{{10, 10}, {10, 140}}}));
        String markdown = snapshot.markdown();
        assertTrue(markdown.contains("PDF 原文背景；没有提取文字。"));
        assertTrue(markdown.contains("图片（未添加说明；图片文件未包含在此 Markdown 中）"));
        assertTrue(markdown.contains("手写笔迹尚未识别。"));
        assertTrue(snapshot.pages.get(0).hasHandwriting && snapshot.pages.get(1).hasHandwriting);
    }

    @Test public void gapsAndNonFiniteSegmentsDoNotInventInk() {
        ContentOutlineSnapshot.InkSource source = ink(new double[][][]{
                {{10, 104}, {10, 116}},
                {{10, 20}, {Double.NaN, 110}, {10, 140}}
        });
        boolean[] pages = ContentOutlineSnapshot.handwritingPages(source, 3, 100, 100, 20);
        assertFalse(pages[0]); assertFalse(pages[1]); assertFalse(pages[2]);
    }

    @Test public void hugeFiniteCoordinateRangeIsClampedBeforeIntegerConversion() {
        boolean[] pages = ContentOutlineSnapshot.handwritingPages(
                ink(new double[][][]{{{10, 1.0e20}, {10, 0}}}), 3, 100, 100, 20);
        assertTrue(pages[0] && pages[1] && pages[2]);
    }

    @Test public void emptyDocumentHasNoPhantomPageAndInkScanStopsWhenAllPagesAreMarked() {
        ContentOutlineSnapshot empty = ContentOutlineSnapshot.create("空", 0, 0, 100, 100, 10,
                Collections.emptyList(), Collections.emptyList(), noInk());
        assertTrue(empty.pages.isEmpty());
        assertTrue(empty.markdown().endsWith("\n"));

        ContentOutlineSnapshot.InkSource earlyStop = new ContentOutlineSnapshot.InkSource() {
            @Override public int strokeCount() { return 2; }
            @Override public int pointCount(int strokeIndex) {
                if (strokeIndex > 0) throw new AssertionError("must stop after marking every page");
                return 2;
            }
            @Override public double x(int strokeIndex, int pointIndex) { return 10; }
            @Override public double y(int strokeIndex, int pointIndex) { return pointIndex == 0 ? 0 : 340; }
        };
        boolean[] pages = ContentOutlineSnapshot.handwritingPages(earlyStop, 3, 100, 100, 20);
        assertTrue(pages[0] && pages[1] && pages[2]);
    }

    private static ContentOutlineSnapshot.InkSource noInk() { return ink(new double[0][][]); }

    private static ContentOutlineSnapshot.InkSource ink(double[][][] strokes) {
        return new ContentOutlineSnapshot.InkSource() {
            @Override public int strokeCount() { return strokes.length; }
            @Override public int pointCount(int strokeIndex) { return strokes[strokeIndex].length; }
            @Override public double x(int strokeIndex, int pointIndex) { return strokes[strokeIndex][pointIndex][0]; }
            @Override public double y(int strokeIndex, int pointIndex) { return strokes[strokeIndex][pointIndex][1]; }
        };
    }
}
