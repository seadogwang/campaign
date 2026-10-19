#!/usr/bin/env python3
"""Call an Anthropic-compatible model with the public Segment Metadata catalog."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit
from zoneinfo import ZoneInfo


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


MAX_RESPONSE_BYTES = 1_048_576
API_VERSION = "2023-06-01"

# --- Host-only boundary ---
# Sentinel markers that authors place in the metadata markdown to fence off
# sections containing physical storage / serving information (table names,
# column names, join keys, tenant/program columns, DataScope).  The model
# must never see these.  If the sentinels are absent, the function falls
# back to scanning for any ## heading whose text contains host-only
# keywords; if that also fails, the entire document is treated as public.
HOST_ONLY_START_SENTINEL = "<!-- BEGIN_HOST_ONLY -->"
HOST_ONLY_END_SENTINEL = "<!-- END_HOST_ONLY -->"
HOST_ONLY_HEADING_KEYWORDS = (
    "serving view", "host-only", "storage mapping",
    "physical schema", "column mapping", "table mapping",
)

# Patterns that must never survive into public metadata.
# If any of these are found after extraction the function raises ValueError.
_FORBIDDEN_RE = re.compile(
    r'\bvw_crm_\w+\b|'
    r'\bvalueColumn\b|'
    r'\bprimaryKeyColumn\b|'
    r'\bcurrencyColumn\b|'
    r'\btenantColumn\b|'
    r'\bprogramColumn\b|'
    r'\bseg_consent_event\b|'
    r'\bseg_redemption_item\b'
)

REQUIRED_OUTPUT_FIELDS = {
    "disposition",
    "clarificationQuestions",
    "resolvedFacts",
    "unresolvedFacts",
}
ALLOWED_OPERATORS = {
    "EQUAL",
    "NOT_EQUAL",
    "GREATER_THAN",
    "GREATER_OR_EQUAL",
    "LESS_THAN",
    "LESS_OR_EQUAL",
    "IN",
    "NOT_IN",
    "BETWEEN",
    "IS_NULL",
    "IS_NOT_NULL",
}

# --- Canonical window schema (matches IntentWindow + SegmentIntentParser) ---
ALLOWED_WINDOW_KINDS = {
    "ROLLING",
    "LIFETIME",
    "FIXED",
    "CALENDAR_MONTHS",
    "RELATIVE_TO_ATTRIBUTE",
    "SELECTED_EVENT",
    "RELATIVE_TO_EVENT",
}
ALLOWED_WINDOW_UNITS = {"DAY", "WEEK"}
ALLOWED_BUCKET_REQUIREMENTS = {"COMBINED", "EVERY_BUCKET"}

# Closed field sets per window kind — any extra field is rejected.
_WF = lambda *fields: frozenset(fields)
WINDOW_FIELDS_BY_KIND = {
    "ROLLING":                 _WF("kind", "amount", "unit"),
    "LIFETIME":                _WF("kind"),
    "FIXED":                   _WF("kind", "start", "end"),
    "CALENDAR_MONTHS":         _WF("kind", "count", "includeCurrent", "bucketRequirement"),
    "RELATIVE_TO_ATTRIBUTE":   _WF("kind", "anchorDefinitionId", "startOffset", "endOffset", "unit"),
    "SELECTED_EVENT":          _WF("kind", "selectorId", "selectorScopeFilters"),
    "RELATIVE_TO_EVENT":       _WF("kind", "selectorId", "selectorScopeFilters",
                                   "startOffset", "endOffset", "unit", "excludeAnchorEvent"),
}
WINDOW_MAX_ROLLING_AMOUNT = 100_000
WINDOW_MAX_CALENDAR_MONTHS = 24

# Matches backtick-enclosed stable semantic IDs (must contain at least one dot).
# Physical table names (vw_crm_*), enum literals, and operator tokens have no dot
# and are never captured.
_SEMANTIC_ID_RE = re.compile(r"`([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)`")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send one Chinese membership-segment requirement to the configured model."
    )
    parser.add_argument("requirement", nargs="?", help="Requirement text; reads stdin when omitted.")
    parser.add_argument(
        "--metadata",
        type=Path,
        default=Path("docs/campaign_ai_metadata_catalog_v1.3.md"),
        help="Semantic Metadata Markdown catalog.",
    )
    parser.add_argument(
        "--as-of",
        help="ISO-8601 evaluation time; defaults to the current Asia/Shanghai time.",
    )
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP timeout in seconds.")
    parser.add_argument(
        "--stream",
        action="store_true",
        help="Use SSE streaming and display thinking/text deltas as they arrive.",
    )
    return parser.parse_args()


def configured_value(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if value:
        return value
    settings_path = Path.home() / ".claude" / "settings.json"
    if settings_path.is_file():
        settings = json.loads(settings_path.read_text(encoding="utf-8"))
        value = str(settings.get("env", {}).get(name, "")).strip()
    if not value:
        raise ValueError(f"Missing configuration: {name}")
    return value


def messages_endpoint(base_url: str) -> str:
    parsed = urlsplit(base_url.strip())
    if parsed.scheme != "https" or not parsed.netloc or parsed.query or parsed.fragment:
        raise ValueError("ANTHROPIC_BASE_URL must be an HTTPS URL without query or fragment")
    path = parsed.path.rstrip("/")
    path = f"{path}/messages" if path.endswith("/v1") else f"{path}/v1/messages"
    return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def _extract_public_sections(text: str) -> str:
    """Extract public-facing sections from a structured metadata catalog.

    Removes:
    - ``Storage catalog`` references in header blockquotes
    - Physical resource file paths (``src/main/resources/metadata/…``)
    - Section 2 (原始表覆盖) in its entirety — raw table names are host-only

    Returns the sanitised document.  Documents without recognisable
    v1.3+ structure pass through unchanged and rely on
    :func:`_validate_public_output` to catch any residual host-only
    tokens.
    """
    lines = text.splitlines(keepends=True)
    result: list[str] = []
    in_section2 = False

    for line in lines:
        stripped = line.strip()

        # Remove header line with Storage catalog reference
        if stripped.startswith(">") and "Storage catalog" in stripped:
            continue

        # Remove physical resource file paths
        if stripped.startswith("- `src/main/resources/metadata/"):
            continue

        # Detect Section 2 (原始表覆盖) boundaries
        if stripped.startswith("## 2.") and (
            "原始表" in stripped or "raw table" in stripped.lower()
        ):
            in_section2 = True
            continue
        if in_section2:
            if stripped.startswith("## ") and not stripped.startswith("### "):
                # Next major section — exit section 2
                in_section2 = False
                result.append(line)
                continue
            else:
                # Still inside section 2 — skip this line
                continue

        result.append(line)

    return "".join(result).rstrip()


def _validate_public_output(text: str) -> None:
    """Raise ``ValueError`` when host-only tokens survive extraction."""
    for match in _FORBIDDEN_RE.finditer(text):
        raise ValueError(
            f"Host-only content leaked into public metadata: {match.group(0)!r}"
        )


def public_metadata(path: Path) -> str:
    """Return the public portion of the metadata catalog.

    Strips any host-only section bracketed by sentinel comments
    ``<!-- BEGIN_HOST_ONLY -->`` … ``<!-- END_HOST_ONLY -->``.  If no
    sentinels are present, scans for a ``##`` heading whose text
    contains host-only keywords (case-insensitive) and strips everything
    from that heading through the next ``##`` heading.  When neither
    mechanism locates a host-only section, the document undergoes
    structured extraction (:func:`_extract_public_sections`) which
    removes header storage-catalog references, physical resource paths,
    and the full Section 2 (原始表覆盖) raw-table listing.  Every
    return path is validated by :func:`_validate_public_output`; a
    ``ValueError`` is raised if any host-only token survives.
    """
    text = path.read_text(encoding="utf-8")

    # 1) Sentinel markers (preferred)
    sentinel_start = text.find(HOST_ONLY_START_SENTINEL)
    if sentinel_start >= 0:
        sentinel_end = text.find(HOST_ONLY_END_SENTINEL, sentinel_start)
        if sentinel_end < 0:
            raise ValueError(
                f"Metadata catalog has {HOST_ONLY_START_SENTINEL!r} "
                f"without a matching {HOST_ONLY_END_SENTINEL!r}"
            )
        # Drop everything between the markers (including the markers themselves)
        result = (
            text[:sentinel_start].rstrip()
            + "\n"
            + text[sentinel_end + len(HOST_ONLY_END_SENTINEL):].lstrip("\n")
        ).strip()
        _validate_public_output(result)
        return result

    # 2) Heading-based fallback — scan for a ## heading with known keywords
    heading_re = re.compile(r"^##\s+(.+)", re.MULTILINE)
    for match in heading_re.finditer(text):
        heading_text = match.group(1).strip().lower()
        if any(keyword in heading_text for keyword in HOST_ONLY_HEADING_KEYWORDS):
            host_only_start = match.start()
            next_heading = heading_re.search(text, match.end())
            if next_heading:
                result = (text[:host_only_start] + text[next_heading.start():]).strip()
            else:
                # Host-only is the last section — return everything before it
                result = text[:host_only_start].strip()
            _validate_public_output(result)
            return result

    # 3) Structured extraction — remove known host-only sections by
    #    structural rules, then validate.  Never fall through to
    #    full-document passthrough.
    result = _extract_public_sections(text)
    _validate_public_output(result)
    return result


def build_prompt(requirement: str, metadata: str, as_of: str) -> str:
    return f"""You are a membership Segment intent resolver.
Use only the supplied Semantic Metadata. Never invent a semantic ID, enum, operator, time rule, assumption, table, column, join, DSL, or SQL.
Keep ambiguous business wording unresolved. If Metadata requires clarification, return NEEDS_INPUT and ask the smallest concrete questions needed.
When Metadata declares a mandatory clarification pair, ask every applicable pair in the same response. Never select a field from the value's appearance.
Treat relative time using the supplied asOf and timezone. Do not silently convert calendar months or years into fixed day counts.

Return exactly one raw JSON object with this shape and no Markdown or prose:
{{
  "disposition": "RESOLVED | NEEDS_INPUT | UNSUPPORTED",
  "clarificationQuestions": ["question"],
  "resolvedFacts": [
    {{
      "sourceText": "exact source text",
      "semanticId": "metadata stable ID",
      "operator": "metadata operator",
      "value": null,
      "window": null
    }}
  ],
  "unresolvedFacts": [
    {{"sourceText": "exact source text", "reason": "short reason"}}
  ]
}}

The operator field must be exactly one of: EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL, IN, NOT_IN, BETWEEN, IS_NULL, IS_NOT_NULL.
Never emit aliases such as EQ, EQUALS, GE, GTE, LT, LTE, =, !=, >, >=, <, or <=.
Attach a time window to its Attribute or Metric fact. Never emit an event-time field such as order.paid_at or order_item.paid_at as a standalone resolved fact.

Window field (null or an object — each kind has a closed field set; extra fields are rejected):
- ROLLING:  {{"kind":"ROLLING","amount":90,"unit":"DAY"}}
- LIFETIME: {{"kind":"LIFETIME"}}
- FIXED:    {{"kind":"FIXED","start":"ISO-8601 instant","end":"ISO-8601 instant"}}
- CALENDAR_MONTHS: {{"kind":"CALENDAR_MONTHS","count":N,"includeCurrent":false,"bucketRequirement":"COMBINED"}}
- RELATIVE_TO_ATTRIBUTE: {{"kind":"RELATIVE_TO_ATTRIBUTE","anchorDefinitionId":"member.registered_at","startOffset":-90,"endOffset":0,"unit":"DAY"}}
- SELECTED_EVENT: {{"kind":"SELECTED_EVENT","selectorId":"order.first_positive","selectorScopeFilters":[]}}
- RELATIVE_TO_EVENT: {{"kind":"RELATIVE_TO_EVENT","selectorId":"order.first_positive","selectorScopeFilters":[],"startOffset":-90,"endOffset":0,"unit":"DAY","excludeAnchorEvent":false}}
The discriminator field is "kind", never "type". A rolling duration uses "amount" and "unit" (DAY or WEEK only), never "days" or "months".
MONTH and YEAR are not legal rolling units. An explicit rolling duration such as 90 days is:
  {{"kind":"ROLLING","amount":90,"unit":"DAY"}}

asOf: {as_of}
timezone: Asia/Shanghai

Semantic Metadata:
<metadata>
{metadata}
</metadata>

Original requirement:
<requirement>{requirement}</requirement>
"""


def request_body(model: str, prompt: str, stream: bool = False) -> bytes:
    payload = {
        "model": model,
        "max_tokens": 4_096,
        "thinking": {"type": "disabled"},
        "messages": [{"role": "user", "content": prompt}],
    }
    if stream:
        payload["stream"] = True
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def call_model(endpoint: str, token: str, body: bytes, timeout: float) -> str:
    request = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "content-type": "application/json",
            "anthropic-version": API_VERSION,
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response_body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Model endpoint returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise RuntimeError("Model endpoint request failed") from error
    if len(response_body) > MAX_RESPONSE_BYTES:
        raise RuntimeError("Model response exceeds the 1 MiB limit")
    envelope = json.loads(response_body.decode("utf-8"))
    content = envelope.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        for block in content:
            if isinstance(block, dict) and isinstance(block.get("text"), str):
                return block["text"]
    choices = envelope.get("choices")
    if isinstance(choices, list) and choices:
        choice = choices[0]
        if isinstance(choice, dict):
            message = choice.get("message")
            if isinstance(message, dict) and isinstance(message.get("content"), str):
                return message["content"]
            if isinstance(choice.get("text"), str):
                return choice["text"]
    if isinstance(envelope.get("completion"), str):
        return envelope["completion"]
    keys = ",".join(sorted(str(key) for key in envelope))
    block_shapes = []
    if isinstance(content, list):
        for block in content[:5]:
            if isinstance(block, dict):
                block_shapes.append(
                    f"type={block.get('type')!r};fields={','.join(sorted(str(key) for key in block))}"
                )
            else:
                block_shapes.append(type(block).__name__)
    shapes = " | ".join(block_shapes) or "none"
    raise RuntimeError(
        "Model response contains no supported text field; "
        f"stop_reason={envelope.get('stop_reason')!r}; top-level fields: {keys}; content shapes: {shapes}"
    )


def call_model_stream(endpoint: str, token: str, body: bytes, timeout: float) -> str:
    request = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "content-type": "application/json",
            "accept": "text/event-stream",
            "anthropic-version": API_VERSION,
            "Authorization": f"Bearer {token}",
        },
    )
    text_parts: list[str] = []
    total_bytes = 0
    thinking_started = False
    answer_started = False
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            for raw_line in response:
                total_bytes += len(raw_line)
                if total_bytes > MAX_RESPONSE_BYTES:
                    raise RuntimeError("Model response exceeds the 1 MiB limit")
                line = raw_line.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or data == "[DONE]":
                    continue
                event = json.loads(data)
                block = event.get("content_block")
                if event.get("type") == "content_block_start" and isinstance(block, dict):
                    if block.get("type") == "thinking" and isinstance(block.get("thinking"), str):
                        if not thinking_started:
                            print("[model thinking]", file=sys.stderr, flush=True)
                            thinking_started = True
                        print(block["thinking"], end="", file=sys.stderr, flush=True)
                    if block.get("type") == "text" and isinstance(block.get("text"), str):
                        if not answer_started:
                            print("\n[model output]", file=sys.stderr, flush=True)
                            answer_started = True
                        text_parts.append(block["text"])
                        print(block["text"], end="", flush=True)
                delta = event.get("delta")
                if event.get("type") == "content_block_delta" and isinstance(delta, dict):
                    if delta.get("type") == "thinking_delta" and isinstance(delta.get("thinking"), str):
                        if not thinking_started:
                            print("[model thinking]", file=sys.stderr, flush=True)
                            thinking_started = True
                        print(delta["thinking"], end="", file=sys.stderr, flush=True)
                    if delta.get("type") == "text_delta" and isinstance(delta.get("text"), str):
                        if not answer_started:
                            print("\n[model output]", file=sys.stderr, flush=True)
                            answer_started = True
                        text_parts.append(delta["text"])
                        print(delta["text"], end="", flush=True)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Model endpoint returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise RuntimeError("Model endpoint request failed") from error
    if thinking_started or answer_started:
        print(file=sys.stderr, flush=True)
    if not text_parts:
        raise RuntimeError("Streaming model response ended without a final text block")
    return "".join(text_parts)


def allowed_semantic_ids(metadata: str) -> set[str]:
    """Return every stable semantic ID found in the public metadata.

    Semantic IDs always contain at least one dot (e.g. ``member.tier``,
    ``order.positive_count``).  Physical table names (``vw_crm_*``),
    enum literals, and operator tokens have no dot and are never captured.
    """
    return set(_SEMANTIC_ID_RE.findall(metadata))


def validate_window(window: object) -> None:
    """Validate the ``window`` field of a resolved fact against the canonical schema.

    Accepts ``None`` (no window).  For an object, checks:
    - ``kind`` is a known discriminator
    - the object contains *only* the fields declared for that kind
    - required fields are present and have the right types
    - value ranges are within bounds
    Raises ``ValueError`` on any violation.
    """
    if window is None:
        return
    if not isinstance(window, dict):
        raise ValueError("window must be null or a JSON object")
    kind = window.get("kind")
    if not isinstance(kind, str) or kind not in ALLOWED_WINDOW_KINDS:
        raise ValueError(
            f"window.kind must be one of {sorted(ALLOWED_WINDOW_KINDS)!r}, got {kind!r}"
        )
    allowed_fields = WINDOW_FIELDS_BY_KIND[kind]
    extra = set(window) - allowed_fields
    if extra:
        raise ValueError(
            f"{kind!r} window must only contain {sorted(allowed_fields)!r}; "
            f"unexpected fields: {sorted(extra)!r}"
        )

    # Per-kind structural checks
    if kind == "ROLLING":
        _require_int(window, "amount", positive=True)
        _require_window_unit(window)
        _check_bounds(window["amount"], 1, WINDOW_MAX_ROLLING_AMOUNT, "amount")
    elif kind == "LIFETIME":
        pass  # no extra fields allowed
    elif kind == "FIXED":
        _require_str(window, "start")
        _require_str(window, "end")
    elif kind == "CALENDAR_MONTHS":
        _require_int(window, "count", positive=True)
        _require_bool(window, "includeCurrent")
        _require_bucket_requirement(window)
        _check_bounds(window["count"], 1, WINDOW_MAX_CALENDAR_MONTHS, "count")
    elif kind == "RELATIVE_TO_ATTRIBUTE":
        _require_str(window, "anchorDefinitionId")
        _require_int(window, "startOffset")
        _require_int(window, "endOffset")
        _require_window_unit(window)
    elif kind == "SELECTED_EVENT":
        _require_str(window, "selectorId")
        _require_list_or_missing(window, "selectorScopeFilters")
    elif kind == "RELATIVE_TO_EVENT":
        _require_str(window, "selectorId")
        _require_list_or_missing(window, "selectorScopeFilters")
        _require_int(window, "startOffset")
        _require_int(window, "endOffset")
        _require_window_unit(window)
        _require_bool(window, "excludeAnchorEvent")


def _require_int(obj: dict, key: str, *, positive: bool = False) -> None:
    try:
        v = obj[key]
    except KeyError:
        raise ValueError(f"window.{key} is required") from None
    if not isinstance(v, int) or isinstance(v, bool):
        raise ValueError(f"window.{key} must be an integer, got {type(v).__name__!r}")
    if positive and v <= 0:
        raise ValueError(f"window.{key} must be positive, got {v!r}")


def _require_str(obj: dict, key: str) -> None:
    try:
        v = obj[key]
    except KeyError:
        raise ValueError(f"window.{key} is required") from None
    if not isinstance(v, str):
        raise ValueError(f"window.{key} must be a string, got {type(v).__name__!r}")


def _require_bool(obj: dict, key: str) -> None:
    try:
        v = obj[key]
    except KeyError:
        raise ValueError(f"window.{key} is required") from None
    if not isinstance(v, bool):
        raise ValueError(f"window.{key} must be a boolean, got {type(v).__name__!r}")


def _require_window_unit(obj: dict) -> None:
    try:
        unit = obj["unit"]
    except KeyError:
        raise ValueError("window.unit is required") from None
    if not isinstance(unit, str) or unit not in ALLOWED_WINDOW_UNITS:
        raise ValueError(
            f"window.unit must be one of {sorted(ALLOWED_WINDOW_UNITS)!r}, got {unit!r}"
        )


def _require_bucket_requirement(obj: dict) -> None:
    try:
        br = obj["bucketRequirement"]
    except KeyError:
        raise ValueError("window.bucketRequirement is required") from None
    if not isinstance(br, str) or br not in ALLOWED_BUCKET_REQUIREMENTS:
        raise ValueError(
            f"window.bucketRequirement must be one of {sorted(ALLOWED_BUCKET_REQUIREMENTS)!r}, "
            f"got {br!r}"
        )


def _require_list_or_missing(obj: dict, key: str) -> None:
    v = obj.get(key)
    if v is None:
        return
    if not isinstance(v, list):
        raise ValueError(f"window.{key} must be an array, got {type(v).__name__!r}")


def _check_bounds(value: int, min_val: int, max_val: int, label: str) -> None:
    if value < min_val or value > max_val:
        raise ValueError(f"window.{label} {value!r} is out of bounds [{min_val}, {max_val}]")


def parse_model_json(text: str, semantic_ids: set[str]) -> dict[str, object]:
    result = json.loads(text)
    if not isinstance(result, dict) or set(result) != REQUIRED_OUTPUT_FIELDS:
        raise ValueError("Model output does not match the required top-level JSON shape")
    if result["disposition"] not in {"RESOLVED", "NEEDS_INPUT", "UNSUPPORTED"}:
        raise ValueError("Model output contains an invalid disposition")
    resolved_facts = result["resolvedFacts"]
    if not isinstance(resolved_facts, list):
        raise ValueError("Model output resolvedFacts must be an array")
    for fact in resolved_facts:
        if not isinstance(fact, dict) or fact.get("operator") not in ALLOWED_OPERATORS:
            raise ValueError("Model output contains an operator not defined by Segment Metadata")
        if fact.get("semanticId") not in semantic_ids:
            raise ValueError("Model output contains a semantic ID not defined by Segment Metadata")
        validate_window(fact.get("window"))
    return result


def main() -> int:
    args = parse_args()
    requirement = (args.requirement if args.requirement is not None else sys.stdin.read()).strip()
    if not requirement:
        raise ValueError("Requirement must not be empty")
    if args.timeout <= 0 or args.timeout > 120:
        raise ValueError("Timeout must be greater than 0 and at most 120 seconds")

    token = configured_value("ANTHROPIC_AUTH_TOKEN")
    base_url = configured_value("ANTHROPIC_BASE_URL")
    model = configured_value("ANTHROPIC_MODEL")
    as_of = args.as_of or datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(timespec="seconds")
    metadata = public_metadata(args.metadata)
    prompt = build_prompt(requirement, metadata, as_of)
    endpoint = messages_endpoint(base_url)
    if args.stream:
        output = call_model_stream(endpoint, token, request_body(model, prompt, stream=True), args.timeout)
    else:
        output = call_model(endpoint, token, request_body(model, prompt), args.timeout)
    parsed = parse_model_json(output, allowed_semantic_ids(metadata))
    if not args.stream:
        print(json.dumps(parsed, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
