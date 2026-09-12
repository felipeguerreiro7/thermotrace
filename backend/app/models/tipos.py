"""Tipos de coluna reutilizados.

Um lugar só para as decisões que se repetem em 25 tabelas — se mudarmos de
ideia sobre precisão de temperatura, muda aqui.
"""

from enum import StrEnum

from sqlalchemy import Enum as SaEnum
from sqlalchemy import Numeric


def enum_pg(classe: type[StrEnum], nome: str) -> SaEnum:
    """ENUM nativo do PostgreSQL a partir de um StrEnum do Python.

    `values_callable` faz o banco guardar o *valor* ("em_transporte") e não o
    *nome* do membro ("EM_TRANSPORTE"). Isso importa porque o app Android
    envia o valor, e porque um `SELECT` manual no banco fica legível.
    """
    return SaEnum(
        classe,
        name=nome,
        native_enum=True,
        create_type=True,
        values_callable=lambda c: [membro.value for membro in c],
    )


# Temperatura configurada e limites: numeric, não float.
# O sensor tem passo de 0,25 °C; numeric(5,2) representa isso exatamente e
# não acumula erro de ponto flutuante em comparação de limite.
TemperaturaC = Numeric(5, 2)

# Tensão de bateria: 1,50 V nominal.
TensaoV = Numeric(4, 2)
