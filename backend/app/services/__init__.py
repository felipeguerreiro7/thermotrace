"""Regras de negocio.

Nada aqui dentro sabe que HTTP existe: sem `Request`, sem `HTTPException`,
sem status code. Um servico recebe dados e uma sessao de banco, e devolve
dados ou levanta um erro de `app.core.errors`.

E o que permite o mesmo servico atender a API, um script de linha de comando
e um teste sem adaptacao - e o que mantem a regra fora do alcance de uma tela
nova mal escrita.
"""
