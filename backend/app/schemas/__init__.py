"""Contratos de entrada e saida (Pydantic).

Separados dos modelos SQLAlchemy de proposito: o formato que trafega na rede
e o formato que se guarda no banco mudam por motivos diferentes e em ritmos
diferentes. Acoplar os dois faz uma mudanca de coluna virar quebra de
contrato com o aplicativo em campo.
"""
