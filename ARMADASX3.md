# Armadasx3

Emulador de PlayStation 3 para Android, com foco no **AYN Odin 2 Portal** (Snapdragon 8 Gen 2,
Adreno 740, ARM64, sem root).

É um fork do **ARMSX3** (https://github.com/ARMSX2/ARMSX3), que por sua vez é um fork do
**RPCS3** (https://github.com/RPCS3/rpcs3). O histórico completo dos dois foi preservado.
Licença: GPL-2.0-only (veja `LICENSE`). O crédito do emulador é dos autores do RPCS3 e do ARMSX3.

Prioridades, nesta ordem:

1. compatibilidade;
2. estabilidade;
3. correção de imagem e áudio;
4. frame time consistente;
5. desempenho medido.

Nenhum resultado de compatibilidade ou desempenho é afirmado sem teste. Veja a matriz.

| Documento | Conteúdo |
|---|---|
| [`docs/armadasx3/AUDITORIA.md`](docs/armadasx3/AUDITORIA.md) | Base, commit, licenças, arquitetura PPU/SPU/RSX/Vulkan, build, testes, lacunas |
| [`docs/armadasx3/DIAGNOSTICO.md`](docs/armadasx3/DIAGNOSTICO.md) | "Exportar diagnóstico da sessão" e esquema do `report.json` |
| [`docs/armadasx3/COMPATIBILIDADE.md`](docs/armadasx3/COMPATIBILIDADE.md) | Estados, protocolo de teste e matriz por jogo |
| [`docs/armadasx3/UPSTREAM.md`](docs/armadasx3/UPSTREAM.md) | Sincronização com ARMSX3/RPCS3 e lista de divergências locais |
| [`docs/armadasx3/BUILD.md`](docs/armadasx3/BUILD.md) | Build reproduzível em Linux (versões fixadas, passo a passo) |
| [`docs/armadasx3/VALIDACAO-ODIN2.md`](docs/armadasx3/VALIDACAO-ODIN2.md) | Roteiro de validação no aparelho |

Para compilar, siga o `README.md` (instruções do ARMSX3). O app não inclui firmware, BIOS, chaves
nem jogos.

## Marcos

| # | Marco | Estado |
|---|---|---|
| 1 | Auditoria da base | Concluído (documentado) |
| 2 | Build reproduzível (core + APK ARM64 a partir de clone limpo) | **Concluído**: core e APK compilam a partir de clone limpo em Linux, localmente e no GitHub Actions (execução 38052040154, commit `217363f5`) |
| 3 | CI confiável | Testes JVM a cada push (verdes); build completo manual verde em cerca de 67 min (core 50 min). Falta: lint/formatação e acionamento automático do build completo |
| 4 | Diagnóstico exportável | Implementado, com métricas de frame time e CPU por thread; testes JVM; **não validado em aparelho** |
| 5 | Baseline no Odin 2 Portal | Não iniciado |
| 6 | Correções direcionadas | Não iniciado |
| 7 | Acompanhamento do upstream | Sincronizado com ARMSX3 1.1 (`6dfb2967`) e RPCS3 `26098fb42` em 2026-10-11; divergências em `UPSTREAM.md` |
