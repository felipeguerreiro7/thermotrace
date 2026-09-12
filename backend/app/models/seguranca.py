from datetime import datetime
from sqlalchemy import DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base


class LimiteLogin(Base):
    __tablename__ = "limite_login"
    chave: Mapped[str] = mapped_column(String(64), primary_key=True)
    inicio: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    tentativas: Mapped[int] = mapped_column(Integer, nullable=False)
