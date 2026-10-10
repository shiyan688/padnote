package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Collections;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

public class DigitizationStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void fingerprintIgnoresViewportButBindsContentAndPdfPresence() throws Exception {
        JSONObject first = document("note-a", "标题", 2);
        JSONObject moved = new JSONObject(first.toString())
                .put("updatedAt", 999999L).put("viewportScale", 3.5)
                .put("viewportCenterX", 800);
        assertEquals(DigitizationStore.canonicalSourceFingerprint(first),
                DigitizationStore.canonicalSourceFingerprint(moved));
        moved.put("title", "另一个标题");
        assertNotEquals(DigitizationStore.canonicalSourceFingerprint(first),
                DigitizationStore.canonicalSourceFingerprint(moved));

        assertFails(() -> DigitizationStore.Source.from(first,
                repeat('a', 64), 2, "profile", "https://example.test/v1?key=hidden", "vision"),
                "PDF 指纹");
        JSONObject pdf = document("note-pdf", "PDF", 2).put("pdfPageCount", 2);
        DigitizationStore.Source source = DigitizationStore.Source.from(pdf,
                repeat('b', 64), 2, "profile", "https://example.test/v1?key=hidden", "vision");
        assertFalse(source.endpointDisplay.contains("key"));
        assertFails(() -> DigitizationStore.Source.from(pdf, "", 2,
                "profile", "https://example.test/v1", "vision"), "PDF 指纹");
    }

    @Test public void cancelResumeOnlyMissingPagesAndPublishWithoutNewPageWork() throws Exception {
        File root = temporary.newFolder("resume");
        DigitizationStore store = new DigitizationStore(root);
        DigitizationStore.Source source = source("note-resume", "标题", 3, "profile-a");
        DigitizationStore.Snapshot created = store.create(source, "标题", 42L);
        DigitizationStore.Snapshot running = store.startAttempt(created);
        DigitizationStore.Snapshot one = store.savePage(running, 0, "第一页");
        DigitizationStore.Snapshot cancelled = store.cancel(one);
        assertEquals(DigitizationStore.State.CANCELLED, cancelled.state);
        assertEquals(1, cancelled.completedCount());
        assertEquals(1, cancelled.nextPendingPage());
        assertTrue(cancelled.partialMarkdown().contains("【待数字化】"));

        DigitizationStore.Snapshot resumed = store.startAttempt(cancelled);
        assertEquals(2, resumed.attempt);
        resumed = store.savePage(resumed, 1, "第二页");
        resumed = store.savePage(resumed, 2, "第三页");
        assertEquals(DigitizationStore.State.READY, resumed.state);
        assertEquals(-1, resumed.nextPendingPage());
        assertTrue(resumed.partialMarkdown().contains("尚未存入知识库"));

        DigitizationStore.Snapshot publishFailed = store.fail(resumed, "磁盘暂不可用");
        assertEquals(DigitizationStore.State.READY, publishFailed.state);
        assertEquals(3, publishFailed.completedCount());
        DigitizationStore.Snapshot published = store.markPublished(publishFailed,
                "标题--abcd.md");
        assertEquals(DigitizationStore.State.COMPLETED, published.state);
        assertEquals("标题--abcd.md", published.vaultFileName);
        assertEquals(published.runId, store.findLatest("note-resume").runId);
        assertTrue(published.matches(source));
        assertFalse(published.matches(source("note-resume", "标题", 3, "profile-b")));
        assertFails(() -> store.savePage(one, 1, "旧对象写入"), "已被其他操作更新");
    }

    @Test public void targetIntentRoundTripsAndLegacyCheckpointsStayUnbound() throws Exception {
        File root=temporary.newFolder("target-roundtrip");
        DigitizationStore store=new DigitizationStore(root);
        DigitizationStore.Source source=source("note-target","T",1,"p");
        DigitizationStore.Target target=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                "00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002","r17-1",
                repeat('a',64),null,null,123456789L);
        DigitizationStore.Snapshot captured=store.create(source,"T",123L,target);
        assertEquals("a locally owned operation UUID is also the durable checkpoint identity",
                "digitize-"+target.operationId,captured.runId);
        DigitizationStore.Snapshot reopened=new DigitizationStore(root).load(captured.runId);
        assertNotNull(reopened.target);
        assertTrue(target.sameIntent(reopened.target));
        assertEquals(DigitizationStore.State.PENDING,reopened.state);
        assertTrue(new String(Files.readAllBytes(new File(root,captured.runId+".json").toPath()),
                StandardCharsets.UTF_8).contains("\"schemaVersion\":3"));

        DigitizationStore.Snapshot legacy=store.create(source("note-old-target","Old",1,"p"),
                "Old",456L);
        DigitizationStore.Snapshot coldLegacy=new DigitizationStore(root).load(legacy.runId);
        assertNull(coldLegacy.target);
        assertTrue(new String(Files.readAllBytes(new File(root,legacy.runId+".json").toPath()),
                StandardCharsets.UTF_8).contains("\"schemaVersion\":1"));
    }

    @Test public void repeatedCreateReusesOnlyTheExactLocalRunAndFrozenTarget() throws Exception {
        File root=temporary.newFolder("operation-run-identity");
        DigitizationStore store=new DigitizationStore(root);
        DigitizationStore.Source source=source("note-operation-run","T",1,"p");
        String operationId="00000000-0000-4000-8000-000000000031";
        DigitizationStore.Target target=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                operationId,operationId,"00000000-0000-4000-8000-000000000032",
                "base-revision",repeat('b',64),null,null,123456789L);
        DigitizationStore.Snapshot created=store.create(source,"T",123L,target);
        DigitizationStore.Snapshot ready=store.savePage(store.startAttempt(created),0,"exact material bytes");

        DigitizationStore.Snapshot repeated=store.create(source,"T",123L,target);
        assertEquals(created.runId,repeated.runId);
        assertEquals("digitize-"+operationId,repeated.runId);
        assertEquals(ready.revision,repeated.revision);
        assertEquals(Collections.singletonList("exact material bytes"),repeated.pages);
        assertEquals(target.groupRevision,repeated.target.groupRevision);
        assertEquals(target.groupDigest,repeated.target.groupDigest);
        assertEquals(target.materialId,repeated.target.materialId);
        String exactMaterial=VaultStore.buildDigitizedMarkdown(repeated.noteId,repeated.title,
                repeated.totalPages,repeated.sourceUpdatedAt,repeated.pages,
                repeated.target.publishTimestamp,repeated.target.operationId);
        assertTrue(exactMaterial.contains("digitization-operation-id: "+repeated.runId.substring(9)+"\n"));
        assertEquals(1,store.listForNote(source.noteId).size());

        DigitizationStore.Target changedBase=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                operationId,operationId,target.lineage,target.groupRevision,repeat('c',64),
                null,null,target.publishTimestamp);
        assertFails(() -> store.create(source,"T",123L,changedBase),"发布操作 UUID 已绑定");
        assertFails(() -> store.create(source("note-operation-run","changed title",1,"p"),
                "changed title",123L,target),"发布操作 UUID 已绑定");

        DigitizationStore.Snapshot cold=new DigitizationStore(root).load(created.runId);
        assertEquals("digitize-"+operationId,cold.runId);
        assertTrue(target.sameIntent(cold.target));
        assertEquals(repeated.pages,cold.pages);
        assertEquals(exactMaterial,VaultStore.buildDigitizedMarkdown(cold.noteId,cold.title,
                cold.totalPages,cold.sourceUpdatedAt,cold.pages,cold.target.publishTimestamp,
                cold.target.operationId));
    }

    @Test public void preexistingCheckpointRunIdentityIsPreservedOnReentry() throws Exception {
        File root=temporary.newFolder("operation-run-legacy-id");
        DigitizationStore store=new DigitizationStore(root);
        DigitizationStore.Source source=source("note-operation-legacy","T",1,"p");
        String operationId="00000000-0000-4000-8000-000000000041";
        DigitizationStore.Target target=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                operationId,operationId,"00000000-0000-4000-8000-000000000042",
                "base-revision",repeat('d',64),null,null,123456789L);
        DigitizationStore.Snapshot captured=store.create(source,"T",123L,target);
        String oldRunId="digitize-00000000-0000-4000-8000-000000000043";
        File oldFile=new File(root,captured.runId+".json");
        File preservedFile=new File(root,oldRunId+".json");
        String json=new String(Files.readAllBytes(oldFile.toPath()),StandardCharsets.UTF_8)
                .replace("\"runId\":\""+captured.runId+"\"",
                        "\"runId\":\""+oldRunId+"\"");
        assertTrue(!json.equals(new String(Files.readAllBytes(oldFile.toPath()),StandardCharsets.UTF_8)));
        Files.write(preservedFile.toPath(),json.getBytes(StandardCharsets.UTF_8));
        assertTrue(oldFile.delete());

        DigitizationStore reopenedStore=new DigitizationStore(root);
        DigitizationStore.Snapshot oldCheckpoint=reopenedStore.load(oldRunId);
        assertEquals(oldRunId,oldCheckpoint.runId);
        assertEquals(operationId,oldCheckpoint.target.operationId);
        byte[] originalBytes=Files.readAllBytes(preservedFile.toPath());
        DigitizationStore.Snapshot reentered=reopenedStore.create(source,"T",123L,target);
        assertEquals("re-entry returns the original checkpoint without rewriting its local ID",
                oldRunId,reentered.runId);
        assertArrayEquals("re-entry preserves the existing checkpoint bytes",originalBytes,
                Files.readAllBytes(preservedFile.toPath()));
        assertEquals(1,reopenedStore.listForNote(source.noteId).size());
    }

    @Test public void legacyOperationIdCollisionAcrossNotesFailsClosed() throws Exception {
        File root=temporary.newFolder("operation-run-cross-note");
        DigitizationStore store=new DigitizationStore(root);
        String operationId="00000000-0000-4000-8000-000000000051";
        DigitizationStore.Source oldSource=source("note-legacy-owner","Old",1,"p");
        DigitizationStore.Target oldTarget=new DigitizationStore.Target(oldSource.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                operationId,operationId,"00000000-0000-4000-8000-000000000052",
                "base-revision",repeat('e',64),null,null,123456789L);
        DigitizationStore.Snapshot old=store.create(oldSource,"Old",123L,oldTarget);
        File current=new File(root,old.runId+".json");
        String oldRunId="digitize-00000000-0000-4000-8000-000000000053";
        File preserved=new File(root,oldRunId+".json");
        String json=new String(Files.readAllBytes(current.toPath()),StandardCharsets.UTF_8)
                .replace("\"runId\":\""+old.runId+"\"","\"runId\":\""+oldRunId+"\"");
        Files.write(preserved.toPath(),json.getBytes(StandardCharsets.UTF_8));
        assertTrue(current.delete());

        DigitizationStore.Source otherSource=source("note-new-owner","New",1,"p");
        DigitizationStore.Target otherTarget=new DigitizationStore.Target(otherSource.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                operationId,operationId,"00000000-0000-4000-8000-000000000054",
                "base-revision",repeat('f',64),null,null,123456790L);
        assertFails(()->store.create(otherSource,"New",123L,otherTarget),
                "发布操作 UUID 已绑定");
        assertEquals(1,store.list().size());
        assertEquals(oldRunId,store.list().get(0).runId);
    }

    @Test public void duplicateLegacyOperationIdCheckpointsAreAmbiguousAndRejected() throws Exception {
        File root=temporary.newFolder("operation-run-duplicate-legacy");
        DigitizationStore store=new DigitizationStore(root);
        DigitizationStore.Source source=source("note-duplicate-legacy","T",1,"p");
        String operationId="00000000-0000-4000-8000-000000000061";
        DigitizationStore.Target target=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                operationId,operationId,"00000000-0000-4000-8000-000000000062",
                "base-revision",repeat('1',64),null,null,123456789L);
        DigitizationStore.Snapshot created=store.create(source,"T",123L,target);
        File current=new File(root,created.runId+".json");
        String duplicateRunId="digitize-00000000-0000-4000-8000-000000000063";
        String json=new String(Files.readAllBytes(current.toPath()),StandardCharsets.UTF_8)
                .replace("\"runId\":\""+created.runId+"\"",
                        "\"runId\":\""+duplicateRunId+"\"");
        Files.write(new File(root,duplicateRunId+".json").toPath(),
                json.getBytes(StandardCharsets.UTF_8));

        assertEquals(2,store.list().size());
        assertFails(()->store.create(source,"T",123L,target),
                "发布操作 UUID 已绑定多个检查点");
    }

    @Test public void explicitTargetBindingIsImmutableAndStaleCheckpointCannotReplaceIt() throws Exception {
        File root=temporary.newFolder("target-bind-cas");
        DigitizationStore store=new DigitizationStore(root);
        DigitizationStore.Source source=source("note-bind","T",1,"p");
        DigitizationStore.Snapshot unbound=store.create(source,"T",1L);
        DigitizationStore.Target selected=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                "00000000-0000-4000-8000-000000000003","00000000-0000-4000-8000-000000000003",
                "00000000-0000-4000-8000-000000000004","r4-1",
                repeat('b',64),null,null,222222222L);
        DigitizationStore.Snapshot bound=store.bindTarget(unbound,selected);
        assertTrue(selected.sameIntent(new DigitizationStore(root).load(bound.runId).target));
        DigitizationStore.Target other=new DigitizationStore.Target(source.noteId,
                DigitizationStore.TargetKind.GROUP,DigitizationStore.TargetOperation.ADD,
                "00000000-0000-4000-8000-000000000005","00000000-0000-4000-8000-000000000005",
                selected.lineage,selected.groupRevision,selected.groupDigest,null,null,222222223L);
        assertFails(()->store.bindTarget(bound,other),"已绑定其他发布目标");
        assertFails(()->store.bindTarget(unbound,other),"已被其他操作更新");
        assertTrue(selected.sameIntent(store.load(bound.runId).target));
    }

    @Test public void frozenPublishTimestampDoesNotDependOnLaterDeviceTimezone() throws Exception {
        TimeZone original=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            String utc=VaultStore.buildDigitizedMarkdown("note-zone","时区样例",1,123L,
                    Collections.singletonList("page"),1_800_000_000_123L);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            String changed=VaultStore.buildDigitizedMarkdown("note-zone","时区样例",1,123L,
                    Collections.singletonList("page"),1_800_000_000_123L);
            assertEquals("a resumed checkpoint renders the same stable timestamp",utc,changed);
        }finally{TimeZone.setDefault(original);}
    }

    @Test public void crashRecoveryReturnsOnlyWholeOldOrNewCheckpoint() throws Exception {
        for (DigitizationStore.WriteStage stage : DigitizationStore.WriteStage.values()) {
            File root = temporary.newFolder("fault-" + stage.name());
            AtomicReference<DigitizationStore.WriteStage> armed = new AtomicReference<>();
            DigitizationStore store = new DigitizationStore(root, reached -> {
                if (armed.compareAndSet(reached, null)) throw new Exception("injected " + reached);
            });
            DigitizationStore.Snapshot snapshot = store.startAttempt(store.create(
                    source("note-" + stage.name().toLowerCase(), "T", 1, "p"), "T", 1));
            armed.set(stage);
            try {
                store.savePage(snapshot, 0, "完整结果");
                fail("fault should interrupt write");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("injected"));
            }
            DigitizationStore.Snapshot reopened = new DigitizationStore(root).load(snapshot.runId);
            if (stage == DigitizationStore.WriteStage.NEW_VERSION_PROMOTED) {
                assertEquals("完整结果", reopened.pages.get(0));
            } else {
                assertNull(reopened.pages.get(0));
            }
        }
    }

    @Test public void backupOnlyRunIsListedAndCorruptionRemainsVisible() throws Exception {
        File root = temporary.newFolder("recovery-list");
        DigitizationStore store = new DigitizationStore(root);
        DigitizationStore.Snapshot snapshot = store.create(
                source("note-list", "T", 1, "p"), "T", 1);
        File target = new File(root, snapshot.runId + ".json");
        File backup = new File(root, target.getName() + ".bak");
        assertTrue(target.renameTo(backup));
        List<DigitizationStore.Snapshot> reopened = new DigitizationStore(root)
                .listForNote("note-list");
        assertEquals(1, reopened.size());
        assertTrue(target.isFile());

        File damaged = new File(root, "digitize-00000000-0000-0000-0000-000000000000.json");
        Files.write(damaged.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertFalse(store.listCorrupt().isEmpty());
        assertEquals("broken", new String(Files.readAllBytes(damaged.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void twoStoreInstancesCasAndFileIdentityPreventCrossRunOverwrite() throws Exception {
        File root = temporary.newFolder("cas");
        DigitizationStore first = new DigitizationStore(root);
        DigitizationStore second = new DigitizationStore(root);
        DigitizationStore.Snapshot original = first.startAttempt(first.create(
                source("note-cas", "T", 2, "p"), "T", 1));
        DigitizationStore.Snapshot stale = second.load(original.runId);
        DigitizationStore.Snapshot saved = first.savePage(original, 0, "第一页");
        assertFails(() -> second.cancel(stale), "已被其他操作更新");
        assertEquals("第一页", first.load(saved.runId).pages.get(0));

        DigitizationStore.Snapshot other = first.create(
                source("note-other", "U", 1, "p"), "U", 1);
        File originalFile = new File(root, original.runId + ".json");
        File otherFile = new File(root, other.runId + ".json");
        new File(root, originalFile.getName() + ".bak").delete();
        byte[] otherBytes = Files.readAllBytes(otherFile.toPath());
        Files.write(originalFile.toPath(), otherBytes);
        assertFails(() -> first.load(original.runId), "检查点损坏");
        assertEquals(other.runId, first.load(other.runId).runId);
    }

    @Test public void leaseIsExclusiveAcrossStoresAndCanBeReacquiredAfterClose() throws Exception {
        File root = temporary.newFolder("lease");
        DigitizationStore first = new DigitizationStore(root);
        DigitizationStore second = new DigitizationStore(root);
        DigitizationStore.Snapshot batch = first.create(
                source("note-lease", "T", 1, "p"), "T", 1);

        DigitizationStore.Lease held = first.acquireLease(batch.runId);
        assertNotNull(held);
        assertNull(second.acquireLease(batch.runId));
        held.close();

        DigitizationStore.Lease reopened = second.acquireLease(batch.runId);
        assertNotNull(reopened);
        reopened.close();
    }

    @Test public void unknownProviderPageSurvivesCancelRestartAndClearsOnlyOnSave() throws Exception {
        File root = temporary.newFolder("unknown-page");
        DigitizationStore store = new DigitizationStore(root);
        DigitizationStore.Snapshot batch = store.startAttempt(store.create(
                source("note-unknown", "T", 2, "p"), "T", 1));
        batch = store.markPageStarted(batch, 0);
        batch = store.cancel(batch);

        DigitizationStore.Snapshot reopened = new DigitizationStore(root).load(batch.runId);
        assertEquals(0, reopened.inFlightPage);
        assertTrue(reopened.partialMarkdown().contains("重试可能再次计费"));
        reopened = store.startAttempt(reopened);
        assertEquals(0, reopened.inFlightPage);
        reopened = store.markPageStarted(reopened, 0); // explicit retry confirmation
        reopened = store.savePage(reopened, 0, "已确认结果");
        assertEquals(-1, reopened.inFlightPage);
        assertEquals(1, reopened.nextPendingPage());
    }

    private static DigitizationStore.Source source(String noteId, String title,
                                                   int pages, String profile) throws Exception {
        return DigitizationStore.Source.from(document(noteId, title, pages), "", pages,
                profile, "https://example.test/v1/chat/completions", "vision-model");
    }

    private static JSONObject document(String noteId, String title, int pages) throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", noteId)
                .put("title", title).put("updatedAt", 1L).put("pageCount", pages)
                .put("canvasWidth", 100).put("canvasHeight", 100)
                .put("pageWidth", 100).put("pageHeight", 100).put("pageGap", 10)
                .put("viewportScale", 1).put("viewportCenterX", 0).put("viewportCenterY", 0)
                .put("strokes", new JSONArray()).put("textFlows", new JSONArray())
                .put("textBoxes", new JSONArray()).put("images", new JSONArray());
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static void assertFails(ThrowingAction action, String message) throws Exception {
        try { action.run(); fail("expected failure containing: " + message); }
        catch (Exception expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }

    private static String repeat(char value, int count) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < count; index++) output.append(value);
        return output.toString();
    }
}
