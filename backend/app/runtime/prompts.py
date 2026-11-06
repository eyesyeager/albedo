from datetime import datetime, timezone

from jinja2 import StrictUndefined, Template

from app.core.errors import ApiException
from app.services.agent_rules import extract_variables


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def render_system_prompt(
    snapshot: dict,
    variables: dict[str, str] | None,
    *,
    strict: bool = False,
    current_time: str | None = None,
) -> tuple[str, list[str]]:
    template_text = str(snapshot.get("systemPrompt") or "")
    declared = [str(name) for name in (snapshot.get("variables") or [])]
    required = extract_variables(template_text)
    values: dict[str, str] = {name: "" for name in declared}
    for name, value in (variables or {}).items():
        if value is not None:
            values[str(name)] = str(value)
    values["currentTime"] = current_time or now_iso()

    not_provided = sorted(name for name in required if not values.get(name))
    if strict and not_provided:
        raise ApiException("missing_variable", f"缺少提示词必需变量：{', '.join(not_provided)}", 422)

    render_values = {name: values.get(name, "") for name in set(required) | {"currentTime"}}
    rendered = Template(template_text, undefined=StrictUndefined).render(**render_values)
    return rendered, not_provided
