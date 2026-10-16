# Matriz de compatibilidade — Odin 2 Portal

**Ainda nenhum jogo foi testado por este projeto.** A tabela abaixo está vazia de propósito:
nenhuma linha entra sem um teste real, com diagnóstico exportado anexado ou referenciado.

## Estados

Cada aspecto recebe uma nota separada. "Abriu" não significa "compatível".

| Código | Estado geral |
|---|---|
| `0-nao-inicia` | Não inicia |
| `1-falha-antes` | Inicia, mas falha antes do jogo |
| `2-chega-ao-jogo` | Chega ao jogo |
| `3-jogavel-com-problemas` | Jogável com problemas |
| `4-estavel-janela` | Estável durante a janela de teste |
| `5-estavel-desempenho` | Estável e com desempenho aceitável para aquele jogo |

Aspectos avaliados separadamente: **inicialização**, **estabilidade**, **gráficos**, **áudio**,
**controles** e **desempenho**. Cada um recebe `ok`, `problemas` (com descrição) ou `falha`.

## Protocolo mínimo de teste

1. Antes de jogar, defina o **cenário** (trecho do jogo, ação) e a **duração**:
   - pelo menos 15 min para a avaliação inicial de estabilidade;
   - pelo menos 30 min para investigar travamentos recorrentes.
2. Registre: build (versão + `core_git_version`), driver (nome, versão, SHA-256), variante do
   core, resolução interna e de saída, e configurações. O diagnóstico exportado já registra a
   maioria.
3. Anote se o cache de shaders/PPU/SPU estava **frio** ou **aquecido**.
4. Exporte o diagnóstico ao final, ou após a falha. Na próxima abertura, se houver crash.
5. O resultado descreve **só a janela observada**. Não garante ausência de falhas depois dela.
6. Resultados obtidos fora do Odin 2 Portal recebem a marca **"não validado no Odin 2 Portal"**.

## Matriz

| Jogo | Serial | Região | Versão | Build | Driver | Config. relevante | Cenário / duração | Cache | Init | Estab. | Gráficos | Áudio | Controles | Desempenho | Estado | Evidência | Data |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Fight Night Round 4 | BLUS30285 | US | — | `15382e58` (ThinLTO, -mtune=cortex-x3) | Sistema | Padrão + Accurate SPU Reservations off (aplicado pelo banco de configs do app) | ~21 min | Quente | Sim | Saída limpa | — | — | — | 48,9 FPS; p50 18,5 ms; p99 36,3 ms; quadros > 25 ms 15% (21,5% no build sem LTO); sem esperas longas de writer_lock; ~6 milhões de PUTLLC por SPU (~4,8 mil/s), 97,5% mudam dados | `3-jogavel-com-problemas` | Pacote 071907 | 2026-10-16 |
| Fight Night Round 4 | BLUS30285 | US | — | `0cef79c6` | Sistema | Scheduler OS, **Accurate SPU Reservations off** (resto padrão) | ~18 min | Quente | Sim | Saída limpa; sem erro novo no log | Não verificado pelo usuário | Não verificado | — | 48,7 FPS (48,6 em jogo); p50 17,6 ms; p99 38,0 ms; maior travada 0,59 s; nenhuma espera longa em writer_lock (com on: ~53 mil barreiras/s e ~87 ms/s de espera; com off a contagem de barreiras não é registrada, porque só sai no relatório de esperas longas) | `3-jogavel-com-problemas` | Pacote 195622 | 2026-10-14 |
| Fight Night Round 4 | BLUS30285 | US | — | `0cef79c6` | Sistema | Scheduler OS, SPURS 6, Block Safe, Accurate SPU Reservations on | ~25 min | Quente | Sim | Saída limpa | — | — | — | 48,1 FPS (45,6 em jogo); p99 40,6 ms; maior travada 1,95 s | `3-jogavel-com-problemas` | Pacote 170958 | 2026-10-14 |
| Fight Night Round 4 | BLUS30285 | US | — | `0cef79c6` | Sistema | **Scheduler RPCS3 Alternative** (resto igual) | ~18 min | Quente | Sim | Saída limpa | — | — | — | 42,0 FPS (41,5 em jogo); p99 51,2 ms; maior travada 0,7 s. Pior que OS; manter OS (padrão do app) | `3-jogavel-com-problemas` | Pacote 173119 | 2026-10-14 |
| Fight Night Round 4 | BLUS30285 | US | — | `0f881c8a` | Sistema e Turnip Amaral 26.3.0-devel | Padrão (Scheduler OS, SPURS 6, Block Safe) | Lutas, ~8 min cada | Quente | Sim | Fechava ao sair (`surface_store`) | Sem problema relatado | — | — | 44,5 / 46,4 FPS; p99 47–48 ms; travadas de até 1,7 s no início das lutas | `3-jogavel-com-problemas` | Pacotes do usuário 052920 e 054846 | 2026-10-14 |
| Fight Night Round 4 | BLUS30285 | US | — | `0f881c8a` | Turnip Amaral 26.3.0-devel | Scheduler RPCS3 Alternative, SPURS 4, Block Mega | Lutas, ~8 min | Quente | Sim | Fechava ao sair | — | — | — | **24,3 FPS**, 494 quadros > 100 ms (6 SPUs presas a 4 núcleos; corrigido em `92fcf31b`) | `3-jogavel-com-problemas` | Pacote 062319 (ZIP truncado) | 2026-10-14 |
| Fight Night Round 4 | BLUS30285 | US | — | `92fcf31b` | Turnip Amaral 26.3.0-devel | Padrão (Scheduler OS, SPURS 6, Block Safe) | ~26 min | Quente | Sim | Saída sem fechar o app (correção validada) | — | — | — | 49,6 FPS; p50 18,3 ms; p99 41,5 ms; maior travada 1,8 s; ~56 mil barreiras de PPU/s nas SPUs | `3-jogavel-com-problemas` | Pacote 131811 | 2026-10-14 |

## Escolha dos primeiros jogos de baseline

Estes ainda não foram testados. Use jogos que exercitem subsistemas diferentes, por exemplo:

- um com uso pesado de SPU;
- um limitado pela PPU;
- um pesado em RSX/shaders;
- um 2D/leve, como controle.

A lista concreta deve sair da biblioteca disponível para o testador. Este repositório não inclui
nem indica fontes de jogos.
