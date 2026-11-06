"""add system_configs table

Revision ID: 7f9c2e4a1b36
Revises: 0cd7e47f3020
Create Date: 2026-11-06

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '7f9c2e4a1b36'
down_revision: Union[str, None] = '0cd7e47f3020'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'system_configs',
        sa.Column('key', sa.String(length=64), nullable=False),
        sa.Column('value', sa.JSON(), nullable=False),
        sa.Column('description', sa.String(length=255), nullable=False),
        sa.Column('updated_at', sa.String(length=40), nullable=False),
        sa.PrimaryKeyConstraint('key', name=op.f('pk_system_configs')),
    )


def downgrade() -> None:
    op.drop_table('system_configs')
