---
tipo: planejamento
projeto: ThermoTrace
contexto: pessoal-independente
status: em-implementacao
atualizado: 2026-09-12
---

# Próximo ciclo sem hardware

Projeto pessoal independente. Conforme orientação do usuário, a bancada física será realizada mais tarde; a implementação pode avançar nas partes independentes.

## Base disponível

- [[ThermoTrace - Entrega Android 0.8.0]] — login e consulta de cargas; 90 testes JVM passaram, APK debug gerado.
- [[ThermoTrace - Entrega servidor 0.4]] — acesso, perfis/produtos, cargas/documentos e recepção de evidências com recibo, autoria e cadeia local; 126 testes passaram.
- Contrato da API e exemplo de fluxo sintético executado disponíveis no código do servidor.

## Próxima entrega: fila Android ligada à conta

1. Separar persistência por empresa e operador. Manter leituras legadas sem empresa fora do envio automático; nunca atribuí-las à conta que acabou de entrar.
2. Registrar instalação e resolver etiqueta no catálogo; carregar carga, volume e critério aprovado; localizar/criar a sessão online correspondente. A operação deve preencher a configuração pelo código da carga, evitando repetição de formulários.
3. Gravar evidência, identidade original do operador, IDs e corpo do envio na mesma transação local que insere a fila. Criar chave de envio/evento antes da primeira tentativa e preservar ambos.
4. Enviar somente com a conta/empresa/autoria compatíveis. Validar o recibo antes de concluir a fila; interromper corretamente quando a sessão de acesso expirar.
5. Em falha de rede, reenviar o mesmo conteúdo. HTTP 409 preserva o item e exige resolução; não significa sucesso. Outro operador cria seus próprios eventos e não assume autoria dos anteriores.
6. Ensaiar, sem etiqueta, app fechado durante envio, resposta perdida, troca de conta, dois operadores, falta de sinal e reabertura. Marcar explicitamente os dados simulados.

Aceite: nenhuma evidência cruzada entre clientes; nenhum envio atribuído ao usuário errado; reenvio não duplica; recibo só confirma dados persistidos; conflito não é apagado nem sobrescrito.

## Depois da fila

Decodificação versionada no servidor; consolidação por índice/tempo e identificação de divergências entre leituras; gráficos e relatório reproduzível; download autorizado; portais cliente/equipe e catálogo; recuperação de senha/MFA; compartilhamento e concessão de suporte; manifesto externo; homologação HTTPS, backup/restauração e distribuição assinada.

## Limites atuais

A API 0.4 preserva declarações e evidência bruta; ainda não interpreta a temperatura no servidor nem confirma fisicamente a etiqueta. Finalização lógica não libera o vínculo nem comprova STOP. O banco existente não foi migrado, não existe serviço público e o Android não envia pela API 0.4. A interface/Keystore ainda precisam de ensaio em Android.

Quando houver hardware: comparar com o app chinês, validar comandos, UID, amostras, tempo, recuperação NFC e STOP; conferir calibração e critérios de qualidade. Faixas de produtos poderão ser cadastradas no onboarding; referências genéricas não aprovam automaticamente um produto desconhecido.


## Atualização — após Android 0.8.3

Histórico local e conferência de evidência avançaram em [[ThermoTrace - Histórico e auditoria Android 0.8.3]]: 117 testes passaram. O usuário já realizou ensaio físico, mas o reteste das versões atuais ainda está aberto. A indisponibilidade da bancada não bloqueia o plano de isolamento/fila acima. API 0.4 e dados legados não foram modificados por esta entrega.
