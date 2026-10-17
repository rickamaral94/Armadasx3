# Otimização de CPU no Odin 2 Portal — diagnóstico e plano

Estado em 2026-10-14, a partir de Fight Night Round 4 (BLUS30285) no Odin 2 Portal. Números medidos
estão na `COMPATIBILIDADE.md`; o resto é estimativa e está marcado como tal.

## Onde está o limite

- Seis threads de SPU a ~80% cada, PPU ~85–90%, RSX ~65–80%: ~7 núcleos ocupados num chip com 1 X3,
  4 A710/A715 e 3 A510. A GPU fica folgada e trocar de driver não muda nada.
- O X3 tem desempenho de um núcleo de desktop (Geekbench 6 ~2.000 contra ~1.540 de um Ryzen 5 3600),
  mas o chip tem só 5 núcleos fortes, NEON de 128 bits e SVE desligado. O RPCS3 recomenda 6 núcleos /
  12 threads de desktop. Conclusão (inferência): metade é silício, metade é software.
- O JIT já está bem portado (alvo `cortex-x3` detectado, TBL/dotprod/NEON nas operações principais).
  O custo restante está sobretudo em sincronização: barreiras de `vm::writer_lock`, esperas por
  polling e `yield`.

## Plano, por ganho/esforço (estimativas)

| # | Item | Onde | Ganho estimado | Risco de precisão |
|---|---|---|---|---|
| 1 | Medir antes: SPU Profiler por jogo, `ARMSX3_PUTLLC_SITES=1`, trocas de contexto por thread | `CPUThread.cpp`, `SPUThread.cpp` | — | nenhum |
| 2 | ThinLTO + `-mtune` + PGO do core (hoje `USE_LTO=OFF`, sem `-mtune`, PGO não usado) | `android/configure.sh`, `android/CMakeLists.txt` | 3–10% no código C++ (MFC, vm, lv2, RSX) | nenhum |
| 3 | FREST/FRSQEST/FI: tabela por lane (4× extract/load/insert) → `tbl` NEON | `SPULLVMRecompiler.cpp` ~8672, 8709, 9752, 9783 | 1–5% do tempo de SPU em jogos com muita matemática | nenhum (resultado idêntico) |
| 4 | Esperas: timeouts de 50/200/100 µs ajustáveis; no Android, futex direto em vez do motor emulado (`has_waitv()` é falso) | `SPUThread.cpp` ~7339, 7320, 5754; `util/atomic.cpp` | 2–8% se as SPUs passam muito tempo esperando | baixo |
| 5 | PUTLLC que muda mais de 16 bytes ainda pega o lock completo | `SPUThread.cpp` ~4703 | 0–6% | alto (atomicidade entre blocos) |
| 6 | ~~`init_arm_timer_scale()` não é chamado no Android~~ — **descartado**: a escala do RPCS3 #18055 já está embutida em `busy_wait` (`cycles/100`); a 19,2 MHz a função daria 0 e manteria a escala 1 | `rpcs3/util/asm.hpp` | nenhum | — |
| 7 | Reteste isolado de SPU Block Size Mega | configuração | ? | depende do jogo |

Do fork do Cemu (`rickamaral94/Cemu-Fork-Amaral`) não há otimização de CPU a portar: ele não mexeu em
JIT, afinidade, prioridades nem esperas. O que se aproveita é o método (A/B com repetições, mediana,
sessões longas para térmica) e a telemetria de frequência por núcleo e de throttling, que falta no
nosso diagnóstico.

## Item 2 em andamento: LTO, `-mtune` e PGO

- `android/CMakeLists.txt`: `USE_LTO=ON` liga ThinLTO em `rpcs3_emu` e na biblioteca do core (o LLVM
  fica de fora); `ARMSX3_TUNE` (padrão `cortex-x3`) passa `-mtune` só a esses dois alvos. `-mtune` não
  muda o conjunto de instruções, então o APK continua rodando em qualquer armv8.2.
- CI (`armadasx3-android-build.yml`): entradas `lto` (padrão ligado), `pgo` (`off`/`generate`/`use`) e
  `pgo_profile` (padrão `android/pgo/armsx3.profdata`). O nome do APK diz como foi compilado.
- Coleta do perfil: build com `pgo = generate` (mais lento, só para isso) → jogar o cenário alvo →
  apertar Home (o perfil é gravado quando o app vai para segundo plano) → exportar o diagnóstico, que
  inclui `pgo/*.profraw`. Depois: `llvm-profdata merge -o android/pgo/armsx3.profdata *.profraw` com o
  `llvm-profdata` do mesmo NDK, commit, e build com `pgo = use`.
- Um perfil envelhece com o código; regenere quando o core mudar bastante. Perfil velho já piorou o
  desempenho do projeto irmão (comentário em `android/CMakeLists.txt`).
- Comparação: mesmo jogo, mesma cena, mesmo driver, A/B alternado, várias sessões; mediana do FPS e do
  p99 em jogo.

## Teste diferencial da SPU (recompilador LLVM × interpretador)

`android/src/armadasx3_spu_difftest.cpp`. Desligado por padrão. Para ligar, crie `driver_env.txt` na
pasta de dados do emulador (a mesma que tem `config/` e `cache/`) com a linha `ARMSX3_SPU_DIFFTEST=1`
(ou `=N` para N rodadas; `ARMSX3_SPU_DIFFTEST_SEED=<n>` muda a semente, fixa por padrão).

- 15 s depois do primeiro quadro de qualquer jogo, uma thread pega um slot livre de SPU "raw" e roda
  rodadas de 256 casos: carrega $2..$9 com valores aleatórios e especiais (LQA), executa 1–6
  instruções aleatórias de 148 opcodes, grava os registradores (STQA) e para (STOP). O código passa
  pelo caminho real (gateway, dispatcher, compilação, block size e XFloat do usuário). Depois o
  interpretador C++ executa o mesmo programa na mesma thread parada, e os resultados são comparados.
- A metade dos casos com uma instrução só identifica o opcode exato. A outra metade encadeia várias e
  exercita o rastreamento de valores do recompilador.
- Saída no log, canal `SPUDIFF`: `PASSED`/`FAILED`, divergências inteiras (bug do recompilador),
  divergências de ponto flutuante (podem ser esperadas com XFloat abaixo de Accurate) e detalhes com
  desmontagem e registradores. O programa de teste começa com uma palavra marcadora e nunca entra no
  cache de SPU do jogo (`spu_cache::add`).
- Limites: não testa desvios, canais, DMA nem instruções de parada; não sai do jogo durante o teste
  (cerca de um minuto).
- Ideia do teste diferencial da ScarletNX (`tests/llvm_differential.c`); nenhum código copiado (GPLv3).


Candidatos novos vindos do estudo de arquitetura (PUTLLC16 notificando a palavra errada, cache de
objetos de SPU desligado no ARM64, tabela de reservas de 32 KB, espera com WFE, `lwsync`): veja a parte
4 de [`ARQUITETURA-PS3.md`](ARQUITETURA-PS3.md).

## Estado em 2026-10-16

- **Feito:** notificação do PUTLLC16 corrigida (acorda quem espera na reserva, em vez de depender do
  timeout); `ARMSX3_SPU_PROF=1` liga o SPU Profiler pelo `driver_env.txt`; totais de PUTLLC no log ao
  fim de cada thread de SPU.
- **Descartado por ora:** tabela de reservas maior (colisão depende de endereços específicos, sem
  evidência; o RPCS3 usa o mesmo tamanho) e FREST/FRSQEST com `tbl` (tabelas de 128/256 bytes exigem
  8+ `tbl` e mais pressão de registradores no LLVM ARM64, que já falha com TBL2; ganho marginal).
- **Próximo:** decidir pelo profiler, não por suposição — percentual de tempo ocioso, em operações de
  reserva e blocos mais quentes das SPUs em Fight Night Round 4.

- **Medido (FNR4, 2026-10-16):** com Accurate SPU Reservations off, ~4,8 mil PUTLLC/s por SPU, 97,5%
  mudando dados e **nenhum** pelo `vm::writer_lock` — todos no caminho de 16 bytes. As operações
  atômicas deixaram de ser o gargalo; o custo restante está no código das SPUs e nas esperas. Próxima
  decisão depende do SPU Profiler.

- **SPU Profiler, FNR4 (2026-10-17, ~22 min):** SPUs 0,65% ociosas; 46% das amostras com reserva
  ativa; **45,4% numa única função (LS 0x3fd00, laço principal do Job Manager)**, 10,6% na 0x3f928,
  6,7% verificando código antes de executar, 6,5% fora do código compilado. Leitura: as seis SPUs giram
  na fila de jobs em vez de dormir, tomando núcleo da PPU e da RSX. Próximos passos: (1) desmontar o
  laço (`ARMSX3_PUTLLC_SITES=1`); (2) A/B de Preferred SPU Threads (2 e 3), que limita quantas SPUs
  executam o mesmo comando de MFC ao mesmo tempo; (3) se o laço for espera pura, detectar o padrão e
  dormir no evento de reserva em vez de girar.
- O primeiro teste diferencial no aparelho caiu em DFCMGT, que o Cell do PS3 não tem e o recompilador
  trata como armadilha sem retorno: 120 s girando e 4 GB de log. Retiradas DFCGT, DFCEQ, DFCMGT, DFCMEQ
  e DFTSV; o tempo limite caiu para 30 s e a SPU é parada no estouro.
