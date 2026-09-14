---
tipo: execucao
projeto: ThermoTrace
contexto: pessoal-independente
status: implementado-aguardando-ensaio
atualizado: 2026-09-14
---

# Fechamento do ciclo — a confirmação do STOP virou evidência

Sessão de 14/09/2026. Commit `65431d9`. Fecha o item 1 do bloco C de
[[ThermoTrace - Triagem para rodar na semana]], que era o único passo obrigatório
do fluxo nunca exercitado, e atende à primeira metade do aceite pendente do TT-012.

## O defeito

O app tinha duas coisas diferentes e uma palavra só.

`SessaoEntity.encerradaEmMillis` era gravado sempre que a leitura final era
persistida, e junto o volume passava a `StatusVolume.ENCERRADO`. Isso diz apenas
que **o celular gravou o histórico**.

Quem responde se a etiqueta **parou de gravar** é a etiqueta, no evento
`LoggingParado`. Essa resposta existia só como frase na tela de coleta. O
operador saía da tela e ela sumia. Dali em diante o volume aparecia encerrado e
nada no aplicativo contradizia essa palavra — nem a tela da remessa, nem o Excel.

STOP recusado não é hipótese remota: é resultado previsto, o fluxo tem um botão
"Parar registro" justamente para repetir. O app sabia disso por alguns segundos
e depois esquecia. Num laudo de cadeia fria, afirmar um fechamento que ninguém
verificou no hardware é pior do que não ter a informação, porque parece
verificado.

Vale registrar o contraste: a mesma classe de defeito — estado de verificação
que só existia enquanto a tela estava aberta — apareceu em `1b76ec9`, na
reconciliação cabeçalho x série. São duas ocorrências do mesmo hábito, e o hábito
é o que vale corrigir: **verificação que não é persistida não é evidência, é
recado**.

## O que foi feito

1. **Coluna própria na sessão.** `loggerParadoEmMillis`, migração 4 → 5, anulável,
   sem valor retroativo. Guarda **quando** a etiqueta confirmou o STOP.
2. **Escrita única.** `Repositorio.marcarLoggerParado` não sobrescreve. O STOP
   pode ser repetido; o instante que vale é o da primeira confirmação. Reescrever
   a cada tentativa apagaria quando o chip de fato parou.
3. **Os dois caminhos registram.** `Encerrar` (baixa e para na mesma aproximação,
   pela tela inicial) e `StopLogging` avulso (o retry de "Parar registro", na
   tela de coleta e na inicial). Recusa não vira registro nenhum.
4. **A regra mora no domínio.** `FechamentoDoCiclo.de(encerrada, loggerParado)`
   devolve `EM_ANDAMENTO`, `FINAL_SEM_STOP` ou `FECHADO`. A tela da remessa e a
   exportação fazem o mesmo julgamento; três cópias da mesma condição é como duas
   delas começam a discordar. Quatro casos travados em `FechamentoDoCicloTest`.
5. **A tela avisa.** Cartão do volume, em vermelho: a leitura final foi
   registrada, a etiqueta não confirmou o STOP, ela pode continuar gravando, use
   "Parar registro".
6. **O Excel separa as duas colunas.** "Sessão encerrada em" e "STOP confirmado
   pela etiqueta em". Uma é o que o celular gravou, a outra é o que a etiqueta
   respondeu. Sem confirmação, a célula diz `NÃO CONFIRMADO`.

## Uma decisão que merece ser explícita

`loggerParadoEmMillis = null` cobre de propósito **dois casos que não se
distinguem**: STOP recusado, e sessão anterior à existência da coluna.

Foi deliberado não inventar um terceiro estado para o histórico antigo. Os dois
pedem exatamente a mesma conduta — encostar a etiqueta e conferir — e tratar a
sessão antiga como "parada" seria supor sobre um hardware que ninguém consultou.
É a mesma regra já aplicada em TT-047 (localização) e TT-054b (exportação):
ausência explícita é informação; chute não é.

## O que isto **não** resolve

- **Não foi exercitado em etiqueta real.** Compilado e instalado no emulador,
  nada mais. A confirmação do STOP nunca passou por um chip de verdade — é
  exatamente o que o ensaio da semana precisa encostar primeiro.
- **Não existe teste de migração.** O projeto usa `exportSchema = false`, então
  `MigrationTestHelper` não tem contra o que comparar e nenhuma das migrações
  1→2, 2→3, 3→4, 4→5 tem teste. Numa base de auditoria, onde
  `fallbackToDestructiveMigration` está proibido de propósito, migração errada
  não perde dado: trava o app na abertura. Aberto como TT-056.
- **Não fecha o TT-012.** A segunda metade do aceite — reuso seguro da etiqueta
  depois do STOP — continua dependendo de bancada.

## Próximo passo de quem está com o aparelho

1. `:app:testDebugUnitTest` (ver [[ThermoTrace - Plano de ação e backlog]]).
2. Ciclo completo num volume: ativação, dois checkpoints, **leitura final e STOP**.
3. Conferir na tela da remessa que, com o STOP aceito, o aviso vermelho **não**
   aparece; e forçar uma recusa (afastar a etiqueta no momento do STOP) para ver
   se aparece.
4. Exportar e conferir as duas colunas novas na aba Auditoria.
5. Anotar em [[ThermoTrace - Ensaio em aparelho 2026-09-12]], inclusive o que falhar.

Relacionado: [[ThermoTrace - TT-005 reconciliação cabeçalho x série]],
[[ThermoTrace - Decisões e pendências]], [[ThermoTrace - Central do projeto]].

## Uma terceira ocorrência do mesmo hábito — e o padrão que fica

Ainda em 14/09, commit `ca28bde`: a **localização da coleta** também só existia na
tela de coleta. Gravada no banco desde `188b620`, nunca mostrada depois. Terceira
aparição, nas mesmas horas, do mesmo erro de projeto.

O padrão vale mais que os três conserto isolados, então fica escrito:

> Sempre que o app **verifica** ou **captura** alguma coisa, perguntar se aquilo
> sobrevive à saída da tela. Se a resposta é não, aquilo é recado, não evidência —
> e quem assina o laudo nunca vai ver.

Os três casos tinham a mesma forma: dado correto, persistido ou calculável,
exibido num único instante e nunca mais. O conserto foi sempre o mesmo — recalcular
ou reler do que já está guardado e mostrar no lugar onde a decisão é tomada, que é
a tela da remessa e o Excel, não a tela do bipe.

Um cuidado que só apareceu no terceiro caso: **a mesma informação muda de sentido
conforme onde é exibida**. "Fix de 3 min antes" responde a pergunta certa na hora
do bipe; no histórico, meses depois, "fix de 4 meses antes" não responde nada. Por
isso a idade do fix passou a ser medida contra o instante da coleta, não contra
agora. Reexibir não é copiar.

## Revisão posterior — 0.8.5, 14/09/2026

A persistência de STOP adicionada pelo Opus foi mantida e tornada atômica: uma nova confirmação não sobrescreve a primeira. Falhar na gravação agora produz aviso explícito. O atalho Finalizar da Home foi redirecionado ao fluxo formal, que salva a leitura antes de solicitar STOP. Os testes automatizados cobrem sucesso/falha de gravação, mas não substituem ensaio físico de TT-012/TT-057.

A marca de exportação anterior podia ser criada só por gerar cache. A versão 6 do banco preserva o campo antigo e acrescenta confirmação separada, preenchida apenas após salvar e reler o arquivo escolhido pelo operador. Cancelar o seletor não confirma cópia. Veja [[ThermoTrace - Revisão integrada Android 0.8.5]].
