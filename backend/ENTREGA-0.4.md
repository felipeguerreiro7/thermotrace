# ThermoTrace — servidor 0.4

Entrega de desenvolvimento de 12/09/2026. Projeto pessoal independente.

## Resultado

A API recebe declarações de início, checkpoint e leitura final ligadas a empresa, operador, aparelho, etiqueta, carga e volume. A evidência bruta é preservada com recibo e cadeia de integridade. Um segundo operador da mesma empresa pode localizar a sessão pelo volume e enviar seu próprio evento, com autoria própria.

**126 testes passaram, sem falhas ou testes ignorados**, em PostgreSQL 17 descartável: 86 anteriores e 40 novos. As migrações e a comparação entre modelos e banco passaram. A migração de um clone sintético 0.3 preservou todos os valores anteriores conferidos em oito tabelas, incluindo sessões, leituras e hashes legados. Nenhum banco de uso real foi migrado.

## Comportamento implementado

- Cadastro autenticado da instalação do aplicativo por empresa; identificador declarado, sem atestação do aparelho.
- Localização de etiqueta previamente cadastrada pelo UID canônico, dentro do cliente autenticado.
- Abertura de sessão em um volume com o perfil e intervalo preservados na carga. Configuração diferente, etiqueta de outro cliente, UID divergente e vínculo ativo conflitante são recusados.
- Evidência original do SDK recebida como lista de strings, preservando espaços/quebras de linha. O servidor registra separadamente o instante declarado pelo aparelho e o instante de recebimento.
- Reenvio HTTP com a mesma chave devolve o recibo original. O identificador do evento também impede duplicação quando chega com uma chave HTTP nova. Troca de conteúdo ou operador para o mesmo evento gera conflito.
- Leitura, estado e recibo são confirmados na mesma transação. Falha antes do commit desfaz a operação inteira; isso foi testado por falha injetada após a escrita da evidência.
- Dois envios simultâneos recebem uma ordem de chegada consistente. Foram testadas duas conexões reais com eventos distintos, mesmo evento, conteúdo divergente e dois fechamentos concorrentes.
- Checkpoint atrasado pode chegar depois da leitura final se seu instante declarado for anterior ou igual ao fechamento. Mantém sua posição de chegada na cadeia. Novo fechamento ou leitura posterior ao corte gera conflito.
- Encerramento lógico não libera o vínculo da etiqueta e não confirma STOP físico. Também não conclui automaticamente a remessa nem decide liberação de produto.
- Simulações ficam identificadas em evidências e recibos; uma sessão não aceita mistura de simulação e declaração Android.
- Consultas de sessões, leituras, evidência bruta e verificações de integridade geram eventos de auditoria autenticados.

## Rotas novas

Todas exigem conta ativa; operadores e gestores atuam somente na própria empresa. A conta administrativa da plataforma não recebe acesso implícito. Os POSTs exigem `Idempotency-Key`.

| Método | Caminho após `/api/v1` | Uso |
|---|---|---|
| POST | `/dispositivos` | Registrar instalação declarada |
| GET | `/etiquetas/localizar?uid=…` | Encontrar etiqueta do catálogo do cliente |
| GET | `/volumes/{id}/sessoes` | Encontrar sessões de recepção do volume |
| POST | `/volumes/{id}/sessoes` | Registrar ativação e primeira evidência |
| POST | `/sessoes/{id}/leituras` | Receber checkpoint ou final |
| GET | `/sessoes/{id}/leituras?apos_ordem=…` | Consultar recibos com paginação por ordem |
| GET | `/sessoes/{id}/leituras/{leitura_id}` | Consultar evidência e envelope de integridade |
| GET | `/sessoes/{id}/integridade` | Recalcular e conferir a cadeia local |

Contrato completo: `docs/openapi-0.4.json`. Exemplo produzido por execução HTTP com dados fictícios: `docs/exemplo-ingestao-sintetica-0.4.json`. O exemplo não contém credenciais.

## Contrato para a próxima integração Android

O aplicativo deve salvar a empresa e o operador originais, IDs da carga/volume/sessão/evento, conteúdo e chave de envio **antes** da primeira tentativa. Deve reenviar exatamente a operação preservada. Não deve trocar o autor quando outra pessoa entrar na conta, usar uma chave nova para esconder conflito ou atribuir automaticamente leituras antigas sem cliente.

O contrato de abertura usa UUID de sessão gerado pelo app, etiqueta do catálogo, evento inicial e configuração declarada. Admite somente base de tempo `instante_start` do fluxo Android. O UID deve estar na mesma ordem canônica do catálogo; inversão entre SDKs ainda precisa ser conferida com hardware.

Checkpoint/final repetem a identidade técnica da sessão: UID, epoch de início e intervalo. Cada evento informa versão do SDK, versão do decodificador, aparelho e origem. Data sem fuso, números não finitos, intervalo fracionado, confirmação ambígua e campos extras de autoria são recusados. A autoria vem da conta autenticada, nunca de um `usuario_id` enviado no corpo.

Limites de engenharia da API: 1 MiB por corpo HTTP de escrita, contado antes do parser, mesmo com cabeçalho incorreto; até 256 KiB de strings brutas em UTF-8 por evento, até 64 itens e até 131.072 caracteres por item, respeitado o limite total em bytes. Esses limites não qualificam modelos de etiqueta. Recibos carregam a situação `evidencia_recebida_sem_validacao_fisica` e `stop_fisico=nao_confirmado_pelo_servidor`.

Falha de rede exige reenvio da operação salva. HTTP 409 exige manter o item e tratar o conflito; não significa sincronização concluída. Um novo operador pode criar um novo checkpoint na mesma sessão, mas não reenviar como autor de um evento anterior.

## Integridade e isolamento

O envelope cobre requisição validada/canônica, autoria, identidades, origem, instantes e ordem de recebimento. A versão `tt-evidencia-1` usa SHA-256 sobre JSON com chaves ordenadas, UTF-8 e separadores compactos. O hash encadeado calcula SHA-256 de `ThermoTrace/evidencia/v1` + byte zero + hash encadeado anterior em bytes (vazio na primeira leitura) + hash do envelope em bytes. Os recibos exibem hashes em hexadecimal.

É integridade da cadeia **local**, sem âncora externa. O teste detecta adulteração do bruto mesmo quando o dono do banco de teste desliga o gatilho para simular corrupção. Um administrador com domínio completo do banco e dos hashes não é neutralizado por essa cadeia; manifesto externo, backup imutável e procedimentos de auditoria permanecem no plano.

RLS agora também cobre dispositivos, etiquetas, vínculos, sessões, leituras, séries e excursões, além das tabelas já protegidas na versão 0.3. Inserções de dispositivo/vínculo/sessão/leitura exigem a autoria corrente no contexto da transação. Identidades e evidências ficam protegidas por gatilhos; o fechamento lógico novo não pode ser reaberto ou reescrito. Um índice impede dois vínculos ativos no mesmo volume. As permissões fornecidas à API não incluem apagar ou truncar evidências.

O fluxo HTTP também foi testado com o conjunto de permissões mínimas do script de implantação, incluindo fechamento por outro operador da mesma empresa. O administrador de banco usado nas migrações é separado do papel público da API.

## Implantação e catálogo

1. Fazer backup e verificar em homologação a migração até `a20409120002`. Existência de dois vínculos ativos no mesmo volume impede a criação do índice e deve ser revisada; a migração não apaga nem escolhe registros.
2. Para papel novo, aplicar `scripts/runtime-permissoes.sql`; para instalação 0.3 já existente, aplicar `scripts/runtime-ingestao-permissoes.sql` após a migração. Definir credenciais e HTTPS no ambiente apropriado.
3. Catálogo físico precisa ser provisionado previamente. `python -m app.catalogo_cli --help` fornece cadastro local de etiqueta em lote existente, com cliente e administrador indicados. Exige credencial de implantação; não roda com as permissões públicas da API. Registra inventário declarado e não comprova calibração ou identidade física. A gestão de lotes e etiquetas pelo portal ainda falta.
4. Registrar a instalação via API, localizar a etiqueta do cliente e executar o fluxo do exemplo somente com dados de homologação.

Não foram criadas contas de clientes reais, etiquetas fictícias no inventário real, credenciais públicas ou serviço publicado.

## Limites e próxima entrega

Esta etapa recebe e protege a evidência. Ainda não decodifica o SDK no servidor, consolida amostras conflitantes, calcula gráficos/relatórios centrais nem valida fisicamente ativação, medição, calibração ou STOP. O corpo bruto permanece disponível para implementação e revisão do decodificador. Não há decisão automática de liberação de cargas de saúde.

O Android continua na versão 0.8.0: login e consulta de cargas disponíveis, mas sem envio por estas rotas. Próximo trabalho: separar banco e fila local por cliente/operador, associar carga/volume/sessão online, integrar fila transacional e recibos, depois decodificação/consolidação no servidor. Recuperação de senha, MFA, compartilhamentos entre empresas, suporte auditado, portais, backup/restore operacional, distribuição e bancada física seguem pendentes.

## Reproduzir a validação

Usar exclusivamente banco descartável com nome terminado em `_test`:

```powershell
.\scripts\testar.ps1 -DatabaseUrl 'postgresql+psycopg://USUARIO@127.0.0.1:PORTA/thermotrace_test' -ReportPath 'resultado.xml'
```

Permanece um aviso de depreciação do cliente HTTP usado nos testes. Esta entrega não executou ensaio de carga comercial, teste físico ou publicação.
