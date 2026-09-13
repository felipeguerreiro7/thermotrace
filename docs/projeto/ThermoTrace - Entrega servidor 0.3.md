---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
status: implementado-em-validacao
atualizado: 2026-09-12
---

# ThermoTrace API 0.3 — cargas, documentos e produto configurado

Trabalho iniciado em 11/09/2026 e consolidado em 12/09/2026. Projeto pessoal independente da ThermoTrace. Código implementado e validado localmente; nenhuma migração foi aplicada ao banco existente do projeto e nenhum serviço foi publicado nesta etapa.

## Resultado prático

O gestor pode associar o código de um produto a uma versão aprovada de perfil térmico e ao intervalo de registro. Depois, a criação de uma carga recebe essa configuração e preenche os critérios sem pedir que o operador digite temperaturas. O aplicativo ainda precisa integrar essas rotas e apresentar esse fluxo no celular.

Cada carga tem UUID próprio e código exclusivo dentro do cliente. Ela pode ter vários documentos; a mesma nota ou pedido pode aparecer em remessas diferentes para representar entregas parciais. Os volumes usam a identidade da remessa, preservada na futura troca de etiqueta. Saber o código de uma nota não concede acesso a outra empresa.

Ao criar a carga, o servidor guarda uma cópia do produto/configuração, versão do perfil, limites, intervalo, evidência, responsável e data de aprovação. Uma configuração posterior não altera esse registro. Produto ainda desconhecido ou perfil retirado/rascunho não recebe faixa genérica: a operação precisa de configuração válida.

## Regras entregues

- Um perfil por remessa nesta versão. Uma carga com condições térmicas diferentes precisa de remessas separadas, podendo compartilhar referências documentais. Não há cálculo de quantidades fiscais ou saldo de entregas parciais.
- Configuração de produto tem versões e controle de concorrência. Atualização exige informar a versão anterior; só uma versão fica ativa por código. A versão antiga continua disponível para histórico.
- Nova carga exige código próprio ou documento. Se houver apenas documento, o servidor gera um código interno. Código de remessa/produto é normalizado para maiúsculas; até 64 caracteres, incluindo números, ponto, hífen, barra e sublinhado.
- Criação aceita até 30 documentos e 200 volumes. Documentos adicionais podem ser vinculados durante a preparação, até 100 por remessa. O mesmo identificador não é duplicado na mesma remessa; referências legadas sem hash também são conferidas.
- Conteúdo bruto do documento é preservado, inclusive espaços e quebras de linha. O registro não faz consulta fiscal nem comprova autenticidade: `validado=false` é explícito. Extração de XML/PDF, leitura de código pela câmera e validação fiscal completa continuam pendentes.
- Localização por código da carga, número do documento ou chave de acesso retorna candidatas paginadas. Se uma nota acompanhar várias entregas, a API não escolhe uma delas silenciosamente. Canceladas ficam fora dessa busca por padrão, mas permanecem consultáveis.
- Gestor pode cancelar uma remessa em preparação com motivo. Documentos, volumes, critérios e auditoria são preservados. Cancelamento administrativo não executa STOP na etiqueta e não significa encerramento de monitoramento.

## Reenvio seguro e contrato para integração

Todos os novos POSTs abaixo exigem `Idempotency-Key`, preferencialmente um UUID gerado uma vez para cada operação lógica. O cliente deve persistir a chave e o corpo antes de enviar, repetindo ambos quando a resposta se perder.

Mesma empresa, usuário, rota, chave e corpo devolvem o resultado original e `Idempotency-Replayed: true`. Conteúdo diferente sob a mesma chave retorna 409; não tratar esse conflito como sucesso. Chaves de empresas/usuários/rotas diferentes não compartilham respostas. Resultado, recibo e auditoria são confirmados na mesma transação. Uma falha não deixa recibo de sucesso. Duas requisições simultâneas são serializadas pelo banco.

Reenvio de criação já confirmada continua retornando o recibo original mesmo que o perfil seja retirado depois; não cria uma nova carga com perfil indisponível. Uma nova operação precisa de nova chave e configuração atual. Os endpoints anteriores de cadastro de clientes/usuários/perfis ainda não usam esse mecanismo.

| Rota sob /api/v1 | Permissão | Uso |
|---|---|---|
| POST /produtos | Gestor | Criar configuração ou próxima versão |
| GET /produtos | Operador/gestor | Listar configurações ativas; conferir disponibilidade |
| GET /produtos/localizar?codigo=... | Operador/gestor | Obter configuração pelo código do produto |
| GET /produtos/{id} | Operador/gestor | Consultar configuração específica, inclusive histórica |
| POST /remessas | Operador/gestor | Criar carga, volumes e documentos juntos |
| GET /remessas | Operador/gestor | Listar cargas próprias, com filtro opcional de status |
| GET /remessas/localizar?valor=... | Operador/gestor | Localizar candidatas por código/documento |
| GET /remessas/{id} | Operador/gestor | Consultar carga, critérios, documentos e volumes |
| POST /remessas/{id}/documentos | Operador/gestor | Acrescentar documento durante preparação |
| POST /remessas/{id}/cancelar | Gestor | Cancelar preparação com motivo |

`disponivel_para_nova_carga` deve ser conferido ao apresentar o produto; `ativo=true` sozinho não significa que o perfil ainda está disponível. O servidor verifica de novo ao criar. A referência usada na criação é `produto_configuracao_id`, não apenas o código textual. Atualizar um formulário antigo exige apresentar a mudança ao operador.

Schemas de entrada e saída das novas rotas estão declarados em docs/openapi-0.3.json. Datas de saída têm fuso explícito; a conexão mantém UTC e timeout mesmo após rollback do pool. Exemplos abaixo usam identificadores ilustrativos, não contas reais.

Configuração, depois da aprovação do perfil:

```json
{"codigo":"SKU-REFRIGERADO","perfil_termico_id":"UUID_DO_PERFIL_APROVADO","intervalo_segundos":600}
```

Na próxima versão do mesmo código, acrescentar `versao_anterior` com a versão atual. Criação de carga:

```json
{
  "codigo":"CARGA-001",
  "produto_configuracao_id":"UUID_DA_CONFIGURACAO",
  "destinatario_nome":"Hospital do cliente",
  "quantidade_volumes":2,
  "documentos":[{"tipo":"pedido","numero":"PED-123"}]
}
```

## Banco, segurança e migração

Migrações: 91b030110001 e 92b030110002, após a versão 0.2 (80dff1100901). Removem a exclusividade global de referência documental; preservam valores e relações existentes. Campos novos de snapshot/configuração ficam nulos em registros antigos: nenhum critério histórico é inventado retroativamente.

RLS forçada agora cobre produto_configuracao, remessa, documento, volume e chave_idempotencia, além dos perfis. Contextos de empresa e usuário vêm da autenticação validada e valem somente na transação. O banco verifica também se perfil/produto pertencem à mesma empresa do vínculo. Essa checagem adicional importa porque chaves estrangeiras não aplicam RLS por si mesmas, conforme a [documentação do PostgreSQL 17](https://www.postgresql.org/docs/17/ddl-rowsecurity.html).

Gatilhos impedem apagar remessas/volumes e modificar identidade do volume, critério congelado, documentos ou recibos de idempotência. A API registra autor e evento das operações de escrita. Ainda não há trilha imutável de todas as consultas/exportações. Administrador de banco mantém poderes sobre estrutura; manifesto externo, retenção contratual e backups independentes seguem necessários para auditoria completa.

Compartilhamento entre embarcador, transportador e destinatário ainda não está implementado. Nesta etapa só a empresa proprietária consulta/opera a carga; a existência de empresa_transportadora_id no modelo não concede acesso. A equipe ThermoTrace não recebe leitura automática das cargas. Tabelas de leituras/sessões, usuários e auditoria ainda precisam do tratamento de escopo previsto para seus próximos fluxos.

Para banco novo, aplicar migrações com credencial separada e executar scripts/runtime-permissoes.sql. Para atualização de papel já provisionado na 0.2, usar scripts/runtime-cargas-permissoes.sql. Esses scripts não embutem senha. O runtime não recebe DELETE, TRUNCATE, propriedade das tabelas nem BYPASSRLS. Rever o legado e restaurabilidade do backup antes de migrar o banco existente.

## Validação executada

- 86 testes passaram, sem falhas ou testes ignorados, em PostgreSQL 17 descartável. Incluem os testes anteriores e os novos fluxos, papéis, isolamento, reenvio, concorrência, perfil retirado, entrega parcial, dados legados e imutabilidade.
- Migrações do zero e Alembic check passaram. Prova adicional partiu do schema 0.2 com remessa, volume, documento e leitura sintéticos: a atualização preservou todos os valores antigos e permitiu nova entrega com a mesma referência.
- Fluxo HTTP de login → perfil → produto → carga/volumes → reenvio → localização → cancelamento → logout passou com as permissões restritas fornecidas.
- Corrigida configuração da conexão: UTC e timeout de 30 segundos agora sobrevivem ao rollback do pool. A regressão está na suíte.

Permanece um aviso de depreciação da biblioteca do cliente HTTP de testes. Não foi executado teste físico NFC, teste de carga em escala comercial ou implantação pública. O Android continua na entrega 0.7.3 e ainda não consome estas rotas.

## Próximo ciclo

1. Contrato e ingestão de ativação/checkpoint/final com evidência bruta, conflitos e autorização por sessão/volume.
2. Integração Android: login, persistência da sessão, seleção/localização de carga, produto configurado e fila offline idempotente.
3. Compartilhamentos explícitos entre empresas e concessão de suporte.
4. Consolidação do histórico entre leituras, gráfico/relatório do servidor e portais cliente/equipe.

Convite, troca/recuperação de senha, MFA, backup/restauração operacional, calibração e bancada seguem no plano para o piloto. A aprovação interna dos perfis não certifica o produto nem libera cargas de saúde.
