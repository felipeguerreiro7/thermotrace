# ThermoTrace — Android 0.8.0

Entrega de desenvolvimento em 12/09/2026. Projeto pessoal independente.

## O que foi construído

O menu inicial ganhou **Conta e cargas**. O cliente informa o endereço HTTPS da ThermoTrace e suas credenciais, consulta as cargas da própria empresa e busca por código, número de documento ou leitura pela câmera. A resposta com várias entregas exige escolha; a nota fiscal não funciona como senha nem identifica uma entrega de maneira única.

O detalhe mostra destinatário, situação, documentos, quantidade de volumes, intervalo de registro e faixa/versão do perfil que foi preservado na criação da carga. Não substitui esses critérios pelo perfil atual do catálogo. Registros legados sem critério completo são apresentados como tal. Listas têm paginação e os horários aparecem no fuso do aparelho.

Contas administrativas da plataforma recebem identificação e indicação de que a gestão será feita pelo futuro portal. Não recebem acesso implícito às cargas dos clientes.

## Acesso e proteção da sessão

- HTTPS obrigatório, validação normal dos certificados da plataforma, limites de tamanho/tempo e redirecionamentos desativados nas chamadas autenticadas.
- Senha usada somente no login; não vai para preferências, estado salvo da tela ou banco local.
- Tokens cifrados com AES-GCM; chave gerada no Android Keystore; arquivo fora dos backups. Escrita atômica, sincronização e conferência de leitura antes de considerar a gravação confirmada.
- Uma instância de repositório serializa pedidos e renovação. Resposta 401 permite uma renovação; 403 não dispara renovação.
- Antes de transmitir um refresh, o arquivo registra renovação pendente. Resultado incerto ou processo interrompido exige novo login, evitando reenviar automaticamente um token possivelmente consumido.
- Identidade/empresa conferidas ao restaurar e renovar. Cargas com empresa diferente são recusadas e a sessão local é removida.
- Sair tenta revogar a família no servidor e remove os tokens locais; a mensagem distingue revogação confirmada de saída apenas no aparelho.

O aplicativo não inclui credenciais, domínio de produção ou certificado de confiança alternativo. O endereço será fornecido depois da implantação HTTPS; a tela não se conecta diretamente ao PostgreSQL.

## Verificação

**90 testes JVM passaram, sem falhas ou testes ignorados** (23 de conta/contrato e 67 anteriores). O APK debug foi gerado; a análise estática terminou com 0 erros e 19 avisos. O aviso adicional sobre a versão disponível de JSON refere-se à dependência de testes. Resultados e arquivos de evidência constam no manifesto desta versão. A suíte inclui cenários de login negado, falha ao salvar, falta de rede, renovação concorrente, refresh incerto, reabertura com renovação pendente, 401 repetido, 403, troca de empresa, logout e documentos com múltiplas candidatas.

Foi capturado um conjunto de respostas do servidor 0.3 executado sobre PostgreSQL 17 descartável, com o papel de permissões mínimas fornecido. O Android interpreta essas respostas em teste JVM. A captura contém somente dados sintéticos e não inclui senhas/tokens. Esse teste verifica o contrato dos dados; não constitui ensaio HTTPS entre um celular e uma implantação pública.

Comandos de validação, na raiz do projeto, com JDK compatível:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

A dependência JSON de testes usa a versão fixada no catálogo; o aplicativo instalado usa o JSON da plataforma Android. Versões novas de dependências apontadas pela análise estática ficam para atualização e validação específicas.

## Como acessar futuramente

1. Implantar a API 0.3 em ambiente de homologação com HTTPS, migrar o banco correspondente e criar cliente/operador pelos mecanismos do servidor.
2. Instalar o APK debug para ensaio controlado e abrir menu → **Conta e cargas**.
3. Entrar com o endereço e a conta de homologação. Consultar uma carga preparada pela API, buscar seu documento e conferir o detalhe.
4. Ensaiar duas empresas, expiração/revogação do acesso, interrupção de rede e reinício do aplicativo antes de liberar distribuição.

## Limites e próxima implementação

Esta versão adiciona consulta online. Ainda não cria cargas pelo novo fluxo de conta, não associa volumes online às etiquetas e não envia início/checkpoints/final para a API. O cadastro e as leituras locais anteriores permanecem componentes de protótipo; seu banco ainda não foi separado por cliente. Por isso a aplicação completa não está liberada para aparelhos compartilhados entre clientes ou operação comercial.

Ainda faltam validação visual e de ciclo de vida no Android, ensaio do Keystore no aparelho, leitura de documentos pela câmera e NFC real. O APK é debug, não uma versão de loja assinada para produção. Nenhum banco existente foi migrado ou serviço publicado nesta entrega.

Próxima sequência: definir/persistir a associação cliente–carga–volume–sessão no aparelho; implementar ingestão autorizada e idempotente com evidência bruta; ligar fila offline transacional e tratar divergências entre aparelhos; então consolidar gráficos, exportações e portais. As configurações locais genéricas não devem substituir o perfil aprovado da carga. O hardware será validado quando estiver disponível.

Referência técnica para o uso das chaves de plataforma: [Android KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).
