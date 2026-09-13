---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Kit inicial de implementação

Entregável desta retomada: pacote local em `C:/Users/lfgue/Documents/Codex/2026-09-11/qu/outputs/thermotrace-kit`.

- `serie_temperatura.py`: componente Python independente que consolida pontos por sessão e índice, rejeita números inválidos, detecta conflito e mantém proveniência de todas as coletas.
- `test_serie_temperatura.py`: cenários de repetição, ordem, lacunas, conflito, sessões e entrada inválida.
- `CONTRATO-API.md`: contratos propostos de ingestão, consulta e emissão.
- `README.md`: uso, escopo e integração proposta.

É um início de código para integrar ao backend existente. Não é servidor publicado, migração aplicada, mecanismo de autenticação ou aplicativo pronto. Dados recebidos ainda precisam ser autorizados, decodificados e validados no servidor antes de entrar nesse componente.

O banco existente deve evoluir por migração revisada. Não criar outro esquema concorrente nem executar SQL antigo diretamente sobre produção.

## Verificação executada
11 testes automatizados do componente passaram em 2026-09-11. Casos sintéticos: histórico repetido, proveniência, reenvio, ordem, lacunas, sessões distintas, conflitos de horário/temperatura, tempo reverso, fusos, entradas inválidas e lista vazia. Isso não corrige nem valida o gráfico do Android atual; integração e regressão com dados reais continuam pendentes.
