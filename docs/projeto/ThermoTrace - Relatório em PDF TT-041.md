---
tipo: entrega
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-22
---

# Relatório em PDF e gráfico em PNG — TT-041 (API 0.19)

Entrega de 22/11/2026, branch `feat/relatorio-pdf`.

## Por quê

O portal já baixava CSV, SVG e relatório HTML. O PDF dependia de o usuário abrir o HTML e mandar imprimir, e cada navegador quebrava a página de um jeito: o arquivo guardado não era o mesmo que o servidor registrou. O SVG não abre direito em e-mail nem no WhatsApp. O TT-041 pedia PDF e PNG com download autorizado e o mesmo conjunto de dados.

## O que mudou

- `formato=pdf` e `formato=png` nas duas exportações: coleta (`/sessoes/{id}/leituras/{id}/temperaturas`) e viagem (`/sessoes/{id}/viagem`).
- **PDF da viagem** (A4): aviso de simulação, resumo, avisos, gráfico com a faixa, excursões, coletas consideradas, rastreabilidade (sessão, versão, coleta base, hash da projeção), QR e endereço de conferência pública, e o aviso de que não há validação física. Rodapé com carga, versão, início do hash e página.
- **PDF da coleta**: mesmo padrão, com hash da evidência e decodificador de origem.
- **PNG** (1800 × 820): o gráfico com título, faixa, horários e início do hash, para colar em e-mail ou mensagem.
- **Determinístico:** o mesmo dado gera os mesmos bytes, em PDF e em PNG. O `sha256_arquivo` gravado na auditoria confere com o arquivo que o cliente guardou, mesmo baixado de novo depois.
- Mesma autorização dos outros formatos: exige escopo de exportação; a parceira sem permissão e o suporte (TT-051) recebem 403; outra empresa, 404. Cada download fica na auditoria com formato e hash.
- Portal: botões **Baixar relatório (PDF)** e **Baixar gráfico (PNG)** no painel da coleta e da viagem. SVG e HTML continuam disponíveis.
- Dependências novas: `reportlab` 4.5.1 (BSD) e `pillow` 12.3.0, que têm wheels e dispensam cairo ou navegador no contêiner. A fonte do PNG é a Bitstream Vera que vem dentro do reportlab (a padrão do Pillow não tem acentos).

## Evidências

- 5 testes novos em `test_relatorio_pdf.py`: PDF da viagem (cabeçalho, mesmo arquivo a cada download, hash da auditoria igual ao do arquivo); PNG da viagem; PDF e PNG da coleta; mesma autorização (401, 404); viagem sem série ainda gera PDF e PNG.
- Suíte da API: 248 testes verdes; 17 web.
- PDF e PNG gerados abertos e conferidos a olho: texto, acentos, gráfico, tabelas e QR legíveis. Com `THERMOTRACE_SALVAR_RELATORIOS=pasta` a suíte grava os arquivos para conferência.
- Não conferido: o clique nos botões novos no navegador. O download usa o mesmo caminho dos formatos que já existiam.

## O que fica para depois

- [ ] **Laudo assinado** (resto do TT-041): assinatura digital do PDF exige definir quem assina (ThermoTrace ou o responsável técnico do cliente) e com qual certificado (ICP-Brasil A1/A3). É decisão de produto e jurídica, não de código.
- [ ] XLSX central: hoje só o app gera; o CSV cobre a planilha no portal.
