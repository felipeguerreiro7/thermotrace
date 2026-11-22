---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-21
---

# Site público, privacidade e termos (API 0.17, Android 0.9.1)

Entrega de 21/11/2026, branch `feat/site-publico`. No mesmo dia, o PR #4 (viagem do servidor no app, Android 0.9.0) foi mesclado no `main`.

## Por quê

O app pede localização e o portal guarda nome, e-mail e IP de quem usa. Sem política de privacidade publicada, o piloto fica fora da LGPD e a Play Store recusa o app. Além disso, a raiz do serviço redirecionava direto para o login: quem recebia o endereço não tinha onde entender o que é o ThermoTrace nem onde conferir um relatório.

## O que mudou

- `/` no navegador abre uma página inicial (o que é, os três bipes, conferência pública, compartilhamento, aviso de piloto). Chamadas JSON na raiz continuam respondendo como antes.
- `/privacidade` e `/termos`: textos escritos a partir do que o código faz de fato, não de modelo genérico. Exemplo: a localização fica no aparelho e no laudo exportado por ele, e não vai ao servidor (conferido: o backend não tem campo de localização).
- Páginas sem nenhum script, com CSP `default-src 'none'`, sem cookie e sem ferramenta de terceiros. Um CSS próprio em `/site/site.css`; nada mais da pasta é servido.
- Login do portal com links para privacidade e termos.
- App, **Ajustes → Sobre**: explicação curta da localização e botões para a política e os termos (abrem no navegador). Endereço fixo no Render até o domínio ter DNS (`URL_SITE` em `AjustesScreen.kt`).

## Evidências

- Suíte da API completa verde em PostgreSQL 17 descartável, com 5 testes novos em `test_portal.py` (sem script, CSP, CSS isolado, faixa de rascunho presa aos campos pendentes).
- 17 testes web, 164 testes JVM, lint e APK debug sem erro.
- Páginas conferidas no navegador, em servidor local.
- Achado de ambiente, não de código: PostgreSQL instalado em português faz falhar 7 testes de RLS que comparam a mensagem em inglês. Para rodar local: `alter database thermotrace_test set lc_messages='C'`.

## O que falta (usuário)

- [ ] Preencher na política e nos termos: razão social e CNPJ do controlador, e-mail do encarregado/canal de privacidade, prazo padrão de guarda das cargas, limites de responsabilidade e foro. Enquanto houver campo `pendente`, a faixa "Rascunho em revisão" fica (há teste que garante isso).
- [ ] Revisão jurídica dos dois textos antes de retirar a faixa.
- [x] Registros de acesso (IP) guardados por pelo menos 6 meses, como a política promete pelo Marco Civil. **Corrigido em 22/11 (API 0.17.1):** a frase anterior dizia que o IP ficava na sessão de autenticação, mas nenhum código gravava a coluna. Ver [[ThermoTrace - Registro de acesso 0.17.1]]. Os logs do Render deixam de ser a única fonte.
- [ ] Quando o domínio tiver DNS, trocar `URL_SITE` no app.
