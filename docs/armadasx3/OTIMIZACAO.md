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
| 6 | `init_arm_timer_scale()` não é chamado no Android | `rpcs3/rpcs3.cpp:717` | pequeno | nenhum |
| 7 | Reteste isolado de SPU Block Size Mega | configuração | ? | depende do jogo |

Do fork do Cemu (`rickamaral94/Cemu-Fork-Amaral`) não há otimização de CPU a portar: ele não mexeu em
JIT, afinidade, prioridades nem esperas. O que se aproveita é o método (A/B com repetições, mediana,
sessões longas para térmica) e a telemetria de frequência por núcleo e de throttling, que falta no
nosso diagnóstico.
