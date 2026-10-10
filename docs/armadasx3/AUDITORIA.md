# Auditoria da base — Armadasx3

Marco 1 do projeto. Data da auditoria: 2026-10-10.

Este documento mostra o estado verificado no código. Quando algo **não foi verificado**, isso
aparece explicitamente.

## 1. Estado do repositório antes da importação

`rickamaral94/Armadasx3` estava **vazio**: nenhum commit e nenhuma ref no remoto
(`git ls-remote origin` sem saída). Não havia código, workflow nem documentação para preservar.

## 2. Base escolhida

| Item | Valor |
|---|---|
| Base | **ARMSX3** — https://github.com/ARMSX2/ARMSX3 |
| Commit | `f3bdcd5c83c3c83f2ab820212d6963923204a85e` (2026-10-09, "Release 1.0.9.1 (versionCode 85)") |
| Upstream do ARMSX3 | **RPCS3** — https://github.com/RPCS3/rpcs3 |
| Merge-base com RPCS3 `master` | `961c86fbbf4a9dd0d717f45fd0c330cecbdccf70` (2026-09-04) |
| Commits exclusivos do ARMSX3 sobre essa base | 1.148 |
| Commits do RPCS3 ainda não incorporados (em 2026-10-08, `e38d2715`) | 282 |
| Histórico | Completo e preservado (21.132 commits, desde o commit inicial do RPCS3 em 2012) |

A importação trouxe o histórico inteiro, não um snapshot. Isso preserva a autoria de cada mudança
e permite fazer `git merge` direto do ARMSX3 e do RPCS3, sem reaplicar patches à mão.

### Por que o ARMSX3

- É um fork **mantido** do RPCS3 (o último commit é do dia anterior à auditoria) com app Android
  funcional: UI Compose, ponte JNI, Storage Access Framework, drivers via AdrenoTools e variantes
  de build ARM64.
- Já contém trabalho específico de Adreno/Turnip (veja §5) e mantém sincronização com o RPCS3
  (há merges e reverts nomeados de upstream no histórico).
- Reaproveita o PPU/SPU/RSX do RPCS3, o emulador de PS3 de referência. Reescrever esses
  componentes contrariaria a regra de não recriar do zero o que pode ser reaproveitado.

### Alternativas consideradas

| Candidato | Motivo de não ser a base |
|---|---|
| RPCS3 puro | Não tem frontend Android. Seria preciso recriar tudo o que o ARMSX3 já faz no Android. |
| RPCSX (`RPCSX/rpcsx`) | Hoje o foco do projeto é outro (PS4), e não serve de base para PS3 no Android. Não auditado a fundo. |
| rpcsx-ui-android | O ARMSX3 já incorpora a UI e a ponte originadas dele (pacote `net.rpcsx`). Não foi auditado separadamente. |

## 3. Licenças e redistribuição

- **Licença do projeto:** GPL-2.0-only (arquivo `LICENSE`, igual ao RPCS3). Alguns arquivos têm
  licença própria indicada no cabeçalho, como avisa o `README.md`.
- **Obrigações:** toda distribuição de APK precisa vir acompanhada do código-fonte correspondente
  (o próprio repositório no commit do build), preservar os avisos de copyright e manter a GPL-2.0.
- **Submódulos de terceiros** (`.gitmodules`): LLVM, FFmpeg, glslang, zlib, libpng, libusb,
  hidapi, pugixml, yaml-cpp, curl, wolfSSL, cubeb, SoundTouch, SDL, OpenAL-soft, zstd, 7zip,
  OpenCV mínimo, protobuf, Oboe, libchdr, VulkanMemoryAllocator, entre outros. Cada um mantém a
  própria licença. **A compatibilidade de cada um com a GPL-2.0-only ainda não foi auditada uma a
  uma.** Isso fica pendente para antes do primeiro APK público.
- **Fora do repositório**, obtidos à parte:
  - `librashader` (clonado em `3rdparty/librashader`): licença dupla MPL-2.0 / GPL-3.0. O core
    usa **só os cabeçalhos** (`librashader_ld.h`, um loader permissivo) e abre `librashader.so`
    com `dlopen` em runtime; nada licenciado sob MPL é ligado ao core (veja
    `upscalers/librashader_pass.h`). Se um APK distribuir `librashader.so`, ele deve ir sob a
    opção MPL-2.0, com o aviso correspondente. Esse uso ainda não foi verificado no APK.
  - `libadrenotools` (clonado em `android/armsx3-ui/app/src/main/cpp/libadrenotools`):
    BSD-2-Clause.
  - Discord Social SDK: proprietário e **não** redistribuído. O build funciona sem ele.
- **Nunca no repositório:** firmware de PS3, BIOS, chaves, jogos e licenças RAP/RIF. A rotina de
  diagnóstico também os exclui (veja `DIAGNOSTICO.md`).

## 4. Arquitetura

```
android/armsx3-ui/        App Android (Kotlin/Compose). applicationId com.armsx3, pacote-fonte com.armsx2
  app/src/main/cpp/       JNI "glue" pequeno, construído pelo Gradle (CMake 3.30.5) + libadrenotools
  app/src/main/java/
    com/armsx2/…          UI (herdada do ARMSX2), runtime, drivers, config, i18n
    com/armsx3/…          Ponte com o core (Rpcs3Bridge, NativeApp)
    net/rpcsx/…           Ponte herdada do rpcsx-ui-android
    com/armadasx3/…       Código local deste fork (diagnóstico)
android/src/              rpcsx-android.cpp: o frontend nativo do core (≈7 mil linhas) + SAF
android/CMakeLists.txt    Alvo rpcsx-android → libarmsx3-core.so
rpcs3/Emu/Cell/           PPU, SPU, lv2 (kernel HLE), módulos HLE
rpcs3/Emu/CPU/Backends/AArch64/  JIT/assembler/sinais ARM64
rpcs3/Emu/RSX/(VK)/       RSX e backend Vulkan
3rdparty/                 Submódulos
```

O app carrega o core pré-compilado (`libarmsx3-core.so`) com `dlopen()`. O Gradle **não**
compila o core.

### PPU

- Decodificadores: `ppu_decoder_type::{_static, llvm}`. O padrão é **LLVM**
  (`rpcs3/Emu/system_config.h`). O tradutor fica em `rpcs3/Emu/Cell/PPUTranslator.cpp`, e o
  interpretador de referência em `PPUInterpreter.cpp`.
- No ARM64 o LLVM gera código AArch64. O LLVM 22 vem do submódulo e é compilado e ligado
  estaticamente.
- Mudanças do ARMSX3 sobre o RPCS3: `PPUThread.cpp` (+1.278 linhas), `PPUTranslator.cpp` (+332),
  `PPUInterpreter.cpp` (+79), `PPUModule.cpp` (+240).

### SPU

- Decodificadores: `spu_decoder_type::{_static, dynamic, asmjit, llvm}`, com padrão LLVM. O
  `asmjit` é o recompilador x86 e não se aplica ao ARM64.
- Arquivos: `SPULLVMRecompiler.cpp` (+882 no ARMSX3), `SPUCommonRecompiler.cpp` (+935),
  `SPUThread.cpp` (+1.719) e o novo `SPUFailedBlocks.h` (+134), que registra blocos que falharam
  na compilação para usar fallback.
- Local Store, MFC/DMA, canais e reservas ficam em `SPUThread.cpp`. As reservas usam a detecção
  LSE2 do upstream (`utils::has_lse2`).

### Backend ARM64 comum

`rpcs3/Emu/CPU/Backends/AArch64/`: `AArch64JIT`, `AArch64ASM`, `AArch64Common` e
`AArch64Signal` (tratamento de sinais e falhas de acesso). Foi pouco alterado pelo ARMSX3
(+44/−35).

### RSX / Vulkan

- O backend Vulkan concentra a maior divergência do ARMSX3: 107 arquivos, +16.274/−1.785 linhas.
- Há adaptações para Adreno e Turnip, como desligar conditional rendering ("device loss on
  Turnip"), desligar async compute e passthrough DMA em GPU móvel, cache de pipeline em disco do
  driver e frame generation (`VKFrameGen.cpp`).
- Drivers: `com.armsx2.CustomDriver` baixa e instala pacotes AdrenoTools (Turnip) de seis fontes
  no GitHub, com driver por jogo. As variáveis do Mesa (por exemplo `TU_DEBUG`) vêm de
  `<raiz>/driver_env.txt`.
- **Não verificado:** não há mensageiro `VK_EXT_debug_utils` no backend VK. Mensagens da camada
  de validação e do driver só chegam ao log quando o core as registra explicitamente.

### Logs já existentes

- Core: `<raiz-de-dados>/cache/ARMSX3.log`, com 3 gerações (`.old`, `.old2`, `.old3`).
  Sessões com menos de 32 KB são descartadas na rotação. O limite é de 25% do espaço livre.
- App: `<externalFilesDir>/logs/session.log` (stdout/stderr, até 512 KB) e `crash-*.txt`
  (exceções Kotlin/Java).
- Não havia exportação de diagnóstico: o usuário precisava achar esses arquivos com um
  gerenciador de arquivos. O Marco 4 resolve isso (veja `DIAGNOSTICO.md`).

## 5. Build Android

O passo a passo do upstream está no `README.md`. Resumo verificado nos scripts:

| Componente | Versão fixada no repositório |
|---|---|
| NDK | `29.0.14206865` (`android/configure.sh`; o NDK 28.2/clang 19 falha com `-Wreturn-type`) |
| CMake | `3.30.5` |
| JDK | 17 (source/target). O Gradle 9.4.1 roda com JDK 17+ (verificado aqui com JDK 21) |
| Gradle / AGP / Kotlin | 9.4.1 / 9.2.1 / 2.4.0 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 33 (sobrescrevível por `-Parmsx3.minSdk`) |
| ABI | somente `arm64-v8a` |

Variantes do core (`android/build-variants.sh`): `legacy` (API 30, armv8.1-a), `a11` (API 30),
**`a13` (API 33, armv8.2-a+dotprod+fp16)** e `a15` (API 35). O Snapdragon 8 Gen 2 do Odin 2
Portal suporta dotprod e fp16, então a variante indicada para ele é a `a13`, ou a `a15` se o
Android do aparelho for 15+.

Variantes do app (flavors): `github` (com atualizador e frame generation) e `play`.

**Estado do Marco 2 (build reproduzível):** procedimento e problemas encontrados em `BUILD.md`.
O texto abaixo era o estado na primeira auditoria.

- Nesta sessão rodaram a configuração do Gradle e os testes unitários JVM do app (veja o
  relatório da tarefa).
- O core (`libarmsx3-core.so`) **não** foi compilado: exige NDK 29 e o build completo do LLVM
  (horas, dezenas de GB).
- **Não foi gerado nenhum APK.**

## 6. Testes e workflows existentes

- `rpcs3/tests/`: testes unitários do RPCS3 (gtest, `-DBUILD_RPCS3_TESTS=ON`), feitos para
  desktop.
- `android/armsx3-ui/app/src/test/`: testes JVM do app (driver env, tier do aparelho, animações).
- `.github/workflows/rpcs3.yml`: builds **desktop** do RPCS3 (Linux, macOS e Windows). Roda em
  `pull_request` e em push para `master`. Os passos de deploy só rodam em `RPCS3/rpcs3`. **Não**
  compila Android.
- `.github/workflows/llvm.yml` (manual) e `qt-ts.yml` (traduções Qt): herdados do desktop.
- Os workflows do ARMSX3 para Android não estão no repositório público (o `build.gradle.kts` cita
  um "private release CI").
- **Adicionado por este fork:** `.github/workflows/armadasx3-android-unit-tests.yml`, que roda os
  testes JVM do app a cada push/PR que toque `android/armsx3-ui/**`.

## 7. Diferenças entre a base e os objetivos deste projeto

| Objetivo do Armadasx3 | Situação na base | Ação |
|---|---|---|
| Diagnóstico exportável por ZIP, sem root, com `report.json` | Inexistente; só arquivos soltos | **Feito** (Marco 4, ainda não validado no aparelho) |
| Sessão interrompida recuperável | Os logs sobrevivem, mas não há marcador de sessão | **Feito** (journal de sessão) |
| Identidade exata do driver (incl. hash) | Nome e versão do `meta.json` | **Feito** no relatório (SHA-256 da `.so`) |
| Métricas de frame time, PPU/SPU/RSX e pipelines no relatório | Existem no overlay (OSD), não exportadas | Pendente: instrumentação no core |
| Matriz de compatibilidade por jogo no Odin 2 Portal | Inexistente | Modelo criado (`COMPATIBILIDADE.md`), sem entradas |
| CI que compila o APK | Não público | Pendente (Marco 3) |
| Testes de equivalência PPU/SPU (recompilador × interpretador) | Inexistentes no ARM64 | Pendente |

## 8. Riscos de divergência e plano de acompanhamento do upstream

Veja `UPSTREAM.md`. Em resumo:

- O ARMSX3 já diverge bastante do RPCS3 (cerca de 40 mil linhas em `rpcs3/`), principalmente no
  Vulkan. Cada merge do RPCS3 feito pelo ARMSX3 chega até nós pelo ARMSX3. Por isso a **fonte
  primária de sincronização é o ARMSX3**, e o RPCS3 entra só para correções pontuais que o ARMSX3
  ainda não incorporou.
- As mudanças locais ficam isoladas em `com.armadasx3.*`, `docs/armadasx3/` e no workflow
  próprio. No código herdado entram apenas **ganchos de uma linha** (veja a lista em
  `UPSTREAM.md`), para que os merges conflitem o mínimo possível.
