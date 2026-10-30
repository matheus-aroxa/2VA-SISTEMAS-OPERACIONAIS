# Simulador de Sistema de Arquivos

Simulador em **Java 17+** (sem dependências externas) de um sistema de arquivos com:

- **Hierarquia em árvore** navegável, com caminhos absolutos e relativos (`/`, `/home/user/doc.txt`, `../logs`, `.`).
- **CRUD** de arquivos e diretórios (`mkdir`, `create`, `cat`, `write`, `append`, `resize`, `mv`, `rm`, `rmdir`).
- **Disco físico** dividido em blocos de tamanho fixo (configurável: 512B, 1KB, 4KB...). O conteúdo dos arquivos é
  gravado de verdade nos blocos e lido de volta seguindo a estrutura de cada estratégia.
- **Três estratégias de alocação**, selecionáveis:
  | Estratégia | Estrutura | Metadados |
  |---|---|---|
  | `contigua` | (início, comprimento) por arquivo; política first/best/worst-fit | Bloco 00 = tabela de diretórios |
  | `encadeada` | Tabela FAT: cada bloco aponta para o próximo até `EOF` | Bloco 00 = FAT |
  | `indexada` | Cada arquivo/diretório tem um i-node (bloco de índice gravado no disco) com os ponteiros dos blocos de dados | i-nodes |
- **Diagnóstico do disco** (`fs_status --details`): mapa bloco a bloco, mapa gráfico colorido, fragmentação interna
  por arquivo, fragmentação externa e indicação explícita de necessidade de compactação (`defrag`).

## Como compilar e executar

```bash
javac -d out src/fsim/*.java
java -cp out fsim.Main                                   # modo interativo (padrão: indexada, 16 blocos de 4KB)
java -cp out fsim.Main -s contigua --block-size 1KB --blocks 32
java -cp out fsim.Main exemplos/indexada.txt             # executa um roteiro de comandos
```

Testes:

```bash
javac -d out src/fsim/*.java test/fsim/*.java
java -cp out fsim.FileSystemTest
```

Dentro do simulador, `demo indexada`, `demo contigua` e `demo encadeada` executam cenários prontos (os mesmos de
`exemplos/`), e `help` lista todos os comandos.

## Comandos

| Comando | Descrição |
|---|---|
| `pwd`, `cd <caminho>`, `ls [caminho]`, `tree [caminho]` | Navegação |
| `mkdir [-p] <dir>` | Cria diretório |
| `create <arq> [--size 10KB] [--content "texto"]` | Cria arquivo |
| `cat <arq>` / `stat <caminho>` | Lê conteúdo / mostra metadados e blocos |
| `write <arq> <texto>` / `append <arq> <texto>` | Sobrescreve / acrescenta (`\n` = nova linha) |
| `resize <arq> --size N` | Trunca ou aumenta o arquivo |
| `mv <origem> <destino>` | Move ou renomeia |
| `rm [-r] <caminho>` / `rmdir <dir>` | Remove |
| `fs_status [--details]` / `map` | Diagnóstico do disco |
| `defrag` | Compacta o disco |
| `format --strategy S --block-size B --blocks N [--fit first\|best\|worst]` | Reformata o disco |

## Exemplo — alocação indexada

```
> mkdir /sistema
> mkdir /sistema/logs
> create /sistema/logs/app.log --size 10KB
> tree
[ÁRVORE DO SISTEMA DE ARQUIVOS]
/
└── sistema/ [DIR]
    └── logs/ [DIR]
        └── app.log (10 KB) [ARQ]
> fs_status --details
Tamanho do Bloco de Disco: 4 KB | Total de Blocos: 16 (64 KB)
Estratégia: Alocação Indexada (i-node)
Ocupação: 7/16 blocos (43.8%) | Livre: 9 blocos (36 KB)

[MAPA DE ALOCAÇÃO DO DISCO (Indexado)]
Bloco 00 [INODE_RAIZ]     : Diretório '/'
Bloco 01 [INODE_SISTEMA]  : Diretório '/sistema'
Bloco 02 [INODE_LOGS]     : Diretório '/sistema/logs'
Bloco 03 [INODE_APP]      : Arquivo '/sistema/logs/app.log' | índice: [04, 05, 06]
Bloco 04 [DADO]           : '/sistema/logs/app.log' (Bloco 1 - 4096 bytes ocupados)
Bloco 05 [DADO]           : '/sistema/logs/app.log' (Bloco 2 - 4096 bytes ocupados)
Bloco 06 [DADO]           : '/sistema/logs/app.log' (Bloco 3 - 2048 bytes ocupados)
Bloco 07..15              : [LIVRE]

[MAPA GRÁFICO DO DISCO]
    0   1   2   3   4   5   6   7   8   9   10  11  12  13  14  15
00 [Ai][Bi][Ci][Di][D█][D█][D▄][··][··][··][··][··][··][··][··][··]
...
--- RELATÓRIO DE FRAGMENTAÇÃO ---
Arquivo '/sistema/logs/app.log'
  Tamanho Real do Arquivo : 10240 bytes (10 KB)
  Espaço Alocado no Disco : 12288 bytes (3 bloco(s) de 4 KB)
  Fragmentação Interna    : 2048 bytes (50% de desperdício no Bloco 06)
  Alocação                : i-node no bloco 03 → índice [04, 05, 06]

Fragmentação Interna Total : 2048 bytes (2 KB) em 1 arquivo(s)
Fragmentação Externa       : 0 bytes (0 B)
Necessidade de Compactação : NÃO (Blocos alocados via i-node indexado)
```

Neste simulador os diretórios também ocupam um i-node, por isso `app.log` começa no bloco 03.

## Exemplo — alocação contígua com fragmentação externa

Após criar e remover arquivos alternados (`demo contigua`), sobram 8 blocos livres espalhados em lacunas de 2:

```
00 [##][A█][A█][░░][░░][B█][B█][░░][░░][C█][C█][░░][░░][D▂][··][··]
> create grande.bin --size 16KB
Erro: fragmentação externa: há 8 blocos livres, mas a maior lacuna contígua tem apenas 2 (necessários 4). Execute 'defrag' para compactar.
...
Fragmentação Externa       : 24576 bytes (24 KB)
Necessidade de Compactação : SIM — 8 blocos livres (32 KB) dispersos em 4 lacunas; maior arquivo alocável hoje: 8 KB, após compactação: 32 KB. Execute 'defrag'.
```

`░░` marca os blocos livres dispersos (fragmentação externa) e os glifos `█ ▆ ▄ ▂ ▁` mostram quanto de cada bloco
está ocupado, o que deixa a fragmentação interna visível no último bloco de cada arquivo.

## Métricas

- **Fragmentação interna** = `blocos_alocados × tamanho_do_bloco − tamanho_real`, ou seja, o espaço livre no último
  bloco de cada arquivo.
- **Fragmentação externa** (só na contígua) = blocos livres fora da maior lacuna contígua, que não conseguem abrigar
  um arquivo maior que essa lacuna. Na FAT e na indexada ela é sempre 0, já que qualquer bloco livre serve.
- **Compactação necessária**: `SIM` quando o espaço livre da alocação contígua está dividido em mais de uma lacuna.
  Também aparece um `ALERTA` quando uma alocação foi recusada por esse motivo.

## Estrutura

```
src/fsim/Main.java                 ponto de entrada (CLI / roteiros)
src/fsim/Shell.java                interpretador de comandos e demos
src/fsim/FileSystem.java           árvore, caminhos, CRUD e desfragmentação
src/fsim/Node.java                 arquivo/diretório
src/fsim/Disk.java                 disco físico em blocos
src/fsim/Allocator.java            base das estratégias de alocação
src/fsim/ContiguousAllocator.java  alocação contígua
src/fsim/FatAllocator.java         alocação encadeada (FAT)
src/fsim/IndexedAllocator.java     alocação indexada (i-node)
src/fsim/Report.java               árvore, mapas e relatório de fragmentação
test/fsim/FileSystemTest.java      testes
exemplos/                          roteiros de demonstração
```
