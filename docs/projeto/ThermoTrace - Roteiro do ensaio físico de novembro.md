---
tipo: roteiro
projeto: ThermoTrace
contexto: pessoal-independente
atualizado: 2026-11-04
---

# Roteiro do ensaio físico — novembro de 2026

Semana 2 do [[ThermoTrace - Plano até 30-11-2026]]. Objetivo: fechar TT-005 e provar o ciclo **ativação → checkpoint → envio automático → leitura final + STOP → gráfico no portal** com etiqueta e celular reais. Retoma o que ficou aberto em [[ThermoTrace - Ensaio em aparelho 2026-09-12]].

## Antes de começar

- [ ] Samsung A57 com depuração USB ligada, carregado.
- [ ] Etiqueta 1 (UID `1DAE2B0B000870`) e, se possível, uma segunda etiqueta.
- [ ] App do fabricante instalado no mesmo celular, para comparação.
- [ ] Termômetro de referência (aferido, se houver), anotando marca e certificado.
- [ ] Servidor publicado: URL do Render conferida e `/api/v1/saude/completa` respondendo. Sem isso, as partes B e D ficam só locais.
- [ ] No portal: empresa de homologação, operador com primeiro acesso feito, perfil aprovado, produto e **carga criada** com a mesma faixa e o mesmo intervalo que o app vai gravar na etiqueta.
- [ ] Celular ligado por cabo a este PC: eu instalo o APK 0.8.8 (`installDebug`) e acompanho o log.

## A. TT-005 — o que a própria etiqueta diz (15 min, sem servidor)

1. App → menu ⋮ → **Diagnóstico**. Encostar a etiqueta.
2. Anotar: mínima registrada (campo 6), máxima registrada (7), pontos abaixo (10) e acima (11), estado e registros.
3. Termômetro ao vivo do Diagnóstico × termômetro de referência, lado a lado, por 2 minutos. Anotar as duas leituras a cada 30 s.
4. Abrir a mesma etiqueta no app do fabricante e anotar mínima, máxima e quantidade de registros.

| Fonte | Mínima | Máxima | Abaixo | Acima | Registros |
|---|---|---|---|---|---|
| Cabeçalho (Diagnóstico) | | | | | |
| Série no gráfico do app | | | | | |
| App do fabricante | | | | | |

O que cada resultado indica está em [[ThermoTrace - TT-005 reconciliação cabeçalho x série]]. Não corrigir nada por suposição: só registrar.

## B. Ciclo completo com envio automático (1 h 30 min ou mais)

1. Entrar no app com a conta do operador de homologação.
2. **Ativar** a etiqueta numa remessa local. Conferir faixa e intervalo iguais aos da carga do portal.
3. Na remessa: **Enviar coletas do volume** → buscar a carga do portal → escolher o volume → confirmar o vínculo. Depois disso não é preciso tocar em "Enviar" de novo.
4. Esperar pelo menos 2 intervalos e **bipar o checkpoint** (registro automático). Com internet ligada, em até 1 minuto o comprovante deve aparecer no portal (carga → volume → coletas → comprovantes).
5. **Teste sem internet:** modo avião → novo checkpoint → anotar a hora → desligar o modo avião. Anotar quanto tempo o comprovante leva para chegar ao portal (esperado: menos de 1 min; no máximo 15 min).
6. **Leitura final:** conferir que a tela mostra a etiqueta **parada e confirmada**. Se mostrar "não confirmada", usar *Tentar parar a etiqueta* e anotar.
7. No portal: abrir o gráfico da coleta final e comparar mínima, máxima e pontos fora da faixa com a tabela da parte A.

## C. Repetição

Repetir B com a segunda etiqueta, ou com a mesma depois de liberada. Anotar qualquer falha de leitura NFC: quantas tentativas, o que a tela disse, posição do celular.

## D. O que me devolver

- A tabela da parte A preenchida.
- Hora de cada bipe e hora em que cada comprovante apareceu no portal.
- Prints de qualquer mensagem de erro.
- Se possível, o celular no cabo durante o ensaio, para eu salvar o log (`adb logcat`).

Com isso eu fecho o TT-005 ou abro a correção certa, e o critério "leitura real validada" do piloto deixa de ser hipótese.
