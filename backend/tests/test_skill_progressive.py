"""Skill L1/L2/L3 分层加载的行为测试。

L1：frontmatter.description 常驻 system prompt，正文不进
L2：命中时加载 SKILL.md 正文，按版本冻结、会话内去重
L3：包内资源按需读取，脚本在沙箱执行且只有输出进上下文
"""
import io
import json
import zipfile
from pathlib import Path

import pytest
from httpx import AsyncClient

from app.core.config import get_settings
from app.core.errors import ApiException
from app.runtime import tools as runtime_tools
from app.runtime.builtin import run_skill_script
from app.runtime.prompts import render_system_prompt
from app.services import skill_package

BODY = "# PDF Processing\n\nFollow the steps in FORMS.md.\n"
TRIGGER = "Extract text and tables from PDF files. Use when working with PDFs or document extraction."


def make_package() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(
            "SKILL.md",
            "---\n"
            "name: pdf-processing\n"
            "description: >-\n"
            "  Extract text and tables from PDF files.\n"
            "  Use when working with PDFs or document extraction.\n"
            "version: 1.2.0\n"
            "---\n" + BODY,
        )
        archive.writestr("FORMS.md", "Form filling guide: use field names.\n")
        archive.writestr("scripts/validate.py", "print('VALIDATED')\n")
    return buffer.getvalue()


def snapshot_for(path: Path) -> dict:
    return {
        "systemPrompt": "You are helpful.",
        "skillCatalog": [
            {"id": "skill-1", "name": "pdf-processing", "description": TRIGGER, "version": "v1.0.0"}
        ],
        "resolvedMounts": {
            "skill": [{
                "id": "skill-1",
                "name": "pdf-processing",
                "description": TRIGGER,
                "instructions": BODY,
                "instructionLimit": 8000,
                "version": "v1.0.0",
                "packagePath": str(path),
                "entry": "SKILL.md",
                "resources": ["SKILL.md", "FORMS.md"],
                "scripts": ["scripts/validate.py"],
            }]
        },
    }


def test_l1_frontmatter_and_l2_body_are_parsed_from_package() -> None:
    package = skill_package.inspect_zip(make_package())
    assert package["hasSkillMd"] is True
    assert package["entry"] == "SKILL.md"
    assert package["frontmatter"]["description"] == TRIGGER
    assert "Follow the steps" in package["body"]
    assert "FORMS.md" in package["members"]


def test_l1_catalog_is_in_system_prompt_without_body() -> None:
    rendered, missing = render_system_prompt(snapshot_for(Path("unused.zip")), {})
    assert not missing
    assert "You are helpful." in rendered
    assert "skillId: skill-1" in rendered
    assert TRIGGER in rendered
    assert "不得覆盖" in rendered
    assert "Follow the steps" not in rendered


def test_l3_members_and_safe_read(tmp_path: Path) -> None:
    path = tmp_path / "skill.zip"
    path.write_bytes(make_package())
    assert "FORMS.md" in skill_package.resource_members(path)
    assert skill_package.script_members(path) == ["scripts/validate.py"]
    assert "Form filling guide" in skill_package.read_member(path, "FORMS.md").decode()
    with pytest.raises(ApiException):
        skill_package.read_member(path, "../secret")


def test_tool_specs_expose_one_l2_and_two_global_l3(tmp_path: Path) -> None:
    path = tmp_path / "skill.zip"
    path.write_bytes(make_package())
    specs = runtime_tools.collect_tool_specs(snapshot_for(path))
    kinds = [spec.kind for spec in specs]
    assert kinds.count("skill") == 1
    assert "skill_resource" in kinds
    assert "skill_script" in kinds


@pytest.mark.asyncio
async def test_l2_runner_loads_body_only_and_advertises_l3() -> None:
    result = json.loads(await runtime_tools.skill_instructions_runner(
        "t0_skill", BODY, 8000,
        version="v1.0.0", resources=["FORMS.md"], scripts=["scripts/validate.py"],
    )())
    assert result["level"] == 2
    assert result["instructions"] == BODY
    assert result["resources"] == ["FORMS.md"]
    assert result["scriptExecuted"] is False
    assert "Form filling guide" not in json.dumps(result)


@pytest.mark.asyncio
async def test_l3_resource_runner_returns_only_requested_file(tmp_path: Path) -> None:
    path = tmp_path / "skill.zip"
    path.write_bytes(make_package())
    runner = runtime_tools.skill_resource_runner(snapshot_for(path))
    result = json.loads(await runner(skillId="skill-1", path="FORMS.md"))
    assert result["level"] == 3
    assert "Form filling guide" in result["content"]
    assert "Follow the steps" not in result["content"]
    denied = json.loads(await runner(skillId="skill-1", path="../secret"))
    assert denied["status"] == "error"


@pytest.mark.asyncio
async def test_l3_script_runner_never_puts_source_in_context(tmp_path: Path, monkeypatch) -> None:
    path = tmp_path / "skill.zip"
    path.write_bytes(make_package())

    async def fake_run(source: str, package: Path, members: list[str]) -> dict:
        assert "print('VALIDATED')" in source
        return {"status": "success", "exitCode": 0, "stdout": "VALIDATED", "stderr": "", "summary": "ok"}

    monkeypatch.setattr(runtime_tools, "run_skill_script", fake_run)
    result = json.loads(await runtime_tools.skill_script_runner(snapshot_for(path))(
        skillId="skill-1", path="scripts/validate.py"
    ))
    assert result["level"] == 3
    assert result["scriptExecuted"] is True
    assert result["output"]["stdout"] == "VALIDATED"
    assert "print(" not in json.dumps(result)

    denied = json.loads(await runtime_tools.skill_script_runner(snapshot_for(path))(
        skillId="skill-1", path="FORMS.md"
    ))
    assert denied["status"] == "error"


@pytest.mark.asyncio
async def test_l3_script_really_executes_in_sandbox(tmp_path: Path, monkeypatch) -> None:
    path = tmp_path / "skill.zip"
    path.write_bytes(make_package())
    monkeypatch.setattr(get_settings(), "sandbox_enabled", True)
    outcome = await run_skill_script(
        "print('VALIDATED')\n", path, ["FORMS.md", "scripts/validate.py"]
    )
    assert outcome["status"] == "success"
    assert "VALIDATED" in outcome["stdout"]


@pytest.mark.asyncio
async def test_upload_skill_md_package_extracts_l1_and_l2(client: AsyncClient) -> None:
    """端到端：包内 SKILL.md 成为 L1 触发面与 L2 正文，不再依赖手填说明。"""
    response = await client.post("/api/v1/auth/login", json={"key": get_settings().demo_access_key})
    assert response.status_code == 200, response.text
    payload = make_package()
    created = await client.post(
        "/api/v1/skills",
        data={"name": "PDF 处理", "fileName": "pdf-processing.zip", "sizeBytes": len(payload)},
        files={"file": ("pdf-processing.zip", payload, "application/zip")},
    )
    assert created.status_code == 201, created.text
    entity = created.json()["entity"]
    assert entity["triggerDescription"] == TRIGGER
    assert entity["description"] == TRIGGER
    assert "Follow the steps" in entity["instructions"]
    # L2/L3 内部字段不应暴露在版本列表中。
    assert "instructions" not in entity["versions"][0]
    assert "packageKey" not in entity["versions"][0]


@pytest.mark.asyncio
async def test_runtime_l1_l2_do_not_consume_tool_quota_and_are_audited(tmp_path: Path) -> None:
    """端到端：L1 清单注入、L2 加载豁免配额、会话内去重、分层事件落库。"""
    from langgraph.checkpoint.memory import InMemorySaver
    from sqlalchemy import select

    from app.db import models as m
    from app.db.session import SessionLocal
    from app.runtime import RuntimeEngine, RuntimeRequest
    from fakes import build_test_model

    path = tmp_path / "skill.zip"
    path.write_bytes(make_package())
    snap = snapshot_for(path)
    snap.update({
        "id": "agt-skill",
        "name": "Skill 运行时助手",
        "variables": [],
        "modelParams": {"temperature": 0.7, "topP": 0.9, "maxOutputTokens": 4096},
        "advanced": {
            "keepContext": True, "autoRetry": True, "outputThinkingSummary": False,
            "maxToolCalls": 1, "maxRunSeconds": 120,
        },
        "model": {"id": "mdl-gpt", "name": "GPT-4.1"},
    })
    engine = RuntimeEngine(
        checkpointer=InMemorySaver(),
        # 固定只调用 L2 加载工具，才能验证同一会话内的重复加载被去重。
        model_factory=lambda s, tools, prompt: build_test_model(s, tools[:1], prompt, tool_rounds=2),
    )
    request = RuntimeRequest(
        agent_id="agt-skill", thread_id="thd-skill-progressive", run_id="run-skill-progressive",
        content="请处理 PDF", snapshot=snap,
    )
    result = await engine.run(request)
    # 两次 Skill 工具调用都不占业务工具预算。
    assert result.tool_call_count == 0
    tool_messages = [message for message in result.messages if message["role"] == "tool"]
    # 第一次送入正文，第二次只回摘要，正文不再重复灌入上下文。
    assert len(tool_messages) >= 2
    contents = [str(message.get("toolCall", {}).get("result", "")) for message in tool_messages]
    assert any("已加载 Skill 指令" in text for text in contents)
    assert any("已在本会话加载过" in text for text in contents)

    async with SessionLocal() as db:
        rows = (
            await db.scalars(
                select(m.SkillLoadRecord).where(m.SkillLoadRecord.run_id == "run-skill-progressive")
            )
        ).all()
        try:
            assert any(row.level == 1 for row in rows)
            assert any(row.level == 2 for row in rows)
        finally:
            for row in rows:
                await db.delete(row)
            await db.commit()
