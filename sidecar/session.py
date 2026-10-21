"""Decision session driver (S0): one decision through the mock mod, in one of three modes.

- v1         : LlmPrompts replica, {"action","args"} contract, no tool catalog (baseline)
- mcp-prompt : catalog rendered into the prompt, {"tool","args"} contract (local-model path)
- mcp-native : catalog sent as native function-calling tools (cloud-model path)

All modes observe through the same MCP pipe, validate against the same catalog, and
execute through the same tools/call boundary — so measured differences attribute to the
model-facing surface, not the plumbing.
"""
from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass, field, asdict

import catalog
from catalog import by_wire, check_args, first_json_object, render_native, render_prompt, render_v1_user, V1_SYSTEM
from mcp import McpClient


@dataclass
class Decision:
    mode: str
    tool: str | None = None
    args: dict | None = None
    decision_outcome: str = "ok"       # ok | malformed_json | unknown_tool | bad_args | model_error:<x>
    exec_outcome: str | None = None    # exec_ok | exec_reject:<reason> | None (not executed)
    model_error: str | None = None
    model_latency_ms: int = 0
    total_latency_ms: int = 0
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    raw_preview: str | None = None
    coverage_any_claimable: bool | None = None    # Tier-0: separate coverage answer (JEVA)
    coverage_confidence: float | None = None      # Tier-0: soft confidence, None if unanswered
    board_seed: int | None = None                 # Tier-0: session leaves None; the runner owns seeding
    board_order: list | None = None               # Tier-0: task keys in the order shown to the model

    def to_row(self, model: str, scenario: str, iteration: int) -> dict:
        row = asdict(self)
        row["board_order"] = list(self.board_order or [])
        row.update({"model": model, "scenario": scenario, "iteration": iteration})
        return row


def _preview(text: str | None, limit: int = 200) -> str | None:
    if text is None:
        return None
    flat = " ".join(text.split())
    return flat if len(flat) <= limit else flat[:limit] + "..."


def _board_lines(board: dict) -> list[str]:
    return list(board.get("content", {}).get("open_tasks") or [])


def _parse_coverage(decision: Decision, coverage) -> None:
    """Tier-0 prompt mode: tolerant coverage parse. Missing/malformed leaves the
    fields None — recorded for null-detection metrics, not an invalid decision."""
    if not isinstance(coverage, dict):
        return
    any_claimable = coverage.get("any_claimable")
    if isinstance(any_claimable, bool):
        decision.coverage_any_claimable = any_claimable
    _record_confidence(decision, coverage.get("confidence"))


def _strip_coverage(decision: Decision) -> None:
    """Tier-0 native mode: lift the coverage args out of the tool arguments before
    catalog validation (check_args would reject them as unknown)."""
    any_claimable = decision.args.pop("any_claimable", None)
    if isinstance(any_claimable, bool):
        decision.coverage_any_claimable = any_claimable
    _record_confidence(decision, decision.args.pop("confidence", None))


def _record_confidence(decision: Decision, confidence) -> None:
    if isinstance(confidence, (int, float)) and not isinstance(confidence, bool):
        decision.coverage_confidence = float(confidence)


def run_decision(mode: str, model, model_tag: str, client: McpClient,
                 profile: dict, tier0: bool = False, rng: random.Random | None = None) -> Decision:
    """One decision through the mock mod.

    tier0=True (JEVA Tier-0 experimental path): the board lines are shuffled with
    the caller-seeded rng (order recorded as board_order), and the model must also
    answer the separate coverage question (never a list option). tier0=False keeps
    the prompts, tool lists, parse paths, and Decision contents byte-identical.
    """
    started = time.monotonic()
    obs = client.call("observe_self")
    board = client.call("observe_board")
    observation = obs["content"].get("observation", "") if obs["ok"] else ""
    open_lines = _board_lines(board)
    open_keys = [line.split(" — ")[0] for line in open_lines]

    temperature = profile.get("temperature", 0.3)
    max_tokens = profile.get("max_tokens", 1024)
    decision = Decision(mode=mode)

    if tier0:
        shuffle_rng = rng if rng is not None else random.Random()
        open_lines = list(open_lines)
        shuffle_rng.shuffle(open_lines)
        decision.board_order = [line.split(" — ")[0] for line in open_lines]

    if mode == "v1":
        messages = [
            {"role": "system", "content": V1_SYSTEM},
            {"role": "user", "content": render_v1_user(observation, open_lines)},
        ]
        reply = model.chat(model_tag, messages, temperature=temperature, max_tokens=max_tokens)
        decision.model_latency_ms = reply.latency_ms
        decision.prompt_tokens, decision.completion_tokens = reply.prompt_tokens, reply.completion_tokens
        if not reply.ok:
            decision.decision_outcome = "model_error:" + (reply.error or "unknown")
            decision.raw_preview = _preview(getattr(reply, "content", None))
            return _finish(decision, started)
        candidate = first_json_object(reply.content or "")
        decision.raw_preview = _preview(reply.content)
        if candidate is None:
            decision.decision_outcome = "malformed_json"
            return _finish(decision, started)
        action = candidate.get("action")
        args = candidate.get("args") if isinstance(candidate.get("args"), dict) else {}
        if not isinstance(action, str):
            decision.decision_outcome = "malformed_json"
            return _finish(decision, started)
        decision.tool, decision.args = action, args

    elif mode == "mcp-prompt":
        if tier0:
            contract = ('\n\nDecide: reply with exactly one JSON object {"tool":"<id>","args":{...},'
                        '"coverage":{"any_claimable":true|false,"confidence":<0-100 optional>}}.')
        else:
            contract = '\n\nDecide: reply with exactly one JSON object {"tool":"<id>","args":{...}}.'
        messages = [
            {"role": "system", "content": render_prompt()},
            {"role": "user", "content": "OBSERVATION:\n" + observation
             + "\n\nOPEN TASKS:\n" + ("\n".join("- " + l for l in open_lines) or "none")
             + contract},
        ]
        reply = model.chat(model_tag, messages, temperature=temperature, max_tokens=max_tokens)
        decision.model_latency_ms = reply.latency_ms
        decision.prompt_tokens, decision.completion_tokens = reply.prompt_tokens, reply.completion_tokens
        if not reply.ok:
            decision.decision_outcome = "model_error:" + (reply.error or "unknown")
            return _finish(decision, started)
        candidate = first_json_object(reply.content or "")
        decision.raw_preview = _preview(reply.content)
        if candidate is None or not isinstance(candidate.get("tool"), str):
            decision.decision_outcome = "malformed_json"
            return _finish(decision, started)
        decision.tool = candidate["tool"]
        decision.args = candidate.get("args") if isinstance(candidate.get("args"), dict) else {}
        if tier0:
            _parse_coverage(decision, candidate.get("coverage"))

    elif mode == "mcp-native":
        # Parity: only the decision effect set is offered natively; observation is
        # pre-injected above (multi-step observe-via-tools is S2+ behavior — in the
        # cloud test the model spontaneously called observe_board 2/3 times when it
        # was offered, which is good instinct but not a decision-shaped reply).
        decision_tools = [t for t in catalog.S0_TOOLS if t.kind == "effect"]
        messages = [
            {"role": "system", "content":
                "You control a Minecraft villager-agent. Observe the situation, then call exactly "
                "one tool to decide: claim a task, rest, or say something."},
            {"role": "user", "content": "OBSERVATION:\n" + observation
             + "\n\nOPEN TASKS:\n" + ("\n".join("- " + l for l in open_lines) or "none")},
        ]
        reply = model.chat(model_tag, messages, tools=render_native(decision_tools, tier0=tier0),
                           temperature=temperature, max_tokens=max_tokens)
        decision.model_latency_ms = reply.latency_ms
        decision.prompt_tokens, decision.completion_tokens = reply.prompt_tokens, reply.completion_tokens
        if not reply.ok:
            decision.decision_outcome = "model_error:" + (reply.error or "unknown")
            return _finish(decision, started)
        if not reply.tool_calls:
            decision.decision_outcome = "malformed_json"
            decision.raw_preview = _preview(reply.content)
            return _finish(decision, started)
        call = reply.tool_calls[0]
        decision.tool, decision.args = call["name"], call["args"]
        if tier0 and isinstance(decision.args, dict):
            _strip_coverage(decision)

    else:
        decision.decision_outcome = "model_error:unknown_mode:" + mode
        return _finish(decision, started)

    # --- catalog validation (the D7 boundary, same for every mode) -------------
    tool = by_wire(decision.tool) if decision.tool else None
    if tool is None:
        decision.decision_outcome = "unknown_tool"
        return _finish(decision, started)
    arg_err = check_args(tool.inputs, decision.args if decision.args is not None else {})
    if arg_err:
        decision.decision_outcome = "bad_args"
        decision.model_error = arg_err
        return _finish(decision, started)

    # --- execute through the mod boundary ---------------------------------------
    result = client.call(tool.wire, decision.args or {})
    if result["ok"]:
        decision.exec_outcome = "exec_ok"
    else:
        reject = result["content"].get("reject") if isinstance(result["content"], dict) else str(result["content"])
        decision.exec_outcome = "exec_reject:" + str(reject)
    return _finish(decision, started)


def _finish(decision: Decision, started: float) -> Decision:
    decision.total_latency_ms = int((time.monotonic() - started) * 1000)
    return decision


# --- S2: the building pattern walk (multi-step session) ------------------------

@dataclass
class WalkResult:
    outcome: str = "steps_exhausted"   # done_converged | rest | steps_exhausted | stuck | model_error:<x>
    steps: int = 0
    tools_chain: list[str] = field(default_factory=list)
    rejects: int = 0
    gate_rejects: int = 0
    reject_details: list[str] = field(default_factory=list)
    total_latency_ms: int = 0
    model_latency_ms: int = 0
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    final_task: str | None = None
    error: str | None = None

    def to_row(self, model: str, scenario: str, iteration: int) -> dict:
        row = asdict(self)
        row.update({"model": model, "scenario": scenario, "iteration": iteration})
        return row


def _walk_allowed_tools() -> list:
    from catalog import (S0_TOOLS, WALK_ENTRY_TOOLS, WALK_ESCAPE_TOOLS, BUILDING_WALK_TOOLS)
    by_id = {t.id: t for t in BUILDING_WALK_TOOLS}
    observe = [t for t in S0_TOOLS if t.kind == "observe"]
    escape = [t for t in S0_TOOLS if t.id in WALK_ESCAPE_TOOLS]
    return [by_id[i] for i in WALK_ENTRY_TOOLS] + observe + escape


def run_pattern_walk(model, model_tag: str, client: McpClient, scenario: dict,
                     profile: dict, mode: str = "native", max_steps: int = 14,
                     max_polls: int = 6) -> WalkResult:
    """Multi-step building walk (D5/D6): situational tool gating via pattern cards,
    effects through the mod boundary, convergence by polling observe_task."""
    from catalog import (render_native, render_walk_prompt_block, render_walk_system,
                         walk_tool_by_wire, S0_TOOLS)

    started = time.monotonic()
    result = WalkResult()
    temperature = profile.get("temperature", 0.3)
    max_tokens = profile.get("max_tokens", 1024)

    obs = client.call("observe_self")
    board = client.call("observe_board")
    observation = obs["content"].get("observation", "") if obs["ok"] else ""
    open_lines = _board_lines(board)
    request = scenario.get("build_request", "build something reasonable")

    observe_wires = {t.wire for t in S0_TOOLS if t.kind == "observe"}
    escape_wires = {"rest"}
    allowed = _walk_allowed_tools()
    allowed_wires = {t.wire for t in allowed}

    base_user = (f"BUILD REQUEST from {scenario.get('player_name', 'Dev')}: {request}\n\n"
                 f"OBSERVATION:\n{observation}\n\nOPEN TASKS:\n"
                 + ("\n".join("- " + l for l in open_lines) or "none"))
    messages: list[dict] = [
        {"role": "system", "content": render_walk_system()},
        {"role": "user", "content": base_user},
    ]

    malformed_streak = 0
    call_seq = 0
    for _step in range(1, max_steps + 1):
        model_messages = messages
        if mode == "prompt":
            model_messages = messages + [{"role": "user", "content":
                render_walk_prompt_block(allowed) + "\n\nNext: reply with exactly one JSON object."}]
        tools_arg = None
        if mode == "native":
            tools_arg = render_native(allowed)
        reply = model.chat(model_tag, model_messages, tools=tools_arg,
                           temperature=temperature, max_tokens=max_tokens)
        result.model_latency_ms += reply.latency_ms
        result.prompt_tokens = (result.prompt_tokens or 0) + (reply.prompt_tokens or 0)
        result.completion_tokens = (result.completion_tokens or 0) + (reply.completion_tokens or 0)
        if not reply.ok:
            result.outcome = "model_error:" + (reply.error or "unknown")
            result.error = result.outcome
            return _finish_walk(result, started)

        tool, args, call_id = None, {}, f"call_{call_seq}"
        if mode == "native":
            if not reply.tool_calls:
                malformed_streak += 1
                messages.append({"role": "assistant", "content": reply.content or ""})
                messages.append({"role": "user", "content":
                    "That was not a tool call. Reply with exactly one tool call."})
                if malformed_streak >= 2:
                    result.outcome = "stuck"
                    return _finish_walk(result, started)
                continue
            call = reply.tool_calls[0]
            tool, args = call["name"], call["args"]
        else:
            candidate = first_json_object(reply.content or "")
            if candidate is None or not isinstance(candidate.get("tool"), str):
                malformed_streak += 1
                messages.append({"role": "assistant", "content": reply.content or ""})
                messages.append({"role": "user", "content":
                    "That was not a valid tool JSON. Reply with exactly one JSON object."})
                if malformed_streak >= 2:
                    result.outcome = "stuck"
                    return _finish_walk(result, started)
                continue
            tool, args = candidate["tool"], candidate.get("args") if isinstance(candidate.get("args"), dict) else {}
        malformed_streak = 0
        call_seq += 1

        # D5 situational gate: only tools the current cards offer
        if tool not in allowed_wires:
            result.gate_rejects += 1
            result.reject_details.append(f"gate:{tool}")
            _walk_append(messages, mode, call_id, tool, args,
                         {"reject": f"tool '{tool}' is not offered right now — follow the opened steps"})
            if result.gate_rejects >= 3:
                result.outcome = "stuck"
                result.error = "repeated gate violations"
                return _finish_walk(result, started)
            continue

        exec_result = client.call(tool, args)
        if exec_result["is_error"]:
            result.rejects += 1
            body = exec_result["content"] if isinstance(exec_result["content"], dict) \
                else {"reject": str(exec_result["content"])}
            result.reject_details.append(f"{tool}:{body.get('reject', body) if isinstance(body, dict) else body}")
            _walk_append(messages, mode, call_id, tool, args, body)
            continue

        result.steps += 1
        result.tools_chain.append(tool)
        body = exec_result["content"] if isinstance(exec_result["content"], dict) else {"result": exec_result["content"]}
        _walk_append(messages, mode, call_id, tool, args, body)

        if tool == "rest":
            result.outcome = "rest"
            return _finish_walk(result, started)

        if tool == "building_apply_blueprint":
            task = body.get("claimed")
            result.final_task = task
            for _ in range(max_polls):
                poll = client.call("observe_task", {"task": task})
                if poll["ok"] and poll["content"].get("status") == "DONE":
                    result.outcome = "done_converged"
                    return _finish_walk(result, started)
            result.outcome = "not_converged"
            return _finish_walk(result, started)

        # pattern result opens its children (D5 card) — children are ADDED from the
        # card (they may be brand-new tools), observe/escape tools always stay
        children = body.get("children") or []
        child_wires = {c["name"] for c in children}
        next_allowed = []
        for wire in child_wires:
            child_tool = walk_tool_by_wire(wire)
            if child_tool is not None and child_tool not in next_allowed:
                next_allowed.append(child_tool)
        for t in allowed:
            if t.wire in observe_wires or t.wire in escape_wires:
                next_allowed.append(t)
        allowed = next_allowed or allowed
        allowed_wires = {t.wire for t in allowed}

    return _finish_walk(result, started)


def _walk_append(messages: list, mode: str, call_id: str, tool: str, args: dict, body: dict):
    if mode == "native":
        messages.append({"role": "assistant", "tool_calls": [{
            "id": call_id, "type": "function",
            "function": {"name": tool, "arguments": json.dumps(args)}}]})
        messages.append({"role": "tool", "tool_call_id": call_id,
                         "content": json.dumps(body)})
    else:
        messages.append({"role": "assistant", "content": json.dumps({"tool": tool, "args": args})})
        messages.append({"role": "user", "content": "TOOL RESULT " + tool + ": " + json.dumps(body)})


def _finish_walk(result: WalkResult, started: float) -> WalkResult:
    result.total_latency_ms = int((time.monotonic() - started) * 1000)
    return result
