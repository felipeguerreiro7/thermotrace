---
tipo: operacao
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-09-13
---

# Infraestrutura confirmada

## Inventário

| Item | Identificação | Evidência / estado |
| --- | --- | --- |
| Domínio | thermotrace.com.br | Compra informada pelo usuário em 13/09/2026; vínculo ao serviço ainda não verificado |
| GitHub | https://github.com/felipeguerreiro7/thermotrace | `origin` confirmado e remoto consultado; branch main |
| Blueprint Render | thermotrace-homologacao | Print enviado pelo usuário em 13/09/2026 |
| Serviço Render | thermotrace-api | Print: Deployed, Docker, Oregon; ligado a felipeguerreiro7/thermotrace, main |
| Banco | thermotrace-homologacao-db | Registro de 12/09: PostgreSQL 17, Oregon, 5 GB; não revalidado no painel nesta entrega |
| URL pública onrender.com | A obter na página do serviço | Não aparece no print do Blueprint; não deduzir o hostname a partir do nome |
| DNS autoritativo | a.sec.dns.br e c.sec.dns.br | Consulta NS em 13/09/2026; consulta A da raiz sem resposta de endereço naquele momento |
| Caminho do portal | /portal | Servido junto da API; não precisa de outro serviço Render |
| Saúde | /api/v1/saude | Deve ser conferida no endereço público real |

“Deployed” no print comprova o estado exibido do serviço anterior. Não comprova a publicação da entrega Portal 0.1 nem domínio, credenciais de usuário ou isolamento no ambiente remoto.

## Passos restantes para o domínio

1. Abrir o serviço `thermotrace-api` no Render e registrar sua URL pública, sem copiar variáveis secretas.
2. Em Settings → Custom Domains, cadastrar `thermotrace.com.br` e usar os destinos DNS que o próprio painel apresentar.
3. Aplicar os registros no provedor de DNS. Os nameservers observados são do Registro.br. Revisar os registros existentes antes de editar; preservar e-mail e outros subdomínios.
4. Verificar o domínio no Render e aguardar emissão do certificado. Conferir HTTPS e o redirecionamento esperado de www.
5. Abrir `/portal`, consultar saúde e conferir qual commit está ativo no histórico de deploys. Registrar a evidência em [[ThermoTrace - Registro de entregas GitHub]].

Fontes oficiais consultadas em 13/09/2026: [domínios e certificado no Render](https://render.com/docs/custom-domains) e [deploy a partir do GitHub](https://render.com/docs/deploys). O Render oferece emissão/renovação TLS e pode publicar automaticamente mudanças da branch vinculada; a configuração efetiva de auto-deploy precisa ser conferida no serviço.

## Cuidados já identificados no projeto

Ver [[ThermoTrace - Roteiro de contratação e entrega da homologação]]: existe uma pendência concreta de rotação da credencial de banco exposta anteriormente. Nenhuma senha, token, URL de conexão autenticada ou valor de variável entra nesta nota ou no GitHub.

Não recriar o banco ou contratar recursos para publicar o portal. O arquivo `render.yaml` existente utiliza o banco já contratado e permanece sem alteração nesta entrega. Arquivos de evidência duráveis não devem depender do disco efêmero do contêiner.
