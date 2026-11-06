import json
from types import SimpleNamespace

import pytest

from app.runtime import builtin


def payload(raw: str) -> dict:
    return json.loads(raw)


def fake_settings(upload_dir, **overrides) -> SimpleNamespace:
    defaults = {
        "upload_dir": str(upload_dir),
        "tool_upload_max_bytes": 1024 * 1024,
        "sandbox_enabled": True,
        "sandbox_timeout_seconds": 2,
        "sandbox_memory_mb": 128,
        "sandbox_max_output_chars": 4000,
        "search_provider": "off",
        "search_api_key": "",
        "search_max_results": 5,
        "http_timeout_seconds": 5,
        "http_max_response_bytes": 4096,
        "http_allow_hosts": "",
        "llm_base_url": "",
        "llm_api_key": "",
        "llm_model": "",
        "vision_model": "",
        "llm_timeout_seconds": 5,
    }
    defaults.update(overrides)
    return SimpleNamespace(**defaults)


@pytest.fixture
def uploads(tmp_path, monkeypatch):
    monkeypatch.setattr(builtin, "get_settings", lambda: fake_settings(tmp_path))
    (tmp_path / "note.txt").write_text("内置工具文件解析测试", encoding="utf-8")
    (tmp_path / "table.csv").write_text("name,score\nalice,90\n", encoding="utf-8")
    return tmp_path


def test_check_code_blocks_dangerous_input():
    assert "socket" in builtin.check_code("import socket")
    assert "subprocess" in builtin.check_code("from subprocess import run")
    assert "eval" in builtin.check_code("eval('1+1')")
    assert builtin.check_code("print(sum(range(5)))") == ""


async def test_code_interpreter_runs_and_reports_timeout(uploads):
    ok = payload(await builtin.run_code_interpreter("print(sum(range(6)))"))
    assert ok["status"] == "success"
    assert ok["stdout"].strip() == "15"
    assert ok["exitCode"] == 0

    failed = payload(await builtin.run_code_interpreter("raise ValueError('boom')"))
    assert failed["status"] == "error"
    assert "boom" in failed["stderr"]

    blocked = payload(await builtin.run_code_interpreter("import socket"))
    assert blocked["status"] == "error"

    timed = payload(await builtin.run_code_interpreter("import time\ntime.sleep(5)"))
    assert timed["status"] == "degraded"
    assert "上限" in timed["summary"]


async def test_file_parser_supports_text_csv_and_word(uploads):
    text = payload(await builtin.run_file_parser("note.txt"))
    assert text["status"] == "success"
    assert "内置工具" in text["content"]

    csv = payload(await builtin.run_file_parser("table.csv"))
    assert csv["rows"] == 2

    from docx import Document

    doc = Document()
    doc.add_paragraph("季度营收 128")
    doc.save(uploads / "doc.docx")
    word = payload(await builtin.run_file_parser("doc.docx"))
    assert word["status"] == "success"
    assert "季度营收" in word["content"]


async def test_file_parser_blocks_escape_and_missing_files(uploads):
    escape = payload(await builtin.run_file_parser("../../etc/passwd"))
    assert escape["status"] == "error"
    assert "未找到" in escape["summary"]

    missing = payload(await builtin.run_file_parser("nope.txt"))
    assert missing["status"] == "error"


async def test_http_request_blocks_private_network(uploads):
    blocked = payload(await builtin.run_http_request("http://127.0.0.1:8000/api/v1/settings"))
    assert blocked["status"] == "error"
    assert "SSRF" in blocked["summary"]

    private = payload(await builtin.run_http_request("http://10.0.0.1/"))
    assert private["status"] == "error"


async def test_http_request_rejects_bad_method_and_bad_headers(uploads):
    method = payload(await builtin.run_http_request("https://example.com", method="TRACE"))
    assert "不支持" in method["summary"]

    headers = payload(await builtin.run_http_request("https://example.com", headers_json="{bad json"))
    assert "JSON" in headers["summary"]


async def test_web_search_validation(uploads, monkeypatch):
    empty = payload(await builtin.run_web_search(""))
    assert empty["status"] == "error"

    monkeypatch.setattr(builtin, "get_settings", lambda: fake_settings(uploads, search_provider="tavily"))
    no_key = payload(await builtin.run_web_search("langgraph"))
    assert no_key["status"] == "degraded"
    assert "SEARCH_API_KEY" in no_key["summary"]


def test_parse_bing_extracts_results():
    html = (
        '<li class="b_algo"><h2><a href="https://example.com/a">标题 A</a></h2>'
        "<p>摘要 A</p></li>"
        '<li class="b_algo"><h2><a href="https://example.com/b">标题 B</a></h2></li>'
    )
    results = builtin.parse_bing(html, 5)
    assert [item["url"] for item in results] == ["https://example.com/a", "https://example.com/b"]
    assert results[0]["title"] == "标题 A"
    assert results[0]["snippet"] == "摘要 A"


def test_parse_duckduckgo_unwraps_redirect():
    html = (
        '<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fx">标题</a>'
        '<a class="result__snippet">摘要</a>'
    )
    results = builtin.parse_duckduckgo(html, 3)
    assert results[0]["url"] == "https://example.com/x"


async def test_image_inspector_requires_vision_credentials(uploads):
    (uploads / "tiny.png").write_bytes(b"\x89PNG\r\n\x1a\n")
    result = payload(await builtin.run_image_inspector("tiny.png"))
    assert result["status"] == "degraded"
    assert "多模态" in result["summary"]
