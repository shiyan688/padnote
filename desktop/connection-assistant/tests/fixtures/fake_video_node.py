#!/usr/bin/env python3
"""Small executable stand-in for the fixed Node/tsx worker command."""

import hashlib
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path


if sys.argv[1:] == ["--version"]:
    print(os.environ.get("FAKE_NODE_VERSION", "v22.22.0"))
    raise SystemExit(0)

argv = sys.argv[1:]
task_root = Path(argv[4])
action = argv[3]
mode_file = task_root / ".fake-worker-mode"
mode = mode_file.read_text() if mode_file.exists() else "valid"
worker_id_path = task_root / ".fake-worker-id"
worker_id = worker_id_path.read_text() if worker_id_path.exists() else "worker-id"
(task_root / ".fake-worker-report.json").write_text(json.dumps({
    "argv": argv,
    "cwd": os.getcwd(),
    "environment": sorted(os.environ),
}))

if mode == "nonzero" or (mode == "mutation-nonzero" and action != "inspect"):
    print("secret stderr should never escape", file=sys.stderr)
    raise SystemExit(9)
if mode == "missing-state" and action == "inspect":
    print("task state is missing", file=sys.stderr)
    raise SystemExit(4)
if mode == "malformed-inspection" and action == "inspect":
    print(json.dumps({"protocol_version": 1, "task_id": worker_id,
                      "status": [], "phase": {}, "event_cursor": 2}))
    raise SystemExit(0)
if mode == "invalid" or (mode == "mutation-invalid" and action != "inspect"):
    print("not-json")
    raise SystemExit(0)
if mode == "multiple" or (mode == "mutation-multiple" and action != "inspect"):
    print('{}\n{}')
    raise SystemExit(0)
if mode == "oversized-stdout" or (mode == "mutation-oversized-stdout" and action != "inspect"):
    sys.stdout.write("x" * (160 * 1024))
    raise SystemExit(0)
if mode == "oversized-stderr" or (mode == "mutation-oversized-stderr" and action != "inspect"):
    sys.stderr.write("x" * (160 * 1024))
    raise SystemExit(0)
if mode == "slow" or (mode == "mutation-slow" and action != "inspect"):
    (task_root / ".fake-worker-parent-pid").write_text(str(os.getpid()))
    time.sleep(30)
if mode == "ignore-term-tree":
    child_code = "import time; time.sleep(30)"
    if os.name != "nt":
        child_code = "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)"
    child = subprocess.Popen([sys.executable, "-c", child_code])
    (task_root / ".fake-worker-child-pid").write_text(str(child.pid))
    if os.name != "nt":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    time.sleep(30)
if mode == "success-with-child":
    child = subprocess.Popen([
        sys.executable, "-c",
        "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)",
    ], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (task_root / ".fake-worker-child-pid").write_text(str(child.pid))

def parse_options(raw):
    options = {}
    index = 0
    while index < len(raw):
        key = raw[index]
        if key.startswith("--") and index + 1 < len(raw) and not raw[index + 1].startswith("--"):
            options[key] = raw[index + 1]
            index += 2
        else:
            index += 1
    return options


if action == "revise-operation":
    # Reading to EOF is itself the assertion that the adapter closed stdin: a
    # pipe left open would block here until the caller's deadline instead.
    feedback = sys.stdin.buffer.read()
    (task_root / ".fake-worker-stdin.bin").write_bytes(feedback)
    options = parse_options(argv[5:])
    operation_id = options["--operation-id"]
    value = {
        "schema_version": 1,
        "operation_id": operation_id,
        "attempt_id": options["--attempt-id"],
        "task_id": options["--task-id"],
        "action": "revise",
        "payload_digest": options["--payload-digest"],
        "source_snapshot_digest": options["--source-snapshot-sha256"],
        "request_sha256": options["--request-sha256"],
        "input_event_cursor": int(options["--event-cursor"]),
        "revision": int(options["--revision"]),
        "review_sha256": options["--review-sha256"],
        "lesson_ir_sha256": options["--lesson-ir-sha256"],
        # The digest of the bytes that really arrived, never the --feedback-sha256
        # the caller sent, so a broken or empty stdin write fails the receipt.
        "feedback_sha256": hashlib.sha256(feedback).hexdigest(),
        "candidate_path": f"work/revise/{operation_id}/candidate-lesson-ir.json",
    }
    if mode == "revise-extra-field":
        value["unsafe"] = "extra"
    elif mode == "revise-wrong-candidate":
        value["candidate_path"] = f"work/revise/{operation_id}/other.json"
    elif mode == "revise-wrong-cursor":
        value["input_event_cursor"] = 1
    elif mode == "revise-wrong-operation":
        value["operation_id"] = "00000000-0000-4000-8000-000000000000"
    elif mode == "revise-float-revision":
        # 2.0 compares equal to 2, so only an explicit int check rejects this.
        value["revision"] = 2.0
    elif mode == "revise-float-cursor":
        value["input_event_cursor"] = 8.0
    elif mode == "revise-bool-schema":
        value["schema_version"] = True
    elif mode == "revise-echo-caller-digest":
        value["feedback_sha256"] = options["--feedback-sha256"]
    print(json.dumps(value))
    raise SystemExit(0)


def make_review(worker_task_id, scene_count=1, narration_length=24, preview_size=1024):
    scenes = []
    for index in range(scene_count):
        scene_id = "intro" if scene_count == 1 else f"scene-{index:02d}"
        scenes.append({
            "id": scene_id,
            "learning_objective": "Explain the core idea",
            "narration": "n" * narration_length,
            "screen_text": ["Key point"],
            "visual_kind": "quantity_change",
            "preview": {
                "path": f"storyboard-{scene_id}.png",
                "media_type": "image/png",
                "size_bytes": preview_size,
                "sha256": "c" * 64,
                "width": 1920,
                "height": 1080,
            },
        })
    return {
        "protocol_version": 1,
        "task_id": worker_task_id,
        "status": "awaiting_storyboard_review",
        "event_cursor": 8,
        "revision": 2,
        "review_sha256": "b" * 64,
        "lesson_ir_sha256": "a" * 64,
        "episode": {
            "title": "A Short Lesson",
            "audience": "Students",
            "learning_goal": "Understand the key idea",
            "language": "zh-CN",
        },
        "scenes": scenes,
    }

if action == "review":
    review_id = "different-worker-id" if mode == "review-wrong-id" else worker_id
    count = 61 if mode == "review-too-many-scenes" else 17 if mode == "review-total-too-large" else 60 if mode == "review-large" else 1
    narration_length = 3000 if mode == "review-large" else 24
    preview_size = 8 * 1024 * 1024 if mode == "review-total-too-large" else 1024
    value = make_review(review_id, count, narration_length, preview_size)
    if mode == "review-extra-field":
        value["unsafe"] = "extra"
    elif mode == "review-bad-path":
        value["scenes"][0]["preview"]["path"] = "../escape.png"
    elif mode == "review-bad-dimensions":
        value["scenes"][0]["preview"]["width"] = 4097
    elif mode == "review-bad-scene-id":
        value["scenes"][0]["id"] = "Invalid_ID"
    elif mode == "review-oversized-stdout":
        sys.stdout.write("x" * (2 * 1024 * 1024 + 1))
        raise SystemExit(0)
    print(json.dumps(value, separators=(",", ":")))
    raise SystemExit(0)
elif action == "inspect":
    value = {
        "protocol_version": 1,
        "task_id": worker_id,
        "status": "awaiting_storyboard_review",
        "phase": "awaiting_approval",
        "event_cursor": 5,
        "revision": 2,
        "lesson_ir_sha256": "a" * 64,
        "review_sha256": "b" * 64,
    }
else:
    value = {
        "task_id": worker_id,
        "status": "awaiting_storyboard_review" if action == "storyboard" else "approved",
        "phase": "awaiting_approval" if action == "storyboard" else "approval_pending",
        "event_cursor": 6,
    }
print(json.dumps(value))
