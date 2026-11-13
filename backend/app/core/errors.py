from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logging import get_request_id


class ApiException(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        status_code: int = 400,
        issues: list[str] | None = None,
        extra: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.issues = issues
        self.extra = extra or {}


def error_body(code: str, message: str, request_id: str, **extra: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"code": code, "message": message, "requestId": request_id}
    payload.update(extra)
    return {"error": payload}


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiException)
    async def api_exception_handler(_: Request, exc: ApiException) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=error_body(
                exc.code,
                exc.message,
                get_request_id(),
                **({"issues": exc.issues} if exc.issues else {}),
                **exc.extra,
            ),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content=error_body("validation_error", "请求参数校验失败，请检查输入内容", get_request_id()),
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = "resource_not_found" if exc.status_code == 404 else "invalid_api_key" if exc.status_code == 401 else "validation_error"
        detail = exc.detail if isinstance(exc.detail, str) else "请求无法完成"
        return JSONResponse(
            status_code=exc.status_code,
            content=error_body(code, detail, get_request_id()),
        )

    @app.exception_handler(IntegrityError)
    async def integrity_error_handler(_: Request, _exc: IntegrityError) -> JSONResponse:
        # check-then-insert 竞态撞唯一约束时应返回 409，而不是未捕获的 500。
        return JSONResponse(
            status_code=409,
            content=error_body("conflict", "资源冲突：唯一约束或关联数据已被占用，请刷新后重试", get_request_id()),
        )
