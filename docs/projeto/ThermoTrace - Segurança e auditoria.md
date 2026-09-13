---
tipo: projeto
projeto: ThermoTrace
status: em-planejamento
atualizado: 2026-09-11
---

# Segurança e auditoria

## Permissões alvo
| Perfil | Permissão |
|---|---|
| Operador cliente | Cargas permitidas, leituras e ocorrências; sem apagar evidências |
| Gestor cliente | Usuários da própria conta, acompanhamento, relatórios e compartilhamentos autorizados |
| Auditor cliente | Consulta/exportação das cargas concedidas; sem comandos de etiqueta |
| Suporte ThermoTrace | Diagnóstico operacional; acesso a dados mediante concessão temporária e motivo |
| Administrador ThermoTrace | Provisionamento, revogação e políticas; acesso excepcional a conteúdo rastreado |

## Controles de entrega
Login individual, recuperação de acesso, MFA para administração, limitação de tentativas, sessões revogáveis e registro de dispositivo. Escolher provedor de identidade ou implementar e revisar o fluxo existente; modelos de tokens não são autenticação pronta.

HTTPS, segredos fora de APK/Git/Obsidian, banco privado, arquivos privados com links curtos e autorizados, escopo mínimo de permissões e cache local separado por conta. Logout/troca de cliente não pode deixar dados visíveis; evidências pendentes precisam de política de recuperação antes da limpeza. Controle de validade offline documentado; revogação sem conectividade não é instantânea.

## Rastro exigido
Registrar criação, vínculo/troca de etiqueta, configuração, tentativa e confirmação de START/STOP, coleta, recebimento, rejeição, correção, mudança de acesso, exportação e acesso de suporte. Guardar ator validado no servidor, aparelho, versão do app/SDK/decodificador, objeto, finalidade, hora declarada, hora recebida e motivo.

Preservar resposta bruta e leitura decodificada. Correção cria nova versão referenciando a anterior. Cadeia de hash ajuda a detectar alteração, mas administrador capaz de reescrever tudo pode refazê-la: ancorar manifestos assinados fora do banco e manter cópias com retenção protegida. Separar chave de assinatura dos dados; documentar rotação e verificação.

Bloqueios UPDATE/DELETE existentes precisam ser complementados por privilégios mínimos, proteção contra TRUNCATE e restauração testada. Relatório deve permitir reconstruir gráfico a partir do corte de dados e da versão do cálculo.

## Limites da evidência física
Hash não prova que temperatura veio de sensor autêntico. Avaliar autenticação do hardware, senha de fábrica de STOP observada no código, clonagem/troca, calibração, lacre, deriva do relógio, memória e interrupções. Guardar certificado de calibração e vigência quando disponível; ausência aparece no relatório. Não emitir declaração automática de conformidade regulatória.

## Aceite de segurança
- A não consulta nem exporta B, mesmo alterando UUID e cliente no pedido.
- Token expirado/revogado, dispositivo revogado e papel incorreto são recusados.
- Suporte sem concessão não obtém conteúdo; concessão expirada também falha.
- Evidência não pode ser editada/apagada pelo papel de execução.
- Arquivo não abre sem autorização e toda exportação é rastreável.
- Backup recuperado em ambiente isolado reconstrói uma carga e seu relatório.

Retenção, privacidade, contratos e exigências do setor serão definidos com o responsável competente e o cliente piloto. Não adotar “60 meses” apenas porque existe esse valor padrão no código.
