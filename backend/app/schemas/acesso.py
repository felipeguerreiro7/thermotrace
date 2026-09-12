from pydantic import BaseModel, ConfigDict, EmailStr, Field, SecretStr, field_validator
from app.models.enums import PapelUsuario, TipoEmpresa


class Entrada(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Login(Entrada):
    email: EmailStr
    senha: SecretStr = Field(min_length=1, max_length=256)


class Renovacao(Entrada):
    refresh_token: SecretStr = Field(min_length=40, max_length=128)


class NovoUsuario(Entrada):
    nome: str = Field(min_length=2, max_length=160)
    email: EmailStr
    senha: SecretStr = Field(min_length=12, max_length=256)
    papel: PapelUsuario = PapelUsuario.OPERADOR

    @field_validator("papel")
    @classmethod
    def papel_cliente(cls, value):
        if value == PapelUsuario.ADMIN:
            raise ValueError("Administrador da plataforma não é um papel de cliente.")
        return value


class NovoCliente(Entrada):
    razao_social: str = Field(min_length=2, max_length=240)
    cnpj: str = Field(pattern=r"^[A-Z0-9]{12}[0-9]{2}$")
    tipo: TipoEmpresa = TipoEmpresa.EMBARCADOR
    gestor: NovoUsuario

    @field_validator("cnpj", mode="before")
    @classmethod
    def normalizar_cnpj(cls, v):
        if isinstance(v, str):
            return v.strip().upper().replace(".", "").replace("/", "").replace("-", "")
        return v

    @field_validator("cnpj")
    @classmethod
    def validar_cnpj(cls, v):
        if len(set(v)) == 1:
            raise ValueError("CNPJ inválido.")
        for tamanho, pesos in [(12, [5,4,3,2,9,8,7,6,5,4,3,2]), (13, [6,5,4,3,2,9,8,7,6,5,4,3,2])]:
            # Manual oficial Receita Federal: valor do caractere = ASCII - 48.
            resto = sum((ord(n)-48)*p for n,p in zip(v[:tamanho], pesos)) % 11
            if int(v[tamanho]) != (0 if resto < 2 else 11-resto):
                raise ValueError("CNPJ inválido.")
        return v

    @field_validator("tipo")
    @classmethod
    def tipo_cliente(cls, value):
        if value == TipoEmpresa.PLATAFORMA:
            raise ValueError("Este endpoint cadastra clientes.")
        return value
