---
tipo: custos-e-contratacoes
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-09-14
---

# Contratações e custos a acompanhar

O usuário autorizou avançar no desenvolvimento e pediu que as necessidades de pagamento sejam comunicadas à medida que aparecerem. Isso é orientação para apresentar a necessidade e o orçamento; não é autorização genérica para contratar planos ou efetuar compras.

## Estado atual

- Domínio thermotrace.com.br: compra confirmada pelo usuário. Valor e vencimento a registrar a partir da conta do provedor.
- Render: serviço e banco já contratados/configurados segundo o histórico. Mensalidade efetiva precisa ser conferida no painel/fatura; não deduzir apenas do arquivo de configuração.
- Portal 0.1 usa o serviço da API existente. A revisão Android 0.8.5 e os ensaios locais não exigem contratação adicional.
- Nenhuma compra, aumento de plano ou novo recurso pago foi feito nesta rodada.

## Necessidades por etapa

| Quando | Item | O que precisa ser apresentado antes de contratar |
| --- | --- | --- |
| Publicação da homologação | DNS e HTTPS do domínio | Conferir associação ao serviço existente e certificado antes de propor qualquer gasto |
| Evidências/relatórios centrais | Armazenamento durável | Volume estimado, retenção, região, acesso privado, custo de armazenamento e de download |
| Convites, recuperação de senha e alertas | Entrega de e-mail | Remetente no domínio, volume, registro de entrega, limite gratuito ou plano necessário |
| Antes de dados reais | Backup e restauração | Cobertura do plano atual, teste de restauração e custo do que faltar |
| Ensaio físico confiável | Etiquetas e referência de temperatura | Quantidade, procedência, instrumento/referência apropriada e orçamento de verificação |
| Distribuição em loja | Conta de desenvolvedor e publicação | Canal escolhido, titularidade da conta e taxa vigente verificada na fonte oficial |
| Piloto e operação | Acompanhamento de erros e disponibilidade | Cobertura atual, volume esperado e custo incremental necessário |
| Crescimento | Capacidade de API/banco | Medição que justifique mudança, preço anterior/novo e efeito no orçamento mensal |

Apresentação padrão ao usuário: **item, finalidade, impedimento que resolve, alternativa, valor/moeda/impostos, cobrança única ou recorrente, fonte e data do preço, titular da conta**. Verificar o preço quando a necessidade estiver pronta para decisão; estimativas antigas não são autorização de compra.

Relacionados: [[ThermoTrace - Infraestrutura confirmada]], [[ThermoTrace - Hospedagem e custos]], [[ThermoTrace - Processos do produto e da empresa]].

## Entrega 0.8.6 — 14/09/2026

Envio inicial usa a API/portal existentes. Nenhuma nova cobrança, contratação ou alteração do Render. Testes rodaram em banco local descartável. Capacidade, retenção e necessidade de armazenamento adicional serão avaliadas com dados medidos do piloto; não é necessário contratar outro serviço para instalar este APK.
