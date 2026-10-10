# Matriz de compatibilidade — Odin 2 Portal

**Ainda nenhum jogo foi testado por este projeto.** A tabela abaixo está vazia de propósito:
nenhuma linha entra sem um teste real, com diagnóstico exportado anexado ou referenciado.

## Estados

Cada aspecto recebe uma nota separada. "Abriu" não significa "compatível".

| Código | Estado geral |
|---|---|
| `0-nao-inicia` | Não inicia |
| `1-falha-antes` | Inicia, mas falha antes do jogo |
| `2-chega-ao-jogo` | Chega ao jogo |
| `3-jogavel-com-problemas` | Jogável com problemas |
| `4-estavel-janela` | Estável durante a janela de teste |
| `5-estavel-desempenho` | Estável e com desempenho aceitável para aquele jogo |

Aspectos avaliados separadamente: **inicialização**, **estabilidade**, **gráficos**, **áudio**,
**controles** e **desempenho**. Cada um recebe `ok`, `problemas` (com descrição) ou `falha`.

## Protocolo mínimo de teste

1. Antes de jogar, defina o **cenário** (trecho do jogo, ação) e a **duração**:
   - pelo menos 15 min para a avaliação inicial de estabilidade;
   - pelo menos 30 min para investigar travamentos recorrentes.
2. Registre: build (versão + `core_git_version`), driver (nome, versão, SHA-256), variante do
   core, resolução interna e de saída, e configurações. O diagnóstico exportado já registra a
   maioria.
3. Anote se o cache de shaders/PPU/SPU estava **frio** ou **aquecido**.
4. Exporte o diagnóstico ao final, ou após a falha. Na próxima abertura, se houver crash.
5. O resultado descreve **só a janela observada**. Não garante ausência de falhas depois dela.
6. Resultados obtidos fora do Odin 2 Portal recebem a marca **"não validado no Odin 2 Portal"**.

## Matriz

| Jogo | Serial | Região | Versão | Build | Driver | Config. relevante | Cenário / duração | Cache | Init | Estab. | Gráficos | Áudio | Controles | Desempenho | Estado | Evidência | Data |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|

## Escolha dos primeiros jogos de baseline

Estes ainda não foram testados. Use jogos que exercitem subsistemas diferentes, por exemplo:

- um com uso pesado de SPU;
- um limitado pela PPU;
- um pesado em RSX/shaders;
- um 2D/leve, como controle.

A lista concreta deve sair da biblioteca disponível para o testador. Este repositório não inclui
nem indica fontes de jogos.
