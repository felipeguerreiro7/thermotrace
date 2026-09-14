---
tipo: plano-de-entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-09-13
---

# Portal e próximas entregas

Plano vigente para as duas frentes solicitadas em 13/09/2026: organizar o projeto no Obsidian e iniciar o site, com entregas registradas no GitHub. Não substitui o relato de falhas de checkpoint/final ou o TT-005.

## Decisões confirmadas pelo usuário

- **Staff:** equipe da ThermoTrace.
- **Contratante:** a transportadora.
- **Cliente:** o dono da carga.
- Domínio comprado: **thermotrace.com.br**.
- Repositório: https://github.com/felipeguerreiro7/thermotrace, branch `main`.
- GitHub é o caminho padrão para cada entrega; commits explicam o que foi feito e por quê.
- ThermoTrace permanece um projeto pessoal separado dos projetos do trabalho.

Ser gestor/operador é uma permissão dentro da empresa, não a definição de cliente/contratante. Uma empresa com as duas atividades pode ser `ambos`; isso não concede acesso às cargas de terceiros.

## Frente 1 — organização e publicação

- [x] WEB-01 — Registrar domínio, GitHub, evidência do Render e a definição dos três públicos.
- [x] WEB-02 — Definir rotina de notas + código + testes + commit + push. `CONTRIBUTING.md` e cópia das notas em `docs/projeto`.
- [ ] WEB-03 — Concluir domínio/HTTPS. Dependência: endereço público real do serviço e acesso ao DNS. Aceite: `thermotrace.com.br` abre o portal por HTTPS, sem erro de certificado; API de saúde confirma o banco; commit publicado identificado no Render.
- [ ] WEB-04 — Homologar acesso com duas empresas fictícias e a plataforma. Aceite: cada uma vê só o que está autorizado; login/expiração/saída e buscas funcionam no site e no Android contra o mesmo servidor.
- [ ] WEB-05 — Validar operação do ambiente: rotação da credencial anteriormente exposta, papel de banco restrito, backup/restauração, armazenamento durável e decisão de região antes de dados reais. Ver [[ThermoTrace - Roteiro de contratação e entrega da homologação]].

## Frente 2 — construção do portal

- [x] WEB-06 — Implementar Portal 0.1 no serviço da API: login real, área conforme empresa/papel, lista e detalhe de cargas, busca por código/NF, documentos, volumes, sessões, comprovantes e integridade.
- [x] WEB-07 — Staff consulta empresas; gestores consultam equipe e auditoria da própria empresa. Não existe acesso de suporte irrestrito às cargas.
- [x] WEB-08 — Estados de carregamento/vazio/erro, navegação responsiva, sessão apenas em memória, renovação concorrente protegida e proteção contra inserção de conteúdo executável.
- [ ] WEB-09 — Validar visual e uso no computador e celular, com contas fictícias. Aceite: busca, retorno à lista, paginação, coletas e saída funcionam sem confundir empresas ou perder contexto.
- [ ] WEB-10 P0 — Vínculo entre dono da carga e transportadora. Implementar contrato/vínculo explícito por remessa, aceite e revogação, escopos de leitura/operação/exportação, autoria e testes RLS entre três empresas. Não reutilizar nome de destinatário nem número de NF como autorização. Aceite: cliente A compartilha somente carga X com transportadora T; B e carga Y permanecem invisíveis; revogação bloqueia novas consultas/exportações imediatamente e é auditada.
- [ ] WEB-11 P1 — Gestão pelo portal: cadastrar empresas/usuários e cargas, convites e recuperação de senha. Parte dos endpoints já existe; faltam telas, entrega segura do acesso e auditoria de suporte autorizado. Aceite: nenhum envio de senha por URL ou log; ações sensíveis claras e reversões registradas.
- [ ] WEB-12 P0 — Gráfico e laudo central. Depende de fila Android vinculada à conta e decodificação canônica versionada no servidor. Aceite: temperaturas e horários reproduzíveis do bruto, divergências e ausência de dados explícitas, exportação autorizada com hash/versão e trilha de acesso.

## Sequência de execução e responsáveis

1. Desenvolvimento: entregar Portal 0.1 e notas pelo GitHub. Usuário: informar URL pública do serviço; domínio e públicos já confirmados.
2. Desenvolvimento + usuário: conferir deploy/domínio e rodar WEB-04 com dados fictícios. Sem credenciais em mensagens ou prints.
3. Desenvolvimento: concluir isolamento do banco/fila local por empresa, associar coleta à carga/volume online e integrar ingestão idempotente 0.4. Aceite: retransmissão não duplica e trocar conta não revela dados anteriores.
4. Desenvolvimento: implementar WEB-10 antes de ativar transporte compartilhado. O modelo atual lista cargas da empresa cadastrante; ainda não representa o dono da carga e a transportadora como partes autorizadas independentes.
5. Desenvolvimento: consolidação, gráficos/exportações e administração do portal. Qualidade: validar reconciliação e tratamento de excursões.
6. Usuário + desenvolvimento: bancada com A57/etiqueta, checkpoint recorrente, final com persistência antes de STOP e comparação com fabricante/termômetro aferido. Responsável técnico de qualidade ainda precisa ser designado.

O teste físico continua aberto, mas não bloqueia as entregas independentes acima. Não se declara piloto operacional ou adequação de produto de saúde com base somente no portal ou nos testes automatizados.

## Entrega 0.1 e evidências

Código em `backend/app/portal`; distribuição pelo Dockerfile já existente, sem novo serviço pago e sem alteração no `render.yaml`. O navegador abre `/portal`; a raiz encaminha solicitações HTML e preserva JSON para a API.

O portal mostra dados efetivamente recebidos pela API. Não há valores fictícios no painel, gráfico simulado, relatório central, leitura NFC no navegador nem compartilhamento automático entre empresas. Os comprovantes de coleta e verificações de integridade são consultas reais; a API registra essas consultas.

Validação automatizada e identificação dos commits: [[ThermoTrace - Registro de entregas GitHub]]. Ensaio visual e publicação desse commit no Render precisam de verificação própria; a prévia local não os substitui.

Próximo passo implementável independente do hardware: isolamento local e sincronização de evidências, seguido do vínculo autorizado dono/transportadora. Android permanece 0.8.3; esta entrega não gera novo APK.

## Dependência Android atualizada — 14/09/2026

Android 0.8.5: isolamento local por conta implementado e testado; revisão de final/STOP, cópia e migrações concluída no código. A próxima integração continua sendo enviar a fila para a API 0.4 e persistir recibos. Ainda não há consolidação central de temperaturas ou compartilhamento automático de cargas entre cliente e contratante. Esta entrega não altera o portal nem a infraestrutura. [[ThermoTrace - Revisão integrada Android 0.8.5]].

## Integração inicial do celular — 14/09/2026

Android 0.8.6 envia manualmente novas sessões com evidência de START para cargas/volumes existentes, preservando recibos. O portal pode consultar evidências que a API efetivamente recebeu; esta entrega não acrescenta gráficos ou laudos centrais. Cadastro de catálogo e carga de homologação precisam estar disponíveis antes do percurso real. Seguirem pendentes vínculo entre empresas, continuidade entre aparelhos e consolidação de temperaturas. [[ThermoTrace - Envio de coletas Android 0.8.6]].
