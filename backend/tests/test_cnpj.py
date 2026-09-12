import pytest
from pydantic import ValidationError
from app.schemas.acesso import NovoCliente


@pytest.mark.parametrize("cnpj,normalizado", [
    ("11.222.333/0001-81", "11222333000181"),
    ("00.000.000/E08G-12", "00000000E08G12"),
    ("00.000.000/e08g-12", "00000000E08G12"),
])
def test_cnpj_numerico_e_alfanumerico(cnpj, normalizado):
    # Exemplo alfanumérico publicado pela Receita; só valida, não cadastra empresa real.
    cliente = NovoCliente(razao_social="Teste", cnpj=cnpj,
                          gestor={"nome": "Teste", "email": "teste@example.org", "senha": "SenhaTesteSomente-42"})
    assert cliente.cnpj == normalizado


@pytest.mark.parametrize("cnpj", ["00000000000000", "00000000E08G13", "00000000É08G12", "123"])
def test_cnpj_invalido(cnpj):
    with pytest.raises(ValidationError):
        NovoCliente(razao_social="Teste", cnpj=cnpj,
                    gestor={"nome": "Teste", "email": "teste@example.org", "senha": "SenhaTesteSomente-42"})
