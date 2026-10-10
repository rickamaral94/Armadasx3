# Diagnóstico exportável da sessão

Marco 4. Código em `android/armsx3-ui/app/src/main/java/com/armadasx3/diagnostics/`.

**Estado:** implementado e coberto por testes unitários JVM. O código nativo compila para arm64.
**Ainda não foi validado em aparelho** (nem no Odin 2 Portal nem em outro). Roteiro em
`VALIDACAO-ODIN2.md`.

## Como usar

1. Abra **Configurações › App** e toque em **"Exportar diagnóstico da sessão"** (🩺), logo abaixo
   de Backup.
2. Escolha a sessão:
   - em andamento;
   - **interrompida**, quando o app morreu com um jogo aberto;
   - última sessão encerrada.
3. Confira a lista de arquivos e desmarque os opcionais que não quiser enviar.
4. Descreva o **cenário** (onde no jogo, o que estava fazendo) e **o que aconteceu**.
5. Toque em **"Escolher onde salvar"**. O seletor do Android (Storage Access Framework) abre e
   você decide onde gravar o `.zip`. Nada é enviado pela internet.

Se a sessão anterior não terminou normalmente, a própria linha avisa: "A última sessão não
terminou normalmente (…). Toque para exportá-la." Essa sessão fica disponível até ser exportada
ou descartada, mesmo que você jogue de novo depois.

## Como a sessão é registrada

Os ganchos são de uma linha cada no código herdado:

| Evento | Onde |
|---|---|
| Abertura do processo: recupera sessão deixada aberta | `Pasx2Application.onCreate` → `SessionDiagnostics.init` |
| Início do jogo | `MainActivityRuntime` (quando `currentGame` é definido no launch) → `onGameStarted` |
| Jogo identificado após boot direto | `MainActivityRuntime` (adoção do serial) → `onGameIdentified` |
| Driver Vulkan fixado | `MainActivityRuntime`, logo após `CustomDriver.applyToNative` → `onDriverPinned` |
| Cada frame do jogo (core) | `rpcsx-android.cpp`, `flip()` → `armadasx3::metrics::on_guest_flip` |
| Volta à biblioteca | `MainActivityRuntime.onReturnedToLibrary` → `onReturnedToLibrary` |

O journal fica em `<externalFilesDir>/diagnostics/`:

- `current/`: `state.json` (reescrito de forma atômica) e `events.jsonl` (append com `fsync`);
- `last/`: a última sessão encerrada;
- `interrupted/`: a última sessão que não terminou normalmente.

Eventos registrados durante a sessão:

- `session_start` e `session_end`;
- `driver_pinned` e `game_identified`;
- `memory_at_start` e `memory_at_end` (`ActivityManager.MemoryInfo`);
- `thermal_status`, no início e a cada mudança (`PowerManager.addThermalStatusListener`);
- `trim_memory` e `low_memory` (`ComponentCallbacks2`).

Os eventos são limitados a 256 KB por sessão. O que passar disso é contado em `events_dropped`,
não perdido em silêncio.

### Métricas de desempenho (amostrador de 1 Hz)

Durante a sessão, `MetricsSampler` roda uma vez por segundo numa thread própria e grava:

- `frame_intervals.u32le`: **todos** os intervalos entre `flip()` consecutivos do jogo, em
  microssegundos (u32 little-endian). Mede a cadência que o **jogo** atinge, não o intervalo de
  apresentação na tela, que vem depois da frame generation, do vsync e do compositor. Limite de
  16 MB por sessão (cerca de 18 h a 60 fps); o excedente é contado em `frames_not_stored`.
- `metrics.jsonl`: uma linha por janela com:
  - FPS e frame time p50/p99/máx da janela;
  - CPU dos grupos de threads PPU, SPU e RSX, e do processo inteiro (`/proc/self/stat`), em % de
    **um** núcleo (pode passar de 100);
  - número de threads PPU/SPU;
  - temperaturas de CPU, GPU e bateria (°C);
  - memória disponível;
  - intervalos perdidos no ring buffer do core.

  Limite de 4 MB por sessão.

De onde vêm os dados:

- **Core** (`android/src/armadasx3_metrics.cpp`):
  - um gancho de uma linha no `flip()` do frontend grava cada intervalo num ring buffer de 8192
    posições;
  - cerca de uma vez por segundo, na própria thread do RSX, lê o relógio de CPU de cada thread
    PPU/SPU e da RSX pelo **tid nativo**.

  O módulo **não** usa `thread_ctrl::get_cycles()`: essa chamada consome o contador (devolve o
  delta desde a chamada anterior) e corromperia os números do overlay de desempenho.
- **Ponte:** `native-lib.cpp` resolve os símbolos `_armadasx3_*` com `dlsym`. Num core sem eles,
  as métricas viram `unavailable`; nada quebra.
- **Temperaturas:** o leitor que o app já tinha (`com.armsx2.Thermals`), via sysfs térmico (zona
  escolhida pelo nome, específica de cada fabricante) e broadcast da bateria. Funciona sem root em
  muitos aparelhos, mas não em todos. Quando não dá para ler, o valor fica nulo.

Custo durante o jogo: uma escrita num array por frame no core e, uma vez por segundo, uma cópia via
JNI, algumas leituras de arquivo e dois appends pequenos. O ganho ou perda de desempenho causado
pela própria instrumentação **ainda não foi medido** no aparelho.

## Conteúdo do ZIP

| Caminho no ZIP | Origem | Padrão |
|---|---|---|
| `report.json` | Gerado | sempre |
| `session/state.json`, `session/events.jsonl` | Journal da sessão escolhida | sempre (quando há sessão) |
| `session/metrics.jsonl` | Amostras de 1 Hz | marcado |
| `session/frame_intervals.u32le` | Intervalos brutos entre flips (binário) | marcado |
| `logs/ARMSX3.log`, `logs/ARMSX3.old.log` | `<raiz>/cache/` | marcados |
| `logs/ARMSX3.old2.log`, `logs/ARMSX3.old3.log` | `<raiz>/cache/` | desmarcados |
| `logs/session.log` | `<externalFilesDir>/logs/` (stdout/stderr do app) | marcado |
| `logs/crash-*.txt` (as 3 mais recentes) | `<externalFilesDir>/logs/` | marcados |
| `config/config.yml` | Configuração global do core | marcado |
| `config/custom_configs/config_<serial>.yml` | Configuração do core por jogo | marcado |
| `config/driver_env.txt` | Variáveis Mesa/Turnip | marcado |

Por que `.old.log` vai marcado: depois de um crash, a reabertura do app faz o core rotacionar
`ARMSX3.log` para `ARMSX3.old.log`. O log da sessão interrompida costuma estar ali. Um log com
menos de 32 KB é descartado pela rotação do ARMSX3; nesse caso o relatório não terá o log daquela
sessão.

Arquivos de texto acima de 16 MB são gravados como início (2 MB) + final (14 MB), com um marcador
no meio. O resumo do log (`log_digest`) lê o arquivo **inteiro**.

## `report.json` — esquema versão 1

`schema` = `"armadasx3.diagnostic-report"`, `schema_version` = `1`.

- Uma mudança de significado ou remoção de campo incrementa a versão.
- Um campo opcional novo não incrementa.
- Um campo nunca é reaproveitado com outro sentido.

| Campo | Conteúdo |
|---|---|
| `generated_at_utc`, `export_reason` | Momento da exportação; `current` / `interrupted` / `last` / `none` |
| `build` | `app_id`, `version_name`, `version_code`, `build_type`, `core_git_version` (do `git-version.h` do core) |
| `device` | Fabricante, modelo, `soc_manufacturer`, `soc_model`, Android e SDK, ABIs, RAM total, `is_odin2_portal` (heurística por `Build`) |
| `session` | `id`, início e fim (UTC), `end_state` (`clean` / `interrupted` / `superseded` / `running`), `duration_ms` (relógio monotônico; nulo se interrompida), `process_uptime_at_start_s`, `events[]` (`t_ms` relativo ao início), `events_dropped` |
| `game` | `title`, `serial`, `region` (derivada da 3ª letra do serial), `version` (ainda nula), `container` |
| `driver` | `kind` (`system` / `custom`), `id`, `name`, `version`, `vendor`, `author`, `library_name`, `library_sha256`, `env_overrides` |
| `settings` | `effective` (config do app resolvida para o jogo) e `per_game_overrides` |
| `user_notes` | `scenario` e `observations` (com redação aplicada) |
| `log_digest[]` | Por log: `total_lines`, `count_by_level` (níveis do RPCS3: fatal/error/todo/warning/notice/success/always/trace), `highlights` (até 200 linhas: erros, fatais, perda de dispositivo Vulkan, sinais, GPU e driver escolhidos), `highlights_truncated` |
| `metrics[]` | `name`, `value`, `unit`, `source`, `method`, `sampled_at_ms`. Ver a tabela abaixo |
| `files[]` | Cada arquivo incluído: `original_bytes`, `stored_bytes`, `truncated`, `redacted` |
| `unavailable[]` | O que não foi coletado e por quê, incluindo os arquivos desmarcados e os excluídos |
| `privacy` | O que nunca é incluído; `sent_automatically: false` |

Use `process_uptime_at_start_s` para alinhar a sessão com o `ARMSX3.log`: os horários do log do
RPCS3 contam a partir do início do core, que fica próximo do início do processo.

### Métricas no `report.json`

Os resumos são calculados na exportação a partir do journal, mesmo que os arquivos brutos sejam
desmarcados.

| Nome | Definição |
|---|---|
| `session.frames` | Intervalos gravados |
| `session.fps_avg` | Frames ÷ soma dos intervalos |
| `session.fps_1pct_low` | 1000 ÷ frame time p99 |
| `session.frame_time_p50/p95/p99` | Percentil por posição mais próxima (nearest-rank), em ms |
| `session.frame_time_max` | Maior intervalo, em ms |
| `session.stutters` | Intervalos maiores que 2× a mediana |
| `session.hitches` | Intervalos ≥ 100 ms (telas de carregamento contam) |
| `cpu.ppu/spu/rsx/process.mean` e `.max` | Média e pico das amostras de 1 Hz, em % de um núcleo |
| `temp.cpu/gpu/battery.mean` e `.max` | Média e pico, em °C |
| `memory.available_min` | Menor memória disponível observada, em MB |
| `session.frame_intervals_lost` | Intervalos sobrescritos no ring antes da leitura (0 é o esperado) |
| `session.frames_not_stored` | Intervalos além do limite de 16 MB |
| `export.mem_available`, `export.thermal_status` | Amostra única **no momento da exportação** |

As definições estão também em `FrameStats.kt`. Mudar uma definição exige mudar o nome da métrica,
para não quebrar a comparação histórica.

## O que ainda **não** é coletado

O relatório lista explicitamente em `unavailable`:

- Estatísticas de compilação de shaders/pipelines e de filas/fences Vulkan. Exigem
  instrumentação no core, que ainda não existe. A métrica não é estimada.
- Mensagens das camadas de validação Vulkan. As camadas não são distribuídas, e o core não instala
  `VK_EXT_debug_utils`.
- `logcat`, que exige `READ_LOGS`, concedida só via ADB.
- Clocks de CPU/GPU: não há API sem privilégio. Temperaturas em °C são de melhor esforço (veja
  acima), e o estado térmico do `PowerManager` é registrado sempre.
- Versão do jogo (`PARAM.SFO`) e capturas de tela.

## Privacidade

- Só entram arquivos de uma **lista fechada**, e o `BundleWriter` ainda recusa qualquer caminho
  ou arquivo com cara de jogo, firmware, BIOS, chave, licença ou save. Isso inclui extensões como
  `.iso`, `.pkg`, `.pup`, `.rap`, `.rif`, `.edat`, `.self`, `.sprx`, `.elf`, `.bin`, `.dat`,
  `.sav` e `.savestat`, e trechos de caminho como `dev_flash`, `dev_hdd0/home`, `dev_hdd0/game`,
  `savedata`, `savestates` e `exdata`. O que for recusado aparece em `unavailable`.
- Todo texto passa por `PathRedactor`:
  - raiz de dados → `<data-root>`;
  - `/storage/emulated/N` e `/sdcard` → `<internal-storage>`;
  - `/storage/XXXX-XXXX` → `<external-storage>`;
  - `/data/user/N/<pacote>` → `<app-private>`;
  - URIs `content://` → `content://<redacted>`;
  - IDs de documento SAF → `<saf-document>`;
  - e-mails → `<email>`.

  Os nomes de arquivo são mantidos. A redação é de melhor esforço, e a tela de exportação avisa
  isso.
- Nada é enviado pela rede. O arquivo vai só para onde o usuário escolher.
- Não precisa de root, ADB nem de permissões novas.

## Testes

`app/src/test/java/com/armadasx3/diagnostics/` cobre:

- **Redação** (`PathRedactorTest`): caminhos e e-mails são substituídos e o texto comum fica
  intacto.
- **Resumo de log** (`LogDigesterTest`): níveis, contagens, limite de destaques e linhas longas.
- **Journal** (`SessionJournalTest`):
  - sessão limpa e sessão interrompida por morte do processo;
  - persistência do driver antes do crash;
  - sessão substituída;
  - limite de eventos.
- **ZIP** (`BundleWriterTest`):
  - redação e `report.json` versionado;
  - exclusões de privacidade, inclusive quando a fonte proibida tem nome inocente no ZIP;
  - corte início+final com o resumo do arquivo completo;
  - divisão de linhas com CRLF.
- **Heurísticas** (`HeuristicsTest`): região e detecção do Odin 2 Portal.
- **Estatística de frames** (`FrameStatsTest`): percentis, stutters/hitches, intervalos u32 acima
  de `Int.MAX`, ida e volta do arquivo `u32le` e limite por sessão.
- **Resumo da sessão** (`SessionMetricsTest`): agregação das amostras, linhas inválidas ignoradas,
  métricas ausentes reportadas como indisponíveis (nunca como zero), cálculo de CPU% e parsing de
  `/proc/self/stat`.

A camada Android (`SessionDiagnostics`, `MetricsSampler`, `DiagnosticsExportUi`) e o módulo
nativo **não** têm teste instrumentado. O módulo nativo e a ponte JNI foram compilados com o NDK 29
para arm64, mas não foram executados.
Ela precisa ser validada no aparelho:

1. Exportar depois de uma sessão normal.
2. Forçar o encerramento durante um jogo (`Configurações do Android › Forçar parada`), reabrir o
   app e conferir se a sessão aparece como interrompida.
3. Abrir o ZIP e conferir o conteúdo e a redação.
