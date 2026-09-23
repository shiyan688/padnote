package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline coverage for the knowledge-base reader tools.
 *
 * <p>The vault tools are pure text processing over {@link NoteTools.VaultReader},
 * so an in-memory fake is enough to pin the contract: read-only authority, the
 * search payload shape, title resolution rules, page-section extraction and the
 * truncation guard. Real digitization quality still needs a device with an API
 * key and is tracked separately.
 */
public class VaultToolsTest {

    /** In-memory vault; mirrors the two methods the tool layer is allowed to see. */
    private static final class FakeVault implements NoteTools.VaultReader {
        final List<VaultStore.VaultNote> notes = new ArrayList<>();
        final Map<String, String> files = new HashMap<>();

        void add(String fileName, String noteId, String title, int pages, String content) {
            notes.add(new VaultStore.VaultNote(fileName, noteId, title, pages,
                    1724200000000L, 1724100000000L));
            files.put(fileName, content);
        }

        @Override
        public List<VaultStore.VaultNote> list() {
            return notes;
        }

        @Override
        public String read(String fileName) throws Exception {
            String content = files.get(fileName);
            if (content == null) {
                throw new IllegalArgumentException("no such vault file: " + fileName);
            }
            return content;
        }
    }

    private static String markdownOf(String title, String page1Body, String page2Body) {
        return "---\n"
                + "title: " + title + "\n"
                + "note-id: note-" + title.hashCode() + "\n"
                + "pages: 2\n"
                + "digitized: 2026-08-21 12:00\n"
                + "digitized-epoch: 1724200000000\n"
                + "source-modified: 1724100000000\n"
                + "---\n\n"
                + "# " + title + "\n\n"
                + "## 第 1 页\n\n" + page1Body + "\n\n"
                + "## 第 2 页\n\n" + page2Body + "\n";
    }

    private static FakeVault sampleVault() {
        FakeVault vault = new FakeVault();
        vault.add("线性代数.md", "note-1", "线性代数", 2, markdownOf("线性代数",
                "极限的定义 \\[\\lim_{n\\to\\infty} f_n = 0\\]",
                "收敛流程：定义 → 夹逼 → 应用"));
        vault.add("概率论.md", "note-2", "概率论", 2, markdownOf("概率论",
                "大数定律要求期望存在，极限行为由弱收敛刻画。",
                "- 中心极限定理的直观解释"));
        return vault;
    }

    private static NoteTool.Result invoke(NoteToolRegistry registry, String name,
                                          JSONObject arguments) {
        return registry.invoke(name, arguments, new NoteToolRegistryTest.FakeContext(),
                null);
    }

    /** Core set stays four; the vault adds exactly the two readers. */
    @Test
    public void vaultToolsExtendTheCoreSet() throws Exception {
        JSONArray described = NoteTools.createDefault(sampleVault()).describe();
        assertEquals(7, described.length());
        boolean sawSearch = false;
        boolean sawRead = false;
        for (int index = 0; index < described.length(); index++) {
            String name = described.getJSONObject(index).optString("name");
            sawSearch |= "search_vault".equals(name);
            sawRead |= "read_vault_note".equals(name);
        }
        assertTrue(sawSearch);
        assertTrue(sawRead);
    }

    /** Vault reads are free: no grant is required, like read_page_map. */
    @Test
    public void vaultReadsNeedNoGrant() throws Exception {
        NoteToolRegistry registry = NoteTools.createDefault(sampleVault());
        assertTrue(invoke(registry, "search_vault",
                new JSONObject().put("query", "极限")).ok);
        assertTrue(invoke(registry, "read_vault_note",
                new JSONObject().put("title", "线性代数")).ok);
    }

    @Test
    public void searchFindsMatchesAcrossNotes() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(sampleVault()),
                "search_vault", new JSONObject().put("query", "极限"));
        assertTrue(result.summary, result.ok);
        // 线性代数第 1 页一处；概率论第 1 页与第 2 页（中心极限定理）各一处
        assertEquals(3, result.payload.optInt("totalMatches"));
        assertEquals(2, result.payload.optJSONArray("matchedNotes").length());
        JSONObject excerpt = result.payload.optJSONArray("excerpts").getJSONObject(0);
        assertTrue(excerpt.optString("line").contains("极限"));
        assertEquals(1, excerpt.optInt("page"));
        assertTrue(result.summary.contains("2 本"));
    }

    @Test
    public void searchTracksPageHeadings() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(sampleVault()),
                "search_vault", new JSONObject().put("query", "中心极限"));
        assertTrue(result.summary, result.ok);
        JSONObject excerpt = result.payload.optJSONArray("excerpts").getJSONObject(0);
        assertEquals("命中落在第 2 页", 2, excerpt.optInt("page"));
    }

    @Test
    public void searchIsCaseInsensitiveForAscii() throws Exception {
        FakeVault vault = new FakeVault();
        vault.add("Calculus.md", "note-3", "Calculus", 1,
                "---\ntitle: Calculus\n---\n\n## 第 1 页\n\nThe LIMIT exists.\n");
        NoteTool.Result result = invoke(NoteTools.createDefault(vault),
                "search_vault", new JSONObject().put("query", "limit"));
        assertTrue(result.summary, result.ok);
        assertEquals(1, result.payload.optInt("totalMatches"));
    }

    @Test
    public void emptyQueryIsRejectedWithoutTouchingTheVault() throws Exception {
        FakeVault vault = sampleVault();
        NoteTool.Result result = invoke(NoteTools.createDefault(vault),
                "search_vault", new JSONObject().put("query", "  "));
        assertFalse(result.ok);
        assertTrue(result.summary.contains("query"));
    }

    @Test
    public void searchOnEmptyVaultReportsZeroHitsInsteadOfFailing() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(new FakeVault()),
                "search_vault", new JSONObject().put("query", "极限"));
        assertTrue(result.summary, result.ok);
        assertEquals(0, result.payload.optInt("totalMatches"));
        assertEquals(0, result.payload.optInt("noteCount"));
    }

    @Test
    public void searchSurvivesAnUnreadableFile() throws Exception {
        FakeVault vault = sampleVault();
        vault.add("损坏.md", "note-4", "损坏", 1, null);
        NoteTool.Result result = invoke(NoteTools.createDefault(vault),
                "search_vault", new JSONObject().put("query", "极限"));
        assertTrue("a corrupt file must not fail the search", result.ok);
        assertEquals(3, result.payload.optInt("totalMatches"));
    }

    @Test
    public void readByExactTitleReturnsFullContent() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(sampleVault()),
                "read_vault_note", new JSONObject().put("title", "线性代数"));
        assertTrue(result.summary, result.ok);
        String content = result.payload.optString("content");
        assertTrue(content.contains("## 第 2 页"));
        assertEquals("线性代数", result.payload.optString("title"));
        assertEquals(2, result.payload.optInt("pages"));
        assertFalse(result.payload.optBoolean("truncated"));
    }

    @Test
    public void readAcceptsFileNameAndUniqueSubstring() throws Exception {
        NoteToolRegistry registry = NoteTools.createDefault(sampleVault());
        assertTrue(invoke(registry, "read_vault_note",
                new JSONObject().put("title", "线性代数.md")).ok);
        assertTrue(invoke(registry, "read_vault_note",
                new JSONObject().put("title", "概率")).ok);
    }

    @Test
    public void ambiguousTitleIsRejectedWithCandidates() throws Exception {
        FakeVault vault = new FakeVault();
        vault.add("凸优化·上.md", "note-6", "凸优化·上", 1,
                markdownOf("凸优化·上", "凸集的定义。", "- 凸函数"));
        vault.add("凸优化·下.md", "note-7", "凸优化·下", 1,
                markdownOf("凸优化·下", "凸优化的对偶理论。", "- KKT 条件"));
        NoteTool.Result result = invoke(NoteTools.createDefault(vault),
                "read_vault_note", new JSONObject().put("title", "凸优化"));
        assertFalse(result.ok);
        assertEquals("ambiguous", result.payload.optString("error"));
        assertEquals(2, result.payload.optJSONArray("availableTitles").length());
    }

    @Test
    public void unknownTitleListsTheAvailableLibrary() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(sampleVault()),
                "read_vault_note", new JSONObject().put("title", "拓扑学"));
        assertFalse(result.ok);
        assertEquals("not_found", result.payload.optString("error"));
        assertTrue("the refusal must be actionable",
                result.payload.optJSONArray("availableTitles").length() == 2);
    }

    @Test
    public void emptyVaultIsAnActionableRejection() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(new FakeVault()),
                "read_vault_note", new JSONObject().put("title", "任何"));
        assertFalse(result.ok);
        assertEquals("vault_empty", result.payload.optString("error"));
        assertFalse(result.payload.optString("hint").isEmpty());
    }

    @Test
    public void pageParameterExtractsOneSection() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(sampleVault()),
                "read_vault_note", new JSONObject().put("title", "线性代数")
                        .put("page", 2));
        assertTrue(result.summary, result.ok);
        String content = result.payload.optString("content");
        assertTrue(content.contains("夹逼"));
        assertFalse("single section must not carry other pages",
                content.contains("极限的定义"));
        assertTrue(result.summary.contains("第 2 页"));
    }

    @Test
    public void outOfRangePageIsRejectedWithPageCount() throws Exception {
        NoteTool.Result result = invoke(NoteTools.createDefault(sampleVault()),
                "read_vault_note", new JSONObject().put("title", "线性代数")
                        .put("page", 5));
        assertFalse(result.ok);
        assertEquals("page_out_of_range", result.payload.optString("error"));
        assertEquals(2, result.payload.optInt("pages"));
    }

    @Test
    public void oversizedContentIsTruncatedWithAHint() throws Exception {
        FakeVault vault = new FakeVault();
        StringBuilder huge = new StringBuilder();
        for (int index = 0; index < 3000; index++) {
            huge.append("这是一段用于撑爆单次读取上限的长文本。");
        }
        vault.add("巨著.md", "note-5", "巨著", 1,
                "---\ntitle: 巨著\npages: 1\n---\n\n## 第 1 页\n\n" + huge + "\n");
        NoteTool.Result result = invoke(NoteTools.createDefault(vault),
                "read_vault_note", new JSONObject().put("title", "巨著"));
        assertTrue(result.summary, result.ok);
        assertTrue(result.payload.optBoolean("truncated"));
        assertFalse(result.payload.optString("hint").isEmpty());
        assertTrue(result.payload.optString("content").length() < huge.length());
    }
}
