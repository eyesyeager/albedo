import secrets

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.core.logging import get_request_id, reset_request_id, set_request_id


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        incoming = request.headers.get("X-Request-Id", "")
        request_id = incoming if incoming.startswith("req_") else "req_" + secrets.token_hex(4)
        token = set_request_id(request_id)
        try:
            response = await call_next(request)
            response.headers["X-Request-Id"] = get_request_id()
            return response
        finally:
            reset_request_id(token)
