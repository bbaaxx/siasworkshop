---
id: "decision-layer-jev-jeva"
title: "Decision layer: JEV (TypeSafe), JEVA (decision-native architecture), and local open-weight alternatives"
category: "reference"
created: "2026-10-17"
updated: "2026-10-17"
related_initiatives: ["research-digest-jev-jeva-decision-layer--2026-10-17", "mcp-model-behavior-research--2026-10-15"]
tags: ["research","llm","agents","decision-layer","system-one","local-llm","paper-digest"]
lifecycle: "evolving"
knowledge_type: "reference"
confidence: "high"
source_initiatives: ["research-digest-jev-jeva-decision-layer--2026-10-17"]
---

# Decision-layer digest: JEV, JEVA, and the local path

Third source of the 2026-10-17 research pause. User requirement (hard):
whatever we adopt must run **locally / self-hosted** — no cloud endpoint, no
usage-based pricing. Sources: [JEVA paper (arXiv 2610.18773)](https://arxiv.org/abs/2610.18773),
[TypeSafe JEV announcement](https://typesafe.ai/blog/introducing-system-one-models-and-jev),
[JevK5 repo](https://github.com/allebee/jevk5),
[Kev-4B model card](https://huggingface.co/jaredpalmer/kev-4b). All read
2026-10-17.

## The two names

- **JEV** = TypeSafe's hosted *product* ("System One Model", named for
  Jevons): state in → typed probabilistic decisions out (noul/choice/score),
  parallel sampling, 70–500 ms, calibrated probabilities, "can't hallucinate"
  (no string generation), trained with RLCD. **Cloud API, usage-priced**
  ($0.042/MTok input, outputs free), early-access waitlist, cardinality ≤255
  natively. Fails our hard constraint as shipped — it is the reference
  implementation of the idea, not something we can run.
- **JEVA** = the open *architecture paper* (NUS; Qwen3.5-4B backbone): asks
  what a decision-native architecture must guarantee and proves/provides four
  structural properties (below). No code/weights release evident as of
  2026-10-17 — it is a design recipe + evaluative framework.

## JEVA's four structural requirements (and why each is our pain)

| Requirement | JEVA mechanism | Our S1-seen pathology |
| --- | --- | --- |
| Null-aware: absence ≠ uncertainty | Separate coverage gate ρ=σ((max sᵢ−b_cov)/τ_cov); P(Y=i)=ρ·qᵢ — answer availability is *not* a candidate | Board of unroutable tasks: model must be able to say "no claimable task" without that being one more option |
| Permutation-invariant | Independent encoding of request and each option; symmetric aggregation ⇒ **zero flips** in 600-order tests (Jev 17%, InternDecision-4B 22.7%, JevK5 26.2% flip rates) | Model latched onto the *first-listed* unroutable task — position sensitivity |
| Option-scalable | No fixed answer-symbol inventory; linear-in-K readout; verified to K=2,048 (78.4% ToolRet vs JevK5's recursive 64%) | Pattern-tree catalog will grow; prompt-based choice degrades as the tool list grows |
| Cacheable | Option embeddings + per-layer K/V projections reusable across requests; 27.98× p50 speedup at full reuse, **decisions unchanged** | Our tool catalog is static per session; re-sending/re-encoding it every decide call is pure waste — LM Studio prompt caching is the poor man's version |

Key theoretical nuggets: (1) independent per-option scorers provably *cannot*
reverse preferences when the candidate set changes (Prop 1) — set context
matters; (2) a "None" option inside a flat softmax has a **compulsory −log K
drift** (Prop 2) — as the board grows, an in-list null option is
systematically suppressed, so null must be a *separate question*, not a
candidate. Templates encode request ("Decision type / Question / State") and
each option ("Option for {type}: …") separately, with **no position
identifiers**.

## Local / open-weight alternative space (the actual deliverable)

All Apache-2.0 unless noted; all run self-hosted on our M3 Max class hardware:

| Model | Backbone+head | Local run story | Options | Null / permutation | Decision quality | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| **JevK5 v0.3 (4B)** | Qwen3.5-4B + LoRA (SemIf letter-logit readout) | **GGUF for llama.cpp** (Q8_0 4.48 GB; ~0.6 s/short decision on M1 Pro, faster on M3 Max); also pip runtime (~9 GB VRAM, 13 ms p50 on H100) | ≤16/pass, knockout grouping beyond | **Weak null**: CLINC150 out-of-scope recall 0.27–0.46; worst-in-class flip rate 26% | JevBench 2nd of 76, 1st open (v0.2); hard tier 0.784 (4B v0.3); ToolRet 64% | TypeSafe `/v1/systemone`-shaped endpoint; **9B is worse than 4B on hard tier + badly calibrated — prefer 4B** |
| **Kev-4B (Kev 1.0)** | Qwen3.5-4B-Base + LoRA rank-16 + pointer head | **MLX on Apple Silicon — validated on 32 GB Macs** (8.4 GB weights; cached-state follow-ups ~350 ms for 8k-token states); CUDA bf16 too | **1–255 native** | No coverage gate; card admits option order can change answers | Held-out breadth 0.690 (Jev 0.757); OOD calibration excellent (ECE 0.017–0.029); MMLU 0.725 (knowledge = base) | State cached across questions (JEVA-cacheability in spirit); 65k-token states served; fine-tunable on own labels |
| JEVA-4B | Qwen3.5-4B + 13.4M-param decision head | No release found — recipe only | to 2,048 | Best in class (98.66% null AUROC, 0 flips) | Leads 7/8 JEVA slices | Architecture to imitate; adopt its *metrics* |
| SemIf (OpenJev) | open | SemIf protocol (MIT) | — | — | — | Readout JevK5 is built on |
| Intern-Decision (InternLM) | open, 4B | HF | registered limits (65/84 @ K=32) | — | 78.70% Typed Decisions | Multimodal variant exists |
| ReFlex / Laya / CLM | small open models | — | — | ReFlex reduces position sensitivity by aggregation | CLM weak on scaling (7/83 all-size) | Background only |

## Recommendation for Sia's Workshop (local-first, tiered)

- **Tier 0 — now, zero new infrastructure.** Apply JEVA's factorization in
  prompt-land on the existing sidecar + bonsai-27b path: (a) **shuffle**
  candidate task lists per decide call; (b) ask null/coverage as a *separate
  noul question* ("is any listed task claimable by this agent?"), never as a
  list option (Prop-2 drift); (c) adopt JEVA's exact metrics into corpus Q4:
  **flip rate, all-order accuracy, null-detection AUROC at ~5% FPR** — we now
  have published numbers for Jev/JevK5/InternDecision to benchmark our LLM
  prompt path against.
- **Tier 1 — self-host a typed-decision endpoint.** Two candidates, both
  Apache-2.0, both M3-Max-runnable: **JevK5-4B GGUF** through llama.cpp (we
  already run llama-server for LM Studio; ~0.6 s/short decision) or **Kev-4B
  via MLX** (Apple-native, 255 options, state caching). Use it for the small
  set of typed decisions in our loop: task-claim choice, D5-style gate
  noul checks, routability coverage. JevK5's known null weakness vs Kev-4B's
  option-order caveat → measure both on our corpus before choosing; corpus
  Q4 is exactly the instrument.
- **Tier 2 — decision-native head, only if the catalog actually grows**
  (pattern tree past ~50–100 tools, or latency/calibration demands exceed
  Tier 1). JEVA-4B if weights ever ship; otherwise its recipe (independent
  option encoding, no position identifiers, separate coverage gate, option
  K/V caching) is a ~13M-param LoRA project on Qwen3.5-4B — a real but
  committal training investment, parked.
- **Not adopted:** JEV itself (cloud/usage-priced — hard constraint), JevK5-9B
  (regressed vs 4B on hard tier + calibration), CLM/Laya/ReFlex (dominated).

## Relation to the rest of the stack

Fits between our pattern-tree session state and the task board: the decision
layer resolves *which* task/tool/gate; the MCP pattern tree is *how* to carry
it out; the executor (leaf effects) is *what actually runs*. Player-1
([player-1-game-agents](player-1-game-agents.md)) covers the control layer we
deliberately don't need; the yuniko ecosystem
([minecraft-mcp-servers](minecraft-mcp-servers.md)) covers flat verb catalogs
we deliberately don't copy. Tier 0 feeds the mcp-model-behavior-research
corpus Q4 work directly; Tier 1 adds a localhost endpoint consistent with our
agents.mcp config-gating stance (default OFF, dev-only).
