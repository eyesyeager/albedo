import secrets

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.core.logging import get_request_id, reset_request_id, set_request_id


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        # 不采信客户端传入的 X-Request-Id：可伪造的 ID 会污染审计链路，
        # 也可能被用于在日志里注入换行或跨请求追踪混淆。
        request_id = "req_" + secrets.token_hex(8)
        token = set_request_id(request_id)
        try:
            response = await call_next(request)
            response.headers["X-Request-Id"] = get_request_id()
            return response
        finally:
            reset_request_id(token)
