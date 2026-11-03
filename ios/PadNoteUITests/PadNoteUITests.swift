import XCTest

final class PadNoteUITests: XCTestCase {
    private var app: XCUIApplication!
    private let noteTitle = "UI Note " + String(UUID().uuidString.prefix(6))
    private var videoFixtureID: String?
    private var contentOutlineFixtureID: String?

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--uitesting"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        if let fixtureID = contentOutlineFixtureID {
            app = XCUIApplication()
            app.launchArguments = ["--uitesting", "--uitesting-content-outline", "--content-outline-fixture-id", fixtureID,
                                  "--uitesting-content-outline-cleanup"]
            app.launch()
            let cleanup = app.buttons["contentOutlineFixtureCleanup"]
            if cleanup.waitForExistence(timeout: 5) { cleanup.tap() }
            app.terminate()
            contentOutlineFixtureID = nil
        }
        app = nil
        if let fixtureID = videoFixtureID {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("padnote-video-ui-\(fixtureID)", isDirectory: true)
            try? FileManager.default.removeItem(at: root)
            let suite = "PadNote.VideoOperation.UITest.\(fixtureID)"
            UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
            videoFixtureID = nil
        }
    }

    func testVideoTaskInitializationStoryboardReviewAndExplicitApproval() throws {
        defer { attachVideoEvidence("video-flow-final") }
        app.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-agent-video"]
        app.launch()
        let entry = app.descendants(matching: .any).matching(identifier: "agentVideoStoryboardEntry").firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 8))
        entry.tap()
        let initialize = app.buttons["videoInitialize"]
        XCTAssertTrue(initialize.waitForExistence(timeout: 8))
        initialize.tap()
        let storyboard = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(storyboard.waitForExistence(timeout: 8))
        storyboard.tap()
        dismissVideoConfirmationPopover()
        waitForFixtureFields(["posts=1", "executions=1"], message: "canceling the material/provider storyboard confirmation must not send a paid request")
        scrollUntilVisible(storyboard)
        storyboard.tap()
        confirmStoryboardCost()
        let loadReview = app.buttons["videoLoadReview"]
        if !loadReview.waitForExistence(timeout: 8) {
            attachVideoEvidence("after-storyboard-without-review-entry")
            XCTFail("successful storyboard should expose a review loader")
            return
        }
        loadReview.tap()
        XCTAssertTrue(app.staticTexts["第1版分镜 · 序号 3"].waitForExistence(timeout: 8))
        let preview = app.images["videoPreview-scene-1"]
        if !preview.waitForExistence(timeout: 8) {
            attachVideoEvidence("after-review-without-verified-preview")
            XCTFail("review image should pass byte, hash and PNG dimension checks")
            return
        }
        let approve = app.buttons["videoApproveRevision"]
        XCTAssertTrue(approve.waitForExistence(timeout: 5))
        approve.tap()
        let confirmationButtons = app.buttons.matching(identifier: "videoApproveCurrentRevision")
        let confirm = confirmationButtons.allElementsBoundByIndex.last(where: { $0.isHittable }) ?? confirmationButtons.firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        let success = app.staticTexts["videoApprovalSucceeded"]
        XCTAssertTrue(success.waitForExistence(timeout: 8), "Only a persisted succeeded approval should show the success message")
        XCTAssertTrue(success.label.contains("第1版分镜已批准"))
        XCTAssertTrue(app.staticTexts["videoApprovedReviewStatus"].exists)
        XCTAssertFalse(app.buttons["videoGenerateNextRevision"].exists,
            "an approved revision cannot be used to submit another storyboard")

        let produce = app.buttons["videoProduce"]
        XCTAssertTrue(produce.waitForExistence(timeout: 5), "production must be a separate explicit action after approval")
        produce.tap()
        let ttsWarning = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "配音可能产生供应商费用")).firstMatch
        XCTAssertTrue(ttsWarning.waitForExistence(timeout: 5), "production must disclose cloud TTS charges")
        dismissVideoConfirmationPopover()
        waitForFixtureFields(["posts=3", "executions=3"], message: "canceling the TTS confirmation must not post a paid action")

        scrollUntilVisible(produce)
        XCTAssertTrue(produce.isHittable, "production must be visible before requesting the paid confirmation")
        produce.tap()
        let produceMatches = app.buttons.matching(identifier: "videoConfirmProduction").allElementsBoundByIndex
        XCTAssertTrue(produceMatches.first?.waitForExistence(timeout: 5) == true)
        (produceMatches.last(where: { $0.isHittable }) ?? produceMatches.first!).tap()
        XCTAssertTrue(app.staticTexts["videoProductionSucceeded"].waitForExistence(timeout: 8),
                      "only a receipt-bound completed production should show success")
        waitForFixtureFields(["posts=4", "executions=4"], message: "production must occur only after explicit confirmation")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let play = app.buttons["agentVideoPlayArtifact-video-output"]
        XCTAssertTrue(play.waitForExistence(timeout: 8), "returning from production should refresh authenticated artifact metadata")
        play.tap()
        let playerReady = app.staticTexts["agentVideoLocalPlayer"]
        XCTAssertTrue(playerReady.waitForExistence(timeout: 8), "the downloaded artifact should load a local video track")
        XCTAssertEqual(playerReady.label, "本地视频已加载", "playback must use a hash-verified MP4 with a decodable video track")
    }

    func testOriginalNoteCanCreateTaskAssociateAndReopenVerifiedLocalVideo() throws {
        let fixtureID = UUID().uuidString.lowercased()
        videoFixtureID = fixtureID
        app.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-note-video-roundtrip",
                              "--video-roundtrip-fixture-id", fixtureID]
        app.launch()

        let more = app.buttons["moreActionsButton"]
        XCTAssertTrue(more.waitForExistence(timeout: 8))
        more.tap()
        let createVideo = app.buttons["生成视频"]
        XCTAssertTrue(createVideo.waitForExistence(timeout: 5))
        createVideo.tap()

        let preview = app.staticTexts["noteVideoMaterialPreview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8))
        XCTAssertTrue(preview.label.contains("合成内容"), "the editor entry must show the selected prepared material")
        XCTAssertTrue(app.staticTexts["noteVideoMaterialDisclosure"].exists,
                      "the exact material scope and storyboard cost must be disclosed before submission")
        let targetDisclosure = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "目标电脑：")
        ).firstMatch
        XCTAssertTrue(targetDisclosure.waitForExistence(timeout: 5), "the selected video target should be visible before sending")
        XCTAssertTrue(targetDisclosure.label.contains("roundtrip-video.fixture.test"), "the test host should be visible")
        XCTAssertTrue(targetDisclosure.label.contains("内置视频"), "the Agent kind should be visible")
        XCTAssertTrue(targetDisclosure.label.contains("roundtrip-bridge"), "the paired bridge identity should be visible")
        XCTAssertTrue(targetDisclosure.label.contains("/ 实例 instance"),
                      "the paired instance should use the existing formatter's contextual shortened ID")
        let targetScreenshot = XCTAttachment(screenshot: app.screenshot())
        targetScreenshot.name = "video-target-kind-and-identity-before-send"
        targetScreenshot.lifetime = .keepAlways
        add(targetScreenshot)

        let send = app.buttons["发送到电脑"]
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        send.tap()
        let staleWarning = app.alerts["使用已整理快照？"]
        XCTAssertTrue(staleWarning.waitForExistence(timeout: 5),
                      "stale prepared material requires a separate explicit choice")
        app.buttons.matching(identifier: "cancelStaleVideoSnapshot").firstMatch.tap()
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["noteVideoRoundTripPostCount"].label.contains("posts=0"),
                      "canceling the stale-material confirmation must send no task request")

        send.tap()
        XCTAssertTrue(staleWarning.waitForExistence(timeout: 5))
        app.buttons.matching(identifier: "confirmStaleVideoSnapshot").firstMatch.tap()
        let taskLink = app.buttons["查看任务状态"]
        XCTAssertTrue(taskLink.waitForExistence(timeout: 12), "confirmed snapshot should create a persistent task")
        let submittedOnce = app.staticTexts["noteVideoRoundTripPostCount"]
        let oneRequest = NSPredicate(format: "label CONTAINS %@", "posts=1")
        expectation(for: oneRequest, evaluatedWith: submittedOnce)
        waitForExpectations(timeout: 5)
        taskLink.tap()

        let associate = app.buttons["associateVideoArtifact-roundtrip-video"]
        scrollUntilVisible(associate)
        XCTAssertTrue(associate.waitForExistence(timeout: 12), "the completed task should expose its authenticated MP4")
        associate.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已关联：离线讲解.mp4"))
            .firstMatch.waitForExistence(timeout: 12), "association should be explicit and persistent")

        app.buttons["完成"].tap()
        let cancelExport = app.buttons["取消"]
        XCTAssertTrue(cancelExport.waitForExistence(timeout: 5))
        cancelExport.tap()

        more.tap()
        let openAttachments = app.buttons["查看已关联视频"]
        XCTAssertTrue(openAttachments.waitForExistence(timeout: 5))
        openAttachments.tap()
        let play = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "playNoteVideo-")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "原笔记已更新"))
            .firstMatch.exists, "the shelf should disclose that this video belongs to an older source snapshot")
        play.tap()
        let player = app.staticTexts["agentVideoLocalPlayer"]
        XCTAssertTrue(player.waitForExistence(timeout: 12))
        XCTAssertEqual(player.label, "本地视频已加载",
                       "reopening from the original note must validate and play a local MP4 video track")
    }

    func testProductionCapabilityDisablesProduceWithoutPosting() throws {
        defer { attachVideoEvidence("video-production-capability-disabled") }
        launchVideoFixture(mode: "no-video-production")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let storyboard = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(storyboard.waitForExistence(timeout: 8)); storyboard.tap(); confirmStoryboardCost()
        let loadReview = app.buttons["videoLoadReview"]
        XCTAssertTrue(loadReview.waitForExistence(timeout: 8)); loadReview.tap()
        let approve = app.buttons["videoApproveRevision"]
        XCTAssertTrue(approve.waitForExistence(timeout: 8)); approve.tap()
        let approval = app.buttons.matching(identifier: "videoApproveCurrentRevision").allElementsBoundByIndex
        XCTAssertTrue(approval.first?.waitForExistence(timeout: 5) == true)
        (approval.last(where: { $0.isHittable }) ?? approval.first!).tap()
        let produce = app.buttons["videoProduce"]
        XCTAssertTrue(produce.waitForExistence(timeout: 5))
        XCTAssertFalse(produce.isEnabled, "missing video_production capability must disable the paid action")
        XCTAssertTrue(app.staticTexts["videoProductionCapabilityUnavailable"].exists,
                      "the UI should explain why production is unavailable")
        waitForFixtureFields(["posts=3", "executions=3"],
                             message: "a disabled production action must not send a fourth request")
    }

    func testProductionCancelRequiresConfirmationAndKeepsRequestedDistinctFromCancelled() throws {
        defer { attachVideoEvidence("video-produce-cancel-requested") }
        launchVideoFixture(mode: "produce-running-cancel")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let storyboard = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(storyboard.waitForExistence(timeout: 8)); storyboard.tap(); confirmStoryboardCost()
        let loadReview = app.buttons["videoLoadReview"]
        XCTAssertTrue(loadReview.waitForExistence(timeout: 8)); loadReview.tap()
        let approve = app.buttons["videoApproveRevision"]
        XCTAssertTrue(approve.waitForExistence(timeout: 8)); approve.tap()
        let approval = app.buttons.matching(identifier: "videoApproveCurrentRevision").allElementsBoundByIndex
        XCTAssertTrue(approval.first?.waitForExistence(timeout: 5) == true)
        (approval.last(where: { $0.isHittable }) ?? approval.first!).tap()
        let produce = app.buttons["videoProduce"]
        XCTAssertTrue(produce.waitForExistence(timeout: 8)); produce.tap()
        let confirm = app.buttons.matching(identifier: "videoConfirmProduction").allElementsBoundByIndex
        XCTAssertTrue(confirm.first?.waitForExistence(timeout: 5) == true)
        (confirm.last(where: { $0.isHittable }) ?? confirm.first!).tap()
        let stop = app.buttons["videoRequestProductionCancel"]
        XCTAssertTrue(stop.waitForExistence(timeout: 8), "a running cloud voice/render operation should expose a bound stop action")
        stop.tap()
        let visibleStopAction = app.buttons.matching(identifier: "videoConfirmProductionStop").firstMatch
        XCTAssertTrue(visibleStopAction.waitForExistence(timeout: 5), "wait for the stop confirmation before dismissing it")
        XCTAssertTrue(visibleStopAction.isHittable, "the destructive stop action should be visible but must not be tapped")
        dismissVideoConfirmationPopover()
        XCTAssertTrue(app.navigationBars["视频分镜"].exists, "dismissing the stop prompt must keep the user on the video task")
        waitForFixtureFields(["posts=4", "executions=4", "attempts=0"],
                             message: "declining the stop confirmation must not send cancellation")

        scrollUntilVisible(stop)
        stop.tap()
        let confirmStop = app.buttons.matching(identifier: "videoConfirmProductionStop").allElementsBoundByIndex
        XCTAssertTrue(confirmStop.first?.waitForExistence(timeout: 5) == true)
        (confirmStop.last(where: { $0.isHittable }) ?? confirmStop.first!).tap()
        let requested = app.staticTexts["videoProductionCancelStatus"]
        XCTAssertTrue(requested.waitForExistence(timeout: 8))
        XCTAssertTrue(requested.label.contains("已记录停止请求"), "an accepted request is not proof of cancellation")
        waitForFixtureFields(["posts=4", "executions=4", "attempts=1", "cancels=1"],
                             message: "the exact running producer receives one explicit stop request")
        XCTAssertFalse(app.staticTexts["videoProductionSucceeded"].exists)
    }

    func testUnknownProductionStaysUnknownAfterRelaunchWithoutAnotherPost() throws {
        defer { attachVideoEvidence("video-produce-unknown-reopen") }
        let fixtureID = launchVideoFixture(mode: "produce-unknown")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let storyboard = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(storyboard.waitForExistence(timeout: 8)); storyboard.tap(); confirmStoryboardCost()
        let loadReview = app.buttons["videoLoadReview"]
        XCTAssertTrue(loadReview.waitForExistence(timeout: 8)); loadReview.tap()
        let approve = app.buttons["videoApproveRevision"]
        XCTAssertTrue(approve.waitForExistence(timeout: 8)); approve.tap()
        let approval = app.buttons.matching(identifier: "videoApproveCurrentRevision").allElementsBoundByIndex
        XCTAssertTrue(approval.first?.waitForExistence(timeout: 5) == true)
        (approval.last(where: { $0.isHittable }) ?? approval.first!).tap()
        let produce = app.buttons["videoProduce"]
        XCTAssertTrue(produce.waitForExistence(timeout: 8)); produce.tap()
        let confirm = app.buttons.matching(identifier: "videoConfirmProduction").allElementsBoundByIndex
        XCTAssertTrue(confirm.first?.waitForExistence(timeout: 5) == true)
        (confirm.last(where: { $0.isHittable }) ?? confirm.first!).tap()
        let reconcile = app.buttons["videoReconcileOperation"]
        XCTAssertTrue(reconcile.waitForExistence(timeout: 8), "an uncertain production must offer inspection, not paid resubmission")
        waitForFixtureFields(["posts=4", "executions=4"], message: "the initial production request is recorded once")

        app.terminate()
        app = makeVideoFixtureApp(mode: "produce-unknown", id: fixtureID)
        app.launch()
        enterVideoDetail()
        XCTAssertTrue(app.buttons["videoReconcileOperation"].waitForExistence(timeout: 8),
                      "reopening must restore the unknown operation for explicit reconciliation")
        XCTAssertFalse(app.buttons["videoProduce"].isEnabled, "unknown results must not unlock production")
        waitForFixtureFields(["posts=4", "executions=4"], message: "reopening must not send another paid POST")
    }

    func testReviewRefreshFailureKeepsSavedTextAndImageButDisablesApproval() throws {
        defer { attachVideoEvidence("video-review-refresh-failure") }
        launchVideoFixture(mode: "review-refresh-failure")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let storyboard = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(storyboard.waitForExistence(timeout: 8)); storyboard.tap()
        confirmStoryboardCost()
        let loadReview = app.buttons["videoLoadReview"]
        XCTAssertTrue(loadReview.waitForExistence(timeout: 8)); loadReview.tap()

        let reviewHeading = app.staticTexts["第1版分镜 · 序号 3"]
        XCTAssertTrue(reviewHeading.waitForExistence(timeout: 8))
        let preview = app.images["videoPreview-scene-1"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8), "the fixture serves a valid PNG before the failed refresh")
        let refresh = app.buttons["videoRefreshReview"]
        scrollUntilVisible(refresh)
        XCTAssertTrue(refresh.isHittable); refresh.tap()

        let error = app.staticTexts["videoOperationError"]
        XCTAssertTrue(error.waitForExistence(timeout: 8), "review refresh errors must propagate through the shared action handler")
        XCTAssertTrue(error.label.contains("HTTP 503"))
        scrollUntilVisible(reviewHeading)
        XCTAssertTrue(reviewHeading.exists, "the last saved review remains readable")
        scrollUntilVisible(preview)
        XCTAssertTrue(preview.exists, "a previously validated in-memory PNG remains visible")
        XCTAssertFalse(app.buttons["videoApproveRevision"].exists,
            "a cached review cannot be approved after an authoritative refresh failed")
        waitForFixtureFields(["posts=2", "executions=2", "reviews=2", "previews=1"],
            message: "the failure is a review GET; it must not submit an approval and the PNG had already loaded")
    }

    func testVideoSubmissionResponseLossRecoversSameKeyAfterAppRelaunch() throws {
        defer { attachVideoEvidence("video-response-loss-reopen") }
        let fixtureID = launchVideoFixture(mode: "uncertain")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let retry = app.buttons["videoRetrySameKey"]
        XCTAssertTrue(retry.waitForExistence(timeout: 8), "a lost response must leave the persisted operation recoverable")
        waitForLabel(app.staticTexts["videoUITestFixtureStatus"], containing: "posts=1 executions=1")

        app.terminate()
        app = makeVideoFixtureApp(mode: "uncertain", id: fixtureID)
        app.launch()
        enterVideoDetail()
        let restoredRetry = app.buttons["videoRetrySameKey"]
        XCTAssertTrue(restoredRetry.waitForExistence(timeout: 8), "reopening must restore the same pending operation")
        restoredRetry.tap()
        XCTAssertTrue(app.buttons["videoGenerateStoryboard"].waitForExistence(timeout: 8),
            "same-key replay should resolve the previously accepted server operation")
        let status = app.staticTexts["videoUITestFixtureStatus"]
        XCTAssertTrue(status.waitForExistence(timeout: 3))
        waitForLabel(status, containing: "posts=2 executions=1",
            message: "the server fixture accepts only the exact saved action/body/key and executes once")
    }

    func testVideoRemoteUnknownCanOnlyRefreshAndNeverReposts() throws {
        defer { attachVideoEvidence("video-remote-unknown-no-repost") }
        launchVideoFixture(mode: "unknown")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let refresh = app.buttons["videoRefreshOperation"]
        XCTAssertTrue(refresh.waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["videoRetrySameKey"].exists,
            "an operation ID with remote unknown must not expose submission retry")
        refresh.tap()
        waitForFixtureFields(["posts=1", "executions=1", "gets=1"],
            message: "refresh is a status GET and does not replay the accepted action")
        XCTAssertFalse(app.buttons["videoRetrySameKey"].exists)
    }

    func testVideoUnknownRequiresExplicitReconcileAndCanBecomeSucceeded() throws {
        defer { attachVideoEvidence("video-reconcile-success") }
        launchVideoFixture(mode: "reconcile-success")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        waitForLabel(videoStatus(), containing: "结果未知")
        let reconcile = app.buttons["videoReconcileOperation"]
        XCTAssertTrue(reconcile.waitForExistence(timeout: 5))
        waitForFixtureFields(["posts=1", "executions=1", "gets=0", "reconciles=0"],
            message: "opening and ordinary status refresh must not invoke reconciliation")
        reconcile.tap()
        waitForLabel(videoStatus(), containing: "操作已完成")
        XCTAssertFalse(app.buttons["videoReconcileOperation"].exists)
        waitForFixtureFields(["posts=1", "executions=1", "reconciles=1"],
            message: "only the explicit button checks the durable computer-side receipt")
    }

    func testVideoUnknownReconcileUnconfirmedKeepsRecordAndDoesNotReplay() throws {
        defer { attachVideoEvidence("video-reconcile-unconfirmed") }
        launchVideoFixture(mode: "reconcile-unconfirmed")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        waitForLabel(videoStatus(), containing: "结果未知")
        let reconcile = app.buttons["videoReconcileOperation"]
        XCTAssertTrue(reconcile.waitForExistence(timeout: 5)); reconcile.tap()
        waitForLabel(videoStatus(), containing: "结果未知")
        XCTAssertTrue(app.buttons["videoReconcileOperation"].exists)
        XCTAssertFalse(app.buttons["videoRetrySameKey"].exists)
        waitForFixtureFields(["posts=1", "executions=1", "gets=0", "reconciles=1"],
            message: "unconfirmed evidence leaves the existing operation unknown without replay")
    }

    func testVideoUnknownReconcileErrorPreservesUnknownOperation() throws {
        defer { attachVideoEvidence("video-reconcile-error") }
        launchVideoFixture(mode: "reconcile-error")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        waitForLabel(videoStatus(), containing: "结果未知")
        app.buttons["videoReconcileOperation"].tap()
        let error = app.staticTexts["videoOperationError"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(error.label.contains("HTTP 503"))
        waitForLabel(videoStatus(), containing: "结果未知")
        XCTAssertTrue(app.buttons["videoReconcileOperation"].exists)
        waitForFixtureFields(["posts=1", "executions=1", "reconciles=1"],
            message: "reconciliation error keeps the original unknown record")
    }

    func testVideoUnknownReconcileIsDisabledAfterConnectionRevocation() throws {
        defer { attachVideoEvidence("video-reconcile-revoked") }
        launchVideoFixture(mode: "reconcile-revoked")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        waitForLabel(videoStatus(), containing: "结果未知")
        let reconcile = app.buttons["videoReconcileOperation"]
        XCTAssertTrue(reconcile.waitForExistence(timeout: 5))
        let revoke = app.buttons["videoUITestRevokeConnection"]
        scrollUntilVisible(revoke)
        XCTAssertTrue(revoke.isHittable); revoke.tap()
        scrollUntilVisible(reconcile)
        XCTAssertTrue(reconcile.exists)
        XCTAssertFalse(reconcile.isEnabled, "reconciliation must remain bound to the original valid connection")
        XCTAssertTrue(app.staticTexts["videoConnectionUnavailable"].exists)
        waitForFixtureFields(["posts=1", "executions=1", "reconciles=0"],
            message: "revocation blocks receipt reads without affecting the saved unknown record")
    }

    func testVideoApprovalIsBlockedAfterConnectionRevocation() throws {
        defer { attachVideoEvidence("video-revoked-before-approval") }
        launchVideoFixture(mode: "revoked")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let storyboard = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(storyboard.waitForExistence(timeout: 8)); storyboard.tap()
        confirmStoryboardCost()
        let loadReview = app.buttons["videoLoadReview"]
        XCTAssertTrue(loadReview.waitForExistence(timeout: 8)); loadReview.tap()
        let preview = app.images["videoPreview-scene-1"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8), "the approval fixture must first show a verified image")
        let revoke = app.buttons["videoUITestRevokeConnection"]
        scrollUntilVisible(revoke)
        XCTAssertTrue(revoke.isHittable); revoke.tap()
        let approve = app.buttons["videoApproveRevision"]
        scrollUntilVisible(approve)
        XCTAssertTrue(approve.waitForExistence(timeout: 5))
        XCTAssertFalse(approve.isEnabled, "an identity change must disable approval before it can be submitted")
        let guidance = app.staticTexts["videoConnectionUnavailable"]
        XCTAssertTrue(guidance.waitForExistence(timeout: 5))
        XCTAssertTrue(guidance.label.contains("电脑 Agent"))
        XCTAssertTrue(guidance.label.contains("用新连接另建任务"))
        let savedReview = app.staticTexts["第1版分镜 · 序号 3"]
        scrollUntilVisible(savedReview)
        XCTAssertTrue(savedReview.exists, "saved review text remains readable")
        scrollUntilVisible(preview)
        XCTAssertTrue(preview.exists, "an already loaded image remains visible in memory")
        waitForFixtureFields(["posts=2", "executions=2"],
            message: "only initialize/storyboard reached the fixture; approval was rejected locally")
    }

    func testQueuedVideoOperationCanBeCancelledAndRecorded() throws {
        defer { attachVideoEvidence("video-queued-cancel") }
        launchVideoFixture(mode: "queued-cancel")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let queuedStatus = videoStatus()
        XCTAssertTrue(queuedStatus.waitForExistence(timeout: 8))
        waitForLabel(queuedStatus, containing: "排队中")
        let cancel = app.buttons["videoCancelQueued"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 5)); cancel.tap()
        let cancelled = app.buttons["videoRetryInitialize"]
        XCTAssertTrue(cancelled.waitForExistence(timeout: 8), "a confirmed queued cancellation permits an explicit later retry")
        waitForLabel(videoStatus(), containing: "已取消")
        waitForFixtureFields(["posts=1", "executions=1", "cancels=1", "attempts=1"])
    }

    func testRunningVideoOperationDoesNotOfferQueuedCancellation() throws {
        defer { attachVideoEvidence("video-running-no-cancel") }
        launchVideoFixture(mode: "running-cancel")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let status = videoStatus()
        XCTAssertTrue(status.waitForExistence(timeout: 8))
        waitForLabel(status, containing: "执行中")
        XCTAssertTrue(app.buttons["videoRefreshOperation"].exists)
        XCTAssertFalse(app.buttons["videoCancelQueued"].exists,
            "the client must not claim it can cancel a worker that is already running")
        waitForFixtureFields(["posts=1", "executions=1", "cancels=0", "attempts=0"])
    }

    func testRunningStoryboardCanBeExplicitlyStoppedAndConfirmed() throws {
        defer { attachVideoEvidence("video-running-stop-confirmed") }
        launchVideoFixture(mode: "running-cancel-confirmed")
        enterVideoDetail()
        startRunningStoryboardInFixture()
        let request = app.buttons["videoRequestRunningCancel"]
        scrollUntilVisible(request)
        XCTAssertTrue(request.waitForExistence(timeout: 5)); XCTAssertTrue(request.isEnabled); request.tap()
        let confirm = app.buttons["videoConfirmRunningCancel"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); tapRunningCancelConfirmation(retrying: false)
        waitForLabel(videoStatus(), containing: "已取消")
        waitForLabel(app.staticTexts["videoRunningCancelStatus"], containing: "已核实")
        XCTAssertTrue(app.staticTexts["videoStoppedTaskRetained"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["videoRetryStoryboard"].exists,
            "a confirmed running stop cannot be converted into a fresh action on the same task")
        waitForFixtureFields(["posts=2", "executions=2", "cancels=1", "attempts=1"])
    }

    func testRunningStoryboardStopRequestPendingIsNotReportedAsStopped() throws {
        defer { attachVideoEvidence("video-running-stop-pending") }
        launchVideoFixture(mode: "running-cancel-pending")
        enterVideoDetail()
        startRunningStoryboardInFixture()
        let request = app.buttons["videoRequestRunningCancel"]
        scrollUntilVisible(request); XCTAssertTrue(request.isEnabled); request.tap()
        tapRunningCancelConfirmation(retrying: false)
        waitForLabel(app.staticTexts["videoRunningCancelStatus"], containing: "已记录停止请求")
        waitForLabel(videoStatus(), containing: "执行中")
        XCTAssertTrue(app.buttons["videoRefreshRunningCancel"].exists,
            "a pending control can only be queried again, not silently submitted again")
        waitForFixtureFields(["posts=2", "executions=2", "attempts=1"])
    }

    func testTooLateRunningStopPreservesOperationAndExplainsOutcome() throws {
        defer { attachVideoEvidence("video-running-stop-too-late") }
        launchVideoFixture(mode: "running-cancel-too-late")
        enterVideoDetail()
        startRunningStoryboardInFixture()
        let request = app.buttons["videoRequestRunningCancel"]
        scrollUntilVisible(request); XCTAssertTrue(request.isEnabled); request.tap()
        tapRunningCancelConfirmation(retrying: false)
        waitForLabel(app.staticTexts["videoRunningCancelStatus"], containing: "操作已先结束")
        waitForLabel(videoStatus(), containing: "执行中")
        waitForFixtureFields(["posts=2", "executions=2", "cancels=1", "attempts=1"])
    }

    func testSavedNoRequestIntentReopensReadOnlyThenExplicitlyRetriesSameOperation() throws {
        defer { attachVideoEvidence("video-running-stop-explicit-retry") }
        let fixtureID = launchVideoFixture(mode: "running-cancel-retry")
        enterVideoDetail()
        startRunningStoryboardInFixture()
        let request = app.buttons["videoRequestRunningCancel"]
        scrollUntilVisible(request); XCTAssertTrue(request.isEnabled); request.tap()
        tapRunningCancelConfirmation(retrying: false)
        let error = app.staticTexts["videoOperationError"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(error.label.contains("没有记录到停止请求"))
        waitForFixtureFields(["posts=2", "executions=2", "attempts=1"])

        app.terminate()
        app = makeVideoFixtureApp(mode: "running-cancel-retry", id: fixtureID)
        app.launch()
        enterReopenedRunningVideoDetail()
        let retry = app.buttons["videoRetryRunningCancel"]
        scrollUntilVisible(retry)
        XCTAssertTrue(retry.waitForExistence(timeout: 8), "a persisted intent with GET 409 offers only explicit user retry")
        waitForFixtureFields(["posts=2", "executions=2", "attempts=1", "cancelGets=1"],
            message: "cold reopen may query, but must not repeat the stop POST")
        retry.tap()
        tapRunningCancelConfirmation(retrying: true)
        waitForLabel(videoStatus(), containing: "已取消")
        waitForFixtureFields(["posts=2", "executions=2", "attempts=2", "cancelGets=1"],
            message: "explicit retry reuses the saved storyboard operation and does not create another action")
    }

    func testRevokedConnectionDisablesRunningStoryboardStop() throws {
        defer { attachVideoEvidence("video-running-stop-revoked") }
        launchVideoFixture(mode: "running-cancel-revoked")
        enterVideoDetail()
        startRunningStoryboardInFixture()
        let request = app.buttons["videoRequestRunningCancel"]
        scrollUntilVisible(request); XCTAssertTrue(request.isEnabled)
        let revoke = app.buttons["videoUITestRevokeConnection"]
        scrollUntilVisible(revoke); XCTAssertTrue(revoke.isHittable); revoke.tap()
        scrollUntilVisible(request)
        XCTAssertTrue(request.exists)
        XCTAssertFalse(request.isEnabled)
        XCTAssertTrue(app.staticTexts["videoConnectionUnavailable"].exists)
        waitForFixtureFields(["posts=2", "executions=2", "cancels=0", "attempts=0"])
    }

    private func startRunningStoryboardInFixture() {
        let initialize = app.buttons["videoInitialize"]
        XCTAssertTrue(initialize.waitForExistence(timeout: 8)); initialize.tap()
        let generate = app.buttons["videoGenerateStoryboard"]
        XCTAssertTrue(generate.waitForExistence(timeout: 8)); generate.tap()
        confirmStoryboardCost()
        waitForLabel(videoStatus(), containing: "执行中")
    }

    private func dismissVideoConfirmationPopover() {
        // iPad renders SwiftUI confirmationDialog as a popover; its cancel-role
        // button is absent from XCTest's accessibility tree, but the overlay
        // exposes a dedicated safe dismissal region.
        let dismissRegion = app.otherElements.matching(identifier: "PopoverDismissRegion").firstMatch
        XCTAssertTrue(dismissRegion.waitForExistence(timeout: 5), "the confirmation popover must be open before dismissing it")
        dismissRegion.tap()
    }

    private func confirmStoryboardCost() {
        let matches = app.buttons.matching(identifier: "videoConfirmStoryboardCost").allElementsBoundByIndex
        XCTAssertTrue(matches.first?.waitForExistence(timeout: 5) == true,
                      "storyboard submission must show its one-time material and provider-cost confirmation")
        let confirm = matches.last(where: { $0.isHittable }) ?? matches.first!
        confirm.tap()
    }

    private func tapRunningCancelConfirmation(retrying: Bool) {
        // SwiftUI exposes the confirmation action as a nested wrapper and leaf
        // button in XCTest's tree. Select the visible leaf by its exact label;
        // fail if the dialog is absent or the framework shape changes.
        let label = retrying ? "再次请求停止" : "请求停止"
        let matches = app.buttons.matching(identifier: "videoConfirmRunningCancel").allElementsBoundByIndex
        let candidates = matches.filter { $0.isHittable && $0.label == label }
        XCTAssertFalse(candidates.isEmpty, "the matching confirmation action must be visible and hittable")
        XCTAssertLessThanOrEqual(candidates.count, 2, "unexpected duplicate confirmation actions")
        candidates.last?.tap()
    }

    func testRevokedConnectionDisablesQueuedCancellation() throws {
        defer { attachVideoEvidence("video-revoked-queued-cancel-disabled") }
        launchVideoFixture(mode: "revoked-queued")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        waitForLabel(videoStatus(), containing: "排队中")
        let cancel = app.buttons["videoCancelQueued"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        let revoke = app.buttons["videoUITestRevokeConnection"]
        scrollUntilVisible(revoke)
        XCTAssertTrue(revoke.isHittable); revoke.tap()
        XCTAssertTrue(cancel.exists)
        XCTAssertFalse(cancel.isEnabled, "a stale identity must disable queued cancellation")
        XCTAssertTrue(app.staticTexts["videoConnectionUnavailable"].exists)
        waitForFixtureFields(["posts=1", "executions=1", "cancels=0", "attempts=0"])
    }

    func testRevokedConnectionDisablesSameKeyRetry() throws {
        defer { attachVideoEvidence("video-revoked-same-key-retry-disabled") }
        launchVideoFixture(mode: "revoked-uncertain")
        enterVideoDetail()
        app.buttons["videoInitialize"].tap()
        let retry = app.buttons["videoRetrySameKey"]
        XCTAssertTrue(retry.waitForExistence(timeout: 8))
        let revoke = app.buttons["videoUITestRevokeConnection"]
        scrollUntilVisible(revoke)
        XCTAssertTrue(revoke.isHittable); revoke.tap()
        XCTAssertTrue(retry.exists)
        XCTAssertFalse(retry.isEnabled, "an uncertain submission cannot be replayed until its original identity is restored")
        XCTAssertTrue(app.staticTexts["videoConnectionUnavailable"].exists)
        waitForFixtureFields(["posts=1", "executions=1", "cancels=0", "attempts=0"])
    }

    @discardableResult private func launchVideoFixture(mode: String) -> String {
        let id = UUID().uuidString.lowercased()
        videoFixtureID = id
        app.terminate()
        app = makeVideoFixtureApp(mode: mode, id: id)
        app.launch()
        return id
    }

    private func makeVideoFixtureApp(mode: String, id: String) -> XCUIApplication {
        let result = XCUIApplication()
        result.launchArguments = ["--uitesting", "--uitesting-agent-video", "--video-fixture-mode", mode,
                                  "--video-fixture-id", id]
        return result
    }

    private func enterVideoDetail() {
        let entry = app.buttons["agentVideoStoryboardEntry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 8))
        entry.tap()
        XCTAssertTrue(app.buttons["videoInitialize"].waitForExistence(timeout: 8)
            || app.buttons["videoRetrySameKey"].waitForExistence(timeout: 3)
            || app.buttons["videoReconcileOperation"].waitForExistence(timeout: 3)
            || app.buttons["videoProduce"].waitForExistence(timeout: 3))
    }

    private func enterReopenedRunningVideoDetail() {
        let entry = app.buttons["agentVideoStoryboardEntry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 8))
        entry.tap()
        waitForLabel(videoStatus(), containing: "执行中",
            message: "reopening must restore the existing running storyboard rather than show initialization")
        XCTAssertTrue(app.staticTexts["videoRunningCancelStatus"].waitForExistence(timeout: 8),
            "the previously persisted stop intent must be restored")
    }

    private func videoStatus() -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "videoOperationStatus").firstMatch
    }

    private func scrollUntilVisible(_ element: XCUIElement) {
        let viewport = app.frame
        for attempt in 0..<12 {
            if element.isHittable { return }
            guard element.exists else {
                if attempt.isMultiple(of: 2) { app.swipeUp() } else { app.swipeDown() }
                continue
            }
            let frame = element.frame
            if frame.maxY < viewport.minY + 80 {
                app.swipeDown()
            } else if frame.minY > viewport.maxY - 80 {
                app.swipeUp()
            } else if attempt.isMultiple(of: 2) {
                app.swipeUp()
            } else {
                app.swipeDown()
            }
        }
    }

    private func waitForLabel(_ element: XCUIElement, containing text: String,
                              message: String = "Expected accessibility label to contain the requested text",
                              timeout: TimeInterval = 8) {
        let expected = NSPredicate(format: "label CONTAINS %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: expected, object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed, message)
    }

    private func waitForFixtureFields(_ fields: [String],
                                      message: String = "Fixture counters did not reach the expected values",
                                      timeout: TimeInterval = 8) {
        let status = app.staticTexts["videoUITestFixtureStatus"]
        scrollUntilVisible(status)
        XCTAssertTrue(status.waitForExistence(timeout: 3))
        for field in fields {
            waitForLabel(status, containing: field, message: "\(message): missing \(field)", timeout: timeout)
        }
    }

    private func attachVideoEvidence(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name + "-screenshot"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = name + "-accessibility"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }

    func testCreateAddPageReturnReopenAndPersist() throws {
        let newNote = app.buttons["newNoteButton"]
        XCTAssertTrue(newNote.waitForExistence(timeout: 5))
        newNote.tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText(noteTitle)
        app.buttons["createNoteConfirm"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch.waitForExistence(timeout: 5))
        let pencilOnly = app.descendants(matching: .any).matching(identifier: "pencilOnlyToggle").firstMatch
        if pencilOnly.value as? String == "1" { pencilOnly.tap() }
        let canvas = app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.2))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(app.buttons["undoButton"].isEnabled)
        app.buttons["insertTextButton"].tap()
        let canvasForText = app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch
        canvasForText.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.3)).tap()
        let source = app.textViews["inlineTextSource"]
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        source.tap()
        source.typeText("E = mc^2\n\nPersistence check: a + b = c")
        XCTAssertTrue(app.buttons["finishInlineText"].waitForExistence(timeout: 3))
        app.buttons["finishInlineText"].tap()
        XCTAssertFalse(source.exists)
        app.buttons["addPageButton"].tap()
        app.buttons["backToShelf"].tap()
        XCTAssertTrue(app.buttons["note-\(noteTitle)"].waitForExistence(timeout: 5))
        app.buttons["note-\(noteTitle)"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch.waitForExistence(timeout: 5))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["note-\(noteTitle)"].waitForExistence(timeout: 5))
    }

    func testPageManagerContextMenuCopyDeleteAndUndo() throws {
        let newNote = app.buttons["newNoteButton"]
        XCTAssertTrue(newNote.waitForExistence(timeout: 5))
        newNote.tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap(); title.typeText("Page UI " + String(UUID().uuidString.prefix(5)))
        app.buttons["createNoteConfirm"].tap()
        XCTAssertTrue(app.buttons["pageManagerButton"].waitForExistence(timeout: 5))
        app.buttons["addPageButton"].tap()
        app.buttons["pageManagerButton"].tap()
        let secondPage = app.buttons["第 2 页"]
        XCTAssertTrue(secondPage.waitForExistence(timeout: 5))
        secondPage.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["删除页面"].waitForExistence(timeout: 3))
        app.buttons["删除页面"].tap()
        app.buttons["完成"].tap()
        XCTAssertTrue(app.buttons["undoButton"].isEnabled)
        app.buttons["undoButton"].tap()
        app.buttons["pageManagerButton"].tap()
        XCTAssertTrue(app.buttons["第 2 页"].waitForExistence(timeout: 5))
    }

    func testFloatingAIKeepsDraftWhenMinimized() throws {
        XCTAssertTrue(app.buttons["newNoteButton"].waitForExistence(timeout: 5))
        app.buttons["newNoteButton"].tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap(); title.typeText("AI UI " + String(UUID().uuidString.prefix(5)))
        app.buttons["createNoteConfirm"].tap()
        XCTAssertTrue(app.buttons["AI 助手"].waitForExistence(timeout: 5))
        app.buttons["AI 助手"].tap()
        let question = app.textViews["给 AI 的问题"]
        XCTAssertTrue(question.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["backToShelf"].exists, "Floating card must preserve the editor navigation")
        question.tap(); question.typeText("Draft remains local")
        app.buttons["最小化 AI"].tap()
        XCTAssertTrue(app.buttons["展开 AI"].waitForExistence(timeout: 3))
        app.buttons["展开 AI"].tap()
        XCTAssertEqual(question.value as? String, "Draft remains local")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Floating AI draft and paper"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["关闭 AI"].tap()
        XCTAssertFalse(app.buttons["最小化 AI"].exists)
        XCTAssertTrue(app.buttons["backToShelf"].exists)
    }

    func testRenderFailureCanBeEditedWithoutDuplicatingFlowAndPersistsAfterReopen() throws {
        let titleValue = "Render UI " + String(UUID().uuidString.prefix(6))
        XCTAssertTrue(app.buttons["newNoteButton"].waitForExistence(timeout: 5))
        app.buttons["newNoteButton"].tap()
        let title = app.textFields["noteTitleField"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap(); title.typeText(titleValue)
        app.buttons["createNoteConfirm"].tap()
        let canvas = app.descendants(matching: .any).matching(identifier: "noteCanvas").firstMatch
        XCTAssertTrue(canvas.waitForExistence(timeout: 5))

        app.buttons["insertTextButton"].tap()
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.25)).tap()
        let inlineSource = app.textViews["inlineTextSource"]
        XCTAssertTrue(inlineSource.waitForExistence(timeout: 5))
        inlineSource.tap(); inlineSource.typeText("\\frac{")
        app.buttons["finishInlineText"].tap()

        let failureEntry = app.buttons["renderFailureEntry"]
        XCTAssertTrue(failureEntry.waitForExistence(timeout: 15), "invalid LaTeX must expose the paper recovery entry")
        let failureShot = XCTAttachment(screenshot: app.screenshot())
        failureShot.name = "Actual paper render failure entry"
        failureShot.lifetime = .keepAlways
        add(failureShot)
        failureEntry.tap()

        let rows = app.buttons.matching(identifier: "textFlowEditButton")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows.count, 1)
        let edit = app.buttons["renderFailureEditSource"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let editor = app.textViews["textSourceEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 1.25),
                       "opening the source editor must not also trigger the retry-error alert")
        XCTAssertTrue(editor.exists, "the source editor must remain presented after the edit action settles")
        XCTAssertEqual(editor.value as? String, "\\frac{")
        replaceText(in: editor, with: "x^2+y^2=z^2")
        app.buttons["saveTextButton"].tap()

        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: failureEntry)
        wait(for: [gone], timeout: 15)
        openTextManager()
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows.count, 1, "repair must update the existing flow rather than insert a second object")
        let repairedSource = app.descendants(matching: .any).matching(identifier: "textFlowSource").firstMatch
        XCTAssertTrue(repairedSource.waitForExistence(timeout: 5))
        XCTAssertEqual(repairedSource.label, "x^2+y^2=z^2")
        let repairedShot = XCTAttachment(screenshot: app.screenshot())
        repairedShot.name = "Repaired source with one text flow"
        repairedShot.lifetime = .keepAlways
        add(repairedShot)
        app.navigationBars["页面文字"].buttons["完成"].tap()

        let saveStatus = app.descendants(matching: .any).matching(identifier: "saveStatus").firstMatch
        let saved = expectation(for: NSPredicate(format: "label BEGINSWITH '已保存'"), evaluatedWith: saveStatus)
        wait(for: [saved], timeout: 10)
        app.buttons["backToShelf"].tap()
        let note = app.buttons["note-\(titleValue)"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        note.tap()
        XCTAssertTrue(canvas.waitForExistence(timeout: 5))
        XCTAssertFalse(failureEntry.waitForExistence(timeout: 2))
        openTextManager()
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "textFlowSource").firstMatch.label,
                       "x^2+y^2=z^2", "the repaired source must survive closing and reopening the note")
        let reopenShot = XCTAttachment(screenshot: app.screenshot())
        reopenShot.name = "Reopened note keeps repaired render source"
        reopenShot.lifetime = .keepAlways
        add(reopenShot)
    }

    func testAgentFollowupComposePersistsChildAndNavigatesHistory() throws {
        launchAgentFollowupFixture()
        let parentRow = app.buttons["agentTaskRow-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]
        XCTAssertTrue(parentRow.waitForExistence(timeout: 5))
        parentRow.tap()

        let parentForm = topmostTaskForm()
        XCTAssertTrue(parentForm.waitForExistence(timeout: 5))
        let continueButton = parentForm.buttons["agentTaskContinueFollowup"]
        let completedOutput = parentForm.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "父轮结果：已总结材料。")).firstMatch
        if !completedOutput.waitForExistence(timeout: 12) {
            attachUITestDiagnostics("followup-parent-not-completed")
            XCTFail("the completed fixture parent must display its saved result before metadata refresh")
            return
        }
        let refreshTaskMetadata = parentForm.buttons["agentTaskRefreshStatus"]
        scrollUntilHittable(refreshTaskMetadata, in: parentForm, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(refreshTaskMetadata.isHittable,
                      "a terminal Bridge task without session metadata must expose an explicit refresh action")
        XCTAssertEqual(refreshTaskMetadata.label, "刷新任务信息")
        refreshTaskMetadata.tap()
        scrollUntilHittable(continueButton, in: parentForm, direction: .towardBottom, maxSwipes: 8)
        if !continueButton.isHittable {
            attachUITestDiagnostics("followup-parent-no-continue")
            XCTFail("an explicit terminal metadata refresh should make the eligible follow-up available")
            return
        }
        continueButton.tap()
        let input = app.textViews["agentTaskFollowupInput"]
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap(); input.typeText("Explain the second step")
        app.buttons["agentTaskSendFollowup"].tap()

        let childResult = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "追问结果：按原材料说明了原因。")
        ).firstMatch
        XCTAssertTrue(childResult.waitForExistence(timeout: 12),
                      "the child result, not the still-visible parent result, must appear after submission")
        let childForm = topmostTaskForm()
        XCTAssertTrue(childForm.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "追问结果：按原材料说明了原因。")
        ).firstMatch.exists, "the foreground detail must be the submitted child round")
        let childInputDisclosure = childForm.buttons["agentTaskInputDisclosure"]
        scrollUntilHittable(childInputDisclosure, in: childForm, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(childInputDisclosure.waitForExistence(timeout: 5), "the original input remains available below the result actions")
        XCTAssertTrue(childInputDisclosure.isHittable)
        childInputDisclosure.tap()
        let childInputText = childForm.staticTexts["Explain the second step"]
        scrollUntilHittable(childInputText, in: childForm, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(childInputText.waitForExistence(timeout: 5),
            "opening the input disclosure must show the exact saved follow-up input")
        let childOutput = childForm.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "追问结果：按原材料说明了原因。" )).firstMatch
        scrollUntilHittable(childOutput, in: childForm, direction: .towardTop, maxSwipes: 8)
        XCTAssertTrue(childOutput.waitForExistence(timeout: 8), "the local fixture response should be shown in the child round")
        let fixtureSnapshot = childForm.staticTexts["fixtureSnapshot"]
        scrollUntilHittable(fixtureSnapshot, in: childForm, direction: .towardBottom, maxSwipes: 8)
        let childSnapshot = fixtureFields(fixtureSnapshot.label)
        let childClientTaskID = childSnapshot["client"] ?? ""
        XCTAssertFalse(childClientTaskID.isEmpty)
        XCTAssertEqual(childSnapshot["key"], childClientTaskID, "the child task ID must also be its idempotency key")
        XCTAssertEqual(childSnapshot["parent"], "bridge-parent-fixture")
        XCTAssertGreaterThan(Int(childSnapshot["bytes"] ?? "0") ?? 0, 0)

        let parentHistoryLink = childForm.buttons["agentTaskHistoryRound-1"]
        scrollUntilHittable(parentHistoryLink, in: childForm, direction: .towardTop, maxSwipes: 8)
        XCTAssertTrue(parentHistoryLink.waitForExistence(timeout: 5))
        parentHistoryLink.tap()
        guard let historicalParentForm = waitForTopmostTaskForm(
            containingResult: "父轮结果：已总结材料。", timeout: 12
        ) else { return }
        let parentInputDisclosure = historicalParentForm.buttons["agentTaskInputDisclosure"]
        scrollUntilHittable(parentInputDisclosure, in: historicalParentForm, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(parentInputDisclosure.waitForExistence(timeout: 5))
        XCTAssertTrue(parentInputDisclosure.isHittable)
        parentInputDisclosure.tap()
        let parentInputText = historicalParentForm.staticTexts["请总结选中的材料"]
        scrollUntilHittable(parentInputText, in: historicalParentForm, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(parentInputText.waitForExistence(timeout: 5))
        let childHistoryLink = historicalParentForm.buttons["agentTaskChildRoundLink"]
        scrollUntilHittable(childHistoryLink, in: historicalParentForm, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(childHistoryLink.waitForExistence(timeout: 5))
        childHistoryLink.tap()
        guard let reopenedChildForm = waitForTopmostTaskForm(
            containingResult: "追问结果：按原材料说明了原因。", timeout: 12
        ) else { return }
        let reopenedChildInputDisclosure = reopenedChildForm.buttons["agentTaskInputDisclosure"]
        scrollUntilHittable(reopenedChildInputDisclosure, in: reopenedChildForm,
                            direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(reopenedChildInputDisclosure.waitForExistence(timeout: 5))
        XCTAssertTrue(reopenedChildInputDisclosure.isHittable)
        reopenedChildInputDisclosure.tap()
        let reopenedChildInput = reopenedChildForm.staticTexts["Explain the second step"]
        scrollUntilHittable(reopenedChildInput, in: reopenedChildForm,
                            direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(reopenedChildInput.waitForExistence(timeout: 5),
                      "reopening the child round must show the exact saved follow-up input")
        let reopenedChildOutput = reopenedChildForm.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "追问结果：按原材料说明了原因。" )).firstMatch
        scrollUntilHittable(reopenedChildOutput, in: reopenedChildForm, direction: .towardTop, maxSwipes: 8)
        XCTAssertTrue(reopenedChildOutput.waitForExistence(timeout: 5), "the saved child must reopen from its parent history")
    }

    func testMissingDeliveryOffersConfirmedFollowupWithExplicitOutputSelection() throws {
        launchAgentFollowupFixture()
        let row = app.buttons["agentTaskRow-cccccccc-cccc-4ccc-8ccc-cccccccccccc"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        var form = topmostTaskForm()
        let missingNotice = form.staticTexts["agentDeliveryMissingNotice"]
        XCTAssertTrue(missingNotice.waitForExistence(timeout: 5))
        XCTAssertTrue(form.staticTexts["agentDeliveryMissingKinds"].label.contains("图片"))
        let repair = form.buttons["agentDeliveryRepair"]
        scrollUntilHittable(repair, in: form, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(repair.isHittable)
        repair.tap()

        let imageSelection = app.switches["agentFollowupExpectedOutput-image"]
        XCTAssertTrue(imageSelection.waitForExistence(timeout: 5))
        XCTAssertEqual(imageSelection.value as? String, "1", "repair follow-up must preselect the missing image expectation")
        let composeForm = app.descendants(matching: .any).matching(identifier: "agentTaskFollowupComposeForm").firstMatch
        XCTAssertTrue(composeForm.waitForExistence(timeout: 5), "the follow-up form must contain the input and delivery choices")
        for kind in ["file", "pdf", "video", "audio"] {
            let selection = app.switches["agentFollowupExpectedOutput-\(kind)"]
            scrollUntilHittable(selection, in: composeForm, direction: .towardBottom, maxSwipes: 8)
            XCTAssertTrue(selection.isHittable, "the \(kind) choice must be reachable in the follow-up form")
            XCTAssertEqual(selection.value as? String, "0", "repair follows only the missing image kind; \(kind) stays off")
        }
        let sendFollowup = app.buttons["agentTaskSendFollowup"]
        XCTAssertTrue(sendFollowup.waitForExistence(timeout: 5))
        XCTAssertTrue(sendFollowup.isHittable, "the explicit send action must remain available after reviewing all choices")
        sendFollowup.tap()

        form = topmostTaskForm()
        XCTAssertTrue(form.staticTexts["agentDeliveryMissingNotice"].waitForExistence(timeout: 8),
            "a returned text answer must not be presented as a delivered image")
        XCTAssertTrue(form.staticTexts["agentDeliveryMissingKinds"].label.contains("图片"))
        let childLink = form.buttons["agentTaskHistoryRound-2"]
        scrollUntilHittable(childLink, in: form, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(childLink.waitForExistence(timeout: 5), "repair must create a separately saved child round")
        XCTAssertTrue(childLink.isHittable, "the saved repair round must remain reachable from history")
    }

    func testBridgeSubmittingRunWithTaskIDCanRetrySameRequest() throws {
        launchAgentFollowupFixture()
        let pendingRow = app.buttons["agentTaskRow-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]
        XCTAssertTrue(pendingRow.waitForExistence(timeout: 5))
        pendingRow.tap()

        var form = topmostTaskForm()
        XCTAssertTrue(form.waitForExistence(timeout: 5))
        var retry = form.buttons["agentTaskRetrySubmission"]
        scrollUntilHittable(retry, in: form, direction: .towardBottom, maxSwipes: 8)
        if !retry.isHittable {
            attachUITestDiagnostics("bridge-submitting-no-retry")
            XCTFail("Bridge must allow POST retry even when a prior response supplied task_id")
            return
        }
        retry.tap()
        var snapshot = form.staticTexts["fixtureSnapshot"]
        scrollUntilHittable(snapshot, in: form, direction: .towardBottom, maxSwipes: 8)
        let firstAttempt = expectation(for: NSPredicate(format: "label CONTAINS %@", "retry=1;"), evaluatedWith: snapshot)
        wait(for: [firstAttempt], timeout: 5)
        form = topmostTaskForm()
        retry = form.buttons["agentTaskRetrySubmission"]
        scrollUntilHittable(retry, in: form, direction: .towardTop, maxSwipes: 8)
        XCTAssertTrue(retry.isHittable)
        retry.tap()
        snapshot = form.staticTexts["fixtureSnapshot"]
        scrollUntilHittable(snapshot, in: form, direction: .towardBottom, maxSwipes: 8)
        let secondAttempt = expectation(for: NSPredicate(format: "label CONTAINS %@", "retry=2;"), evaluatedWith: snapshot)
        wait(for: [secondAttempt], timeout: 5)
        let fields = fixtureFields(snapshot.label)
        XCTAssertEqual(fields["key"], "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        XCTAssertEqual(fields["stable"], "1", "the replayed Bridge request body must be byte-identical")
        let runningStatus = form.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "运行中")).firstMatch
        scrollUntilHittable(runningStatus, in: form, direction: .towardTop, maxSwipes: 8)
        XCTAssertTrue(runningStatus.waitForExistence(timeout: 5))
    }

    private func launchAgentFollowupFixture() {
        app.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-agent-followup"]
        app.launch()
    }

    private enum FormScrollDirection { case towardTop, towardBottom }

    private func topmostTaskForm() -> XCUIElement {
        let forms = app.descendants(matching: .any).matching(identifier: "agentTaskDetailForm")
        guard forms.firstMatch.waitForExistence(timeout: 5), forms.count > 0 else {
            XCTFail("Task detail form must be present")
            return app.otherElements.firstMatch
        }
        return forms.element(boundBy: forms.count - 1)
    }

    private func waitForTopmostTaskForm(containingResult resultText: String,
                                        timeout: TimeInterval) -> XCUIElement? {
        let forms = app.descendants(matching: .any).matching(identifier: "agentTaskDetailForm")
        let resultPredicate = NSPredicate(format: "label CONTAINS %@", resultText)
        let foregroundResult = NSPredicate { _, _ in
            guard forms.count > 0 else { return false }
            let candidate = forms.element(boundBy: forms.count - 1)
            return candidate.staticTexts.containing(resultPredicate).firstMatch.isHittable
        }
        let expectation = XCTNSPredicateExpectation(predicate: foregroundResult, object: app)
        guard XCTWaiter.wait(for: [expectation], timeout: timeout) == .completed else {
            attachUITestDiagnostics("followup-round-transition-\(resultText.hashValue)")
            XCTFail("the foreground task detail must show its own expected result before interacting with its input")
            return nil
        }
        return topmostTaskForm()
    }

    private func scrollUntilHittable(_ element: XCUIElement, in form: XCUIElement, direction: FormScrollDirection, maxSwipes: Int) {
        for _ in 0..<maxSwipes where !element.isHittable {
            switch direction {
            case .towardTop: form.swipeDown()
            case .towardBottom: form.swipeUp()
            }
        }
    }

    private func fixtureFields(_ label: String) -> [String: String] {
        Dictionary(uniqueKeysWithValues: label.split(separator: ";").compactMap { field in
            let parts = field.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { return nil }
            return (parts[0], parts[1])
        })
    }

    private func attachUITestDiagnostics(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "\(name)-screen"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func openTextManager() {
        let more = app.buttons["moreActionsButton"]
        XCTAssertTrue(more.waitForExistence(timeout: 5))
        more.tap()
        let manage = app.buttons["管理文字"]
        XCTAssertTrue(manage.waitForExistence(timeout: 3))
        manage.tap()
    }

    private func replaceText(in element: XCUIElement, with value: String) {
        for _ in 0..<3 {
            if element.value as? String == value { return }
            element.tap()
            element.typeKey("a", modifierFlags: .command)
            element.typeText(value)
        }
        XCTAssertEqual(element.value as? String, value, "keyboard replacement must be confirmed before saving")
    }

    func testLibraryBackupEntryAndFilePickerCanBeCancelled() throws {
        defer { attachLibraryBackupScreenshot("entry-picker-cancel") }
        launchLibraryBackupFixture(mode: "picker")
        let entry = app.buttons["bookshelfBackupEntry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 8))
        entry.tap()
        let importButton = app.buttons["libraryBackupImport"]
        XCTAssertTrue(importButton.waitForExistence(timeout: 5))
        importButton.tap()
        let pickerCancel = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Cancel", "取消")).firstMatch
        XCTAssertTrue(pickerCancel.waitForExistence(timeout: 8), "the platform document picker should open for ZIP selection")
        pickerCancel.tap()
        XCTAssertTrue(importButton.waitForExistence(timeout: 5), "canceling selection must return to the backup page")
        XCTAssertFalse(app.buttons["libraryBackupRestore"].exists)
    }

    func testLibraryBackupPreflightCancellationLeavesNoPreviewOrNewNote() throws {
        defer { attachLibraryBackupScreenshot("preflight-cancel") }
        launchLibraryBackupFixture(mode: "cancel")
        enterLibraryBackupFixturePage()
        let load = app.buttons["libraryBackupLoadUITestFixture"]
        XCTAssertTrue(load.waitForExistence(timeout: 10), fixtureErrorMessage())
        load.tap()
        let waiting = app.buttons["libraryBackupCancel"]
        XCTAssertTrue(waiting.waitForExistence(timeout: 10), "real ZIP inspection must reach the bounded cancellable test waitpoint")
        let waitpoint = app.descendants(matching: .any)["libraryBackupUITestWaitpoint"]
        XCTAssertTrue(waitpoint.waitForExistence(timeout: 3))
        scrollBackupControl(waiting)
        XCTAssertTrue(waiting.isHittable)
        waiting.tap()
        let alert = app.alerts["备份操作未完成"]
        XCTAssertTrue(alert.waitForExistence(timeout: 8))
        XCTAssertTrue(alert.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已取消备份操作")).firstMatch.exists)
        alert.buttons["好"].tap()
        XCTAssertFalse(app.buttons["libraryBackupRestore"].exists, "canceled preflight must not publish a preview")
        XCTAssertEqual(app.staticTexts["libraryBackupUITestNoteCount"].label, "当前笔记数：0")
    }

    func testLibraryBackupCanContinueRealIncompleteRestore() throws {
        defer { attachLibraryBackupScreenshot("continue-restore") }
        launchLibraryBackupFixture(mode: "resume")
        enterLibraryBackupFixturePage()
        let load = app.buttons["libraryBackupLoadUITestFixture"]
        XCTAssertTrue(load.waitForExistence(timeout: 10), fixtureErrorMessage())
        load.tap()
        XCTAssertTrue(app.staticTexts["libraryBackupRecoverySummary"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["libraryBackupRecoverySummary"].label.contains("待处理 1 组"))
        let proceed = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "libraryBackupContinue-")).firstMatch
        XCTAssertTrue(proceed.waitForExistence(timeout: 5))
        scrollBackupControl(proceed)
        proceed.tap()
        XCTAssertTrue(app.staticTexts["libraryBackupUITestActionResult"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["libraryBackupUITestActionResult"].label, "测试结果：continued")
        XCTAssertEqual(app.staticTexts["libraryBackupUITestNoteCount"].label, "当前笔记数：1")
        XCTAssertTrue(app.staticTexts["合成恢复笔记"].exists)
    }

    func testLibraryBackupCleanupOnlyRemovesIncompleteRestore() throws {
        defer { attachLibraryBackupScreenshot("cleanup-incomplete") }
        launchLibraryBackupFixture(mode: "cleanup")
        enterLibraryBackupFixturePage()
        let load = app.buttons["libraryBackupLoadUITestFixture"]
        XCTAssertTrue(load.waitForExistence(timeout: 10), fixtureErrorMessage())
        load.tap()
        XCTAssertTrue(app.staticTexts["libraryBackupRecoverySummary"].waitForExistence(timeout: 10))
        let cleanup = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "libraryBackupCleanup-")).firstMatch
        XCTAssertTrue(cleanup.waitForExistence(timeout: 5))
        scrollBackupControl(cleanup)
        cleanup.tap()
        let cleanupSheet = app.sheets["清理未完成恢复？"]
        XCTAssertTrue(cleanupSheet.waitForExistence(timeout: 5))
        let cleanupActions = cleanupSheet.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@",
            "libraryBackupConfirmCleanup", "清理未完成恢复"))
        XCTAssertEqual(cleanupActions.count, 2, "SwiftUI exposes a containing button and its actionable child in the confirmation sheet")
        let confirm = cleanupActions.element(boundBy: 1)
        XCTAssertTrue(confirm.isHittable)
        XCTAssertTrue(confirm.isEnabled)
        confirm.tap()
        XCTAssertTrue(app.staticTexts["libraryBackupUITestActionResult"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["libraryBackupUITestActionResult"].label, "测试结果：cleaned")
        XCTAssertEqual(app.staticTexts["libraryBackupUITestNoteCount"].label, "当前笔记数：0", "cleanup must leave source library unchanged")
        let summary = app.staticTexts["libraryBackupRecoverySummary"]
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: summary)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed, "rolled-back recovery must disappear after journal refresh")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "libraryBackupContinue-")).firstMatch.exists)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "libraryBackupCleanup-")).firstMatch.exists)
    }

    private func attachLibraryBackupScreenshot(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "library-backup-\(name)"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func launchLibraryBackupFixture(mode: String) {
        app.terminate()
        app = XCUIApplication()
        let id = UUID().uuidString.lowercased()
        app.launchArguments = ["--uitesting", "--uitesting-library-backup", "--library-backup-fixture-id", id,
                              "--library-backup-fixture-mode", mode]
        app.launch()
    }

    private func enterLibraryBackupFixturePage() {
        let entry = app.buttons["bookshelfBackupEntry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 8))
        entry.tap()
    }

    private func fixtureErrorMessage() -> String {
        let error = app.staticTexts["libraryBackupUITestFixtureError"]
        return error.exists ? error.label : ""
    }

    private func scrollBackupControl(_ element: XCUIElement) {
        for _ in 0..<6 where !element.isHittable { app.swipeUp() }
    }
}

extension PadNoteUITests {
    func testContentOutlineOpensAnchoredFlowCopiesResetsAndCancelsShare() {
        app.terminate()
        app = XCUIApplication()
        let fixtureID = UUID().uuidString.lowercased()
        contentOutlineFixtureID = fixtureID
        app.launchArguments = ["--uitesting", "--uitesting-content-outline", "--content-outline-fixture-id", fixtureID]
        app.launch()

        let more = app.buttons["moreActionsButton"]
        XCTAssertTrue(more.waitForExistence(timeout: 8))
        more.tap()
        let outlineEntry = app.buttons["contentOutlineEntry"]
        XCTAssertTrue(outlineEntry.waitForExistence(timeout: 5))
        outlineEntry.tap()

        let flowID = "outline-flow-\(fixtureID)"
        let flow = app.buttons["contentOutlineFlow-\(flowID)-page-1"]
        XCTAssertTrue(flow.waitForExistence(timeout: 5))
        let outlineScreenshot = XCTAttachment(screenshot: app.screenshot())
        outlineScreenshot.name = "content-outline-open-on-page-two"
        outlineScreenshot.lifetime = .keepAlways
        add(outlineScreenshot)
        flow.tap()
        let editor = app.textViews["textSourceEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.value as? String, "第二页的合成测试正文")
        replaceText(in: editor, with: "Edited second-page content")
        app.buttons["saveTextButton"].tap()
        XCTAssertTrue(app.buttons["pageManagerButton"].label.contains("第 2 / 2 页"))

        more.tap()
        app.buttons["contentOutlineEntry"].tap()
        let editedFlow = app.buttons["contentOutlineFlow-\(flowID)-page-1"]
        XCTAssertTrue(editedFlow.waitForExistence(timeout: 5))
        XCTAssertTrue(editedFlow.label.contains("Edited second-page content"), "the saved source must appear after reopening the outline")
        editedFlow.tap()
        let reopenedEditor = app.textViews["textSourceEditor"]
        XCTAssertTrue(reopenedEditor.waitForExistence(timeout: 5))
        XCTAssertEqual(reopenedEditor.value as? String, "Edited second-page content")
        app.buttons["saveTextButton"].tap()
        XCTAssertTrue(app.buttons["pageManagerButton"].label.contains("第 2 / 2 页"),
                      "reopening and saving the flow must keep the two-page note on page two")

        more.tap()
        app.buttons["contentOutlineEntry"].tap()
        app.buttons["contentOutlineCopy"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["contentOutlineCopyAcknowledgement"].waitForExistence(timeout: 3))
        app.buttons["contentOutlineDone"].tap()

        more.tap()
        app.buttons["contentOutlineEntry"].tap()
        XCTAssertFalse(app.descendants(matching: .any)["contentOutlineCopyAcknowledgement"].exists,
                       "opening the outline again must clear the previous copy acknowledgement")
        app.buttons["contentOutlineShare"].tap()
        let activitySheet = app.navigationBars["UIActivityContentView"]
        let closeShare = activitySheet.buttons["header.closeButton"]
        XCTAssertTrue(closeShare.waitForExistence(timeout: 5), "Markdown share should present the native activity sheet")
        let shareScreenshot = XCTAttachment(screenshot: app.screenshot())
        shareScreenshot.name = "content-outline-native-share-sheet"
        shareScreenshot.lifetime = .keepAlways
        add(shareScreenshot)
        closeShare.tap()
        XCTAssertTrue(app.buttons["moreActionsButton"].waitForExistence(timeout: 5), "cancel returns to the note editor")
        let canceledShareScreenshot = XCTAttachment(screenshot: app.screenshot())
        canceledShareScreenshot.name = "content-outline-editor-after-share-cancel"
        canceledShareScreenshot.lifetime = .keepAlways
        add(canceledShareScreenshot)

        launchContentOutlineMaintenance("inspect", fixtureID: fixtureID)
        let base64Element = app.staticTexts["contentOutlineFixtureMarkdownBase64"]
        let hasExport = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label.length > 0"), object: base64Element)
        XCTAssertEqual(XCTWaiter.wait(for: [hasExport], timeout: 5), .completed, "inspect reads the exact markdown file saved by ShareSheet")
        guard let markdownBytes = Data(base64Encoded: base64Element.label) else {
            XCTFail("the owned Markdown export should be readable and UTF-8 encoded")
            return
        }
        XCTAssertTrue(String(data: markdownBytes, encoding: .utf8)?.contains("Edited second-page content") == true)
        let markdownAttachment = XCTAttachment(data: markdownBytes, uniformTypeIdentifier: "public.plain-text")
        markdownAttachment.name = "content-outline-shared-markdown.md"
        markdownAttachment.lifetime = .keepAlways
        add(markdownAttachment)

        launchContentOutlineMaintenance("cleanup", fixtureID: fixtureID)
        app.buttons["contentOutlineFixtureCleanup"].tap()
        let cleaned = app.staticTexts["contentOutlineFixtureCleanupResult"]
        XCTAssertTrue(cleaned.waitForExistence(timeout: 3))
        XCTAssertEqual(cleaned.label, "fixture-cleaned; root-exists=false; suite-keys=0")
        app.terminate()
        contentOutlineFixtureID = nil
    }

    private func launchContentOutlineMaintenance(_ mode: String, fixtureID: String) {
        app.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-content-outline", "--content-outline-fixture-id", fixtureID,
                              "--uitesting-content-outline-\(mode)"]
        app.launch()
    }
}

final class CredentialClearUITests: XCTestCase {
    private var fixtureID: String!
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        fixtureID = UUID().uuidString.lowercased()
        app = XCUIApplication()
        launchFixture()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        guard let fixtureID else { return }
        app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-credential-clear",
                              "--credential-clear-fixture-id", fixtureID,
                              "--credential-clear-fixture-cleanup"]
        app.launch()
        let result = app.staticTexts["credentialClearCleanupResult"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        XCTAssertEqual(result.label, "fixture-cleaned; root-exists=false; suite-keys=0")
        app.terminate()
        self.fixtureID = nil
    }

    func testCancelConfirmationPreservesSyntheticCredentialsAndMetadata() {
        let before = snapshot()
        XCTAssertTrue(before.contains("state=ready"))
        XCTAssertTrue(before.contains("Fixture AI Profile|https://fixture.invalid/v1|fixture-vision-model|fixture-text-model|disabled=false|readable=true|raw=present"))
        XCTAssertTrue(before.contains("Fixture Agent Profile|https://credential-clear-fixture.invalid/v1|disabled=false|readable=true|raw=present"))

        app.buttons["credentialClearAISettings"].tap()
        let clear = app.buttons["清除本机连接凭据"]
        scrollUntilVisible(clear)
        clear.tap()
        let confirmation = app.alerts["清除本机连接凭据？"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        let cancel = confirmation.buttons.matching(identifier: "credentialClearCancel").firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        XCTAssertTrue(cancel.isHittable, "the cancel action in the visible confirmation alert must be tappable")
        cancel.tap()
        app.buttons["完成"].tap()

        refreshFixtureSnapshot()
        let after = snapshot()
        XCTAssertEqual(after, before, "cancel must leave the fixture's raw tokens, metadata, and lifecycle state unchanged")
        attachScreenshot("credential-clear-cancel")
    }

    func testConfirmDisablesOldReadsRetainsMetadataAndReconnects() {
        app.buttons["credentialClearAISettings"].tap()
        let clear = app.buttons["清除本机连接凭据"]
        scrollUntilVisible(clear)
        clear.tap()
        let confirmation = app.alerts["清除本机连接凭据？"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        let confirm = confirmation.buttons.matching(identifier: "credentialClearConfirm").firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertTrue(confirm.isHittable, "the destructive action in the visible confirmation alert must be tappable")
        confirm.tap()

        let reconnect = app.staticTexts["credentialClearReconnectNeeded"]
        XCTAssertTrue(reconnect.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["credentialClearSaveProfile"].isEnabled,
                      "the user must be able to enter replacement credentials")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已停用")).firstMatch.waitForExistence(timeout: 5))
        attachScreenshot("credential-clear-confirmed-ai")
        app.buttons["完成"].tap()
        refreshFixtureSnapshot()

        let cleared = app.staticTexts["credentialClearFixtureSnapshot"]
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@",
            "state=disabledAwaitingNextProcess", "readable=false"), object: cleared)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 5), .completed)
        XCTAssertTrue(cleared.label.contains("Fixture AI Profile|https://fixture.invalid/v1|fixture-vision-model|fixture-text-model|disabled=true|readable=false|raw=present"))
        XCTAssertTrue(cleared.label.contains("Fixture Agent Profile|https://credential-clear-fixture.invalid/v1|disabled=true|readable=false|raw=present"))

        app.buttons["credentialClearAgentSettings"].tap()
        XCTAssertTrue(app.staticTexts["需重新连接"].waitForExistence(timeout: 5))
        let probe = app.buttons["测试连接"]
        XCTAssertTrue(probe.waitForExistence(timeout: 5))
        XCTAssertTrue(probe.isEnabled, "the connection action remains available to show the re-entry requirement")
        probe.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "已在本机停用")).firstMatch.waitForExistence(timeout: 5),
                      "the old token must be rejected before any network request")
        attachScreenshot("credential-clear-confirmed-agent")
        app.buttons["完成"].tap()

        let simulate = app.buttons["credentialClearSimulateNextProcess"]
        scrollUntilVisible(simulate)
        simulate.tap()
        refreshFixtureSnapshot()
        let postRestart = app.staticTexts["credentialClearFixtureSnapshot"]
        let cleaned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@",
            "next-process=completeKnownReferences", "raw=absent"), object: postRestart)
        XCTAssertEqual(XCTWaiter.wait(for: [cleaned], timeout: 5), .completed,
                       "a fresh runtime generation should complete pending deletion; this is a coordinator simulation, not an OS restart")
        XCTAssertTrue(postRestart.label.contains("Fixture AI Profile|https://fixture.invalid/v1|fixture-vision-model|fixture-text-model|disabled=true|readable=false|raw=absent"))
        XCTAssertTrue(postRestart.label.contains("Fixture Agent Profile|https://credential-clear-fixture.invalid/v1|disabled=true|readable=false|raw=absent"))
        attachScreenshot("credential-clear-next-process-simulation")
    }

    private func launchFixture() {
        app.launchArguments = ["--uitesting", "--uitesting-credential-clear",
                              "--credential-clear-fixture-id", fixtureID]
        app.launch()
        XCTAssertTrue(app.buttons["credentialClearAISettings"].waitForExistence(timeout: 8))
    }

    private func snapshot() -> String {
        let item = app.staticTexts["credentialClearFixtureSnapshot"]
        XCTAssertTrue(item.waitForExistence(timeout: 5))
        return item.label
    }

    private func scrollUntilVisible(_ element: XCUIElement) {
        var attempts = 0
        while !element.isHittable && attempts < 8 {
            app.swipeUp()
            attempts += 1
        }
        XCTAssertTrue(element.isHittable)
    }

    private func refreshFixtureSnapshot() {
        let refresh = app.buttons["credentialClearRefreshFixtureSnapshot"]
        scrollUntilVisible(refresh)
        refresh.tap()
    }

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

final class AgentSettingsLayoutUITests: XCTestCase {
    private var fixtureID = UUID().uuidString.lowercased()
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting-agent-settings-layout", "--agent-settings-layout-fixture-id", fixtureID,
                              "--agent-settings-layout-cleanup"]
        app.launch()
        let result = app.staticTexts["agentSettingsLayoutCleanupResult"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        XCTAssertEqual(result.label, "fixture-cleaned; root-exists=false; suite-keys=0")
        app.terminate()
        app = nil
    }

    func testSameNamedAgentTargetsAndCapabilityDisclosureAtDefaultTextSize() throws {
        launchFixture(accessibilityTextSize: false)
        assertIdentityAndCapabilities()
    }

    func testSameNamedAgentTargetsAndCapabilityDisclosureAtLargestAccessibilityTextSize() throws {
        launchFixture(accessibilityTextSize: true)
        assertIdentityAndCapabilities()
    }

    func testPastePairApproveAndAutoProbeKeepsReadyAndUnavailableTargets() throws {
        let suppliedEnvironment = ProcessInfo.processInfo.environment
        let readyJSON = suppliedEnvironment["PADNOTE_QA_PAIR_READY_JSON"] ?? app.launchEnvironment["PADNOTE_QA_PAIR_READY_JSON"] ?? ""
        let unavailableJSON = suppliedEnvironment["PADNOTE_QA_PAIR_UNAVAILABLE_JSON"] ?? app.launchEnvironment["PADNOTE_QA_PAIR_UNAVAILABLE_JSON"] ?? ""
        XCTAssertFalse(readyJSON.isEmpty, "the owned fixture runner must supply the ready pairing JSON privately")
        XCTAssertFalse(unavailableJSON.isEmpty, "the owned fixture runner must supply the unavailable pairing JSON privately")

        app.launchArguments = ["--uitesting-agent-settings-layout", "--agent-settings-layout-fixture-id", fixtureID,
                               "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchEnvironment = [
            "PADNOTE_QA_PAIR_READY_JSON": readyJSON,
            "PADNOTE_QA_PAIR_UNAVAILABLE_JSON": unavailableJSON
        ]
        app.launch()
        XCTAssertTrue(app.navigationBars["电脑 Agent"].waitForExistence(timeout: 8))

        pairThroughVisibleUI(json: readyJSON, targetName: "QA Ready Hermes", expectedStatus: "Agent 可用")
        attachScreenshot("pairing-ui-ready-target-and-auto-probe")
        pairThroughVisibleUI(json: unavailableJSON, targetName: "QA Unavailable Hermes", expectedStatus: "Agent 不可用")
        let readyRow = app.cells.containing(NSPredicate(format: "label CONTAINS %@", "QA Ready Hermes")).firstMatch
        scrollCellIntoHittable(readyRow)
        let readyName = readyRow.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA Ready Hermes")).firstMatch
        let readyStatus = readyRow.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Agent 可用")).firstMatch
        XCTAssertTrue(readyRow.exists, "the first newly paired row remains in the list")
        XCTAssertTrue(readyName.exists)
        XCTAssertTrue(readyStatus.exists, "the ready status remains attached to its own saved target row")
        let unavailableRow = app.cells.containing(NSPredicate(format: "label CONTAINS %@", "QA Unavailable Hermes")).firstMatch
        let unavailableRetry = unavailableRow.buttons.matching(NSPredicate(format: "label CONTAINS %@", "测试连接")).firstMatch
        scrollCellIntoHittable(unavailableRow)
        XCTAssertTrue(unavailableRetry.isHittable, "an unavailable but saved connection retains its explicit retry action")
        attachScreenshot("pairing-ui-unavailable-target-retained-with-retry")
    }

    private func pairThroughVisibleUI(json: String, targetName: String, expectedStatus: String) {
        let pairButton = app.buttons["用连接助手配对"]
        scrollToHittable(pairButton, toward: .bottom)
        pairButton.tap()
        XCTAssertTrue(app.navigationBars["连接助手配对"].waitForExistence(timeout: 8))

        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 5), "the ordinary pairing sheet exposes its JSON paste editor")
        editor.tap()
        editor.typeText(json)
        XCTAssertTrue((editor.value as? String) == json, "the paste editor retains the supplied payload without writing it into test logs")
        let parse = app.buttons["解析粘贴内容"]
        scrollPairingControlIntoHittable(parse)
        XCTAssertTrue(parse.isHittable, "the pasted pairing payload can be parsed from the normal sheet")
        parse.tap()
        let request = app.buttons["向电脑申请连接"]
        let scannerNavigation = app.navigationBars["扫描二维码"]
        XCTAssertTrue(request.waitForExistence(timeout: 8), "a valid parse exposes the normal request action")
        XCTAssertFalse(scannerNavigation.exists, "parsing pasted text must not present the camera scanner")
        scrollPairingControlIntoHittable(request)
        XCTAssertTrue(request.isHittable, "a valid pasted code exposes the normal request action")
        request.tap()

        let pairingBar = app.navigationBars["连接助手配对"]
        let settingsBar = app.navigationBars["电脑 Agent"]
        let returnedToSettings = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !pairingBar.exists && settingsBar.isHittable
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [returnedToSettings], timeout: 45), .completed,
                       "wait until the pairing sheet is gone and its parent settings list is hittable")
        let targetRow = app.cells.containing(NSPredicate(format: "label CONTAINS %@", targetName)).firstMatch
        scrollCellIntoHittable(targetRow)
        XCTAssertTrue(targetRow.exists, "only the requested newly paired target satisfies this identity check")
        let targetIdentity = targetRow.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", targetName)).firstMatch
        let status = targetRow.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", expectedStatus)).firstMatch
        XCTAssertTrue(targetIdentity.exists, "the exact QA target name is present inside its row")
        XCTAssertTrue(status.waitForExistence(timeout: 20), "the expected automatic-probe status appears inside that same row")
    }

    func testUnavailableScannerCanReturnToPaste() throws {
        launchFixture(accessibilityTextSize: false)
        let pairButton = app.buttons["用连接助手配对"]
        scrollCellIntoHittable(pairButton)
        pairButton.tap()
        XCTAssertTrue(app.navigationBars["连接助手配对"].waitForExistence(timeout: 8))

        let scan = app.buttons["扫描二维码"]
        scrollPairingControlIntoHittable(scan)
        XCTAssertTrue(scan.isHittable)
        scan.tap()
        let scannerNavigation = app.navigationBars["扫描二维码"]
        XCTAssertTrue(scannerNavigation.waitForExistence(timeout: 8), "the scanner opens in its own titled sheet")

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let permissionAlert = springboard.alerts.firstMatch
        if permissionAlert.waitForExistence(timeout: 4) {
            let cameraPromptText = permissionAlert.staticTexts.matching(NSPredicate(format:
                "label CONTAINS[c] %@ OR label CONTAINS %@", "camera", "相机")).firstMatch
            XCTAssertTrue(cameraPromptText.exists, "the test only dismisses the system camera-permission alert")
            let deny = permissionAlert.buttons.matching(NSPredicate(format: "label IN %@",
                ["Don't Allow", "不允许", "不允许一次", "不允许访问"])).firstMatch
            XCTAssertTrue(deny.waitForExistence(timeout: 3), "the isolated test denies camera permission only if iOS asks")
            deny.tap()
        }

        let unavailable = app.staticTexts["相机不可用。请返回并粘贴配对 JSON。"]
        XCTAssertTrue(unavailable.waitForExistence(timeout: 8), "unavailable-camera guidance remains visible in the scanner")
        attachScreenshot("pairing-camera-unavailable-with-return-action")
        let returnToPaste = app.buttons["返回粘贴配对"]
        XCTAssertTrue(returnToPaste.waitForExistence(timeout: 5))
        XCTAssertTrue(returnToPaste.isHittable)
        returnToPaste.tap()
        let pairingNavigation = app.navigationBars["连接助手配对"]
        let returnedToPaste = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !scannerNavigation.exists && pairingNavigation.isHittable
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [returnedToPaste], timeout: 8), .completed,
                       "the explicit scanner return keeps the pairing sheet open")

        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 5), "the paste editor remains available after scanner dismissal")
        scrollPairingControlIntoHittable(editor)
        XCTAssertTrue(editor.isHittable)
        attachScreenshot("pairing-returned-to-paste-editor")
    }

    private func scrollPairingControlIntoHittable(_ element: XCUIElement) {
        for _ in 0..<8 where !element.isHittable { app.swipeUp() }
        for _ in 0..<16 where !element.isHittable { app.swipeDown() }
        XCTAssertTrue(element.exists && element.isHittable,
                      "the requested pairing control is visible in the current sheet")
    }

    private func scrollCellIntoHittable(_ cell: XCUIElement) {
        for _ in 0..<8 where !cell.isHittable { app.swipeUp() }
        for _ in 0..<16 where !cell.isHittable { app.swipeDown() }
        XCTAssertTrue(cell.isHittable, "the requested QA connection row is reachable in the saved connection list")
    }

    private func launchFixture(accessibilityTextSize: Bool) {
        app.launchArguments = ["--uitesting-agent-settings-layout", "--agent-settings-layout-fixture-id", fixtureID]
        if accessibilityTextSize {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        app.launch()
        XCTAssertTrue(app.navigationBars["电脑 Agent"].waitForExistence(timeout: 8))
    }

    private func assertIdentityAndCapabilities() {
        let videoIdentity = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@ AND label CONTAINS %@ AND label CONTAINS %@",
            "实验室视频连接专用工作站和团队协作助手", "alpha-workstation-with-a-very-long-name.fixture.invalid:18443",
            "实例 VIDEO042", "内置视频")).firstMatch
        let hermesIdentity = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@ AND label CONTAINS %@ AND label CONTAINS %@",
            "实验室视频连接专用工作站和团队协作助手", "bravo-render-node-with-a-very-long-name.fixture.invalid:29443",
            "实例 HERMES09", "Hermes")).firstMatch
        assertIdentityFullyVisible(videoIdentity,
            expected: ["实验室视频连接专用工作站和团队协作助手", "alpha-workstation-with-a-very-long-name.fixture.invalid:18443", "实例 VIDEO042"],
            screenshotName: accessibilitySize ? "agent-settings-video-identity-xxxl" : "agent-settings-video-identity-default",
            direction: .top)
        assertIdentityFullyVisible(hermesIdentity,
            expected: ["实验室视频连接专用工作站和团队协作助手", "bravo-render-node-with-a-very-long-name.fixture.invalid:29443", "实例 HERMES09"],
            screenshotName: accessibilitySize ? "agent-settings-hermes-identity-xxxl" : "agent-settings-hermes-identity-default",
            direction: .bottom)

        let disclosurePrefix = "agentCapabilityDisclosure.builtin_video."
        let capabilityDisclosure = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", disclosurePrefix)).firstMatch
        scrollToHittable(capabilityDisclosure, toward: .top)
        XCTAssertTrue(capabilityDisclosure.exists, "the selected disclosure must belong to the fixture's builtin-video profile")
        let disclosureIdentifier = capabilityDisclosure.identifier
        XCTAssertTrue(disclosureIdentifier.hasPrefix(disclosurePrefix))
        let profileID = String(disclosureIdentifier.dropFirst(disclosurePrefix.count))
        XCTAssertNotNil(UUID(uuidString: profileID), "the disclosure identifier must be scoped to a valid profile UUID")
        capabilityDisclosure.tap()
        XCTAssertFalse(app.navigationBars["编辑连接"].exists,
                       "tapping the capability label must expand its disclosure instead of opening the profile editor")
        let firstCapability = app.staticTexts["agentCapability.\(profileID).run_submission"]
        let lastCapability = app.staticTexts["agentCapability.\(profileID).artifacts"]
        let explanation = app.staticTexts["agentCapabilityDisclaimer.\(profileID)"]
        attachDisclosureDiagnostics(stage: "after-disclosure-open", profileID: profileID,
                                    capability: lastCapability, disclaimer: explanation)
        XCTAssertTrue(firstCapability.waitForExistence(timeout: 5), "expanding this exact disclosure must expose its first capability item")
        let firstCapabilityVisible = assertDisclosureTextFullyVisible(firstCapability, profileID: profileID, direction: .bottom)
        if firstCapabilityVisible {
            attachDisclosureDiagnostics(stage: "disclosure-open-settled", profileID: profileID,
                                        capability: firstCapability, disclaimer: explanation)
            attachScreenshot(accessibilitySize ? "agent-settings-disclosure-open-xxxl" : "agent-settings-disclosure-open-default")
        }
        XCTAssertTrue(lastCapability.waitForExistence(timeout: 5), "expanding this exact disclosure must expose its final capability item")
        let lastCapabilityVisible = assertDisclosureTextFullyVisible(lastCapability, profileID: profileID, direction: .bottom)
        let explanationVisible = assertDisclosureTextFullyVisible(explanation, profileID: profileID, direction: .bottom)
        if lastCapabilityVisible && explanationVisible {
            attachDisclosureDiagnostics(stage: "after-scrolling-to-disclaimer", profileID: profileID,
                                        capability: lastCapability, disclaimer: explanation)
            attachScreenshot(accessibilitySize ? "agent-settings-capabilities-bottom-xxxl" : "agent-settings-capabilities-bottom-default")
        }

        let probe = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "测试连接")).firstMatch
        scrollToHittable(probe, toward: .bottom)
        XCTAssertTrue(probe.isHittable, "the connection test action should be reachable; the test deliberately does not tap it")

        let makeDefault = app.buttons.matching(NSPredicate(format: "label == %@", "设为默认")).firstMatch
        scrollToHittable(makeDefault, toward: .bottom)
        makeDefault.tap()
        app.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting-agent-settings-layout", "--agent-settings-layout-fixture-id", fixtureID,
                              "--agent-settings-layout-inspect-default"]
        app.launch()
        let defaultInspection = app.staticTexts["agentSettingsLayoutDefaultInspection"]
        XCTAssertTrue(defaultInspection.waitForExistence(timeout: 5))
        XCTAssertEqual(defaultInspection.label, "default-profile=https://bravo-render-node-with-a-very-long-name.fixture.invalid:29443",
                       "the action in the Hermes row must persistently select that target in the isolated defaults suite")
    }

    private var accessibilitySize: Bool {
        app.launchArguments.contains("UICTContentSizeCategoryAccessibilityXXXL")
    }

    private enum ScrollDirection: Equatable { case top, bottom }

    private func assertIdentityFullyVisible(_ element: XCUIElement, expected: [String],
                                            screenshotName: String, direction: ScrollDirection) {
        scrollTextFullyVisible(element, toward: direction)
        XCTAssertTrue(element.exists, "the identity Text must exist as its own static text")
        for value in expected {
            XCTAssertTrue(element.label.contains(value), "the accessible identity must include its full distinguishing text: \(value)")
        }
        let isStable = waitForStableFrame(element)
        XCTAssertTrue(isStable, "waited for identity text layout to stop moving before screenshot capture")
        guard isStable else {
            attachDisclosureDiagnostics(stage: "identity-frame-unstable", profileID: element.label,
                                        capability: element, disclaimer: element)
            return
        }
        let viewport = visibleViewport()
        let frame = element.frame
        let fullyVisible = frameIsFullyContained(frame, in: viewport)
        XCTAssertTrue(fullyVisible,
                      "all identity text bounds must be inside the window below the navigation bar and above the bottom safe-area reserve; frame=\(frame.debugDescription) viewport=\(viewport.debugDescription)")
        let hittable = element.isHittable
        XCTAssertTrue(hittable, "the complete identity Text must be visible, not merely partly hittable")
        attachIdentityDiagnostics(element, viewport: viewport, stage: screenshotName)
        if fullyVisible && hittable {
            attachScreenshot(screenshotName)
        }
    }

    @discardableResult
    private func assertDisclosureTextFullyVisible(_ element: XCUIElement, profileID: String,
                                                  direction: ScrollDirection) -> Bool {
        scrollTextFullyVisible(element, toward: direction)
        XCTAssertTrue(element.exists, "the selected profile's exact disclosure text must exist")
        let isStable = waitForStableFrame(element)
        XCTAssertTrue(isStable, "waited for disclosure layout/animation to settle")
        guard isStable else { return false }
        let frame = element.frame
        let viewport = visibleViewport()
        let fullyVisible = frameIsFullyContained(frame, in: viewport)
        XCTAssertTrue(fullyVisible,
                      "the complete disclosure text must be inside the safe visible viewport; profile=\(profileID) frame=\(frame.debugDescription) viewport=\(viewport.debugDescription)")
        let hittable = element.isHittable
        XCTAssertTrue(hittable, "the exact disclosure text must be fully visible after scrolling")
        return fullyVisible && hittable
    }

    private func scrollToHittable(_ element: XCUIElement, toward direction: ScrollDirection) {
        let list = app.tables.firstMatch
        var attempts = 0
        while (!element.exists || !element.isHittable) && attempts < 18 {
            if list.exists {
                direction == .top ? list.swipeDown() : list.swipeUp()
            } else {
                direction == .top ? app.swipeDown() : app.swipeUp()
            }
            attempts += 1
        }
        XCTAssertTrue(element.exists && element.isHittable,
                      "expected the matching row/control to be reachable by scrolling in the requested direction")
    }

    private func scrollTextFullyVisible(_ element: XCUIElement, toward initialDirection: ScrollDirection) {
        var direction = initialDirection
        var attempts = 0
        while attempts < 24 {
            guard element.exists else {
                dragList(toward: direction)
                attempts += 1
                continue
            }
            let frame = element.frame
            let viewport = visibleViewport()
            if frameIsFullyContained(frame, in: viewport) { return }
            direction = frame.minY < viewport.minY || frame.minX < viewport.minX ? .top : .bottom
            dragList(toward: direction)
            attempts += 1
        }
        let exists = element.exists
        let frame = exists ? element.frame.debugDescription : "unavailable: element does not exist"
        XCTFail("identity/disclosure text did not fit fully in the visible viewport after scrolling; exists=\(exists) frame=\(frame) viewport=\(visibleViewport().debugDescription)\n\(app.debugDescription)")
    }

    private func visibleViewport() -> CGRect {
        let window = app.windows.firstMatch.frame
        let navigationBar = app.navigationBars["电脑 Agent"]
        let top = max(window.minY, navigationBar.exists ? navigationBar.frame.maxY : window.minY) + 8
        let bottom = window.maxY - 50
        return CGRect(x: window.minX, y: top, width: window.width, height: max(0, bottom - top))
    }

    private func frameIsFullyContained(_ frame: CGRect, in viewport: CGRect) -> Bool {
        !frame.isEmpty && frame.minX >= viewport.minX && frame.maxX <= viewport.maxX &&
            frame.minY >= viewport.minY && frame.maxY <= viewport.maxY
    }

    private func dragList(toward direction: ScrollDirection) {
        let list = app.tables.firstMatch
        let container: XCUIElement
        if list.exists {
            container = list
        } else {
            container = app
        }
        let startY: CGFloat = direction == .bottom ? 0.80 : 0.22
        let endY: CGFloat = direction == .bottom ? 0.55 : 0.48
        let start = container.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: startY))
        let end = container.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: endY))
        start.press(forDuration: 0.05, thenDragTo: end)
    }

    private func waitForStableFrame(_ element: XCUIElement) -> Bool {
        var previous: CGRect?
        var stableSamples = 0
        for _ in 0..<12 {
            guard element.exists else {
                previous = nil
                stableSamples = 0
                Thread.sleep(forTimeInterval: 0.15)
                continue
            }
            let current = element.frame
            if current == previous { stableSamples += 1 } else { stableSamples = 0 }
            if stableSamples >= 4 { return true }
            previous = current
            Thread.sleep(forTimeInterval: 0.15)
        }
        return false
    }

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func attachDisclosureDiagnostics(stage: String, profileID: String,
                                             capability: XCUIElement, disclaimer: XCUIElement) {
        let capabilityExists = capability.exists
        let capabilityFrame = capabilityExists ? capability.frame.debugDescription : "unavailable: element does not exist"
        let disclaimerExists = disclaimer.exists
        let disclaimerFrame = disclaimerExists ? disclaimer.frame.debugDescription : "unavailable: element does not exist"
        let details = """
        stage=\(stage)
        selectedProfileID=\(profileID)
        lastCapability.exists=\(capabilityExists)
        lastCapability.frame=\(capabilityFrame)
        disclaimer.exists=\(disclaimerExists)
        disclaimer.frame=\(disclaimerFrame)

        \(app.debugDescription)
        """
        let attachment = XCTAttachment(string: details)
        attachment.name = "agent-settings-\(stage)-\(accessibilitySize ? "xxxl" : "default")-accessibility"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func attachIdentityDiagnostics(_ element: XCUIElement, viewport: CGRect, stage: String) {
        let details = """
        stage=\(stage)
        label=\(element.label)
        fullyVisibleFrame=\(element.frame.debugDescription)
        safeVisibleViewport=\(viewport.debugDescription)
        isHittable=\(element.isHittable)

        \(app.debugDescription)
        """
        let attachment = XCTAttachment(string: details)
        attachment.name = "\(stage)-identity-frame"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

final class AgentTaskHistoryIdentityUITests: XCTestCase {
    private let fixtureID = UUID().uuidString.lowercased()
    private var app: XCUIApplication!
    private enum ScrollDirection { case towardTop, towardBottom }

    private let fixtures: [(String, String, String, String)] = [
        ("a1111111-1111-4111-8111-111111111111", "history-hermes-long-host.fixture.invalid:18443", "Hermes", "Bridge 助手 bridgeHERMES01 · 实例 instanceHERMES01"),
        ("b2222222-2222-4222-8222-222222222222", "history-video-long-host.fixture.invalid:29443", "内置视频", "Bridge 助手 bridgeVIDEO01 · 实例 instanceVIDEO02"),
        ("c3333333-3333-4333-8333-333333333333", "history-hermes-secondary-long-host.fixture.invalid:38080", "Hermes", "Bridge 助手 bridgeHERMES02 · 实例 instanceHERMES99"),
        ("d4444444-4444-4444-8444-444444444444", "history-legacy.fixture.invalid:443", "Hermes", "Bridge 助手 未知 · 实例 未知")
    ]

    override func setUpWithError() throws {
        continueAfterFailure = false
        executionTimeAllowance = 240
        app = XCUIApplication()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-agent-task-history",
                              "--agent-task-history-fixture-id", fixtureID,
                              "--agent-task-history-cleanup"]
        app.launch()
        let receipt = app.staticTexts["agentTaskHistoryCleanupResult"]
        XCTAssertTrue(receipt.waitForExistence(timeout: 8))
        XCTAssertEqual(receipt.label, "fixture-cleaned; root-exists=false; suite-keys=0")
        let cleanupEvidence = XCTAttachment(string: "cleanup-receipt=\(receipt.label); observed-after-cleanup-host-appeared=true")
        cleanupEvidence.name = "agent-history-cleanup-receipt"
        cleanupEvidence.lifetime = .keepAlways
        add(cleanupEvidence)
        app.terminate()
        app = nil
    }

    func testAgentResultUXKeepsLongAnswerAndFileActionsDiscoverable() throws {
        let resultFixtureID = UUID().uuidString.lowercased()
        let resultSuite = "PadNote.AgentResultUX.UITest.\(resultFixtureID)"
        let resultRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("PadNoteAgentResultUXUITest-\(resultFixtureID)", isDirectory: true)
        app.launchArguments = ["--uitesting", "--uitesting-agent-result-ux",
                               "--result-ux-fixture-id", resultFixtureID]
        app.launch()
        defer {
            app.terminate()
            try? FileManager.default.removeItem(at: resultRoot)
            UserDefaults(suiteName: resultSuite)?.removePersistentDomain(forName: resultSuite)
        }

        let clientTaskID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        let row = app.buttons["agentTaskRow-\(clientTaskID)"]
        XCTAssertTrue(row.waitForExistence(timeout: 8), "the isolated synthetic result fixture must load")
        XCTAssertTrue(row.label.contains("已完成"), "execution status remains visible as the primary row state")
        let deliveryCue = app.staticTexts["agentTaskDeliveryCue-\(clientTaskID)"]
        XCTAssertTrue(deliveryCue.waitForExistence(timeout: 5))
        XCTAssertEqual(deliveryCue.label, "缺少：PDF")
        row.tap()

        let forms = app.descendants(matching: .any).matching(identifier: "agentTaskDetailForm")
        XCTAssertTrue(forms.firstMatch.waitForExistence(timeout: 5))
        let form = forms.element(boundBy: forms.count - 1)
        let answer = form.staticTexts["agentTaskResultText"]
        XCTAssertTrue(answer.waitForExistence(timeout: 5))
        XCTAssertEqual(answer.value as? String, "collapsed")
        let collapsedHeight = answer.frame.height
        let disclosure = form.buttons["agentTaskOutputDisclosure"]
        scrollUntilHittable(disclosure, in: form, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(disclosure.exists, "the full answer must always remain reachable, including short wrapped answers")
        XCTAssertTrue(disclosure.isHittable)
        attach("agent-result-ux-answer-collapsed")
        disclosure.tap()
        XCTAssertEqual(answer.value as? String, "expanded")
        XCTAssertTrue(answer.label.contains("RESULT-UX-END-MARKER"), "expansion must expose the original answer ending")
        XCTAssertGreaterThan(answer.frame.height, collapsedHeight, "the collapsed presentation must bound the long answer")
        attach("agent-result-ux-answer-expanded")
        scrollUntilHittable(disclosure, in: form, direction: .towardBottom, maxSwipes: 8)
        XCTAssertTrue(disclosure.isHittable, "the collapse control must remain reachable after expanding a long answer")
        disclosure.tap()
        XCTAssertEqual(answer.value as? String, "collapsed", "the full answer must collapse again without changing its text")

        XCTAssertTrue(form.buttons["agentResultPreviewToPaper"].exists)
        XCTAssertTrue(form.buttons["agentVideoPlayArtifact-synthetic-image-1"].exists,
                      "the returned image file action must remain discoverable")
        XCTAssertTrue(form.buttons["agentImagePlaceArtifact-synthetic-image-1"].exists,
                      "placing the returned image must remain discoverable")
        XCTAssertTrue(form.descendants(matching: .any)["agentTaskInputDisclosure"].exists,
                      "the long original input remains available in its collapsed section after result actions")
    }

    func testSavedTaskDestinationsAreVisuallyDistinctAtDefaultTextSize() throws {
        launchFixture(accessibilityTextSize: false)
        assertHistoryRowsAndSameNamedTaskSelection(suffix: "default")
    }

    func testSavedTaskDestinationsAreVisuallyDistinctAtLargestAccessibilityTextSize() throws {
        launchFixture(accessibilityTextSize: true)
        assertHistoryRowsAndSameNamedTaskSelection(suffix: "xxxl")
    }

    private func launchFixture(accessibilityTextSize: Bool) {
        app.launchArguments = ["--uitesting", "--uitesting-agent-task-history",
                              "--agent-task-history-fixture-id", fixtureID]
        if accessibilityTextSize {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        app.launch()
        XCTAssertTrue(app.navigationBars["电脑任务"].waitForExistence(timeout: 10))
    }

    private func scrollUntilHittable(_ element: XCUIElement, in container: XCUIElement,
                                     direction: ScrollDirection, maxSwipes: Int) {
        for _ in 0..<maxSwipes where !element.isHittable {
            switch direction {
            case .towardBottom: container.swipeUp()
            case .towardTop: container.swipeDown()
            }
        }
    }

    private func assertHistoryRowsAndSameNamedTaskSelection(suffix: String) {
        for (taskID, host, kind, bridge) in fixtures {
            let row = app.buttons["agentTaskRow-\(taskID)"]
            XCTAssertTrue(row.waitForExistence(timeout: 8), "fixture task row must be found by stable task ID \(taskID)")
            scrollIntoVisibleViewport(row)
            XCTAssertTrue(row.label.contains("同名的已保存任务"), "rows intentionally share the same title")
            let identity = row.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", host)).firstMatch
            XCTAssertTrue(identity.waitForExistence(timeout: 5), "destination identity must be an individual readable Text in the correct stable-ID row")
            XCTAssertTrue(identity.label.contains(kind))
            XCTAssertTrue(identity.label.contains(bridge))
            assertFullyVisible(identity, row: row, attachmentName: "agent-history-\(suffix)-\(taskID.prefix(1))")
        }

        openTaskAndVerifyBody(taskID: fixtures[0].0, host: fixtures[0].1, kind: fixtures[0].2,
                              bridge: fixtures[0].3, marker: "history-body-hermes-a111",
                              evidenceName: "agent-history-\(suffix)-detail-hermes")
        openTaskAndVerifyBody(taskID: fixtures[1].0, host: fixtures[1].1, kind: fixtures[1].2,
                              bridge: fixtures[1].3, marker: "history-body-video-b222",
                              evidenceName: "agent-history-\(suffix)-detail-video")
    }

    private func openTaskAndVerifyBody(taskID: String, host: String, kind: String, bridge: String,
                                      marker: String, evidenceName: String) {
        let row = app.buttons["agentTaskRow-\(taskID)"]
        scrollIntoVisibleViewport(row)
        XCTAssertTrue(row.isHittable)
        let rowIdentity = row.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", host)).firstMatch
        XCTAssertTrue(rowIdentity.waitForExistence(timeout: 5))
        let expectedIdentity = rowIdentity.label
        row.tap()
        let detail = app.descendants(matching: .any).matching(identifier: "agentTaskDetailForm").firstMatch
        XCTAssertTrue(detail.waitForExistence(timeout: 8), "the exact row tap should open the real task detail form")
        XCTAssertEqual(detail.elementType, .collectionView, "SwiftUI Form is exposed by this UI as a CollectionView")
        let title = app.navigationBars["同名的已保存任务"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        assertPersistedConnectionIdentity(in: detail, expectedName: "同名的电脑助手连接",
                                          expectedIdentity: expectedIdentity, host: host, kind: kind,
                                          bridge: bridge, evidenceName: evidenceName + "-destination")
        let expectedBodyLabel = "合成历史结果：\(marker)"
        let body = detail.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", expectedBodyLabel)).firstMatch
        _ = scrollDetailTextIntoViewport(body, detail: detail, evidenceName: evidenceName + "-body")
        guard body.exists else {
            attach(evidenceName + "-body-not-found")
            attachText(evidenceName + "-body-not-found-ax", detail.debugDescription)
            XCTFail("the exact unique saved-body label must materialize in the opened task detail after bounded scrolling")
            return
        }
        let expectedBodySnapshot = body.label
        let stable = waitForStableFrame(body)
        let bodyStillExists = body.exists
        let settledFrame = stable && bodyStillExists ? body.frame : .null
        let fullyVisible = bodyStillExists && frameIsContained(settledFrame, in: detailViewport(detail))
        if !stable || !fullyVisible {
            attach(evidenceName + "-visibility-failure-screen")
            attachText(evidenceName + "-visibility-failure-ax", detail.debugDescription)
        }
        XCTAssertTrue(stable, "detail marker frame should settle after scrolling the detail Form")
        XCTAssertTrue(fullyVisible, "the entire unique task-body marker must be visible inside the detail Form viewport; frame=\(settledFrame) viewport=\(detailViewport(detail))")
        XCTAssertEqual(expectedBodySnapshot, expectedBodyLabel, "the selected task ID must resolve to its own persisted input marker")
        if stable {
            attachText(evidenceName + "-frame",
                       "stable=true\nform-type=\(String(describing: detail.elementType))\nform-frame=\(detail.frame.debugDescription)\nbody=\(expectedBodySnapshot)\nbody-frame=\(settledFrame.debugDescription)\nvisible-viewport=\(detailViewport(detail).debugDescription)\nfully-contained=\(fullyVisible)")
            attach(evidenceName)
        }
        app.navigationBars["同名的已保存任务"].buttons["完成"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 8), "returning from detail must preserve the exact saved row")
    }

    private func assertPersistedConnectionIdentity(in detail: XCUIElement, expectedName: String,
                                                  expectedIdentity: String, host: String, kind: String,
                                                  bridge: String, evidenceName: String) {
        let name = detail.descendants(matching: .any)
            .matching(identifier: "agentTaskDetailConnectionName").firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 5), "detail must render its persisted connection name")
        XCTAssertEqual(name.label, expectedName)
        assertDetailTextFullyVisible(name, detail: detail, evidenceName: evidenceName + "-name")

        let identity = detail.descendants(matching: .any)
            .matching(identifier: "agentTaskDetailDestinationIdentity").firstMatch
        XCTAssertTrue(identity.waitForExistence(timeout: 5), "detail must render the persisted safe target identity")
        XCTAssertEqual(identity.label, expectedIdentity, "detail must use the same saved snapshot identity as the selected history row")
        XCTAssertTrue(identity.label.contains(host))
        XCTAssertTrue(identity.label.contains(kind))
        XCTAssertTrue(identity.label.contains(bridge))
        XCTAssertTrue(identity.label.contains("连接 #"), "the rendered identity includes the saved connection short ID")
        XCTAssertFalse(identity.label.contains("https://"))
        XCTAssertFalse(identity.label.contains("synthetic-history"))
        assertDetailTextFullyVisible(identity, detail: detail, evidenceName: evidenceName + "-identity")
    }

    private func assertDetailTextFullyVisible(_ element: XCUIElement, detail: XCUIElement, evidenceName: String) {
        scrollDetailTextIntoViewport(element, detail: detail, evidenceName: evidenceName)
        let sampledLabel = element.exists ? element.label : "unavailable: lazy accessibility element is not present"
        let stable = waitForStableFrame(element)
        let elementStillExists = element.exists
        let frame = stable && elementStillExists ? element.frame : .null
        let viewport = detailViewport(detail)
        let fullyVisible = stable && elementStillExists && frameIsContained(frame, in: viewport)
        if !stable || !fullyVisible {
            attach(evidenceName + "-visibility-failure-screen")
            attachText(evidenceName + "-visibility-failure-ax", detail.debugDescription)
        }
        XCTAssertTrue(stable, "detail text frame should settle before capture")
        XCTAssertTrue(fullyVisible, "the complete saved snapshot text must be visible; frame=\(frame) viewport=\(viewport)")
        let attachment = XCTAttachment(string: "text=\(sampledLabel)\nframe=\(frame.debugDescription)\nviewport=\(viewport.debugDescription)\nfully-contained=\(fullyVisible)")
        attachment.name = evidenceName + "-frame-and-label"
        attachment.lifetime = .keepAlways
        add(attachment)
        attach(evidenceName)
    }

    @discardableResult
    private func scrollDetailTextIntoViewport(_ element: XCUIElement, detail: XCUIElement, evidenceName: String) -> Bool {
        var contentPerFinger: CGFloat = 0.62
        let initiallyExists = element.exists
        if !initiallyExists {
            attach(evidenceName + "-before-scroll")
            attachText(evidenceName + "-before-scroll-ax",
                       "exact-query-exists=false\nviewport=\(detailViewport(detail).debugDescription)\n\(detail.debugDescription)")
        }
        for dragIndex in 0..<8 {
            let existsBefore = element.exists
            let viewport = detailViewport(detail)
            guard !viewport.isEmpty else {
                attach(evidenceName + "-empty-viewport")
                attachText(evidenceName + "-empty-viewport-ax", detail.debugDescription)
                return false
            }
            var beforeFrame = CGRect.null
            var beforeFrameText = "missing"
            if existsBefore {
                beforeFrame = element.frame
                beforeFrameText = beforeFrame.debugDescription
                if frameIsContained(beforeFrame, in: viewport) { return true }
                guard !beforeFrame.isEmpty, beforeFrame.height <= viewport.height else {
                    attach(evidenceName + "-text-does-not-fit-viewport")
                    attachText(evidenceName + "-text-does-not-fit-viewport-ax", detail.debugDescription)
                    return false
                }
            }
            // SwiftUI Form may omit later cells from AX until they are scrolled into view.
            // When the exact result query is absent, move toward the later form sections.
            let movesContentUp = !existsBefore || beforeFrame.maxY > viewport.maxY
            let overflow = existsBefore
                ? (movesContentUp ? beforeFrame.maxY - viewport.maxY : viewport.minY - beforeFrame.minY)
                : viewport.height * 0.72
            let usableTravel = max(0, viewport.height - 48)
            // R8 evidence showed 12pt residual drags left the scroll view at delta-y=0.
            let minimumRecognizedFingerTravel: CGFloat = 40
            let desiredFingerTravel = existsBefore
                ? min(usableTravel * 0.88, max(minimumRecognizedFingerTravel, overflow / max(contentPerFinger, 0.15) * 0.92))
                : usableTravel * 0.88
            let startPoint = CGPoint(x: viewport.midX, y: movesContentUp ? viewport.maxY - 24 : viewport.minY + 24)
            let endPoint = CGPoint(x: viewport.midX, y: startPoint.y + (movesContentUp ? -desiredFingerTravel : desiredFingerTravel))
            guard desiredFingerTravel > 0, viewport.contains(startPoint), viewport.contains(endPoint),
                  detail.frame.contains(startPoint), detail.frame.contains(endPoint) else {
                attach(evidenceName + "-unsafe-detail-drag")
                attachText(evidenceName + "-unsafe-detail-drag-ax", detail.debugDescription)
                return false
            }
            let start = detail.coordinate(withNormalizedOffset: CGVector(
                dx: (startPoint.x - detail.frame.minX) / detail.frame.width,
                dy: (startPoint.y - detail.frame.minY) / detail.frame.height))
            let end = detail.coordinate(withNormalizedOffset: CGVector(
                dx: (endPoint.x - detail.frame.minX) / detail.frame.width,
                dy: (endPoint.y - detail.frame.minY) / detail.frame.height))
            start.press(forDuration: 0.12, thenDragTo: end)
            let settledAfterDrag = waitForStableFrame(detail)
            let existsAfter = element.waitForExistence(timeout: 0.35) || element.exists
            var afterFrame = CGRect.null
            var afterFrameText = "missing"
            if existsAfter {
                afterFrame = element.frame
                afterFrameText = afterFrame.debugDescription
            }
            let afterViewport = detailViewport(detail)
            let deltaY: String
            if existsBefore && existsAfter {
                let delta = afterFrame.minY - beforeFrame.minY
                deltaY = String(describing: delta)
                let fingerDeltaY = endPoint.y - startPoint.y
                if abs(fingerDeltaY) > 1, abs(delta) > 2 {
                    let observed = min(12.0, max(0.15, abs(delta / fingerDeltaY)))
                    contentPerFinger = contentPerFinger * 0.5 + observed * 0.5
                }
            } else {
                deltaY = "unavailable; exact query was missing before/after drag"
            }
            let fullyVisible = existsAfter && frameIsContained(afterFrame, in: afterViewport)
            attachText("\(evidenceName)-drag-\(dragIndex + 1)",
                       "direction=\(movesContentUp ? "up" : "down")\ndrag=\(dragIndex + 1)\nexact-query-exists-before=\(existsBefore)\nexact-query-exists-after=\(existsAfter)\nrequested-overflow=\(overflow)\nsettled-after-drag=\(settledAfterDrag)\ncontent-per-finger=\(contentPerFinger)\nstart=\(startPoint.debugDescription)\nend=\(endPoint.debugDescription)\nbefore-frame=\(beforeFrameText)\nbefore-viewport=\(viewport.debugDescription)\nafter-frame=\(afterFrameText)\nafter-viewport=\(afterViewport.debugDescription)\ndelta-y=\(deltaY)\nfully-contained=\(fullyVisible)")
            if fullyVisible { return true }
        }
        guard element.exists else { return false }
        return frameIsContained(element.frame, in: detailViewport(detail))
    }

    private func detailViewport(_ detail: XCUIElement) -> CGRect {
        let window = app.windows.firstMatch.frame
        let detailNavigationBar = app.navigationBars["同名的已保存任务"]
        let top = max(detail.frame.minY, detailNavigationBar.exists ? detailNavigationBar.frame.maxY + 6 : detail.frame.minY)
        let bottom = min(detail.frame.maxY, window.maxY - 48)
        return CGRect(x: detail.frame.minX, y: top, width: detail.frame.width, height: max(0, bottom - top))
    }

    private func frameIsContained(_ frame: CGRect, in viewport: CGRect) -> Bool {
        !frame.isEmpty && !viewport.isEmpty && frame.minX >= viewport.minX && frame.minY >= viewport.minY &&
            frame.maxX <= viewport.maxX && frame.maxY <= viewport.maxY
    }

    private func assertFullyVisible(_ identity: XCUIElement, row: XCUIElement, attachmentName: String) {
        scrollIntoVisibleViewport(identity)
        XCTAssertTrue(identity.exists)
        XCTAssertTrue(row.label.contains(identity.label), "the rendered identity must remain associated with its own title row")
        let stable = waitForStableFrame(identity)
        XCTAssertTrue(stable, "identity text frame should settle before capture")
        guard stable else { attach(attachmentName + "-unstable"); return }
        let window = app.windows.firstMatch.frame
        let safeViewport = CGRect(x: window.minX, y: app.navigationBars.firstMatch.frame.maxY + 8,
                                  width: window.width, height: window.maxY - app.navigationBars.firstMatch.frame.maxY - 52)
        let frame = identity.frame
        let fullyContained = !frame.isEmpty && safeViewport.contains(CGPoint(x: frame.minX, y: frame.minY)) &&
            safeViewport.contains(CGPoint(x: frame.maxX, y: frame.maxY))
        XCTAssertTrue(fullyContained, "the complete destination identity must be visually on-screen; frame=\(frame) viewport=\(safeViewport)")
        XCTAssertTrue(identity.isHittable, "destination text itself must be visible")
        let evidence = XCTAttachment(string: "stable=true\nidentity=\(identity.label)\nidentity-frame=\(frame.debugDescription)\nvisible-viewport=\(safeViewport.debugDescription)\nfully-contained=\(fullyContained)")
        evidence.name = attachmentName + "-identity-frame-and-label"
        evidence.lifetime = .keepAlways
        add(evidence)
        attach(attachmentName)
    }

    private func scrollIntoVisibleViewport(_ element: XCUIElement) {
        let window = app.windows.firstMatch.frame
        let top = app.navigationBars.firstMatch.frame.maxY + 8
        let bottom = window.maxY - 52
        for _ in 0..<14 {
            let frame = element.frame
            if !frame.isEmpty && frame.minY >= top && frame.maxY <= bottom { return }
            if frame.isEmpty || frame.maxY > bottom { app.swipeUp() }
            else { app.swipeDown() }
            _ = element.waitForExistence(timeout: 2)
        }
    }

    private func waitForStableFrame(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        var previous = element.frame
        var matchingSamples = 0
        for _ in 0..<12 {
            Thread.sleep(forTimeInterval: 0.2)
            guard element.exists else { return false }
            let current = element.frame
            if !current.isEmpty && abs(current.minY - previous.minY) < 1 && abs(current.height - previous.height) < 1 {
                matchingSamples += 1
                if matchingSamples >= 3 { return true }
            } else { matchingSamples = 0 }
            previous = current
        }
        return false
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func attachText(_ name: String, _ text: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

final class AgentResultToPaperUITests: XCTestCase {
    private var app: XCUIApplication!
    private let fixtureID = UUID().uuidString.lowercased()

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        app = makeApp(cleanup: true)
        app.launch()
        let receipt = app.staticTexts["agentResultPaperCleanupResult"]
        XCTAssertTrue(receipt.waitForExistence(timeout: 8))
        XCTAssertEqual(receipt.label, "result-paper-fixture-cleaned; root-exists=false; suite-keys=0")
        app.terminate()
        app = nil
    }

    func testCompletedTaskPreviewAppendOpenAndReopenHasExactlyOneOriginFlow() throws {
        app = makeApp()
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        app.buttons["agentResultPreviewToPaper"].tap()

        let confirm = app.buttons["agentResultConfirmAppend"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 8))
        let form = app.descendants(matching: .any).matching(identifier: "agentResultAppendForm").firstMatch
        XCTAssertTrue(form.waitForExistence(timeout: 8))
        let rawAnswer = app.buttons["agentResultRawAnswerDisclosure"]
        XCTAssertTrue(rawAnswer.waitForExistence(timeout: 8), "the raw-answer disclosure should be listed before the paper preview")
        if !rawAnswer.isHittable {
            attachResultPaperFailureDiagnostics("raw-answer-disclosure-not-hittable")
            let status = """
            form.exists=\(form.exists); form.frame=\(String(describing: form.frame)); form.hittable=\(form.isHittable)
            disclosure.exists=\(rawAnswer.exists); disclosure.frame=\(String(describing: rawAnswer.frame)); disclosure.hittable=\(rawAnswer.isHittable)
            confirm.exists=\(confirm.exists); confirm.enabled=\(confirm.isEnabled)
            """
            let attachment = XCTAttachment(string: status)
            attachment.name = "raw-answer-disclosure-not-hittable-state"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertTrue(rawAnswer.isHittable)
        rawAnswer.tap()
        XCTAssertTrue(app.staticTexts["Synthetic result for the isolated result-to-paper UI test."].exists)
        waitUntilEnabledWithPaperDiagnostics(confirm)
        confirm.tap()
        let open = app.buttons["agentResultOpenSavedNote"]
        if !open.waitForExistence(timeout: 30) {
            attachResultPaperFailureDiagnostics("saved-note-link-missing")
            XCTFail("the result must be durably appended before the paper link appears")
            return
        }
        XCTAssertTrue(open.isHittable, "saved-note action should be surfaced in the visible result status")
        open.tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["backToShelf"].exists)
        let noteBack = app.buttons["backToAgentResult"]
        XCTAssertEqual(app.buttons.matching(identifier: "backToAgentResult").count, 1,
                       "the nested editor must expose one save-aware return action")
        noteBack.tap()
        XCTAssertTrue(app.navigationBars["预览并添加"].waitForExistence(timeout: 10), "back must return to result confirmation")
        XCTAssertTrue(app.buttons["agentResultOpenSavedNote"].exists)
        app.buttons["agentResultOpenSavedNote"].tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["backToAgentResult"].waitForExistence(timeout: 5))
        let openedPaperState = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(openedPaperState.label.contains("schema=9; flows=1; receipts=1"), openedPaperState.label)

        // Reopen the app against the same isolated files and retry the same completed task.
        // The origin receipt must be found and must not append another flow.
        app.terminate()
        app = makeApp()
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        let beforeRetry = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(beforeRetry.waitForExistence(timeout: 10))
        XCTAssertTrue(beforeRetry.label.contains("schema=9; flows=1; receipts=1"), beforeRetry.label)
        app.buttons["agentResultPreviewToPaper"].tap()
        let retryConfirm = app.buttons["agentResultConfirmAppend"]
        XCTAssertTrue(retryConfirm.waitForExistence(timeout: 8))
        waitUntilEnabledWithPaperDiagnostics(retryConfirm)
        retryConfirm.tap()
        XCTAssertTrue(app.staticTexts["结果已在笔记中"].waitForExistence(timeout: 20))
        let reopen = app.buttons["agentResultOpenSavedNote"]
        XCTAssertTrue(reopen.waitForExistence(timeout: 8))
        XCTAssertTrue(reopen.isHittable, "dedupe result should keep the open action visible")
        reopen.tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        let afterRetry = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(afterRetry.label.contains("schema=9; flows=1; receipts=1"), afterRetry.label)
    }

    func testLegacyAgentMarkdownProfilePreviewCancelAndAcceptPersistsSchema10() throws {
        app = makeApp()
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        app.buttons["agentResultPreviewToPaper"].tap()
        let append = app.buttons["agentResultConfirmAppend"]
        XCTAssertTrue(append.waitForExistence(timeout: 8))
        waitUntilEnabled(append)
        append.tap()
        let open = app.buttons["agentResultOpenSavedNote"]
        XCTAssertTrue(open.waitForExistence(timeout: 30))
        open.tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        let fixtureState = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(fixtureState.waitForExistence(timeout: 8))
        XCTAssertTrue(fixtureState.label.contains("schema=9; flows=1; receipts=1; profile=1"), fixtureState.label)
        XCTAssertTrue(fixtureState.label.contains("sourceExact=true; sourceDigestMatches=true; taskMatches=true"), fixtureState.label)
        XCTAssertTrue(fixtureState.label.contains("anchor=1,16,40"), "the append fixture starts on its explicit final page: \(fixtureState.label)")
        XCTAssertTrue(fixtureState.label.contains("exactAnchor=1,16.000000,40.000000"), fixtureState.label)

        func openTypographySwitch() -> XCUIElement {
            app.buttons["moreActionsButton"].tap()
            app.buttons["管理文字"].tap()
            XCTAssertTrue(app.navigationBars["页面文字"].waitForExistence(timeout: 8))
            let button = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "markdownProfileSwitch-")).firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 8))
            return button
        }

        func directlyOpenPersistedResult(profile: Int) -> XCUIElement {
            let openExisting = app.buttons["agentTaskOpenSavedNote"]
            XCTAssertTrue(openExisting.waitForExistence(timeout: 12),
                          "a durable matching receipt must expose the saved note on task detail")
            XCTAssertTrue(openExisting.isHittable)
            openExisting.tap()
            XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
            let state = app.staticTexts["agentResultPaperFixtureState"]
            XCTAssertTrue(state.waitForExistence(timeout: 8))
            XCTAssertTrue(state.label.contains("schema=10; flows=1; receipts=1; profile=\(profile)"), state.label)
            XCTAssertTrue(state.label.contains("sourceExact=true; sourceDigestMatches=true; taskMatches=true"), state.label)
            XCTAssertTrue(state.label.contains("anchor=1,16,40"), state.label)
            XCTAssertTrue(state.label.contains("exactAnchor=1,16.000000,40.000000"), state.label)
            return state
        }

        var profileSwitch = openTypographySwitch()
        profileSwitch.tap()
        XCTAssertTrue(app.navigationBars["排版预览"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.descendants(matching: .any)["typographyPreviewBefore"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["typographyPreviewAfter"].exists)
        app.buttons["typographyPreviewCancel"].tap()
        XCTAssertTrue(app.navigationBars["页面文字"].waitForExistence(timeout: 8))
        XCTAssertTrue(fixtureState.label.contains("schema=9; flows=1; receipts=1; profile=1"),
                      "cancel must preserve the legacy note bytes/profile: \(fixtureState.label)")

        profileSwitch = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "markdownProfileSwitch-")).firstMatch
        profileSwitch.tap()
        XCTAssertTrue(app.navigationBars["排版预览"].waitForExistence(timeout: 30))
        app.buttons["typographyPreviewAccept"].tap()
        XCTAssertTrue(app.navigationBars["页面文字"].waitForExistence(timeout: 12))
        let upgraded = NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "schema=10; flows=1; receipts=1; profile=2", "sourceDigestMatches=true")
        expectation(for: upgraded, evaluatedWith: fixtureState)
        waitForExpectations(timeout: 15)
        XCTAssertTrue(fixtureState.label.contains("anchor=1,16,40"), fixtureState.label)
        XCTAssertTrue(fixtureState.label.contains("exactAnchor=1,16.000000,40.000000"), fixtureState.label)
        app.terminate()
        app = makeApp()
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        let fixtureAfterProfile2Restart = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(fixtureAfterProfile2Restart.waitForExistence(timeout: 8))
        XCTAssertTrue(fixtureAfterProfile2Restart.label.contains("schema=10; flows=1; receipts=1; profile=2"), fixtureAfterProfile2Restart.label)
        XCTAssertTrue(fixtureAfterProfile2Restart.label.contains("sourceExact=true; sourceDigestMatches=true; taskMatches=true"), fixtureAfterProfile2Restart.label)
        let afterRestart = directlyOpenPersistedResult(profile: 2)

        profileSwitch = openTypographySwitch()
        profileSwitch.tap()
        XCTAssertTrue(app.navigationBars["排版预览"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["typographyPreviewAccept"].label.contains("应用旧版排版"))
        app.buttons["typographyPreviewAccept"].tap()
        XCTAssertTrue(app.navigationBars["页面文字"].waitForExistence(timeout: 12))
        let switchedBack = NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "schema=10; flows=1; receipts=1; profile=1", "sourceDigestMatches=true")
        expectation(for: switchedBack, evaluatedWith: afterRestart)
        waitForExpectations(timeout: 15)
        XCTAssertTrue(afterRestart.label.contains("sourceExact=true; sourceDigestMatches=true; taskMatches=true"), afterRestart.label)
        XCTAssertTrue(afterRestart.label.contains("anchor=1,16,40"), afterRestart.label)
        XCTAssertTrue(afterRestart.label.contains("exactAnchor=1,16.000000,40.000000"), afterRestart.label)

        app.terminate()
        app = makeApp()
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        let fixtureAfterProfile1Restart = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(fixtureAfterProfile1Restart.waitForExistence(timeout: 8))
        XCTAssertTrue(fixtureAfterProfile1Restart.label.contains("schema=10; flows=1; receipts=1; profile=1"), fixtureAfterProfile1Restart.label)
        XCTAssertTrue(fixtureAfterProfile1Restart.label.contains("sourceExact=true; sourceDigestMatches=true; taskMatches=true"), fixtureAfterProfile1Restart.label)
        let afterSwitchBackRestart = directlyOpenPersistedResult(profile: 1)
    }

    func testSyntheticAgentPNGPlacementPreviewConfirmReopenAndDuplicateGuard() throws {
        app = makeApp(image: true)
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        let place = app.buttons["agentImagePlaceArtifact-synthetic-image-1"]
        XCTAssertTrue(place.waitForExistence(timeout: 8))
        place.tap()

        let confirm = app.buttons["agentImagePlacementConfirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 8))
        waitUntilEnabled(confirm)
        let preview = app.descendants(matching: .any)["agentResultPaperViewport"]
        XCTAssertTrue(preview.exists, "the candidate must be rendered on a paper before confirmation")
        let selected = app.staticTexts["agentImagePlacementState"]
        XCTAssertTrue(selected.exists)
        let before = selected.value as? String ?? ""
        XCTAssertTrue(before.contains("page=1;"), before)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "synthetic-agent-image-paper-preview"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        preview.coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.43)).tap()
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", before), object: selected)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 8), .completed,
                       "the preview tap should update the paper-space image placement")
        let chosen = selected.value as? String ?? ""
        XCTAssertTrue(chosen.contains("page=1;"), chosen)
        XCTAssertNotEqual(chosen, before)
        waitUntilEnabled(confirm)
        confirm.tap()

        let openSaved = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "agentTaskOpenSavedImage-agent-image-")).firstMatch
        XCTAssertTrue(openSaved.waitForExistence(timeout: 30), "a saved/reopened image link appears only after durable verification")
        openSaved.tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        let paperState = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(paperState.waitForExistence(timeout: 8))
        XCTAssertTrue(paperState.label.contains("schema=11"), paperState.label)
        XCTAssertTrue(paperState.label.contains("images=1; imageReceipts=1; imageHashMatches=true"), paperState.label)
        let pdfValue = app.staticTexts["agentImagePaperPDFBase64"]
        XCTAssertTrue(pdfValue.waitForExistence(timeout: 8), "the synthetic paper test exports its actual NoteRenderer PDF")
        let pdfBytes = try XCTUnwrap(Data(base64Encoded: pdfValue.label))
        let pdfAttachment = XCTAttachment(data: pdfBytes, uniformTypeIdentifier: "com.adobe.pdf")
        pdfAttachment.name = "synthetic-agent-image-paper-export.pdf"
        pdfAttachment.lifetime = .keepAlways
        add(pdfAttachment)
        let placementParts = chosen.split(separator: ";")
        let expectedX = placementParts.first(where: { $0.hasPrefix("x=") }).map { String($0.dropFirst(2)) } ?? ""
        let expectedY = placementParts.first(where: { $0.hasPrefix("y=") }).map { String($0.dropFirst(2)) } ?? ""
        XCTAssertTrue(paperState.label.contains("imageAnchor=1,\(expectedX),\(expectedY)"), paperState.label)

        app.terminate()
        app = makeApp(image: true)
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        let directLink = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "agentTaskOpenSavedImage-agent-image-")).firstMatch
        XCTAssertTrue(directLink.waitForExistence(timeout: 12), "the exact saved receipt must resolve after relaunch")
        let placeAgain = app.buttons["agentImagePlaceArtifact-synthetic-image-1"]
        XCTAssertTrue(placeAgain.exists)
        XCTAssertFalse(placeAgain.isEnabled, "a receipt already placed in this note must not offer a second insertion")
        directLink.tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        let reopenedState = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(reopenedState.waitForExistence(timeout: 8))
        XCTAssertTrue(reopenedState.label.contains("schema=11; flows=0; receipts=0; profile=0"), reopenedState.label)
        XCTAssertTrue(reopenedState.label.contains("images=1; imageReceipts=1; imageHashMatches=true"), reopenedState.label)
    }

    func testSyntheticTaskPointPreviewConfirmAndReopenPreservesTappedAnchor() throws {
        app = makeApp()
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        app.buttons["agentResultPreviewToPaper"].tap()
        let appendMode = app.buttons["agentResultAppendMode"]
        let pointMode = app.buttons["agentResultPointPlacementToggle"]
        XCTAssertTrue(appendMode.waitForExistence(timeout: 10))
        XCTAssertTrue(pointMode.waitForExistence(timeout: 10))
        XCTAssertTrue(appendMode.isSelected, "fixture begins in compatibility append mode")
        XCTAssertFalse(pointMode.isSelected)
        pointMode.tap()
        XCTAssertTrue(pointMode.isSelected, "the explicit point mode action must select point placement")
        XCTAssertFalse(appendMode.isSelected)
        let placementState = app.staticTexts["agentResultPlacementState"]
        XCTAssertTrue(placementState.waitForExistence(timeout: 8))
        XCTAssertEqual(placementState.label, "已选第 1 页的位置")
        XCTAssertTrue((placementState.value as? String)?.contains("point=1; page=0;") == true,
                      "a11y diagnostic should expose selected page and coordinates: \(String(describing: placementState.value))")
        waitUntilEnabledWithPaperDiagnostics(app.buttons["agentResultConfirmAppend"])
        let paper = app.descendants(matching: .any)["agentResultPaperViewport"]
        XCTAssertTrue(paper.waitForExistence(timeout: 20), "point mode must show a raster paper candidate")
        for _ in 0..<6 where !paper.isHittable { app.swipeUp() }
        XCTAssertTrue(paper.isHittable)
        let zoomIn = app.buttons["agentResultPaperZoomIn"]
        XCTAssertTrue(zoomIn.waitForExistence(timeout: 8))
        zoomIn.tap()
        let dragStart = paper.coordinate(withNormalizedOffset: CGVector(dx: 0.50, dy: 0.80))
        let dragEnd = paper.coordinate(withNormalizedOffset: CGVector(dx: 0.50, dy: 0.64))
        dragStart.press(forDuration: 0.1, thenDragTo: dragEnd)
        let priorCoordinates = placementState.value as? String ?? ""
        let pageFit = min((paper.frame.width - 32) / 768, (paper.frame.height - 24) / 1086)
        let displayedPageWidth = 768 * pageFit * 1.35
        let paperInteriorX = min(0.95, max(0.05, 0.5 + 0.10 * displayedPageWidth / paper.frame.width))
        paper.coordinate(withNormalizedOffset: CGVector(dx: paperInteriorX, dy: 0.50)).tap()
        let confirm = app.buttons["agentResultConfirmAppend"]
        let changedPoint = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", priorCoordinates), object: placementState)
        let pointChangeResult = XCTWaiter.wait(for: [changedPoint], timeout: 8)
        if pointChangeResult != .completed {
            attachResultPaperFailureDiagnostics("agent-result-paper-point-not-changed")
            let pointState = """
            waiter=\(pointChangeResult)
            placement.label=\(placementState.label)
            placement.value=\(String(describing: placementState.value))
            viewport.label=\(paper.label)
            viewport.value=\(String(describing: paper.value))
            viewport.frame=\(String(describing: paper.frame))
            confirm.exists=\(confirm.exists); confirm.enabled=\(confirm.isEnabled)
            """
            let stateAttachment = XCTAttachment(string: pointState)
            stateAttachment.name = "agent-result-paper-point-not-changed-state"
            stateAttachment.lifetime = .keepAlways
            add(stateAttachment)
        }
        XCTAssertEqual(pointChangeResult, .completed)
        let selectedPlacement = placementState.value as? String ?? ""
        XCTAssertTrue(selectedPlacement.contains("point=1; page=0;"), selectedPlacement)
        XCTAssertFalse(selectedPlacement.contains("x=24.000; y=60.000"), "the paper tap must change the selected paper position: \(selectedPlacement)")
        let pointScreenshot = XCTAttachment(screenshot: app.screenshot())
        pointScreenshot.name = "agent-result-paper-zoom-pan-point-selection-c37"
        pointScreenshot.lifetime = .keepAlways
        add(pointScreenshot)
        waitUntilEnabled(confirm)
        confirm.tap()
        let open = app.buttons["agentResultOpenSavedNote"]
        XCTAssertTrue(open.waitForExistence(timeout: 30))
        open.tap()
        XCTAssertTrue(app.navigationBars["Synthetic result-to-paper note"].waitForExistence(timeout: 15))
        let state = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(state.waitForExistence(timeout: 8))
        XCTAssertTrue(state.label.contains("schema=9; flows=1; receipts=1"), state.label)
        XCTAssertTrue(state.label.contains("sourceExact=true; sourceDigestMatches=true; taskMatches=true"), state.label)
        XCTAssertTrue(state.label.contains("anchor=0,"), "point placement must stay on the selected first page: \(state.label)")
        XCTAssertFalse(state.label.contains("anchor=1,16,40"), "point placement must not silently fall back to append")
        let x = selectedPlacement.split(separator: ";").first(where: { $0.contains("x=") })
            .flatMap { Double($0.split(separator: "=").last ?? "") }
        let y = selectedPlacement.split(separator: ";").first(where: { $0.contains("y=") })
            .flatMap { Double($0.split(separator: "=").last ?? "") }
        if let x, let y {
            XCTAssertTrue(abs(x - 384) > 20 || abs(y - 543) > 20,
                          "the viewport flow must persist a known non-center paper selection: \(selectedPlacement)")
            let exactX = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), x)
            let exactY = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), y)
            XCTAssertTrue(state.label.contains("exactAnchor=0,\(exactX),\(exactY)"),
                          "persisted anchor must equal the clicked paper coordinates: \(state.label); \(selectedPlacement)")
        } else { XCTFail("Could not parse confirmed point from \(selectedPlacement)") }
    }

    func testStalePreviewCannotAppendAfterSyntheticTargetChanges() throws {
        app = makeApp(stale: true)
        app.launch()
        XCTAssertTrue(app.navigationBars["Synthetic completed task"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["agentResultPreviewToPaper"].waitForExistence(timeout: 10))
        app.buttons["agentResultPreviewToPaper"].tap()

        let confirm = app.buttons["agentResultConfirmAppend"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 8))
        // Let the captured candidate become ready before injecting the deliberate
        // concurrent edit; this test is for the save-time CAS rejection, not a
        // mutation racing initial preview compilation.
        waitUntilEnabledWithPaperDiagnostics(confirm)
        let change = app.buttons["agentResultUITestChangeTarget"]
        XCTAssertTrue(change.waitForExistence(timeout: 8))
        XCTAssertTrue(change.isHittable)
        change.tap()
        XCTAssertTrue(confirm.exists)
        XCTAssertTrue(confirm.isEnabled, "the ready candidate should remain confirmable so save exercises CAS")
        waitUntilEnabled(confirm)
        confirm.tap()

        let error = app.staticTexts["agentResultAppendError"]
        if !error.waitForExistence(timeout: 20) {
            attachResultPaperFailureDiagnostics("stale-preview-error-missing")
            let state = app.staticTexts["agentResultPaperFixtureState"]
            let status = """
            confirm.exists=\(confirm.exists); confirm.enabled=\(confirm.isEnabled)
            error.exists=\(error.exists); error.label=\(error.label); error.value=\(String(describing: error.value))
            fixture.exists=\(state.exists); fixture.label=\(state.label)
            """
            let attachment = XCTAttachment(string: status)
            attachment.name = "stale-preview-error-missing-state"
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTFail("stale preview must return an error instead of overwriting the newer target")
            return
        }
        XCTAssertTrue(error.isHittable, "stale-preview feedback should be visible without scrolling past the form")
        XCTAssertFalse(app.buttons["agentResultOpenSavedNote"].exists)
        app.buttons["关闭"].tap()
        let state = app.staticTexts["agentResultPaperFixtureState"]
        XCTAssertTrue(state.label.contains("schema=8; flows=0; receipts=0"), state.label)
    }

    private func attachResultPaperFailureDiagnostics(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "\(name)-screenshot"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "\(name)-accessibility-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }

    private func waitUntilEnabledWithPaperDiagnostics(_ element: XCUIElement, timeout: TimeInterval = 30,
                                                       file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: element)
        let result = XCTWaiter.wait(for: [expectation], timeout: timeout)
        if result != .completed {
            let errorElement = app.staticTexts["agentResultAppendError"]
            let placementElement = app.staticTexts["agentResultPlacementState"]
            let paperElement = app.descendants(matching: .any)["agentResultPaperViewport"]
            let diagnosticElement = app.descendants(matching: .any)["agentResultPaperPreviewDiagnostic"]
            let errorStatus = errorElement.exists
                ? "label=\(errorElement.label);value=\(String(describing: errorElement.value))" : "absent"
            let placementStatus = placementElement.exists
                ? "label=\(placementElement.label);value=\(String(describing: placementElement.value))" : "absent"
            let paperStatus = paperElement.exists
                ? "hittable=\(paperElement.isHittable);frame=\(String(describing: paperElement.frame))" : "absent"
            let diagnosticStatus = diagnosticElement.exists
                ? "label=\(diagnosticElement.label);value=\(String(describing: diagnosticElement.value))" : "absent"
            let status = """
            result=\(result); confirm.exists=\(element.exists); confirm.enabled=\(element.isEnabled)
            appendError=\(errorStatus)
            placement=\(placementStatus)
            paper=\(paperStatus)
            previewDiagnostic=\(diagnosticStatus)
            """
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "agent-result-paper-preview-not-ready-screenshot"
            screenshot.lifetime = .keepAlways
            add(screenshot)
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.name = "agent-result-paper-preview-not-ready-accessibility-hierarchy"
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
            let state = XCTAttachment(string: status)
            state.name = "agent-result-paper-preview-not-ready-state"
            state.lifetime = .keepAlways
            add(state)
        }
        XCTAssertEqual(result, .completed, "paper candidate should become ready before confirmation", file: file, line: line)
    }

    private func waitUntilEnabled(_ element: XCUIElement, timeout: TimeInterval = 30,
                                  file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed,
                       "paper candidate should become ready before confirmation", file: file, line: line)
    }

    private func makeApp(cleanup: Bool = false, stale: Bool = false, image: Bool = false) -> XCUIApplication {
        let result = XCUIApplication()
        result.launchArguments = ["--uitesting", "--uitesting-agent-result-paper",
                                 "--agent-result-paper-fixture-id", fixtureID]
        if cleanup { result.launchArguments.append("--agent-result-paper-cleanup") }
        if stale { result.launchArguments.append("--uitesting-agent-result-paper-stale") }
        if image { result.launchArguments.append("--uitesting-agent-image-paper") }
        return result
    }
}

final class WKLoadProbeUITests: XCTestCase {
    func testMinimalLocalPageThenBundledReaderNavigation() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--uitesting", "--uitesting-wk-load-probe"]
        app.launch()
        defer { app.terminate() }

        let result = app.staticTexts["wkLoadProbeResult"]
        XCTAssertTrue(result.waitForExistence(timeout: 10), "the isolated WK probe host should appear")
        guard result.exists else { return }
        let terminal = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "probe=complete", "probe=error"),
            object: result)
        let terminalWait = XCTWaiter.wait(for: [terminal], timeout: 70)
        let report = (result.value as? String) ?? result.label
        let reportAttachment = XCTAttachment(string: report)
        reportAttachment.name = "wk-load-probe-results-no-paths-or-content"
        reportAttachment.lifetime = .keepAlways
        add(reportAttachment)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "wk-load-probe-final-state"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        XCTAssertEqual(terminalWait, .completed, "bounded WK probe should reach a terminal report")
        XCTAssertTrue(report.contains("minimal=finish"), "the minimal local page must reach didFinish; report=\(report)")
        XCTAssertTrue(report.contains("reader=finish"), "the bundled reader must reach didFinish; report=\(report)")
    }
}
