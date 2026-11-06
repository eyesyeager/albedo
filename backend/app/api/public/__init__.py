from fastapi import APIRouter

from app.api.public import agents as public_agents
from app.api.public import models as public_models

public_router = APIRouter(tags=["public"])
public_router.include_router(public_agents.chat_router)
public_router.include_router(public_models.router)

agui_router = APIRouter(tags=["agui"])
agui_router.include_router(public_agents.agui_router)

__all__ = ["agui_router", "public_router"]
