# Roteiro de validação no Odin 2 Portal

Para quem tem o aparelho. Cada passo diz o que observar. Anote o resultado (ok/falhou e o que viu)
e anexe os ZIPs exportados. Nada aqui exige root ou ADB.

## 0. Preparação

1. Instale o APK do build (`Armadasx3-<versão>-a13-<commit>.apk`, gerado pelo workflow
   "Armadasx3 Android build").
2. Anote: versão do Android, driver em uso (sistema ou Turnip, com nome e versão) e temperatura
   inicial aproximada (aparelho frio ou não).
3. Escolha **um** jogo e **um** trecho reproduzível (por exemplo, "primeiro minuto após carregar o
   save X"). Use o mesmo jogo e trecho nos passos abaixo.

## 1. Diagnóstico após sessão normal

1. Jogue por **15 minutos** no trecho escolhido e volte à biblioteca.
2. Abra **Configurações › App › Exportar diagnóstico da sessão**.
3. Confira:
   - aparece "Última sessão: <jogo>";
   - a lista mostra `session/metrics.jsonl`, `session/frame_intervals.u32le` e `logs/ARMSX3.log`.
4. Preencha o cenário, exporte e abra o ZIP num computador. Em `report.json`, confira:
   - [ ] `device.is_odin2_portal` é `true` (se der `false`, informe `manufacturer` e `model`);
   - [ ] `session.end_state` é `clean` e `duration_ms` é próximo de 15 min;
   - [ ] `driver` mostra o driver correto (com `library_sha256` se for Turnip);
   - [ ] `metrics` traz `session.fps_avg` coerente com o que você via no overlay de desempenho;
   - [ ] `cpu.ppu.mean`, `cpu.spu.mean` e `cpu.rsx.mean` existem;
   - [ ] `temp.cpu.*` e `temp.gpu.*` existem, ou aparecem em `unavailable` (anote qual dos dois);
   - [ ] os logs não contêm o caminho real da sua pasta (`/storage/emulated/0/...`).

## 2. Sessão interrompida

1. Abra o mesmo jogo e, com ele rodando, vá em **Configurações do Android › Apps › Armadasx3 ›
   Forçar parada**.
2. Reabra o app. A linha de exportação deve dizer "A última sessão não terminou normalmente (…)".
3. Exporte e confira:
   - [ ] `session.end_state` é `interrupted`;
   - [ ] `session/metrics.jsonl` tem amostras até perto do momento da parada;
   - [ ] `logs/ARMSX3.old.log` contém a sessão interrompida.
4. Jogue outra sessão curta e confira se a sessão interrompida continua oferecida até ser exportada
   ou descartada.

## 3. Impacto da instrumentação (opcional, mas importante)

A instrumentação deve ser leve, mas isso ainda não foi medido. Para medir:

1. Escolha uma cena estável, por exemplo parado num ponto fixo do jogo.
2. Faça 3 rodadas de 5 minutos com o build anterior (sem métricas) e 3 com este, começando sempre
   com o aparelho na mesma temperatura.
3. Compare o FPS do overlay de desempenho. Uma diferença dentro da variação entre rodadas do mesmo
   build significa "sem impacto mensurável".

## O que devolver

Os ZIPs dos passos 1 e 2 e as caixas marcadas ou não. Com isso dá para começar a matriz de
compatibilidade (`COMPATIBILIDADE.md`) e a baseline do Marco 5.
