import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from app.main import app  # noqa: E402

CONTRACT_PATH = BACKEND_ROOT.parent / "packages" / "contracts" / "openapi.json"


def main() -> None:
    spec = app.openapi()
    CONTRACT_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT_PATH.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"exported {len(spec.get('paths', {}))} paths -> {CONTRACT_PATH}")


if __name__ == "__main__":
    main()
