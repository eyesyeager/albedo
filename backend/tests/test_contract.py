import json
from pathlib import Path

import schemathesis

from app.main import app

CONTRACT_PATH = Path(__file__).resolve().parents[2] / "packages" / "contracts" / "openapi.json"


def test_openapi_snapshot_is_current() -> None:
    spec = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    assert spec["openapi"].startswith("3.1"), "契约必须使用 OpenAPI 3.1"
    assert spec["paths"].keys() == app.openapi()["paths"].keys(), "契约快照已过期，请运行 packages/contracts/generate.sh"


def test_management_and_public_plane_contract() -> None:
    spec = app.openapi()
    assert "/api/v1/meta" in spec["paths"]
    assert "/api/v1/workspace/bootstrap" in spec["paths"]
    assert "/v1/agents/{agent_id}/chat" in spec["paths"]
    assert "/v1/chat/completions" in spec["paths"]
    assert "/api/agui/v1/agents/{agent_id}/run" in spec["paths"]
    assert not any(path.startswith("/api/v1/v1/") for path in spec["paths"]), "管理面与对外面前缀必须隔离"


def test_schemathesis_can_load_contract() -> None:
    legacy_view = {**app.openapi(), "openapi": "3.0.3"}
    schema = schemathesis.openapi.from_dict(legacy_view)
    assert len(list(schema)) >= 1
