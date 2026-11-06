from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class ToolSpec:
    key: str
    name: str
    description: str
    kind: str
    resource_id: str


@dataclass
class RuntimeRequest:
    agent_id: str
    thread_id: str
    run_id: str
    content: str
    snapshot: dict[str, Any]
    variables: dict[str, str] = field(default_factory=dict)
    source: str = "test_session"
    strict_variables: bool = False


@dataclass
class RuntimeResult:
    run_id: str
    thread_id: str
    messages: list[dict[str, Any]]
    logs: list[dict[str, Any]]
    finish_reason: str
    usage: dict[str, int]
    duration_ms: int
    tool_call_count: int
    missing_variables: list[str] = field(default_factory=list)
