from fastapi import APIRouter

from app.api.v1 import (
    agents,
    auth,
    logs,
    mcps,
    meta,
    models,
    notifications,
    publications,
    resources,
    settings,
    skills,
    stats,
    tools,
    workspace,
)

v1_router = APIRouter()
v1_router.include_router(meta.router)
v1_router.include_router(auth.router)
v1_router.include_router(workspace.router)
v1_router.include_router(models.router)
v1_router.include_router(mcps.router)
v1_router.include_router(skills.router)
v1_router.include_router(tools.router)
v1_router.include_router(resources.router)
v1_router.include_router(agents.router)
v1_router.include_router(publications.router)
v1_router.include_router(logs.router)
v1_router.include_router(stats.router)
v1_router.include_router(notifications.router)
v1_router.include_router(settings.router)
