#!/usr/bin/env python3
"""Mock OpenAI-compatible provider for integration testing.

Runs a tiny HTTP server that answers POST /v1/chat/completions with a valid
agent action, so the Android app's provider path can be exercised end to end
without a live paid API.

Usage:
    python tools/mock_provider.py --port 8099 [--delay-ms 250] [--fail 0]

From the Android emulator the host is reachable at http://10.0.2.2:<port>.

Options:
    --delay-ms   artificial latency, to make timeouts testable
    --fail       return this HTTP status instead of 200 (to test error paths)
    --log        print each request's shape (model, message count, has image)
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"delay_ms": 0, "fail": 0, "log": False, "requests": 0}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quieter default logging
        if STATE["log"]:
            print("[mock] " + (fmt % args))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        STATE["requests"] += 1

        if STATE["log"]:
            try:
                body = json.loads(raw)
                msgs = body.get("messages", [])
                has_image = any(
                    "image_url" in json.dumps(m) for m in msgs
                )
                print(
                    f"[mock] #{STATE['requests']} {self.path} model={body.get('model')!r} "
                    f"messages={len(msgs)} image={has_image}"
                )
            except Exception as exc:  # pragma: no cover - diagnostics only
                print(f"[mock] unparsable body: {exc}")

        if STATE["delay_ms"]:
            time.sleep(STATE["delay_ms"] / 1000.0)

        if STATE["fail"]:
            payload = json.dumps({"error": {"message": "mock failure"}}).encode()
            self.send_response(STATE["fail"])
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        # A valid agent action, in the schema the Rust parser accepts.
        answer = {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": "mock-model",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": '{"type":"done"}',
                    },
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }
        payload = json.dumps(answer).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--delay-ms", type=int, default=0)
    ap.add_argument("--fail", type=int, default=0)
    ap.add_argument("--log", action="store_true")
    args = ap.parse_args()

    STATE["delay_ms"] = args.delay_ms
    STATE["fail"] = args.fail
    STATE["log"] = args.log

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"[mock] OpenAI-compatible provider on :{args.port} (delay={args.delay_ms}ms fail={args.fail})")
    print(f"[mock] emulator reaches the host at http://10.0.2.2:{args.port}/v1")
    server.serve_forever()


if __name__ == "__main__":
    main()
