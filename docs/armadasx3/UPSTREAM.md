# Acompanhamento do upstream

## Remotos

```sh
git remote add armsx3 https://github.com/ARMSX2/ARMSX3   # fonte primária
git remote add rpcs3  https://github.com/RPCS3/rpcs3     # correções pontuais
```

| Remoto | Papel | Último ponto incorporado |
|---|---|---|
| `armsx3` (`main`/HEAD) | Base direta. Seus merges do RPCS3 chegam por ele | `f3bdcd5c` (1.0.9.1, 2026-10-09) |
| `rpcs3` (`master`) | Base do ARMSX3. Só para correções que o ARMSX3 ainda não trouxe | merge-base `961c86fb` (2026-09-04), via ARMSX3 |

## Rotina de sincronização

1. Busque os dois remotos: `git fetch armsx3 && git fetch rpcs3 master`.
2. Veja o que entrou: `git log --oneline HEAD..armsx3/HEAD`.
3. Para mudanças no Cell ou no Vulkan, veja também
   `git log --oneline $(git merge-base HEAD rpcs3/master)..rpcs3/master -- rpcs3/Emu/Cell rpcs3/Emu/RSX`.
4. Faça `git merge armsx3/HEAD` num branch próprio. Não use rebase: o histórico é compartilhado.
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

Fora do código herdado: `android/src/armadasx3_metrics.{h,cpp}`,
`.github/workflows/armadasx3-android-{unit-tests,build}.yml`, `ARMADASX3.md` e `docs/armadasx3/`.

Candidatas a enviar ao ARMSX3 como PR (corrigem o build fora do macOS e sem o SDK do Discord):
as mudanças em `build.gradle.kts`, `cpp/CMakeLists.txt` e `shockwave.cpp`.

## Histórico de sincronizações

| Data | De | Até | Conflitos | Observações |
|---|---|---|---|---|
| 2026-10-10 | — | `armsx3` `f3bdcd5c` | — | Importação inicial com histórico completo |
