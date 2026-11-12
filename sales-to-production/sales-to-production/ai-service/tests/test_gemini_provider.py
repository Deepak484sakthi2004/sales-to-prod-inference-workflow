"""The real google-genai SDK against a local server that speaks the Gemini REST API: checks the exact request the
service sends (forced function call, JSON Schema parameters, no automatic calling) and how replies are read."""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from app.config import Settings
from app.gemini import FunctionSpec, GeminiProvider, ModelUnavailable


class FakeGemini:
    def __init__(self, reply: dict, status: int = 200):
        self.reply, self.status, self.requests = reply, status, []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                outer.requests.append({"path": self.path, "body": body, "key": self.headers.get("x-goog-api-key")})
                data = json.dumps(outer.reply).encode()
                self.send_response(outer.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *a):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()


def call_reply(name: str, args: dict) -> dict:
    return {"candidates": [{"content": {"role": "model", "parts": [{"functionCall": {"name": name, "args": args}}]},
                            "finishReason": "STOP"}], "modelVersion": "gemini-3.8-flash"}


@pytest.fixture
def gemini():
    servers = []

    def make(reply, status=200):
        s = FakeGemini(reply, status)
        servers.append(s)
        return s
    yield make
    for s in servers:
        s.close()


def provider(url: str) -> GeminiProvider:
    return GeminiProvider(Settings(api_key="test-key", model="gemini-3.8-flash", base_url=url, timeout_ms=5000,
                                   thinking_level="LOW"))


SPEC = FunctionSpec("record_extraction", "Record what the customer asked",
                    {"type": "object", "properties": {"intent": {"type": "object"}}, "required": ["intent"]})


def test_a_forced_function_call_is_sent_and_read(gemini):
    g = gemini(call_reply("record_extraction", {"intent": {"value": "ENQUIRY", "evidence": "We need"}, "lines": []}))
    call = provider(g.url).call_function(system="You read mail.", user="<email>hi</email>", functions=[SPEC],
                                         allowed=["record_extraction"])
    assert call.name == "record_extraction" and call.args["intent"]["value"] == "ENQUIRY"
    assert call.model == "gemini-3.8-flash"
    req = g.requests[0]
    assert req["path"].endswith("/models/gemini-3.8-flash:generateContent")
    assert req["key"] == "test-key"
    body = req["body"]
    decl = body["tools"][0]["functionDeclarations"][0]
    # The SDK sends this field in proto snake_case; the Gemini REST API accepts either spelling.
    schema = decl.get("parametersJsonSchema") or decl.get("parameters_json_schema")
    assert decl["name"] == "record_extraction" and schema["required"] == ["intent"]
    fcc = body["toolConfig"]["functionCallingConfig"]
    assert fcc["mode"] == "ANY" and fcc["allowedFunctionNames"] == ["record_extraction"]
    assert body["systemInstruction"]["parts"][0]["text"] == "You read mail."
    thinking = body["generationConfig"]["thinkingConfig"]
    assert (thinking.get("thinkingLevel") or thinking.get("thinking_level")) == "LOW"
    assert "temperature" not in body["generationConfig"]


def test_a_refused_request_is_reported_as_unavailable(gemini):
    g = gemini({"error": {"code": 400, "message": "API key not valid", "status": "INVALID_ARGUMENT"}}, status=400)
    with pytest.raises(ModelUnavailable, match="API key not valid"):
        provider(g.url).call_function(system="s", user="u", functions=[SPEC], allowed=["record_extraction"])
