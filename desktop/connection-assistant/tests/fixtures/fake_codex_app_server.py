#!/usr/bin/env python3
"""Deterministic fake child for codex_transport tests; never touches a network."""

from __future__ import annotations

import json
import sys
import threading
import time


def read() -> dict:
    line = sys.stdin.buffer.readline()
    if not line:
        raise EOFError
    value = json.loads(line)
    if not isinstance(value, dict):
        raise ValueError("object required")
    return value


def send(value: dict) -> None:
    sys.stdout.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def handshake() -> None:
    initialize = read()
    if initialize.get("method") != "initialize":
        raise ValueError("initialize required")
    send({"id": initialize["id"], "result": {"platformFamily": "fixture"}})
    initialized = read()
    if initialized.get("method") != "initialized" or "id" in initialized:
        raise ValueError("initialized notification required")


def normal() -> None:
    handshake()
    first = read()
    second = read()
    send({"method": "turn/started", "params": {"turn": {"id": "turn-fixture"}}})
    send({"id": second["id"], "result": {"echo": second.get("params")}})
    send({"id": first["id"], "result": {"echo": first.get("params")}})


def reverse() -> None:
    handshake()
    trigger = read()
    send({"id": "approval-1", "method": "item/commandExecution/requestApproval",
          "params": {"threadId": "thread-1", "turnId": "turn-1", "itemId": "item-1",
                     "command": ["echo", "fixture"]}})
    received: list[dict] = []

    def wait_reply() -> None:
        try:
            received.append(read())
        except EOFError:
            pass

    reader = threading.Thread(target=wait_reply, daemon=True)
    reader.start()
    reader.join(0.2)
    send({"method": "fixture/approvalHeld" if not received
          else "fixture/approvalAutoReplied", "params": {}})
    if not received:
        reader.join(5)
    if not received:
        raise TimeoutError("approval reply missing")
    send({"id": trigger["id"], "result": {"approvalReply": received[0]}})
    messages_before_report: list[dict] = []
    while True:
        try:
            message = read()
        except EOFError:
            return
        if message.get("method") == "fixture/report":
            send({"id": message["id"],
                  "result": {"messagesBeforeReport": messages_before_report}})
        else:
            messages_before_report.append(message)


def oversize() -> None:
    handshake()
    read()
    sys.stdout.buffer.write(b"{" + b"x" * 1024 + b"}\n")
    sys.stdout.buffer.flush()
    time.sleep(1)


def exit_while_pending() -> None:
    handshake()
    read()
    raise SystemExit(7)


def hang() -> None:
    handshake()
    while True:
        try:
            read()
        except EOFError:
            time.sleep(60)


def notification_overflow() -> None:
    handshake()
    for index in range(3):
        send({"method": "fixture/event", "params": {"index": index}})
    time.sleep(1)


def notification_byte_overflow() -> None:
    handshake()
    for index in range(2):
        send({"method": "fixture/largeEvent",
              "params": {"index": index, "text": "x" * 400}})
    time.sleep(1)


def late_response() -> None:
    handshake()
    cancelled = read()
    send({"method": "fixture/requestReceived", "params": {}})
    time.sleep(0.2)
    send({"id": cancelled["id"], "result": {"late": True}})
    current = read()
    send({"id": current["id"], "result": {"current": True}})


def malformed_response() -> None:
    handshake()
    request = read()
    send({"id": request["id"], "result": {}, "error": {"code": 1, "message": "both"}})
    time.sleep(1)


def blocked_stdin() -> None:
    handshake()
    # Keep stdout open while deliberately never reading the next request. A
    # request larger than the OS pipe buffer must block the transport writer.
    time.sleep(60)


def delayed_stdin() -> None:
    handshake()
    time.sleep(0.4)
    cancelled_seen = 0
    while True:
        request = read()
        method = request.get("method")
        if method == "fixture/cancelled":
            cancelled_seen += 1
        elif method == "fixture/barrier":
            send({"id": request["id"],
                  "result": {"cancelledSeen": cancelled_seen}})
            return
        elif "id" in request:
            send({"id": request["id"], "result": {"received": method}})


def count_requests() -> None:
    handshake()
    methods: list[str] = []
    while True:
        request = read()
        methods.append(request.get("method"))
        if request.get("method") == "fixture/report":
            send({"id": request["id"], "result": {"methods": methods}})
            return


def nonfinite(token: str) -> None:
    handshake()
    payload = ("{\"method\":\"fixture/nonfinite\","
               "\"params\":{\"value\":" + token + "}}\n")
    sys.stdout.buffer.write(payload.encode("ascii"))
    sys.stdout.buffer.flush()
    time.sleep(1)


MODES = {
    "normal": normal,
    "reverse": reverse,
    "oversize": oversize,
    "exit": exit_while_pending,
    "hang": hang,
    "notification-overflow": notification_overflow,
    "notification-byte-overflow": notification_byte_overflow,
    "late-response": late_response,
    "malformed-response": malformed_response,
    "blocked-stdin": blocked_stdin,
    "delayed-stdin": delayed_stdin,
    "count-requests": count_requests,
    "nonfinite-nan": lambda: nonfinite("NaN"),
    "nonfinite-infinity": lambda: nonfinite("Infinity"),
    "nonfinite-overflow": lambda: nonfinite("1e999"),
}


if __name__ == "__main__":
    MODES[sys.argv[1]]()
