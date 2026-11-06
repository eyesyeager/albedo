from typing import Annotated, Any, TypedDict

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages


class AgentState(TypedDict, total=False):
    messages: Annotated[list[BaseMessage], add_messages]
    system_prompt: str
    tool_call_count: int
    finish_reason: str
    usage: dict[str, int]
    started_at: float
    agent_name: str
    model_name: str
    max_tool_calls: int
    max_run_seconds: float
    final_text: str
