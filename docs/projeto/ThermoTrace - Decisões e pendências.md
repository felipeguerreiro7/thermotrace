---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Decisões e pendências

## Requisitos confirmados pelo usuário em 2026-09-11
Paridade funcional com o chinês; acompanhamento por bipes; gráficos ao longo e ao fim; gráfico e relatório para download; acesso privado por cliente; visão da equipe ThermoTrace; auditabilidade; identificação da carga.

## Propostas técnicas, ainda sujeitas à validação
| ID | Proposta | Razão | Validação |
|---|---|---|---|
| D01 | Reaproveitar Android e backend atuais | Já há implementação relevante | Build e testes de bancada |
| D02 | Clientes isolados no PostgreSQL compartilhado inicialmente | Operação e migrações centralizadas | Confirmar se “banco por cliente” exige separação física |
| D03 | UUID de carga + documentos associados | Entregas parciais e múltiplas notas | Validar processo comercial |
| D04 | Evidência imutável, correções versionadas | Reconstrução histórica | Testes de privilégios e restauração |
| D05 | Android primeiro; iOS após homologação | Base Android concreta | Lista de aparelhos do piloto |
| D06 | Alembic/backend como esquema canônico | Evitar SQL e modelos divergentes | Comparar modelo antigo e preservar dados |

## Lacunas para fechar
- Primeiro cliente/setor, tipo de carga e responsável de qualidade.
- Teste físico atual: START, checkpoint, final e STOP funcionaram em quais etiquetas/celulares?
- Versão exata e lista completa de funções do app chinês; amostra de exportação e licença do SDK.
- Necessidade de iPhone no primeiro piloto.
- Faixas térmicas, intervalos, duração das viagens, tolerâncias, calibração e critérios de aprovação definidos pelo cliente.
- Quantidade de clientes, usuários, cargas/mês e etiquetas/carga; retenção e região de hospedagem.
- Bancos fisicamente separados ou acesso segregado por cliente; orçamento e titularidade de domínio/contas.
- Quem pode compartilhar carga com transportadora/destinatário e por quanto tempo.

## Riscos principais
Hardware incompatível → bancada antes de promessa comercial. Documentação divergente → código + execução como evidência. Vazamento entre clientes → testes negativos no banco, API e arquivos. História duplicada → índice da amostra e idempotência. Relógio incorreto → tempos distintos e metodologia versionada. Internet ausente → fila e relatório provisório. Falta de calibração → registrar limitação e envolver qualidade.

Atualizar cada decisão com autor, data, evidência e status aprovado/substituído. Não sobrescrever a justificativa anterior.

## Resposta recebida
Confirmado: piloto com parceiros da área de saúde; app atual instável no NFC e incorreto nos gráficos. Continua pendente: parceiro nominal, produto monitorado, modelo/lote da etiqueta, aparelhos e exemplos reproduzíveis. Decisão de planejamento desta retomada: estabilidade e integridade do gráfico são bloqueadores de liberação.

## Definição comercial — 2026-09-11
O usuário informou que os produtos específicos só serão conhecidos com a entrada no mercado. A lista de SKUs não está disponível agora e não é dependência para construir a plataforma. Desenvolver biblioteca de perfis-base e cadastro por cliente. Antes da primeira operação real de cada produto, associar apresentação/condição e documentação ao perfil, com validação pelo responsável do cliente. Não presumir que os modelos pesquisados representam a demanda comercial.

## Separação de contexto — definição do usuário

ThermoTrace é um projeto pessoal independente e não integra os projetos do trabalho. Manter organização, materiais e decisões separados. A pasta atual está em `03 - Projetos/ThermoTrace`, fora de `03 - Projetos/Clientes`.

## D07 — Localização da coleta via Google Play Services (2026-09-12)

Decisão do usuário, 2026-09-12: **adicionar Google Play Services** e usar
`FusedLocationProviderClient.getCurrentLocation()` para a localização da coleta, em vez de
ficar com `LocationManager` sem dependência Google.

Autor da decisão: usuário. Status: aprovado. Evidência e alternativas comparadas em
[[ThermoTrace - Transparência da coleta e localização]] (TT-048).

Razão: é o caminho que a documentação do Android chama de recomendado para obter um fix
novo, dá melhor precisão e evita gerenciar `requestLocationUpdates()` à mão. O `minSdk` 26
deixa de ser problema, porque a alternativa sem Play Services (`LocationManager.getCurrentLocation`)
só existe da API 30 em diante e exigiria caminho duplicado.

Custo aceito: primeira dependência Google do aplicativo, e aparelhos sem serviços Google
ficam sem localização — o app precisa continuar operando inteiro nesse caso, gravando
"sem localização" de forma explícita.

Condições que seguem valendo, de [[ThermoTrace - Transparência da coleta e localização]]:
a coordenada é evidência e o endereço não; a coleta nunca espera o GPS; precisão, provedor
e instante do fix são gravados junto; e a localização é dado pessoal do operador, sujeita a
divulgação e proteção contra alteração.

## D08 — Checkpoint registra automaticamente no bipe (2026-09-12)

Decisão do usuário, 2026-09-12: o checkpoint deve ser **registrado automaticamente** quando
a etiqueta é bipada, sem exigir toque de confirmação. Resolve o TT-044.

Autor da decisão: usuário. Status: aprovado. Alternativas e a tensão de auditoria em
[[ThermoTrace - Proposta de gráfico e coleta]].

Escopo da automação: **somente o checkpoint**. A razão está na semântica que o próprio
`TipoLeitura` já declara — checkpoint não é obrigatório e não escreve na etiqueta, é
observação de leitura. Ativação escreve configuração no chip; leitura final encerra o
monitoramento e fecha o laudo. Essas duas seguem exigindo ato explícito do operador.

Por que isto não afrouxa a cadeia de custódia: o risco medido em campo em 12/09 foi
evidência **baixada e perdida** na volta da tela, não evidência registrada sem conferência
(ver [[ThermoTrace - Ensaio em aparelho 2026-09-12]], achado das 13:30). Gravar na hora do
bipe elimina a perda. O operador continua vendo o veredito depois — o que ele deixa de
fazer é autorizar a gravação de algo que a etiqueta já havia registrado por conta própria.

Implementação (build 13:51): `persistir()` extraído de `confirmar()`; o checkpoint chama
`registrarCheckpointAutomatico()` ao fim do download, que persiste **sem fechar a tela** —
o campo novo `registrada` separa "já gravado" de `concluido`, que continua significando
"pode sair". A barra do rodapé deixa de cobrar ação e passa a confirmar o que aconteceu,
com "Voltar para a remessa"; o painel diz "Coleta registrada — pode afastar o celular".

A confirmar no aparelho: que o checkpoint grava sem toque, que a tela não fecha sozinha, e
que a coleta aparece na linha do tempo ao voltar.


## Aplicação de D08 na revisão 0.8.2

Checkpoint segue automático. Na tela de leitura final, aproximar só baixa; **Encerrar monitoramento** é a autorização explícita. O app primeiro persiste, depois solicita STOP e mantém a tela aberta com o resultado. Substitui a implementação intermediária descrita no build 14:32, que armava Encerrar automaticamente. Não altera D07. Evidências e limites em [[ThermoTrace - Revisão de campo Android 0.8.2]].

## Risco de processo — duas IAs no mesmo repositório sem versionamento (2026-09-12, 15:16)

Registro de fato observado, não proposta.

Entre 14:41 e 15:07 de 12/09 o Codex editou os mesmos arquivos em que o Claude havia
trabalhado às 14:32, no mesmo diretório `C:\Users\lfgue\ThermoTrace`, **sem controle de
versão**. Arquivos tocados pelos dois: `LeituraViewModel.kt`, `LeituraScreen.kt`,
`RemessaScreen.kt`, `SessionDecoder.kt`, `ExportadorLaudo.kt`, `ReconciliacaoTest.kt`.

Desta vez deu certo: o Codex **preservou** o trabalho anterior e o melhorou — extraiu
`Reconciliacao` para arquivo próprio, criou `HistoricoColetas.kt` (TT-045),
`EvidenciaBrutaXlsx.kt`, `LeituraViewModelTest.kt` e `ExportacaoAuditoriaTest.kt`, e
refinou a ordem do STOP (ver abaixo). Não houve perda.

Mas isso foi sorte somada a notas de cofre bem escritas, não garantia. Sem `git`, uma
sobrescrita não tem como ser desfeita nem detectada: não há diff, não há histórico, não há
"voltar para a versão de antes". O **TT-010** do backlog — "Versionar fonte canônica" — está
aberto e marcado P0 desde o início, e este episódio é a evidência concreta de por que ele
vem antes de qualquer outra coisa.

Recomendação: `git init` e primeiro commit antes da próxima rodada, com `.gitignore`
cobrindo `local.properties`, `backend/.env`, `.gradle*`, `build/` e os APKs. Enquanto não
houver versionamento, o mais seguro é uma IA por vez no repositório.

### Divergência técnica resolvida a favor do Codex

O Claude havia trocado a leitura final de `Operation.Download("destino")` para
`Operation.Encerrar()`, que baixa e envia o STOP na mesma aproximação. O Codex desfez isso e
manteve `Download`, com STOP separado em `pararRegistro()`, gatilhado só quando
`registrada == true` — ou seja, **só depois de a evidência estar persistida no banco**.

A escolha do Codex é melhor e fica como a válida. Com `Encerrar`, o STOP é enviado antes de
a leitura chegar ao banco local: uma falha do app nessa janela pararia a etiqueta com o
histórico não salvo. A ordem correta para um sistema de evidência é persistir primeiro,
parar depois. Coerente com a D08.

### Versionamento feito no mesmo dia (2026-09-12, 15:30)

Repositório git inicializado na raiz do projeto, branch `main`, commit inicial `867a107`:
221 arquivos, 1,9 MB. O trabalho das duas IAs foi preservado no mesmo commit, porque nada
havia se perdido até aqui.

Conferido arquivo a arquivo antes de commitar: `backend/.env`, `local.properties`, caches
do Gradle, `build/`, `.venv` e os APKs ficaram de fora. `core.autocrlf=false`, para o git
não reescrever as quebras de linha CRLF que o projeto usa.

Pendente para fechar o TT-010 por inteiro: validar o aceite "clone limpo compila" e criar
um remoto privado — enquanto o repositório existir só neste computador, ele protege contra
sobrescrita entre IAs, mas não contra perda do aparelho.

### Subir para o GitHub — verificação feita em 12/09/2026, antes do push

Varredura dos 221 arquivos rastreados, antes de qualquer envio:

1. Nenhuma chave privada, certificado, `.p12`, `.jks` ou `.keystore`.
2. Nenhum segredo real. A única senha em texto é `POSTGRES_PASSWORD: senha_local` no
   `docker-compose.yml`, que é de ambiente local — mas não deve ser reaproveitada em
   homologação nem produção.
3. `backend/.env` e `local.properties` continuam fora do versionamento.

**O repositório precisa ser privado, e há uma razão específica além da óbvia.** O módulo
`nfcinstruct` é código-fonte do SDK do fabricante (Fudan/FMSH) embutido no projeto, e **não
há arquivo de licença nenhum** dentro dele. Publicar isso em repositório público pode violar
os termos do fornecedor. Isso conecta com uma lacuna que já estava aberta nesta mesma nota:
"versão exata e lista completa de funções do app chinês; amostra de exportação e **licença do
SDK**". Enquanto essa licença não for conhecida, repositório público está fora de questão.

Autenticação é ato do usuário: nenhuma IA deve receber token de acesso pessoal nem chave SSH.
O caminho mais curto é o próprio Android Studio, que já está com o projeto aberto —
Git → GitHub → Share Project on GitHub faz login por navegador, cria o repositório e envia.

Regra que vale a partir daqui: se algum segredo for commitado por engano, **trocar o segredo**
é obrigatório. Apagar o commit não basta, porque o valor já saiu do controle.


## Decisões confirmadas em 13/09/2026 — públicos e publicação

O usuário definiu **contratante como transportadora** e **cliente como dono da carga**. Staff é a equipe ThermoTrace. Permissões gestor/operador continuam internas à empresa; não se confundem com esses públicos. Vínculo explícito por carga e autorização auditada serão necessários para acesso entre empresas.

Domínio comprado: thermotrace.com.br. GitHub: https://github.com/felipeguerreiro7/thermotrace. Render: Blueprint thermotrace-homologacao, serviço thermotrace-api, Docker/Oregon, status Deployed no print. O endereço público do serviço não apareceu na captura. [[ThermoTrace - Infraestrutura confirmada]].

Instrução permanente do usuário: usar GitHub nas entregas e commitar explicando o que mudou e por quê. Procedimento em CONTRIBUTING.md. O Obsidian continua sendo o planejamento operacional; cópia apenas das notas ThermoTrace em docs/projeto acompanha os commits.

Decisão de implementação: Portal 0.1 no contêiner da API já contratado; sem serviço extra ou mudança de recursos Render. Próximos aceites em [[ThermoTrace - Portal e próximas entregas]]. Permanecem válidas a persistência antes de STOP (D08), localização aprovada (D07) e separação do projeto em relação ao trabalho.
