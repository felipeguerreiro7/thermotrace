# Portal ThermoTrace 0.1 — 13/09/2026

Primeira área web conectada à API 0.4, servida em `/portal` pelo contêiner existente no Render. Navegadores que abrem `/` são encaminhados ao portal; consumidores JSON mantêm a resposta da API. Nenhum recurso pago adicional ou migração de banco foi criado.

## Entregue

- Login, consulta da identidade no servidor, renovação de sessão e saída. Tokens apenas em memória, sem cookies ou armazenamento persistente do navegador. Uma única renovação atende consultas concorrentes; resultado incerto não reutiliza refresh token. Atualizar a página exige novo login.
- Áreas derivadas de `tipo_empresa` e `papel` do cadastro autenticado: `plataforma + admin` → equipe ThermoTrace; `embarcador + gestor/operador` → cliente/dono da carga; `transportadora + gestor/operador` → contratante; `ambos` → área identificada com as duas atividades.
- Equipe ThermoTrace consulta empresas. Empresas consultam suas cargas, buscam código/documento exato, abrem detalhes, critério preservado e volumes. Gestores consultam usuários e eventos de auditoria da própria empresa.
- Volumes permitem consultar sessões, comprovantes de início/checkpoint/final, evidência bruta e verificação de integridade. Paginação das listas; origem simulada identificada. Essas consultas usam os endpoints existentes e sua auditoria.
- Estados vazios, erro de conexão, acesso negado, sessão expirada, carregamento e navegação para celular. Conteúdo da API é inserido como texto; CSP bloqueia scripts externos e embutimento em outro site. Portal e respostas autenticadas não são armazenados em cache.

## Limites desta versão

- O transporte compartilhado entre empresas ainda não foi implementado. A listagem existente é de propriedade da empresa cadastrante (`empresa_embarcador_id` no modelo legado), não de contratos de transporte. Uma transportadora não passa a enxergar uma carga de outro cliente só por ter uma conta.
- O site ainda não cria empresas/usuários/cargas, convida pessoas, recupera senha nem exporta laudo. Essas operações de cadastro que já existem na API continuam disponíveis aos responsáveis autorizados.
- O servidor recebe bruto sem consolidar temperaturas: esta versão não apresenta gráfico, veredito térmico ou certificação do sensor. Cadeia íntegra e encerramento lógico não comprovam temperatura correta nem STOP físico.
- Registros que ficaram apenas no banco local do Android não aparecem no portal. Isolamento local, fila integrada e decodificação central continuam prioridades.
- Ensaio visual no navegador/celular e fluxo ponta a ponta no Render não realizados nesta entrega. Prévia local aberta; verificações automatizadas HTTP, permissões e sessão descritas no registro do projeto.

## Publicação

O Dockerfile já inclui a pasta `app`, portanto o portal entra no mesmo deploy. Mantido `render.yaml` sem alterações. Com a versão publicada, abrir `https://ENDERECO_REAL_DO_SERVICO/portal`. Não deduzir o hostname pelo nome do serviço: obtê-lo no painel Render.

Domínio comprado: `thermotrace.com.br`; associação DNS/HTTPS ainda pendente. A consulta de 13/09/2026 encontrou nameservers `a.sec.dns.br` e `c.sec.dns.br`, sem resposta A para a raiz. Blueprint `thermotrace-homologacao`, serviço `thermotrace-api`, Docker/Oregon/Deployed confirmados no print enviado pelo usuário; isso não confirma a publicação do commit desta entrega.

Cadastro do domínio: adicionar o hostname em **Settings → Custom Domains** do serviço, aplicar no provedor DNS os destinos apresentados pelo Render e verificar o certificado. Não substituir destinos por valores adivinhados. [Documentação oficial de domínios](https://render.com/docs/custom-domains), [publicação por commit](https://render.com/docs/deploys), consultadas em 13/09/2026.
