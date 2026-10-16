# Acompanhamento do upstream

## Remotos

```sh
git remote add armsx3 https://github.com/ARMSX2/ARMSX3   # fonte primária (branch master)
git remote add rpcs3  https://github.com/RPCS3/rpcs3     # correções pontuais
```

| Remoto | Papel | Último ponto incorporado |
|---|---|---|
| `armsx3` (`master`) | Base direta | `6dfb2967` (1.1, versionCode 86) |
| `rpcs3` (`master`) | Correções que o ARMSX3 ainda não trouxe | `26098fb42` (2026-10-10), merge direto |

## Rotina de sincronização

1. Busque os dois remotos **sem submódulos** (senão baixa o LLVM inteiro): `git fetch --no-recurse-submodules armsx3 && git fetch --no-recurse-submodules rpcs3 master`.
2. Veja o que entrou: `git log --oneline HEAD..armsx3/HEAD`.
3. Para mudanças no Cell ou no Vulkan, veja também
   `git log --oneline $(git merge-base HEAD rpcs3/master)..rpcs3/master -- rpcs3/Emu/Cell rpcs3/Emu/RSX`.
4. Faça `git merge armsx3/master` e depois `git merge rpcs3/master` num branch próprio. Não use rebase: o histórico é compartilhado.
   Vários arquivos do RPCS3 usam CRLF e o ARMSX3 os converteu para LF; um conflito do arquivo inteiro
   quase sempre é só isso. Refaça o merge 3-way desses arquivos com as três versões normalizadas para LF
   (`git merge-file`), e devolva CRLF aos arquivos que eram CRLF no nosso lado.
5. Resolva conflitos. Os pontos de contato esperados estão na tabela abaixo.
6. Rode os testes JVM (`./gradlew :app:testGithubDebugUnitTest` em `android/armsx3-ui`) e o build
   do core, quando disponível.
7. Registre a sincronização na tabela de histórico.

Antes de escrever uma correção própria para um problema, procure se o ARMSX3 ou o RPCS3 já a
fizeram. Um cherry-pick do RPCS3 leva `-x` e a origem anotada no commit.

## Divergências locais (o que este fork muda no código herdado)

Toda mudança em arquivo herdado entra aqui, com o motivo. Código novo fica em
`com.armadasx3.*` e `docs/armadasx3/`, onde não conflita.

| Arquivo herdado | Mudança | Motivo |
|---|---|---|
| `android/armsx3-ui/app/build.gradle.kts` | O Discord SDK `.aar` só vira dependência se existir | Sem isso, um clone limpo e o CI não compilam: o SDK é proprietário e não está no repositório. O Kotlin já o usa só por reflexão |
| `android/armsx3-ui/app/src/main/java/com/armsx2/Pasx2Application.kt` | +1 linha: `SessionDiagnostics.init(this)` | Recuperar a sessão interrompida |
| `.../com/armsx2/runtime/MainActivityRuntime.kt` | +4 linhas de ganchos (`onGameStarted`, `onGameIdentified`, `onDriverPinned`, `onReturnedToLibrary`) | Journal da sessão |
| `.../com/armsx2/ui/settings/AppTab.kt` | +3 linhas: linha "Exportar diagnóstico da sessão" abaixo de Backup | Acesso fácil ao diagnóstico |
| `.../com/armsx2/i18n/I18n.kt`, `assets/i18n/pt-BR.json` | Chaves `diag.*` (inglês + pt-BR) | Textos da exportação |
| `android/src/rpcsx-android.cpp` | +1 include, +1 linha em `flip()`: `armadasx3::metrics::on_guest_flip(now)` | Intervalos de frame e CPU por thread para o diagnóstico |
| `android/CMakeLists.txt` | +1 fonte: `src/armadasx3_metrics.cpp` | Idem |
| `android/armsx3-ui/app/src/main/cpp/native-lib.cpp` | +4 ponteiros opcionais resolvidos por `dlsym` e +5 funções JNI `Java_com_armadasx3_diagnostics_CoreMetrics_*` | Ponte das métricas; um core antigo deixa os ponteiros nulos |
| `android/armsx3-ui/app/src/main/cpp/CMakeLists.txt` | Alvo `armsx2_discord` só existe se `libdiscord_partner_sdk.so` estiver presente | Compilar sem o SDK proprietário |
| `.../cpp/savers/skyrocket/shockwave.cpp` | `<gl/gl.h>` → `<GL/gl.h>` | Só compilava em sistema de arquivos sem distinção de maiúsculas (macOS) |
| `rpcs3/Emu/RSX/VK/vkutils/sync.cpp` | `"Emu/system.h"` → `"Emu/System.h"` | Idem; o erro veio do ARMSX3 `36c4820b8` |
| `rpcs3/Emu/RSX/VK/FrameGen/FrameGenTypes.h` | Removido `#include "Config.h"` (arquivo inexistente) | No macOS resolvia por acaso para o `config.h` do wolfSSL; no Linux quebrava |
| `rpcs3/Emu/CMakeLists.txt` | `IRD.cpp`, `IRD_validation.cpp` e `content_validation.cpp` só fora do Android | Dependem do leitor de ISO do RPCS3, que não usamos (veja abaixo) |
| `rpcs3/Emu/Cell/lv2/sys_rsx.cpp` | `sys_rsx_context_attribute` 0x101 e `sys_rsx_attribute` 0x2/0x202 em nível trace | Fight Night Round 4 os chama a cada frame: 81% do log, que estourava o limite do pacote de diagnóstico e cortava o meio da sessão |
| `rpcs3/Emu/Cell/SPUCommonRecompiler.cpp` | `spu_cache::add` ignora programas que começam com a palavra marcadora do teste diferencial | O teste não pode entrar no cache de SPU do jogo |
| `rpcs3/Emu/Cell/SPULLVMRecompiler.cpp` | PUTLLC16 (JIT e auxiliar `spu_putllc16_rsx_res`) notifica com `vm::reservation_notifier_notify(addr, rtime)`, como `do_putllc` | O aviso ia para a palavra do contador, onde ninguém espera; as SPUs só acordavam no timeout de 50–200 µs |
| `rpcs3/Emu/System.cpp` | `ARMSX3_SPU_PROF=1` liga o SPU Profiler no boot | Medir onde as SPUs gastam tempo no aparelho sem editar o YAML |
| `rpcs3/Emu/Cell/SPUThread.cpp` | Linha de estatística de PUTLLC no fim de cada thread de SPU | Saber se os atômicos do jogo pegam o caminho de 16 bytes ou a barreira completa |
| `rpcs3/Emu/RSX/Common/surface_store.h` | `invalidate_all()`: resíduo em `m_active_memory_used` vira log de erro + zera, em vez de `ensure()` | O `ensure` derrubava o app ao sair de Fight Night Round 4 (Odin 2 Portal, 2026-10-14). Só roda na destruição do renderer |
| `Utilities/Thread.cpp` | `detect_cpu_layout()` registra a capacidade de cada núcleo; `get_affinity_mask()` só prende as SPUs ao cluster rápido se ele tiver 6 núcleos além do principal, senão usa todos menos o principal, e registra as máscaras | Com o Thread Scheduler "RPCS3 Alternative", Fight Night Round 4 caiu de ~45 para ~22 FPS no Odin 2 Portal: 6 SPUs em 4 núcleos |

Fora do código herdado: `android/src/armadasx3_metrics.{h,cpp}`, `android/src/armadasx3_spu_difftest.{h,cpp}`,
`.github/workflows/armadasx3-android-{unit-tests,build}.yml`, `ARMADASX3.md` e `docs/armadasx3/`.

Candidatas a enviar ao ARMSX3 como PR (corrigem o build fora do macOS e sem o SDK do Discord):
as mudanças em `build.gradle.kts`, `cpp/CMakeLists.txt`, `shockwave.cpp`, `sync.cpp` e `FrameGenTypes.h`.

## Divergências deliberadas em relação ao RPCS3 (sincronização de 2026-10-11)

Na sincronização com o RPCS3 `26098fb42`, 59 arquivos tiveram conflito (144 blocos). Regra aplicada: mudança específica de
Android/ARM64 do ARMSX3, com justificativa documentada no código, foi mantida; correção do upstream que o ARMSX3
não tinha foi trazida; onde os dois lados se sobrepunham, foram combinados. O que **não** veio do RPCS3 e por quê:

| Área | Mantido do ARMSX3 / não trazido do RPCS3 | Motivo |
|---|---|---|
| Leitor de ISO (`Loader/ISO.*`) | Leitor do ARMSX3 inteiro. Não vieram: verificação por IRD (`532b6520b`), aviso de chave errada (`52616e0f1`), velocidade de leitura de drive BD (`8e2d700fa`), correção de caminhos de dispositivo ISO (`205a4481f`) | O ARMSX3 reverteu o refactor do leitor do RPCS3 (`f707458b0`) porque ele deixou de abrir imagens de usuários (provavelmente 3k3y). As novidades dependem desse refactor. `IRD.cpp`, `IRD_validation.cpp` e `content_validation.cpp` (usados só pela interface Qt) ficam fora do build Android (`rpcs3/Emu/CMakeLists.txt`) |
| `cellAudio` | Versão do ARMSX3. Não vieram `7390f3dd9` (estalos de áudio em FMV com Audio Buffering) e `80567b686` (#19607, deadlock com `suspend_all`) | Não aplicam sobre a reescrita do ARMSX3; o `de6c84838` do ARMSX3 resolve o mesmo deadlock de outra forma. **Avaliar com teste no aparelho** |
| `sys_rsx` (`_sys_rsx_drain_event_queue`) | `thread_ctrl::wait_for(100)` em vez de `utils::spin_on_cacheline_once` | No ARM o spin ignora o timeout e, em alguns núcleos, vira espera ocupada; o laço pode durar até 1 s |
| FIFO (`RSXFIFO.*`) | Publicação de GET em lotes, busca de 4 KB com janelas de 1 KB, `sync_get_force` | Otimizações medidas pelo ARMSX3 (Spider-Man: Web of Shadows, Minecraft) que já incorporam as correções de GET do RPCS3 |
| Vulkan: Turnip | Sem `g_drv_strict_query_scopes` no Turnip | O RPCS3 liga para travamentos no **Linux**; no Android custa um tile store por consulta de oclusão e não há caso conhecido. Testar se um jogo travar no Turnip |
| Vulkan: cache de pipeline | Cache persistente do dispositivo (salvo em disco) | O RPCS3 cria um cache em memória por sessão |
| Vulkan: render pass em barreira de textura | Mantido aberto no Android | Medido pelo ARMSX3: 20–23 reinícios a menos por frame em Arkham City |
| Memória (`vm.cpp`) | Notificação do RSX para **todo** mapeamento | O filtro do RPCS3 fez Guitar Hero World Tour perder a GPU no Turnip (Odin 3) |
| Fabricante da GPU | `QUALCOMM` do RPCS3 = `ADRENO` do ARMSX3 | O enum do ARMSX3 já tinha os fabricantes móveis |

Trazido do RPCS3 nesta sincronização, entre outros: estimativas vetoriais precisas na PPU (`accurate_vest`, que entra
na identidade do cache da PPU, então os jogos recompilam o cache uma vez), caminho SVE para upload de índices
(ativa só se a CPU e o kernel reportarem SVE), renderização sem attachments, emulação da faixa de profundidade
(OpenGL), pausa do driver manager durante captura de tela, emulação da velocidade de leitura de HDD/Blu-ray
(desligada por padrão), correção de `vm::main` em capturas RSX, correção de deadlock no decodificador de música,
escape de nomes na instalação de PKG e versão 25 de savestate. **Savestates de builds anteriores podem não
carregar.**

## Histórico de sincronizações

| Data | De | Até | Conflitos | Observações |
|---|---|---|---|---|
| 2026-10-10 | — | `armsx3` `f3bdcd5c` | — | Importação inicial com histórico completo |
| 2026-10-11 | `armsx3` `f3bdcd5c` | `armsx3` `6dfb2967` (1.1) | 0 | 10 commits |
| 2026-10-11 | merge-base `961c86fb` | `rpcs3` `26098fb42` | 59 arquivos / 144 blocos | 224 commits novos (75 já estavam no ARMSX3); divergências acima |
