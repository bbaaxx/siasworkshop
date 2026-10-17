"""Model leg: minimal OpenAI-compatible chat client (stdlib urllib) + per-model profiles.

Failure taxonomy mirrors LlmClient.LlmResult: every failure collapses into a machine-ish
string (http_<code>, timeout, connect_error...) so callers can count and fall back.
Never sends response_format (LM Studio/llama.cpp rejects json_object — SEQ-011).
"""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


@dataclass
class ModelReply:
    ok: bool
    content: str | None = None                 # text content (prompt-mode answers)
    tool_calls: list[dict] | None = None       # [{"id", "name", "args"}]
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    latency_ms: int = 0
    error: str | None = None                   # machine-ish failure reason


class OpenAiChat:
    """One-shot stateless chat client; a fresh instance per request is fine (no sessions)."""

    def __init__(self, endpoint: str, api_key: str | None, timeout_seconds: int = 120):
        # OpenRouter 404s double-slash paths (SEQ-014): strip trailing slashes.
        self.endpoint = endpoint.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout_seconds

    def chat(self, model: str, messages: list[dict], tools: list[dict] | None = None,
             temperature: float = 0.3, max_tokens: int = 1024) -> ModelReply:
        body = {"model": model, "temperature": temperature, "max_tokens": max_tokens,
                "messages": messages}
        if tools is not None:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        request = urllib.request.Request(
            self.endpoint + "/chat/completions",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST")
        if self.api_key:
            request.add_header("Authorization", "Bearer " + self.api_key)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            return ModelReply(ok=False, error=f"http_{e.code}",
                              latency_ms=int((time.monotonic() - started) * 1000))
        except TimeoutError:
            return ModelReply(ok=False, error="timeout",
                              latency_ms=int((time.monotonic() - started) * 1000))
        except urllib.error.URLError as e:
            reason = e.reason.__class__.__name__ if hasattr(e.reason, "__class__") else "url_error"
            return ModelReply(ok=False, error=f"connect_{reason}",
                              latency_ms=int((time.monotonic() - started) * 1000))
        except (ValueError, OSError) as e:
            return ModelReply(ok=False, error=e.__class__.__name__,
                              latency_ms=int((time.monotonic() - started) * 1000))
        latency_ms = int((time.monotonic() - started) * 1000)
        try:
            choice = payload["choices"][0]
            message = choice["message"]
            usage = payload.get("usage") or {}
            tool_calls = None
            calls = message.get("tool_calls") or []
            if calls:
                tool_calls = []
            for call in calls:
                fn = call["function"]
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except ValueError:
                    return ModelReply(ok=False, error=f"bad_tool_args:{fn.get('name')}",
                                      latency_ms=latency_ms)
                tool_calls.append({"id": call.get("id"), "name": fn["name"], "args": args})
            content = message.get("content")
            if tool_calls is None and (content is None or content == ""):
                # e.g. reasoning model that burned the whole budget (finish_reason=length)
                finish = choice.get("finish_reason")
                return ModelReply(ok=False, error=f"empty_content:{finish}",
                                  prompt_tokens=usage.get("prompt_tokens"),
                                  completion_tokens=usage.get("completion_tokens"),
                                  latency_ms=latency_ms)
            return ModelReply(ok=True, content=content, tool_calls=tool_calls,
                              prompt_tokens=usage.get("prompt_tokens"),
                              completion_tokens=usage.get("completion_tokens"),
                              latency_ms=latency_ms)
        except (KeyError, IndexError, TypeError) as e:
            return ModelReply(ok=False, error=f"missing_fields:{e.__class__.__name__}",
                              latency_ms=latency_ms)


# --- per-model profiles (D8: model variance lives here) ------------------------

PROFILES: dict[str, dict] = {
    "prism-ml/bonsai-27b": {
        "mode": "prompt",          # llama.cpp local: prompt-constrained JSON, not native tool calling
        "max_tokens": 1024,        # reasoning burns 500-900 hidden tokens (SEQ-011)
        "temperature": 0.3,
        "timeout_seconds": 120,
    },
    "~deepseek/deepseek-pro-latest": {
        "mode": "native",          # OpenRouter cloud: reliable tool calling, second-fast
        "max_tokens": 1024,
        "temperature": 0.3,
        "timeout_seconds": 120,
    },
    "default": {
        "mode": "prompt",
        "max_tokens": 1024,
        "temperature": 0.3,
        "timeout_seconds": 120,
    },
}


def profile_for(model: str) -> dict:
    if model in PROFILES:
        return PROFILES[model]
    for prefix, profile in PROFILES.items():
        if prefix != "default" and model.startswith(prefix.rstrip("-latest")):
            return profile
    return PROFILES["default"]


class FakeModel:
    """Replay-mode model: serves scripted raw replies, no network. The gametest analog.

    Each script entry: {"name", "content"? , "tool_call"?: {"name", "arguments"},
                       "modes"?: [...], "expect_tool"?, "expect_decision"?, "expect_exec"?}
    {{build_key}} / {{player}} placeholders are substituted from the scenario.
    """

    def __init__(self, script: list[dict], scenario: dict):
        self._script = [s for s in script]
        self._scenario = scenario
        self._cursor = 0
        self.calls: list[dict] = []

    def _fill(self, text: str) -> str:
        build_key = next((t["key"] for t in self._scenario.get("open_tasks", [])
                          if t.get("type") == "BUILD"), "")
        return (text.replace("{{build_key}}", build_key)
                    .replace("{{player}}", self._scenario.get("player_name", "Dev")))

    def chat(self, model: str, messages: list[dict], tools: list[dict] | None = None,
             temperature: float = 0.3, max_tokens: int = 1024) -> ModelReply:
        self.calls.append({"n_messages": len(messages), "has_tools": tools is not None})
        applicable = [s for s in self._script if not s.get("modes") or model in s["modes"]]
        if self._cursor >= len(applicable):
            entry = applicable[-1]
        else:
            entry = applicable[self._cursor]
            self._cursor += 1
        time.sleep(0.01)
        if "tool_call" in entry:
            call = entry["tool_call"]
            return ModelReply(ok=True, content=None, tool_calls=[{
                "id": "call_replay", "name": call["name"],
                "args": json.loads(self._fill(json.dumps(call["arguments"])))}],
                prompt_tokens=100, completion_tokens=20, latency_ms=10)
        return ModelReply(ok=True, content=self._fill(entry.get("content", "")),
                          prompt_tokens=100, completion_tokens=20, latency_ms=10)
