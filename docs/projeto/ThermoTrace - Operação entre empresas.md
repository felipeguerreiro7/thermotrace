---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-10
---

# Operação entre empresas · API 0.12 · Android 0.8.9

Entrega de 10/11/2026, projeto pessoal ThermoTrace. Fecha a etapa que o [[ThermoTrace - Compartilhamento de cargas WEB-10]] deixou aberta: a transportadora deixa de só consultar a carga compartilhada e passa a bipar nela, quando o dono autoriza.

## O que mudou no uso

1. O dono cadastra a carga e, ao compartilhar com a transportadora, marca **"Permitir operar a carga"**.
2. O gestor da transportadora aceita o convite, como antes.
3. O operador da transportadora ativa a etiqueta no celular, abre "Enviar coletas", busca a carga pelo código e escolhe o volume. A carga aparece identificada como "Carga de X, compartilhada com a sua empresa".
4. Checkpoints e leitura final seguem pelo mesmo caminho. O dono vê a sessão, o gráfico e a viagem no portal, e cada leitura da transportadora aparece na auditoria dele com a empresa e o operador que biparam.

## Decisões provisórias desta entrega

O WEB-10 registrou que esta etapa exigia decidir de quem é a etiqueta e quem fecha a sessão. Decisão de implementação, substituível pelo usuário ou pelo primeiro contrato:

- **Etiqueta:** é sempre do catálogo de quem ativa. A transportadora usa as etiquetas dela na carga do dono; o dono continua usando as dele. Nenhuma empresa enxerga o catálogo da outra.
- **Aparelho:** sempre da empresa de quem bipa.
- **Quem fecha:** qualquer das duas empresas com acesso à carga envia a leitura final. As duas também podem enviar checkpoints na mesma sessão, não importa quem ativou.
- **O que continua só com o dono:** documentos, cancelamento, novos compartilhamentos e a evidência bruta.
- **Permissão fixa no convite:** para mudar entre consultar e operar, o dono encerra o compartilhamento e convida de novo. A transportadora aceita sabendo exatamente o que foi concedido.
- **Encerramento no meio da viagem:** vale na requisição seguinte. O que a transportadora já enviou fica com o dono, íntegro. Coletas que ainda estavam só no celular dela deixam de ser aceitas pelo servidor e permanecem no aparelho, exportáveis pelo Excel do app.

## Proteções

- Cada leitura registra a empresa que a enviou (`empresa_autora_id`). O banco recusa leitura gravada em nome de outra empresa, inclusive pelo dono em nome da transportadora. A conferência de integridade compara essa coluna com o envelope assinado pela cadeia.
- As regras do banco (RLS) permitem à transportadora autorizada somente: vincular etiqueta dela a volume da carga concedida, abrir e fechar a sessão, gravar leitura e mudar o andamento do volume. Um gatilho impede que ela altere qualquer outro dado do volume.
- A conferência do UID nos checkpoints passou a usar o UID preservado na evidência de ativação, porque quem continua a sessão pode ser de outra empresa e não enxerga a etiqueta de quem ativou.
- Uma trava por carga coloca dono e transportadora na mesma fila ao iniciar monitoramento ou cancelar a carga.

## Defeito encontrado e corrigido no Android

`ContratoEnvio.destino` só oferecia volumes com `monitorado = true`. O servidor cria todo volume com `monitorado = false` e só marca `true` quando uma etiqueta é ativada nele. Na prática, uma carga recém-cadastrada pelo portal apareceria no app **sem nenhum volume para escolher**, e o envio celular → servidor não começaria. O teste automático não acusava porque o dado de teste trazia `monitorado = true`, valor que o servidor real nunca devolve para volume livre.

Corrigido: o app oferece os volumes livres, e avisa quando todos já estão em monitoramento. O dado de teste foi alinhado ao servidor. **Não foi conferido em aparelho**; é o primeiro ponto a olhar no ensaio ponta a ponta (WEB-04).

## Evidências

- API: 197 testes aprovados em PostgreSQL 17 descartável (189 anteriores + 8 novos), `alembic check` sem divergência. Os novos cobrem: transportadora ativa, acompanha e encerra; as duas empresas bipando na mesma sessão; carga Y do mesmo dono e empresa B intocáveis; sem permissão de operar a resposta é 403; revogação no meio da viagem; fluxo inteiro com o papel mínimo de `runtime-permissoes.sql`, com RLS valendo; e gravações diretas por SQL recusadas pelo banco.
- Um teste anterior mudou de expectativa: transportadora sem permissão de operar tentando iniciar sessão recebia 404 e agora recebe 403 com explicação, porque a carga já é visível para ela.
- Portal: 17 testes web aprovados; `node --check` sem erro. O formulário novo **não foi conferido no navegador** nesta rodada.
- Android: 152 testes JVM aprovados (150 anteriores + 2 novos: carga compartilhada só é destino com permissão de operar; volume já monitorado não é oferecido). Sem ensaio em aparelho nem no emulador.
- Contrato exportado em `backend/docs/openapi-0.12.json`.

## Publicação

Migração nova `e10411100001`, aplicada pelo `preDeployCommand` do Render. Não cria permissão nova: o papel restrito `thermotrace_runtime` já tem o que as regras novas usam. Sem mudança em `render.yaml`, serviço ou custo. O Android 0.8.9 exige a API 0.12 para a busca de cargas compartilhadas; com API anterior ele continua funcionando para cargas próprias.

## Limites e próximos passos

1. O painel "Acompanhamento" da transportadora ainda mostra só cargas próprias; as etiquetas dela abertas em cargas de terceiros aparecem no painel do dono.
2. Continuar no celular da transportadora uma sessão ativada no celular do dono depende da continuidade entre aparelhos (TT-032), que segue aberta. O servidor já aceita; falta o app.
3. Destinatário ainda sem papel próprio: link de consulta e aceite no recebimento ficaram como ideias na conversa de 10/11.
4. WEB-04: repetir este fluxo no ambiente publicado, com duas empresas fictícias e um aparelho.
