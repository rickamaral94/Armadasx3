# Arquitetura do PS3 e onde ela vive no código

Referência para quem otimiza o emulador no ARM64 (Odin 2 Portal, Snapdragon 8 Gen 2). Três partes:
o hardware (fontes primárias da IBM), como o RPCS3 chegou ao que é hoje (histórico e números
publicados), e o mapa do hardware para o código deste repositório. Montado em 2026-10-16.

Convenções: **[não verificado]** = de memória ou fonte secundária; `arquivo:linha` = árvore no commit
`fa05e9ce`, as linhas mudam com o tempo. psdevwiki, wiki.rpcs3.net e rpcs3.net/blog estavam
inacessíveis (HTTP 403) durante a pesquisa; o que vem do blog do RPCS3 veio de trechos citados por
terceiros.

---

## Parte 1 — O hardware

### 1.1 Cell Broadband Engine

| Item | Valor |
|---|---|
| Clock | 3,2 GHz |
| PPE | 1 núcleo PowerPC 64 bits (ISA 2.02), in-order, dual-issue, SMT de 2 threads, VMX/AltiVec, L1 32+32 KB, L2 512 KB. Não é derivado do PowerPC 970 |
| SPEs | 8 no chip; 1 desligado (rendimento), 1 reservado ao sistema (segurança, modo isolado), **6 para jogos** |
| EIB | Anel de 4 canais de 16 bytes (2 por sentido) a 1,6 GHz, ~204,8 GB/s de pico |
| Memória principal | 256 MB XDR, 25,6 GB/s, latência ~28–36 ns |
| RSX | NV47 (GeForce 7), 500 MHz (anunciado 550), 256 MB GDDR3 a 20,8 GB/s; lê/escreve a XDR via FlexIO |
| Pico SP | 25,6 GFLOPS por SPE (FMA de 4 lanes), 204,8 no total |

### 1.2 A SPU

- **Registradores:** 128 de 128 bits, arquivo único para inteiro, float, endereço e condição. Não há
  registrador escalar: o escalar vive no **preferred slot** (bytes 0–3 para word/endereço, byte 3 para
  byte, bytes 2–3 para halfword, 0–7 para doubleword).
- **Local store (LS):** 256 KB de SRAM, sem cache. Código e dados vêm só dela; memória principal só
  por DMA. Todo endereço passa por `& LSLR`: dados `(RA+off) & 0x3FFF0`, PC `& 0x3FFFC` — acesso além
  do fim **dá a volta**. Só existem loads/stores de 16 bytes alinhados; escrita escalar é
  ler-modificar-escrever (`cwd`/`cbd` + `shufb` + `stqd`), leitura escalar é `lqd` + `rotqby`.
- **Pipelines:** par (even) e ímpar (odd). Um par de instruções alinhado a 8 bytes faz dual-issue se a
  do endereço par for de pipe par e a do ímpar de pipe ímpar.

| Classe | Latência (ciclos) | Pipe |
|---|---|---|
| Ponto fixo simples (add, lógicas, compare, select) | 2 | par |
| Shift/rotate de word, operações de byte (cntb, absdb, sumb) | 4 | par |
| Float simples | 6 | par |
| Conversão float↔int, multiplicação 16×16 (`mpy*`) | 7 | par |
| Double | 13, sendo 6 bloqueando toda emissão | par |
| Shuffle/rotate de quadword (shufb, rotqby, gb, fsm, frest) | 4 | ímpar |
| Load/store, canal | 6 | ímpar |
| Desvio | 4 | ímpar |
| Hint de desvio | 15 | ímpar |

- **Desvios:** sem preditor. Previsão estática "não tomado", salvo hint (`hbr`/`hbra`/`hbrr`). Erro de
  previsão custa 18–19 ciclos. O emulador ignora hints.
- **Float simples (modo de faixa estendida):** não existe NaN nem Inf — expoente 255 é número normal;
  máximo `0x7FFFFFFF` (~6,8e38), overflow satura; denormal vira zero; zero é sempre +0; arredondamento
  só por **truncamento**. Os flags por slot no FPSCR (OVF, UNF, DIFF, DBZ) são acumulativos.
- **Float duplo:** IEEE, 4 modos de arredondamento por slot, exceções só em flags.
- **Parada:** `stop <tipo>` (14 bits) sinaliza o SO; o lv2 usa códigos para syscalls de SPU (0x110
  receive event, 0x102 exit etc. **[não verificado]**). `heq/hgt/hlgt` param por condição.
- **Interrupção:** uma só, por evento; habilitada pelos bits E/D de desvios indiretos; salva PC em SRR0
  e pula para LS 0.

### 1.3 Instruções que importam para a emulação

| Família | Semântica | Equivalente NEON |
|---|---|---|
| `shufb rt,ra,rb,rc` | por byte: `rc[j]` `10xxxxxx`→0x00, `110xxxxx`→0xFF, `111xxxxx`→0x80, senão `(ra‖rb)[b&0x1F]` em ordem big-endian | `TBL` de 2 registradores + máscaras: 5 instruções (RPCS3 #18056) |
| `rotqby`, `shlqby`, `rotqmby` (+`bi`/`i`) | rotação/deslocamento do quadword por bytes, contagem módulo 16 | `EXT` (imediato) ou `TBL` (variável) |
| `selb` | `(rc & rb) \| (~rc & ra)` | `BSL`/`BIT`/`BIF` (atenção à ordem) |
| `gb`, `fsm` e variantes | junta bits baixos / expande bits em máscaras | `dup`+`cmtst` para FSM (RPCS3 #18806), `udot` para GB |
| `cntb`, `clz` | popcount por byte, zeros à esquerda por word | `CNT`, `CLZ .4S` |
| `mpy*` | só multiplicação 16×16→32; `a*b` de 32 bits é `mpyh+mpyh+mpyu` | `SMULL`/`UMULL`; padrão reconhecível como `MUL .4S` |
| `cg`/`bg`/`addx`/`sfx`/`cgx`/`bgx` | carry/borrow por word para aritmética de 64/128 bits | `CMHI` + somas |
| `frest`/`frsqest` + `fi` | estimativa por tabela (base 13 bits + passo 10 bits) e interpolação | `FRECPE`/`FRSQRTE` dão **bits diferentes**; exatidão exige a tabela |
| `cflts`/`csflt` | conversão com escala 2^(173−I8) / 2^(155−I8), satura | `FCVTZS`/`SCVTF` com fbits |
| `rdch`/`wrch`/`rchcnt` | canais; bloqueiam se vazios/cheios | — (é o ponto de sincronização) |

### 1.4 MFC (DMA e atômicos)

- **DMA:** `get`/`put` com sufixos `b` (barreira), `f` (fence), `l` (lista). Tamanhos 1, 2, 4, 8 ou
  múltiplos de 16 até 16 KB; os 4 bits baixos de LS e EA devem coincidir. Listas de até 2048 elementos.
  32 grupos de tag; sem fence/barreira **não há ordem garantida**. Fila de 16 comandos (SPU) e 8
  (proxy, PPE).
- **Atômicos:** `getllar` lê a linha de 128 bytes e cria reserva; `putllc` escreve só se a reserva
  continua; `putlluc` escreve sem condição; `putqlluc` é a versão em fila. O grânulo de 128 bytes é o
  mesmo das reservas do PPE (`lwarx`/`stwcx.`), então PPE e SPU disputam as mesmas linhas.
- **Evento Lr (reserva perdida):** só por escrita externa; permite à SPU dormir em `rdch
  SPU_RdEventStat` em vez de girar. É assim que os kernels SPURS ociosos esperam.
- **Canais principais:** 0 RdEventStat, 3/4 sinal 1/2, 7/8 decrementador, 16–21 parâmetros e comando
  do MFC, 22–24 tags, 27 status atômico, 28/29/30 mailboxes (entrada 4 posições; saída 1; saída com
  interrupção 1).

### 1.5 Modelos de programação de SPU no PS3

- **Grupos de threads de SPU (lv2):** o modelo dos jogos. Comunicação com o PPE por códigos de `stop`,
  mailbox de interrupção e filas de eventos.
- **SPU raw:** acesso direto por MMIO (`0xE0000000 + n·1 MB`). Usado pelo teste diferencial.
- **SPURS:** runtime da Sony; um kernel em cada SPU escolhe trabalho (workloads, tasksets, job chains)
  num bloco de controle em memória principal. **Cada escolha e liberação de trabalho é `getllar` +
  `putllc` nas mesmas poucas linhas** — por isso o tráfego de reservas domina em jogos com SPURS.
- **libsync** (`cellSync`): mutex, barreira, filas, sobre os mesmos loops de reserva.
- Middleware pesado em SPU: Edge (geometria, descompressão), MLAA, decodificação de áudio, física,
  job managers próprios dos estúdios. O PS3 **não tem DSP de áudio**: problema de tempo de SPU aparece
  primeiro como estalo no áudio.

### 1.6 PPU, memória e sistema

- **PPU:** big-endian; VMX com modo non-Java (denormais a zero) e regras de NaN próprias; reservas de
  128 bytes; ordem de memória fraca (`sync`, `lwsync`, `eieio`, `isync`); timebase ~79,8 MHz (o
  emulador usa 80 MHz).
- **Mapa de endereços do jogo:** `0x00010000` principal; `0x30000000` segmento fixo; `0x40000000` RSX;
  `0xB0000000` mmapper 1 MB; `0xC0000000` memória de vídeo; `0xD0000000` pilhas; `0xE0000000` SPU
  (raw em `0xE0000000+n·1 MB`, LS de threads em `0xF0000000+`).
- **Sistema:** lv1 (hypervisor), lv2 (kernel, ~1000 syscalls), módulos PRX. O emulador faz HLE do lv2 e
  **LLE** da libsre (SPURS), libsync e libfiber por padrão: roda o kernel SPURS real.

### 1.7 Por que emular SPU é caro, e o que o ARM64 tem a favor

1. **Pressão de registradores:** 128 do guest contra 32 do NEON (16 no x86 sem AVX-512) — o ARM está
   melhor que o x86 comum.
2. **Float:** NEON é IEEE. Truncamento e denormal a zero o FPCR resolve; ausência de Inf/NaN,
   saturação e +0 exigem sequências extras. Por isso os modos XFloat Accurate / Approximate / Relaxed.
3. **Código auto-modificável:** a SPU carrega código por DMA o tempo todo (overlays, jobs). O JIT
   verifica o conteúdo da LS na entrada de cada função compilada.
4. **Atômicos de 128 bytes:** o host faz no máximo CAS de 16 bytes. Não há TSX nem memória
   transacional no ARM. O emulador usa contador de versão por linha + lock + comparação dos 128 bytes;
   o atalho de 16 bytes (PUTLLC16) cobre cerca de um terço dos loops atômicos.
5. **Sincronização:** 6 SPUs, 2 threads de PPU, RSX e lv2 trocando sinais por canais, mailboxes e loops
   de espera. Espera mal feita queima núcleo; no 8 Gen 2 só há 5 núcleos fortes.

---

## Parte 2 — Como o RPCS3 chegou aqui (o que já foi tentado)

| Quando | Mudança | Ganho publicado |
|---|---|---|
| 2018 | Reservas com TSX | até ~40% em CPUs com TSX; depois desligado por padrão (2020) |
| mai–jul 2018 | Recompilador LLVM de SPU | melhor que ASMJIT em desempenho e áudio |
| dez 2018 | XFloat Approximate (#5430) | Sly 39→50, GoW3 9→15 FPS |
| jan 2019 | Compilação de cache de SPU multithread | 12 min 34 s → 1 min 28 s |
| 2019 | Programas de SPU independentes de posição | 11 mil → 2 mil programas (Spider-Man SD) |
| 2021–22 | Caminhos AVX-512 | GoW3: SSE4.1 160, AVX2 190, AVX-512 235 FPS |
| mai–jul 2024 | PUTLLC16, detecção de spin no GETLLAR, notificações, detecção de loop de canal (elad335) | 30–100% em 4 núcleos/4 threads |
| mar 2026 | Predição de iterações de loop (#18395) | +3–8% em jogos; **revertido em parte no ARM** (#18979) |
| jan–set 2026 | Campanha ARM64 (whatcookie, no Odin 2) | "60% mais rápido com 25% menos energia" |

Campanha ARM64 de 2026, item a item (todas já presentes neste fork, que sincronizou com o RPCS3 em
2026-10-11, salvo onde indicado):

- **#18055 escala do `busy_wait`** pelo timer ARM (19,2 MHz no 8 Gen 2): +25% médio, −10% energia. Aqui
  a escala está embutida na conta (`cycles/100`, `util/asm.hpp`); `init_arm_timer_scale()` daria 0 a
  19,2 MHz e manteria 1, então não chamá-la no Android não perde nada.
- **#18151 `ISB` em vez de `YIELD`** em `utils::pause()`. WFE mediu quase o consumo ocioso.
- **#18133 detecção de recursos** do ARM para o LLVM (o LLVM assumia SVE no X3 e quebrava Metal Gear
  Rising). Aqui: `llvm_cpu` vazio + flags por HWCAP.
- **#19646 LSE2 em tempo de execução** (GTA IV 19→58 FPS). Aqui há detecção própria (`util/atomic.hpp`).
- **SHUFB via TBL** (#18056, 9→5 instruções), **FSM** (#18806), **ROTQBY via TBL** (#18818), **USHL**
  (#18816), **udot** para SUMB/GB (#18350), checksum UABA e `cmp_rdata` com MLA (#18824, +22–38%).
- **Não aceitos upstream:** espera de reserva com WFE (#18913, fechado sem merge em 06/2026) e EarlyCSE
  no PPU (#19126). A ideia do WFE continua sendo a sugestão do próprio whatcookie, com a ressalva do
  event stream do kernel (~100 µs) e de não haver WFET no 8 Gen 2.
- Problemas abertos no ARM64: #16484 (lista de jogos quebrados), #17640 (congelamentos em M-series,
  teoria de livelock com `busy_wait`), #19511 (−14% de SPU em RDR após a reversão de #18395).

Lições:
1. Os maiores ganhos no ARM vieram de **esperas e sincronização** (busy_wait, ISB, LSE2), não de
   instrução por instrução.
2. O 8 Gen 2 faz 3 loads de 128 bits por ciclo mas só 2 operações de ALU: preferir reduções com MLA/UABA
   e loops enrolados (#18824).
3. Nunca confiar no nome de CPU do LLVM; passar recursos explícitos.
4. Segundo o whatcookie, o Odin 2 ficou **majoritariamente limitado pela GPU** depois de 2026 no RPCS3
   oficial. Para Fight Night Round 4 neste fork a medição mostra o contrário (CPU no limite, GPU
   folgada) — vale comparar com o RPCS3 oficial no mesmo jogo.

---

## Parte 3 — Mapa do hardware para o código

### 3.1 Thread de SPU (`rpcs3/Emu/Cell/SPUThread.{h,cpp}`)

- `spu_thread : cpu_thread`, uma thread do host por SPU. Tipos `threaded/raw/isolated`
  (`SPUThread.h:617`). No ARM64 o decodificador é sempre o LLVM de blocos (`SPUThread.cpp:2111`).
- `cpu_task` (`SPUThread.cpp:1712`): `while(true){ check_state; g_gateway(...); }`. O interpretador de
  reserva para blocos que não compilam roda **dentro** do gateway (comentário em `:1813`).
- LS: reserva de 7×256 KB com a mesma memória mapeada 5 vezes (espelhos para offsets fora da faixa,
  `map_ls` `:2043`). Bytes na ordem do guest; load de quadword = load + inversão de bytes.
- `gpr` em ordem do host (preferred slot na lane 3). Reserva: `rtime`, `rdata[128]`, `raddr`.
- Canais: `spu_channel` (`SPUThread.h:190`); `get_ch_value` `:6832`, `set_ch_value` `:7506`,
  `RdEventStat` `:6982` (espera de reserva do SPURS ocioso). Esperas: `pop_wait` faz 10× `busy_wait`,
  depois `notify_all`, depois futex.
- Decrementador com thread de interrupção própria (`:6640`, NBA 08). `stop_and_signal` `:8049`.
- Grupos lv2: `lv2/sys_spu.cpp` (create `:940`, start `:1201`). **SPU não passa pelo escalonador lv2**;
  roda livre no Linux, limitada só por SPURS/Preferred SPU Threads.

### 3.2 MFC e reservas

- `process_mfc_cmd` (`SPUThread.cpp:5471`), `do_dma_transfer` (`:2747`), listas `:3832`, fila `do_mfc`
  `:4988`.
- Tabela de reservas: `vm::reservation_acquire` (`Memory/vm_reservation.h:27`); `g_reservations` tem só
  32 KB — **linhas a cada 64 KB compartilham o mesmo contador**.
- GETLLAR (`:5510`): reaproveita, detecta spin, espera com `atomic_wait_timeout{100'000}`.
- `do_putllc` (`:4480`): (1) rtime diferente falha; (2) atalho SPURS sem checagem com Accurate off;
  (3) **caminho de 16 bytes** com range lock (RPCS3 #19568) com Accurate off; (4) senão
  `vm::writer_lock`, que **para todas as PPUs** (`Memory/vm.cpp:706`, espera em `:885`).
- PUTLLC16 no JIT (`SPULLVMRecompiler.cpp:1327`) instalado por lista branca de hashes
  (`SPUCommonRecompiler.cpp:9290`). **Bug conhecido não corrigido:** o commit do PUTLLC16 notifica a
  palavra errada, então os que esperam só acordam no timeout de 50–200 µs
  (`SPUCommonRecompiler.cpp:9500`).
- `cmp_rdata`/`mov_rdata` em NEON (`SPUThread.cpp:255`, `:418`); `rdata_fence` (Killzone 3).

### 3.3 Recompiladores de SPU

- Runtime (`SPURecompiler.h:131`): `g_dispatcher` com 2^20 entradas indexadas pela primeira palavra;
  `g_gateway` (`SPUCommonRecompiler.cpp:732`) salva registradores em `hv_ctx`; `g_escape` volta ao
  epílogo. Rascunho de pilha de 256 KB porque o código ARM64 de SPU não monta frames.
- Analisador `analyse` (`SPUCommonRecompiler.cpp:3574`): Safe para em chamadas; Mega segue chamadas
  fixas e retornos; Giga junta grafos de função. Rastreamento de valores detecta `reduced_loop`,
  `rchcnt_loop` e `putllc16`.
- LLVM (`SPULLVMRecompiler.cpp:218`, `compile` `:1803`): verificação da LS na entrada (`:2068`),
  `check_state` em laços, SHUFB ARM64 `:8285`, MPY `:7475`, floats `:8880–9690`, FREST/FRSQEST por lane
  `:8646/:8694`.
- **Cache de objetos de SPU desligado no ARM64** (`:4251–4290`): os objetos guardavam ponteiros
  absolutos. Toda sessão recompila o código de SPU a partir da lista `spu-*.dat` — pesa no tempo de
  boot e nos travamentos de compilação.
- Robustez ARM64 (ARMSX3): nova tentativa sem TBL2 e sem FMA quando o LLVM falha; faixa marcada para o
  interpretador (`SPUFailedBlocks.h`, `SPUCommonRecompiler.cpp:343`).
- Interpretadores: C++ (`SPUInterpreter.cpp`, sse2neon no ARM) e o "dinâmico" construído com LLVM.

### 3.4 PPU

- `ppu_thread::cpu_task` (`PPUThread.cpp:2900`); tradutor `PPUTranslator.cpp`. `lwsync` vira `dmb ish`
  completo (`:3800`). Com "Accurate PPU 128-byte Reservation Op" ≠ 0, todo `lwarx` cai no interpretador.
- Cache PPU: nome inclui `arm64_codegen_v12` — subir a versão ao mudar a geração de código ARM64.

### 3.5 Escalonamento e esperas no host

- `lv2_obj` (`lv2/lv2.cpp`): só PPUs; acima de `ppu_threads` (2) ficam suspensas.
- Afinidade: `get_affinity_mask` (`Utilities/Thread.cpp:4899`), só fora do modo OS.
- `utils::pause()` = `isb`; `wait_for_event()` = `sevl; wfe; wfe`; `busy_wait(300)` ≈ 3 ticks de
  52 ns no 8 Gen 2.
- `atomic_wait` no Android usa o motor emulado (`has_waitv()` falso, `util/atomic.cpp:38`).

### 3.6 RSX (contexto)

- FIFO `RSXFIFO.cpp` (espera ociosa com WFE no ARM64), Vulkan `VK/`, caches de superfície e textura,
  ZCULL `RSXZCULL.cpp`, sincronização com a PPU em `lv2/sys_rsx.cpp`.

---

## Parte 4 — Candidatos de otimização que esta pesquisa acrescenta

Além de `OTIMIZACAO.md`. Estimativas, não medições.

1. **Notificação do PUTLLC16:** corrigir o alvo para acordar quem espera de verdade, em vez de depender
   do timeout. Afeta jogos com PUTLLC16 na lista branca (Sonic '06, Soulcalibur V).
2. **Cache de objetos de SPU no ARM64:** gerar código relocável para religar o cache em disco. Ganho em
   boot e em travamentos de compilação, não em FPS sustentado.
3. **`g_reservations` de 32 KB:** contadores compartilhados a cada 64 KB causam falsas falhas de
   PUTLLC/GETLLAR entre linhas sem relação. Testar uma tabela maior.
4. **Espera de reserva com WFE** (`LDAXR` + `WFE`), com fallback e medição de energia e FPS. Upstream
   recusou a primeira tentativa por falta de evidência; precisa de A/B reprodutível.
5. **`lwsync` como `dmb ishld`+`dmb ishst`** em vez de `dmb ish` completo no tradutor PPU. Risco de
   ordem de memória; validar com testes.
6. **Comparar com o RPCS3 oficial** no mesmo jogo e aparelho para saber quanto do gargalo é do fork.

## Fontes

- IBM, *SPU Instruction Set Architecture* v1.2; *Cell Broadband Engine Architecture* v1.01; *CBE
  Programming Tutorial* v3.0 — https://arcm.csc.ncsu.edu/~mueller/cluster/ps3/SDK3.0/docs/
- Copetti, *PlayStation 3 Architecture* — https://www.copetti.org/writings/consoles/playstation-3/
- whatcookie — https://whatcookie.github.io/posts/why-is-avx-512-useful-for-rpcs3/ e
  https://whatcookie.github.io/posts/rpcs3-on-arm64-what-didnt-make-the-cut/
- RPCS3 PRs citados: https://github.com/RPCS3/rpcs3/pull/NNNNN (#5430, #15429, #15623, #18055, #18056,
  #18133, #18151, #18350, #18395, #18806, #18816, #18818, #18824, #18913, #18979, #19126, #19568,
  #19646); issues #16484, #17640, #19511.
- Imprensa: timeextension.com (2026/08, campanha ARM), computerbase.de, phoronix.com (TSX),
  guru3d.com (AVX-512/Alder Lake).
- Código deste repositório (arquivos citados acima).
