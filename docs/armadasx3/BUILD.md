# Build reproduzível (Marco 2)

Como compilar o core (`libarmsx3-core.so`) e o APK a partir de um clone limpo, em **Linux x86-64**.
O mesmo procedimento está automatizado em `.github/workflows/armadasx3-android-build.yml`
(acionamento manual).

## Versões fixadas

| Item | Versão | Onde está fixada |
|---|---|---|
| NDK | 29.0.14206865 | `android/configure.sh` (o NDK 28.2/clang 19 falha no core) |
| CMake (core) | 3.30.5 | `android/configure.sh` |
| JDK | 17+ (testado com 21) | `app/build.gradle.kts` (source/target 17) |
| Gradle / AGP / Kotlin | 9.4.1 / 9.2.1 / 2.4.0 | wrapper e `gradle/libs.versions.toml` |
| Android SDK | platform `android-37.0`, build-tools 37.0.0 | `compileSdk = 37` |
| LLVM | submódulo `3rdparty/llvm/llvm` (compilado do código-fonte) | `.gitmodules` |
| librashader | `87e8a97b50516d997defeaa168173dcd185d4022` | workflow (só os cabeçalhos são usados no build) |
| libadrenotools | `8fae8ce254dfc1344527e05301e43f37dea2df80` | workflow |

Variante para o Odin 2 Portal: **`a13`** (API 33, `armv8.2-a+dotprod+fp16`). Use `a15` se o
aparelho estiver no Android 15 ou mais novo.

## Passo a passo

```sh
git clone https://github.com/rickamaral94/Armadasx3 && cd Armadasx3
git submodule update --init --recursive --depth 1 --jobs 8
git clone https://github.com/SnowflakePowered/librashader 3rdparty/librashader
git -C 3rdparty/librashader checkout 87e8a97b50516d997defeaa168173dcd185d4022
git clone https://github.com/bylaws/libadrenotools android/armsx3-ui/app/src/main/cpp/libadrenotools
git -C android/armsx3-ui/app/src/main/cpp/libadrenotools checkout 8fae8ce254dfc1344527e05301e43f37dea2df80
git -C android/armsx3-ui/app/src/main/cpp/libadrenotools submodule update --init --recursive --depth 1

export ANDROID_HOME=/caminho/do/android-sdk
sdkmanager "ndk;29.0.14206865" "cmake;3.30.5" "platforms;android-37.0" "build-tools;37.0.0"

bash android/stamp-git-version.sh
NDK_VERSION=29.0.14206865 ANDROID_API=33 BUILD_DIR=$PWD/build-a13 \
  bash android/configure.sh -DARMSX3_ARM_MARCH=armv8.2-a+dotprod+fp16
$ANDROID_HOME/cmake/3.30.5/bin/ninja -C build-a13 android/libarmsx3-core.so

$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded \
  -o android/armsx3-ui/app/src/main/jniLibs/arm64-v8a/libarmsx3-core.so build-a13/android/libarmsx3-core.so

cd android/armsx3-ui
./gradlew :app:testGithubDebugUnitTest
./gradlew :app:assembleGithubRelease -Parmsx3.minSdk=33   # assinado com a chave de debug
```

O APK sai em `android/armsx3-ui/app/build/outputs/apk/github/release/app-github-release.apk`.

## O que precisou mudar para compilar fora do macOS e sem o SDK do Discord

Problemas encontrados ao compilar a base num Linux limpo, todos corrigidos com mudanças mínimas
(listadas em `UPSTREAM.md`):

1. `app/build.gradle.kts` dependia sempre de `libs/discord_partner_sdk.aar`, que é proprietário e
   não está no repositório. Agora essa dependência só entra se o arquivo existir.
2. `app/src/main/cpp/CMakeLists.txt` também exigia `libdiscord_partner_sdk.so` para o alvo
   `armsx2_discord`. Agora esse alvo só é criado se o arquivo existir; o Java já tolera a ausência
   da biblioteca.
3. `savers/skyrocket/shockwave.cpp` incluía `<gl/gl.h>`, que só resolve em sistema de arquivos
   sem distinção de maiúsculas (macOS). Corrigido para `<GL/gl.h>`.
4. `android/build-variants.sh` usa ferramentas do NDK em `prebuilt/darwin-x86_64`. Não foi
   alterado; o workflow e o passo a passo acima usam `linux-x86_64` diretamente.

Frame generation (`armsx3_lsfg`) **não** é compilada: o código de `3rdparty/lsfg/lsfg-vk-android`
não está no repositório. Sem a biblioteca, o core desliga o recurso.

## Estado

| Etapa | Situação |
|---|---|
| Configuração do core (CMake, NDK 29, API 33) | **OK** neste ambiente |
| Módulo de métricas `armadasx3_metrics.cpp` compilado isoladamente (arm64) | **OK** |
| Glue JNI do app (`libarmsx3-jni.so`, com a ponte de métricas) via Gradle | **OK** |
| Testes JVM do app | **OK** |
| Core completo (`libarmsx3-core.so`, inclui o LLVM) | Ver o relatório da tarefa |
| APK | Ver o relatório da tarefa |
| Workflow de CI de build completo | Escrito; ainda não executado no GitHub Actions |
