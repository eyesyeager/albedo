from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.session import get_db
from app.schemas.domain import DomainModel
from app.services.system_config import get_configs

router = APIRouter(tags=["meta"])


class ContractInfo(DomainModel):
    name: str
    version: str
    plane: str


@router.get("/meta", response_model=ContractInfo, summary="契约信息")
async def contract_info(db: AsyncSession = Depends(get_db)) -> dict[str, str]:
    config = await get_configs(db)
    platform = config["platform"]
    return {
        "name": f"{str(platform['name']).lower()}-management-api",
        "version": str(platform["version"]),
        "plane": "management",
    }
