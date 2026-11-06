"""离线单元测试专用的确定性对话模型。

仅被 pytest 用于验证 LangGraph 图的编排、终止条件与持久化逻辑；
不参与任何生产代码路径，也不作为运行时的降级实现。
"""

import asyncio
import time
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult


class DeterministicChatModel(BaseChatModel):
    tool_names: list[str] = []
    tool_rounds: int = 1
    delay_seconds: float = 0.0
    agent_name: str = "智能体"
    model_name: str = "test-model"
    rendered_prompt: str = ""

    @property
    def _llm_type(self) -> str:
        return "albedo-test-deterministic"

    def bind_tools(self, tools: Any, **kwargs: Any) -> "DeterministicChatModel":
        return self

    def _topic(self, messages: list) -> str:
        for message in reversed(messages):
            if getattr(message, "type", "") == "human":
                return str(getattr(message, "content", ""))[:36]
        return ""

    def _reply_text(self, messages: list) -> str:
        executed = sum(1 for message in messages if isinstance(message, ToolMessage))
        return (
            f"已收到你的问题「{self._topic(messages)}」。我是{self.agent_name}，"
            f"当前使用 {self.model_name}，已按顺序执行 {executed} 次工具调用。"
        )

    def _generate(self, messages: list, stop: list[str] | None = None, run_manager: Any = None, **kwargs: Any) -> ChatResult:
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        executed = sum(1 for message in messages if isinstance(message, ToolMessage))
        if executed < self.tool_rounds and self.tool_names:
            call_name = self.tool_names[executed % len(self.tool_names)]
            message = AIMessage(content="", tool_calls=[{
                "name": call_name,
                "args": {"query": self._topic(messages)[:80]},
                "id": f"call_{executed + 1}",
            }])
        else:
            message = AIMessage(content=self._reply_text(messages))
        return ChatResult(generations=[ChatGeneration(message=message)])

    async def _astream(self, messages: list, stop: list[str] | None = None, run_manager: Any = None, **kwargs: Any):
        if self.delay_seconds:
            await asyncio.sleep(self.delay_seconds)
        executed = sum(1 for message in messages if isinstance(message, ToolMessage))
        if executed < self.tool_rounds and self.tool_names:
            yield ChatGenerationChunk(message=AIMessageChunk(content="", tool_call_chunks=[{
                "name": self.tool_names[executed % len(self.tool_names)],
                "args": '{"query":""}',
                "id": f"call_{executed + 1}",
                "index": 0,
            }]))
            return
        text = self._reply_text(messages)
        for index in range(0, len(text), 8):
            yield ChatGenerationChunk(message=AIMessageChunk(content=text[index:index + 8]))


def build_test_model(snapshot: dict, tool_names: list[str], rendered: str, **overrides: Any) -> DeterministicChatModel:
    return DeterministicChatModel(
        tool_names=tool_names,
        agent_name=str(snapshot.get("name") or "智能体"),
        model_name=str((snapshot.get("model") or {}).get("name") or "test-model"),
        rendered_prompt=rendered,
        **overrides,
    )
