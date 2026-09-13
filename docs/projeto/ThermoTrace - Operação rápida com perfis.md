---
tipo: pesquisa-e-especificacao
projeto: ThermoTrace
status: em-validacao
atualizado: 2026-09-11
---

# Perfis rápidos — especificação da experiência

Status: proposta de implementação, não tela já entregue. Diretriz do usuário: processo rápido.

## Configurar uma vez, usar em segundos
O responsável técnico/qualidade da empresa cadastra o item e associa um perfil versionado, com faixa, restrições, prazo e fonte. A ThermoTrace pode preparar o cadastro; o operador de transporte recebe apenas os perfis liberados para sua empresa.

Fluxo normal de saída: **ler código da carga → conferir produto/perfil sugerido → aproximar etiqueta**. Se a carga já existe, nenhuma digitação de temperatura. Se o código for apenas uma chave de NF sem itens integrados, ele identifica a carga mas não revela sozinho os produtos: usar itens previamente cadastrados/importados ou um favorito validado.

Exemplo de cartão: **Carga 1542 · NovoRapid FlexPen fechado · 2–8 °C**. Botão: **Iniciar leitura**. Perfil e fonte detalhados ficam em “Ver detalhes”. Sucesso só após confirmação da operação NFC; proximidade detectada recebe feedback diferente de operação concluída.

Checkpoint: **aproximar → atualizar histórico**. Produto, faixa e carga vêm do vínculo da sessão. No destino: **leitura final → gráfico → baixar relatório**; indicar parada física separada quando necessária pelo protocolo. Downloads não devem exigir recadastro.

Objetivo de UX: até 3 ações principais e nenhuma digitação térmica no fluxo recorrente. Meta preliminar de interação abaixo de 15 segundos, excluindo transferência NFC e sincronização; medir em bancada antes de prometer tempo total.

## Evitar perguntas repetidas
- Favoritos por cliente e rota com produto/condição explícitos; nunca lembrar “última temperatura” global do aparelho.
- Cadastro de itens antecipado pelo gestor, importação/integração futura e cache offline de perfis aprovados.
- Scanner de produto (GTIN/DataMatrix) apenas resolve um cadastro previamente validado; não inferir perfil pelo nome ou NCM.
- Em carga com perfis incompatíveis, separar volumes/compartimentos/sensores; não tirar média dos limites.
- Não aplicar automaticamente a interseção de faixas: compatibilidade de acondicionamento, congelamento e tempo também precisa de validação.

## Exceções com mensagem curta
Produto desconhecido: “Perfil ainda não cadastrado” e encaminhamento ao gestor; permitir rascunho sem declarar conformidade.
Etiqueta incompatível: “Esta etiqueta não atende à faixa deste produto”; registrar motivo técnico nos detalhes.
Coleta com falha: “Leitura não concluída. Aproxime novamente”; conservar dados parciais com estado explícito.
Excursão: “Fora da faixa — avaliação necessária”. Dados incompletos: “Histórico incompleto”. Faixa atendida: “Sem desvio observado”. Liberação do produto é evento separado assinado por pessoa autorizada.

## Regras de engenharia
Perfil aprovado é congelado na sessão, junto à versão e responsável. Mudança de catálogo não reescreve viagens anteriores. Expiração/revogação impede novas ativações conforme política offline; sessões existentes continuam permitindo coleta e preservação de evidência.

Não pré-configurar tolerância universal (ex.: 30 minutos) nem “MKT aprovou”. Toda leitura fora do limite gera ocorrência técnica; avaliação pode registrar liberação fundamentada sem apagar a excursão. Separar aviso preventivo, limite de especificação e critério de decisão. Histerese pode controlar notificações, nunca ocultar amostras.

Guardar limites inclusivos/exclusivos, resolução/incerteza, regra de decisão aprovada, unidade, intervalo e versão do cálculo. Não arredondar antes da comparação. Intervalo de amostragem é escolhido na qualificação com duração/memória/risco, não um valor universal determinado nesta pesquisa.

Antes de liberar perfil, conferir faixa e precisão da etiqueta, calibração, autonomia, memória, posicionamento e embalagem qualificada. Sensor na caixa não prova sozinho a temperatura interna do produto. O bip recupera o registro; cobertura entre bipes exige registro autônomo validado.

## Testes de aceite
- Carga conhecida preenche perfil sem digitação; perfil de outra conta não aparece.
- Mesmo produto aberto e fechado não compartilha cadastro indevido.
- 8,01 °C excede limite superior 8 °C sem arredondamento prévio; decisão final segue regra metrológica do cliente.
- Upload offline, perfil atualizado e dois aparelhos preservam a versão da sessão.
- Hemácias em transporte não recebem perfil de armazenamento, nem plaquetas perfil refrigerado.
- Produto desconhecido/incompatível não recebe automaticamente status de conformidade.
- Meta de rapidez verificada com operador real e etiqueta física, incluindo falhas e recuperação.

## Cadastro inicial por cliente — decisão de 2026-09-11
A ausência de lista comercial de produtos agora não impede o desenvolvimento. Na implantação: responsável informa produto/apresentação/condição, associa documento e escolhe modelo compatível; define parâmetros de transporte e aprova. Depois disso, os operadores veem somente favoritos e cargas já configuradas. O cadastro técnico fica fora do embarque recorrente. Sem produto identificado, não escolher 2–8 °C como padrão universal nem liberar conformidade.
