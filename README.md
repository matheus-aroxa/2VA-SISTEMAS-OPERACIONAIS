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
java -cp out fsim.Main roteiro.txt                       # opcional: executa um arquivo de roteiro de comandos
```

Testes:

```bash
javac -d out src/fsim/*.java test/fsim/*.java
java -ea -cp out fsim.IoTest
java -ea -cp out fsim.SecurityTest
java -ea -cp out fsim.MemoryTest
java -ea -cp out fsim.ProcessTest
```

Dentro do simulador, `demo indexada`, `demo contigua`, `demo encadeada`, `demo raid5`, `demo seguranca`, `demo memoria` e `demo processos` executam
cenários prontos de demonstração, e `help` lista todos os comandos.

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

## Etapa 2 — Integração com o disco: E/S, escalonamento, SSD e RAID

Cada leitura e gravação do sistema de arquivos vira requisições de E/S no arranjo de discos:

- **Integração**: `Disk.read` (dado) e `Disk.write` geram requisições; `Disk.peek` (metadados em cache) não gera E/S.
  O arranjo traduz cada bloco lógico em operações nos discos físicos, e cada disco físico tem sua fila e sua cabeça.
- **Escalonamento do braço** (`disk_sched`, ou automático com `iosched`): FCFS, SSTF, SCAN e C-SCAN, com o
  deslocamento total. SCAN e C-SCAN vão até a última trilha; o salto de retorno do C-SCAN entra no total.
- **SSD** (`ssd_demo`): FTL de páginas com escrita fora do lugar, coleta de lixo (GC) e balanceamento de
  desgaste (wear leveling). Mostra a amplificação de escrita e a distribuição de apagamentos por bloco.
- **RAID** (`format --raid 0|1|4|5 --disks N`): RAID 0 (listras), RAID 1 (espelho), RAID 4 (paridade em disco
  dedicado) e RAID 5 (paridade rotativa). Escritas pequenas usam leitura-modificação-escrita; `fail N` simula a
  falha de um disco e `rebuild N` reconstrói por espelho ou XOR.

| Comando | Descrição |
|---|---|
| `array` | Layout do arranjo: quais blocos lógicos e paridades estão em cada disco |
| `io` | Requisições pendentes em cada disco físico |
| `disk_sched FCFS\|SSTF\|SCAN\|C-SCAN [--head N] [--trilhas 98,183,37]` | Escalona as filas (ou as trilhas informadas) |
| `iosched FCFS\|SSTF\|SCAN\|C-SCAN\|off` | Escalona automaticamente após cada comando com E/S |
| `fail <disco>` / `rebuild <disco>` | Falha simulada e reconstrução |
| `ssd_demo [--writes N]` | Compara SSD sem e com balanceamento de desgaste |

Exemplo — escalonamento SSTF (cabeça na trilha 53, pedidos 98, 183, 37, 122, 14):

```
> format --blocks 200 --block-size 512B
> disk_sched SSTF --head 53 --trilhas 98,183,37,122,14
Ordem de Atendimento : 53 -> 37 -> 14 -> 98 -> 122 -> 183
Total de Movimento   : 16 + 23 + 84 + 24 + 61 = 208 trilhas
```

Exemplo — RAID 5 com 3 discos (`demo raid5`): a faixa 0 tem paridade no Disco 3 e dados nos Discos 1 e 2; com o
Disco 2 em falha, o bloco é recuperado por XOR dos Discos 1 e 3.

Testes da Etapa 2:

```bash
javac -d out src/fsim/*.java test/fsim/IoTest.java
java -ea -cp out fsim.IoTest
```

## Etapa 3 — Segurança e Proteção

Camada de segurança, controle de acesso, criptografia e integridade integrada ao sistema de arquivos e aos discos:

- **Modos de operação (`MODO_KERNEL` vs `MODO_USUARIO`) e `sudo`**: o simulador inicia como `root` em `MODO_KERNEL`
  (mantendo compatibilidade integral com as Etapas 1 e 2). Ao autenticar um usuário comum (`login`), a sessão passa a
  operar em `MODO_USUARIO` (prompt `$` em vez de `#`), bloqueando operações administrativas de sistema (`format`,
  `defrag`, `useradd`, `userdel`, `groupadd`, `usermod`, `chown`, `fail`, `rebuild`, `iosched`, `tamper_block` e
  `audit_log`). O comando `sudo` eleva temporariamente uma única execução para `MODO_KERNEL` mediante validação de
  senha, restaurando `MODO_USUARIO` imediatamente ao final.
- **Autenticação com `SHA-256` + `salt` aleatório de 16 bytes**: nenhuma senha é armazenada em claro; cada conta gera
  um `salt` criptograficamente aleatório de 16 bytes (`SecureRandom`) e persiste apenas `hash = SHA-256(salt || senha_utf8)`,
  verificado em tempo constante (`MessageDigest.isEqual`) para mitigar *rainbow tables* e canais laterais de tempo.
- **Matriz de permissões POSIX (`rwx`) e ACL estendida**: cada arquivo e diretório guarda dono, grupo e modo de 9 bits
  (`rwxrwxrwx`, aceitando notação octal como `750` e simbólica como `u=rwx,g=rx,o=`, `+x`), além de entradas nominais
  de ACL estendida por usuário ou grupo (`setfacl` / `getfacl`, indicadas pelo sufixo `+`). A travessia de caminhos
  exige permissão `x` em cada diretório percorrido, e toda verificação ocorre em `FileSystem` **antes** de alocar
  blocos ou enfileirar requisições de E/S nos discos físicos (preservando atomicidade).
- **Criptografia de dados em repouso (`AES-256/CTR` + `HMAC-SHA256`)**: `encrypt_file` cifra o conteúdo real gravado
  nos blocos físicos com `AES/CTR/NoPadding` (subchave de 256 bits derivada por domínio e IV aleatório de 16 bytes,
  preservando o tamanho exato do arquivo sem *padding*) e armazena um verificador `HMAC-SHA256(subchave_mac, IV)` para
  rejeitar chaves incorretas sem corromper o disco. Funciona de forma transparente sobre qualquer alocador e sobre
  arranjos RAID (incluindo reconstrução por XOR em modo degradado).
- **Monitor de integridade por bloco (`SHA-256`) e antivírus com quarentena**: a cada gravação legítima do sistema de
  arquivos, `IntegrityMonitor` registra o hash `SHA-256` oficial de cada bloco lógico (via `peek`, sem gerar E/S extra).
  O comando `antivirus_scan` lê os blocos do disco, detecta adulterações físicas diretas (`tamper_block` / corrupção
  silenciosa) e identifica assinaturas conhecidas de malware (como `EICAR-Test-Signature`). Com `--quarantine`, arquivos
  comprometidos recebem modo `000`, têm ACLs limpas e ficam bloqueados contra leitura, escrita e execução.
- **Trilha de auditoria (`audit_log`)**: eventos de identidade, autenticação, tentativas de privilégio, negações de ACL,
  execução, criptografia e integridade são registrados sequencialmente em memória com categoria, usuário e modo.

| Comando | Descrição |
|---|---|
| `login [--user] <usuario> [--password S]` / `logout` | Autentica usuário (alternando o modo da sessão) / retorna a `root` (`MODO_KERNEL`) |
| `whoami` / `id` | Exibe usuário atual, grupos e modo de operação |
| `sudo [--password S] <cmd> ...` | Executa um comando temporariamente em `MODO_KERNEL` |
| `useradd <usr> [--group G] [--password S] [--admin]` | Cria usuário com `salt` aleatório de 16 B + hash `SHA-256` |
| `userdel <usuario>` | Remove usuário cadastrado |
| `groupadd <grupo>` / `usermod <usr> --add-group <grp>` | Cria grupo / adiciona usuário a grupo suplementar |
| `passwd [usuario] [--password S]` | Altera a senha regenerando `salt` e hash `SHA-256` |
| `users` / `groups` | Lista grupos, usuários, modos e prefixos de `salt`/`sha256` |
| `chmod <modo> <caminho>` | Altera permissões POSIX em octal (`750`) ou simbólico (`u=rwx,g=rx,o=`, `+x`) |
| `chown <dono>[:<grp>] <caminho>` / `chgrp <grupo> <caminho>` | Altera proprietário e/ou grupo do objeto |
| `setfacl (-m <u\|g:nome:perm> \| -x <u\|g:nome> \| -b) <caminho>` | Define, remove ou limpa entradas da ACL estendida |
| `getfacl <caminho>` / `acl <caminho>` | Exibe permissões POSIX e entradas detalhadas da ACL |
| `ls -l [caminho]` / `stat <caminho>` | Lista permissões, dono, grupo e proteção (`[ENC]`, `[QUAR]`) |
| `read_file <arq> [--key CHAVE]` (`cat`) / `exec <arq>` | Lê conteúdo (decifrando em memória se `--key`) / simula execução exigindo `x` |
| `encrypt_file <arq> --key CHAVE` / `decrypt_file <arq> --key CHAVE` | Cifra / decifra blocos físicos em repouso (`AES-256-CTR`) |
| `antivirus_scan [caminho] [--quarantine]` | Verifica hashes `SHA-256` dos blocos e assinaturas maliciosas |
| `tamper_block <bloco> [dados]` | Simula adulteração direta em bloco físico fora do sistema de arquivos |
| `audit_log [--clear]` | Exibe ou limpa a trilha cronológica de auditoria de segurança |

Exemplo 3.1 — autenticação em `MODO_USUARIO` e bloqueio pela Matriz/ACL (sem gerar E/S física):

```
> login --user carlos --password segredo123
Senha: **********
Autenticado com sucesso. Usuário: 'carlos' | Grupo: 'devs' | Modo: MODO_USUARIO
> read_file /sistema/config.sys
[SISTEMA DE SEGURANÇA - VERIFICAÇÃO DE ACL]
Objeto      : /sistema/config.sys
Proprietário: root (Grupo: admin)
Permissões  : rwxr-x--- (750)
Ação Pedida : LEITURA por 'carlos' (Grupo: devs)

[NEGADO]: O usuário 'carlos' não possui permissão de leitura no objeto '/sistema/config.sys'.
Tentativa não autorizada registrada nos logs do sistema.
```

Exemplo 3.2 — criptografia de dados em repouso e varredura de integridade / antivírus (`demo seguranca`):

```
> encrypt_file /home/carlos/segredo.txt --key "MinhaChave123"
Arquivo '/home/carlos/segredo.txt' criptografado com sucesso.
Conteúdo gravado nos blocos físicos: "0x8F4A12B09C3E71D4... (Encrypted Ciphertext)"
> antivirus_scan / --quarantine
[MÓDULO DE SEGURANÇA - VARREDURA DE INTEGRIDADE]
Analisando 4 arquivos em busca de assinaturas maliciosas e hashes corrompidos...
- /home/carlos/segredo.txt    : [OK - Criptografado e Íntegro]
- /home/carlos/suspeito.tmp   : [AMEAÇA] Assinatura maliciosa detectada: 'EICAR-Test-Signature'!
  Resultado: Conteúdo malicioso identificado no arquivo.
  Ação     : Arquivo isolado em quarentena (permissões 000).
- /sistema/logs/app.log       : [ALERTA] Hash do bloco difere do registro oficial!
  Resultado: Possível modificação direta não autorizada no bloco do disco.
  Ação     : Arquivo isolado em quarentena (permissões 000).
- /sistema/config.sys         : [OK - Íntegro]
```

Testes da Etapa 3:

```bash
javac -d out src/fsim/*.java test/fsim/*.java
java -ea -cp out fsim.SecurityTest
```

## Etapa 4 — Gerenciamento de Memória (Alocação Híbrida, Paginação e Swapping)

Subsistema de gerenciamento de memória principal (RAM) que implementa uma arquitetura híbrida com alocação contígua e memória virtual paginada sob demanda:

- **Arquitetura Híbrida de Memória**:
  - **Área Reservada (Alocação Contígua Estática)**: reservada para o **Núcleo Base do SO** e **processos críticos** (`--type critical`). Utiliza particionamento dinâmico com estratégias *First-Fit*, *Best-Fit* ou *Worst-Fit*. Sujeita à **fragmentação externa** quando processos críticos são finalizados de forma intercalada, mitigada pela compactação de memória (`memory_compact --reserved`).
  - **Área Dinâmica (Memória Virtual Paginada)**: dividida em molduras físicas (*frames*) de tamanho fixo (configurável: 64 B a 64 KB, padrão 4 KB), destinada a processos normais e de usuário (`--type normal`). Sujeita à **fragmentação interna** apenas na última página lógica de cada processo.
- **Paginação sob Demanda e Tradução de Endereços (MMU)**:
  - Processos normais são criados com a tabela de páginas lógicas calculada, mas **nenhuma moldura física é alocada a priori** (alocação sob demanda).
  - A MMU traduz endereços lógicos `(página, offset)` ou lineares `vaddr` em endereços físicos na RAM: `endereço_físico = moldura × tamanho_página + deslocamento`.
  - Quando a página está presente na RAM, o acesso resulta em **HIT**. Caso ausente, a MMU sinaliza **Page Fault (MISS)**, provocando a carga sob demanda da página na RAM dinâmica.
- **Algoritmos de Substituição de Páginas e Área de Troca (Swap)**:
  - Quando a RAM física está cheia e ocorre um *Page Fault*, o sistema aciona um algoritmo de substituição de páginas para selecionar uma moldura vítima:
    - **LRU (*Least Recently Used*)**: descarrega a página com maior tempo de ociosidade (`idleMs`).
    - **Segunda Chance / Clock**: algoritmo de aproximação circular do LRU baseado no bit de referência `R`. Páginas com `R=1` têm o bit zerado e ganham uma segunda chance; a primeira página com `R=0` é eleita vítima.
    - **FIFO (*First-In, First-Out*)**: descarta a página residente há mais tempo na RAM (`loadedAtMs`).
    - **LFU (*Least Frequently Used*)**: seleciona a página com o menor número total de acessos acumulados.
    - **Ótimo (Belady)**: algoritmo teórico *offline* disponível via `page_sim --policy OTIMO`, que conhece as referências futuras e garante o número mínimo teórico de faltas de página.
  - A página vítima é salva na **área de troca (swap)** em disco (`SwapSpace`), gerando **requisições reais de gravação física no arranjo RAID** (`RaidArray`). Ao ser referenciada novamente, é restaurada automaticamente via leitura do disco (*swap-in*).
- **Segurança de Memória e Trilha de Auditoria**:
  - Processos críticos (`--type critical`), compactação da área reservada (`memory_compact`), reconfiguração física (`memory_format`), alteração de política (`page_policy`) e monitoramento periódico (`memwatch`) exigem privilégios de **`MODO_KERNEL`** (ou `sudo`).
  - Usuários em `MODO_USUARIO` só podem finalizar processos próprios.
  - Tentativas não autorizadas são bloqueadas via `FSException` e registradas na categoria `PRIVILEGIO` do `AuditLog`.
  - Processos criados a partir de binários do sistema de arquivos (`--file`) validam permissões POSIX de leitura e são recusados se o arquivo estiver isolado em **quarentena** pelo antivírus.

| Comando | Descrição |
|---|---|
| `create_process [--name N] [--size T \| --file F] [--type critical\|normal]` | Cria processo no modelo híbrido (apelido: `proc_create`) |
| `terminate_process <PID\|nome>` | Encerra processo e libera área reservada ou molduras/swap (apelido: `kill`) |
| `ps` | Lista processos ativos na RAM, tipo, páginas e estado (apelido: `processes`) |
| `page_table <PID\|nome>` | Exibe tabela de páginas, bits V/R/M e slots de swap (apelido: `ptable`) |
| `translate_address --process P (--page N [--offset D] \| --vaddr V) [--load]` | Traduz endereço lógico em físico via MMU (apelido: `mmu`) |
| `execute_step --process P [--instruction T] [--page N] [--write]` | Simula ciclo de CPU com acesso à memória e Page Fault (apelido: `step`) |
| `memory_status [--reserved] [--virtual] [--details]` | Diagnóstico e mapa completo da RAM física (apelido: `free`) |
| `memory_compact [--reserved]` | Compacta blocos livres contíguos na área reservada |
| `memory_format [--reserved T] [--dynamic T] [--page-size T] [--fit F] [--policy P]` | Reconfigura a arquitetura de memória (apelido: `mem_config`) |
| `page_policy <LRU\|SEGUNDA_CHANCE\|FIFO\|LFU>` | Define o algoritmo ativo de substituição de páginas na RAM |
| `page_sim [--frames N] [--refs R] [--policy P\|ALL] [--steps]` | Simulação comparativa de algoritmos de substituição |
| `memwatch <N\|off>` | Monitor periódico automático a cada N operações de memória |

Exemplo 4.1 — Área reservada, fragmentação externa e compactação (`demo memoria`):

```
> memory_format --reserved 4MB --dynamic 64KB --page-size 4KB --fit first
> create_process --name Kernel_DiskDriver --size 1MB --type critical
> create_process --name Kernel_NetDriver --size 512KB --type critical
> create_process --name Kernel_CryptoEngine --size 512KB --type critical
> terminate_process --name Kernel_NetDriver
> memory_status --reserved
--- MAPA DA ÁREA CRÍTICA/RESERVADA DA RAM (Total: 4.0 MB - Alocação Fixa) ---
[0.0 MB - 1.0 MB] : Kernel Base (Núcleo) (1 MB)  [OCUPADO]
[1.0 MB - 2.0 MB] : Kernel_DiskDriver    (1 MB)  [OCUPADO]
[2.0 MB - 2.5 MB] : FRAGMENTO LIVRE 1    (512 KB)  [LIVRE - LACUNA]
[2.5 MB - 3.0 MB] : Kernel_CryptoEngine  (512 KB)  [OCUPADO]
[3.0 MB - 4.0 MB] : FRAGMENTO LIVRE 2    (1 MB)  [LIVRE - LACUNA]

--- DIAGNÓSTICO DE FRAGMENTAÇÃO EXTERNA ---
Total de Memória Livre na Área Reservada : 1.5 MB (1.5 MB)
Maior Bloco Contíguo Disponível          : 1 MB (1.0 MB)
Fragmentação Externa Detectada           : SIM (2 fragmentos não contíguos)
Status: Uma requisição contígua de 1.5 MB falhará por ausência de bloco único.
> memory_compact --reserved
[EXECUTANDO COMPACTAÇÃO DA ÁREA RESERVADA DA RAM]
Realocando blocos contíguos para eliminar a fragmentação externa...
- Mapeamento de 'Kernel_CryptoEngine': [2.5 MB a 3.0 MB] -> Ajustado para: [2.0 MB a 2.5 MB]

--- MAPA APÓS COMPACTAÇÃO ---
[0.0 MB - 1.0 MB] : Kernel Base (Núcleo) (1 MB) [OCUPADO]
[1.0 MB - 2.0 MB] : Kernel_DiskDriver    (1 MB) [OCUPADO]
[2.0 MB - 2.5 MB] : Kernel_CryptoEngine  (512 KB) [OCUPADO]
[2.5 MB - 4.0 MB] : Bloco Livre Contíguo (1.5 MB) [LIVRE]
Resultado: Fragmentação externa eliminada. Maior bloco contíguo: 1.5 MB.
```

Exemplo 4.2 — Memória virtual, paginação sob demanda e cálculo de endereços (MMU):

```
> create_process --name App_Navegador --size 10KB --type normal
[CRIAÇÃO DE PROCESSO NÃO CRÍTICO - MEMÓRIA VIRTUAL]
Tamanho do Processo : 10 KB (10240 Bytes)
Tamanho da Página   : 4 KB (4096 Bytes)
Cálculo de Páginas  : 10 KB / 4 KB = 2.5 -> Requer 3 Páginas Lógicas (Páginas 0, 1 e 2).

--- ANÁLISE DE FRAGMENTAÇÃO INTERNA ---
Página Lógica 0 : 4096 Bytes ocupados (100.0%)
Página Lógica 1 : 4096 Bytes ocupados (100.0%)
Página Lógica 2 : 2048 Bytes ocupados (50.0%)
Fragmentação Interna Total: 2048 Bytes de desperdício na Página Lógica 2.
Status: Tabela de Páginas gerada sem alocar molduras na RAM (Alocação por demanda).
> page_table --process App_Navegador
TABELA DE PÁGINAS — Processo 'App_Navegador' (PID 101, 10 KB)
PÁG    MOLDURA    V     R     M     SWAP     OCUPADO    FRAG.INT    
--------------------------------------------------------------------
0      -          0     0     0     -        4096 B     0 B         
1      -          0     0     0     -        4096 B     0 B         
2      -          0     0     0     -        2048 B     2048 B      
Fragmentação Interna Total: 2048 B.
> translate_address --process App_Navegador --page 2 --offset 100
[TRADUÇÃO DE ENDEREÇO: PÁGINA LÓGICA -> MOLDURA FÍSICA]
Solicitação: Página Lógica 2 -> [MISS / AUSENTE NA RAM]
> execute_step --process App_Navegador --instruction "LOAD_DATA Página 2"
[CPU - REFERÊNCIA DE MEMÓRIA EM EXECUÇÃO]
App_Navegador requisita acesso à Página Lógica 2...
- Consulta Tabela de Páginas -> Página Lógica 2 NÃO ESTÁ NA RAM!

[PAGE FAULT DETECTADO]: Página Lógica 2 ausente na RAM!
- Carregando Página Lógica 2 do App_Navegador na Moldura Física 0 (RAM Dinâmica).
- Tabela de Páginas atualizada: Página Lógica 2 ---> Moldura Física 0.
> translate_address --process App_Navegador --page 2 --offset 100
[TRADUÇÃO DE ENDEREÇO: PÁGINA LÓGICA -> MOLDURA FÍSICA]
Solicitação: Página Lógica 2 -> Mapeada na Moldura Física 0 (RAM Dinâmica) [HIT]
Endereço Físico: Moldura 0 × 4096 + Deslocamento 100 = 0x64 (100 B)
```

Exemplo 4.3 — Substituição de páginas (LRU com Page Fault e Swap em disco) e simulação comparativa:

```
> execute_step --process ProcB --page 1
[CPU - REFERÊNCIA DE MEMÓRIA EM EXECUÇÃO]
ProcB requisita acesso à Página Lógica 1...
- Consulta Tabela de Páginas -> Página Lógica 1 NÃO ESTÁ NA RAM!

[PAGE FAULT DETECTADO]: Página Lógica 1 ausente na RAM!
Memória RAM cheia. Executando Substituição de Página (Algoritmo LRU):
- Analisando histórico de acesso: Página Lógica 0 do PID 101 não é usada há 45ms (Mais antiga).
- Removendo Página Lógica 0 do PID 101 da RAM -> Salvando no Swap (Disco).
- Carregando Página Lógica 1 do ProcB na Moldura Física 0 (RAM Dinâmica).
- Tabela de Páginas atualizada: Página Lógica 1 ---> Moldura Física 0.
> page_sim --frames 3 --refs 7,0,1,2,0,3,0,4,2,3,0,3,2,1,2,0,1,7,0,1 --policy ALL
--- RESULTADO DA SIMULAÇÃO DE SUBSTITUIÇÃO DE PÁGINAS ---
POLÍTICA         MOLDURAS   HITS     FAULTS   TAXA ACERTO 
----------------------------------------------------------
LRU              3          8        12       40.0%       
Segunda Chance   3          6        14       30.0%       
FIFO             3          5        15       25.0%       
LFU              3          6        14       30.0%       
Ótimo            3          11       9        55.0%       
```

Testes da Etapa 4:

```bash
javac -d out src/fsim/*.java test/fsim/*.java
java -ea -cp out fsim.MemoryTest
```

## Etapa 5 — Gerenciamento de Processos, Threads, Syscalls e Escalonamento de CPU

Camada de gerenciamento de processos, execução de ciclos de instrução, controle de chamadas de sistema (syscalls), exclusão mútua concorrente (mutexes) e escalonamento de CPU integrada à memória virtual e ao sistema de arquivos:

- **Ciclo de Vida do Processo e PCB Completo**:
  - Processos operam nos estados canônicos do sistema operacional: `NOVO → PRONTO → EXECUTANDO → BLOQUEADO → CONCLUÍDO`.
  - Processos com tempo de chegada imediato (`--arrival 0`) realizam admissão automática para `PRONTO` no instante de criação.
  - Cada processo possui um Bloco de Controle de Processo (`SimProcess`) contendo PID, identificador do usuário (`owner`), nível de prioridade (menor número inteiro = maior prioridade: `0` = Crítica, `1` = Alta, `2` = Baixa), tempo total de CPU (`burstMs`), tempo de CPU executado (`executedCpuMs`), histórico cronológico de transições de estado (`transitions`), tabela de páginas virtuais e conjunto de threads associadas.
- **Syscalls Embutidas e Bloqueio Automático por E/S**:
  - Processos podem incorporar um perfil determinístico de chamadas de sistema via `--syscall` (ex.: `"read_disk at 3ms (duration: 4ms)"`, `"write_disk at 6ms (duration: 2ms)"`, `"read_file /caminho at 2ms (duration: 3ms)"` ou `--syscall random`).
  - O gatilho (`at Xms`) é avaliado em função do **tempo de CPU acumulado pelo processo** (`executedCpuMs == X`). Ao atingir a marca, a CPU detecta a interrupção/syscall, transitando o processo de `EXECUTANDO` para `BLOQUEADO`.
  - A CPU é imediatamente liberada para o próximo processo elegível da fila de prontos. O retorno da E/S ocorre após o intervalo de duração estipulado, transitando o processo de volta para `PRONTO` (`BLOQUEADO → PRONTO`).
  - Syscalls que referenciam caminhos de arquivos reais validam permissões POSIX, integridade e quarentena, enfileirando requisições `IoRequest` reais nos discos físicos do arranjo RAID.
- **Memória Virtual Sincronizada ao Relógio de CPU (1 ms)**:
  - O motor de simulação discreta (`run_simulation` e `schedule`) avança o relógio da CPU em passos de `1 ms`.
  - A cada tick de CPU, o processo referencia páginas lógicas na MMU (`PagedMemory.accessPageAt`). Se a página já reside em uma moldura física, contabiliza-se um **HIT** e atualiza-se o timestamp de acesso para algoritmos de substituição (como LRU).
  - Em caso de ausência (**Page Fault**), uma moldura física é alocada; se a RAM estiver lotada, aciona-se o algoritmo de substituição configurado (LRU, Segunda Chance, FIFO ou LFU), salvando a página vítima na partição de *swap* em disco e restaurando páginas antigas quando necessário.
- **Concorrência de Threads e Exclusão Mútua (`Mutex`)**:
  - Cada processo cria automaticamente sua thread principal (`T1`) e permite instanciar threads adicionais (`create_thread`), compartilhando o mesmo espaço de memória do processo pai.
  - As threads podem disputar recursos protegidos por travas nomeadas (`Mutex`).
  - Quando uma thread tenta adquirir um mutex ocupado, ocorre contenção: a thread é suspensa (`EXECUTANDO → BLOQUEADO`) e inserida na fila de espera FIFO do mutex. O processo pai só transita para `BLOQUEADO` se todas as suas threads ativas estiverem bloqueadas.
  - A liberação do mutex (`unlock`) transfere a posse imediatamente para a próxima thread da fila de espera, acordando-a para o estado `PRONTO`.
- **Escalonador Combinado (`Priority_RR`) e Métricas Globais de Desempenho**:
  - Algoritmo de escalonamento baseado em **múltiplas filas de prioridade com preempção e Round-Robin**:
    - **Preempção imediata entre níveis de prioridade**: sempre que um processo de prioridade estritamente superior (menor número) entrar em `PRONTO` (por chegada ou desbloqueio de E/S), o processo atual de menor prioridade sofre preempção e retorna para a sua fila.
    - **Round-Robin dentro da mesma fila de prioridade**: processos com a mesma prioridade compartilham a CPU por fatias de tempo controladas pelo `--quantum` (padrão: 2 ms). Ao expirar o quantum, o processo cede a vez ao próximo da mesma fila sem preempção arbitrária.
    - Suporte aos algoritmos adicionais `RR`, `Priority` e `FCFS` para fins comparativos.
  - Métricas calculadas ao término do escalonamento:
    - **Tempo de Espera ($WT$)**: total de milissegundos em que o processo permaneceu no estado `PRONTO` aguardando a CPU.
    - **Tempo de Retorno ($TAT$)**: tempo decorrido entre a chegada do processo e sua conclusão final ($t_{\text{conclusão}} - t_{\text{chegada}}$).
    - **Tempo de Resposta ($RT$)**: intervalo entre a chegada e o primeiro despacho efetivo na CPU ($t_{\text{1º despacho}} - t_{\text{chegada}}$).
    - **Médias Globais**: Tempo Médio de Espera ($AWT$), Tempo Médio de Retorno ($ATT$) e Tempo Médio de Resposta ($ART$).
    - **Trocas de Contexto Totais**: contagem exata de transições de processo na CPU.
    - **Utilização Global da CPU (%)**: percentual de tempo em que a CPU executou processos úteis em relação ao tempo total decorrido.

| Comando | Descrição |
|---|---|
| `create_process --name N --burst B [--priority P] [--syscall S] [--arrival A] [--pages P]` | Cria processo com perfil de CPU e memória (apelido: `proc_create`) |
| `run_simulation --process <N\|PID> --steps <N> [--pages P1,P2]` | Executa $N$ passos de instrução com CPU, memória e syscalls (apelido: `sim_run`) |
| `schedule [--algorithm Priority_RR\|RR\|Priority\|FCFS] [--quantum Q]` | Escalonamento na CPU com linha do tempo e métricas (apelido: `cpu_sched`) |
| `process_status <N\|PID>` | Exibe PCB completo, ciclo de vida, syscalls e threads (apelido: `pcb`) |
| `sched_reset` | Reinicia contadores e estado de execução dos processos |
| `create_thread <proc> <nome> [--lock M --at X] [--hold H] [--unlock M --at X]` | Cria thread concorrente com agendamento de mutex (apelido: `thread_create`) |
| `mutex_lock <proc> <thread> <mutex>` | Solicita aquisição manual de mutex por uma thread |
| `mutex_unlock <proc> <thread> <mutex>` | Solicita liberação manual de mutex por uma thread |
| `mutex_status` | Exibe estado, dono e filas de espera de todos os mutexes (apelido: `mutexes`, `threads`) |

Exemplo 5.1 — Criação de processos com perfil de CPU, prioridade e syscall:

```
> create_process --name App_Web --burst 8ms --priority 1 --syscall "read_disk at 3ms (duration: 4ms)" --pages 2,7,4
[CRIAÇÃO DE PROCESSO] PID: 101 | Nome: App_Web | Prioridade: 1 (Alta)
Alocação     : Memória Virtual Paginada (Tabela de Páginas criada)
Syscall/I/O  : Agendada para t=3ms (Duração do Bloqueio: 4ms)
Estado Inicial: PRONTO
> create_process --name App_Render --burst 5ms --priority 2 --syscall random
[CRIAÇÃO DE PROCESSO] PID: 102 | Nome: App_Render | Prioridade: 2 (Baixa)
Alocação     : Memória Virtual Paginada (Tabela de Páginas criada)
Syscall/I/O  : Evento Aleatório Habilitado
Estado Inicial: PRONTO
```

Exemplo 5.2 — Execução de ciclos de instrução, memória virtual (HIT vs Page Fault) e detecção de syscall:

```
> run_simulation --process App_Web --steps 3
--- STEP 1 (t=1ms) | Executando PID 101 ---
CPU acessa: Página Lógica 02 -> [PAGE FAULT DETECTADO: Moldura 00 alocada] | Histórico LRU: Pág 02 atualizada (t=1ms)
--- STEP 2 (t=2ms) | Executando PID 101 ---
CPU acessa: Página Lógica 07 -> [PAGE FAULT DETECTADO: Moldura 01 alocada] | Histórico LRU: Pág 07 atualizada (t=2ms)
--- STEP 3 (t=3ms) | Executando PID 101 ---
CPU acessa: Página Lógica 04 -> [PAGE FAULT DETECTADO: Moldura 02 alocada] | Histórico LRU: Pág 04 atualizada (t=3ms)

[SYSCALL DETECTADA]: PID 101 atingiu a instrução de leitura de disco agendada para t=3ms!
- Mudança de Estado: EXECUTANDO -> BLOQUEADO (E/S de Disco).
- Tempo de Retorno Previsto do Bloqueio: 4ms (Desbloqueio em t=7ms).
- CPU liberada -> Escalonador seleciona próximo processo da Fila de Pronto.

[RESUMO DA GERÊNCIA DE MEMÓRIA (RAM DINÂMICA)]
+------------------+---------------+-------------------+---------------------+
| Moldura RAM (F)  | Processo/Pág  | Estado de Acesso  | Último Acesso (LRU) |
+------------------+---------------+-------------------+---------------------+
| Moldura 00       | PID 101 / P02 | PAGE FAULT (NEW)  | t=1ms               |
| Moldura 01       | PID 101 / P07 | PAGE FAULT (NEW)  | t=2ms               |
| Moldura 02       | PID 101 / P04 | PAGE FAULT (NEW)  | t=3ms               |
| Moldura 03..15   | [LIVRE]       | [LIVRE]           | -                   |
+------------------+---------------+-------------------+---------------------+
```

Exemplo 5.3 e 5.4 — Escalonamento combinado (`Priority_RR`), preempção, concorrência e métricas finais (`demo processos`):

```
> schedule --algorithm Priority_RR --quantum 2ms
Fila de Pronto Inicial:
- Prioridade 1 (Alta) : [PID 101 (Burst 8ms), PID 103 (Burst 4ms)]
- Prioridade 2 (Baixa) : [PID 102 (Burst 5ms)]

--- LINHA DO TEMPO DA CPU ---
[0ms - 2ms] Executando PID 101 | Quantum 2ms expirou -> Volta p/ Fila P1
[2ms - 4ms] Executando PID 103 | Quantum 2ms expirou -> Volta p/ Fila P1
[4ms - 5ms] Executando PID 101 | Syscall de leitura atingida -> Estado EXECUTANDO -> BLOQUEADO (retorno em 8ms)
[5ms - 7ms] Executando PID 103 | Execução concluída -> Estado EXECUTANDO -> CONCLUÍDO
[7ms - 8ms] Executando PID 102 | Preempção por processo de prioridade superior -> Volta p/ Fila P2
[8ms]       Evento E/S: PID 101 encerra leitura -> Estado BLOQUEADO -> PRONTO
[8ms - 10ms] Executando PID 101 | Quantum 2ms expirou -> Volta p/ Fila P1
[10ms - 12ms] Executando PID 101 | Quantum 2ms expirou -> Volta p/ Fila P1
[12ms - 13ms] Executando PID 101 | Execução concluída -> Estado EXECUTANDO -> CONCLUÍDO
[13ms - 15ms] Executando PID 102 | Syscall de leitura atingida -> Estado EXECUTANDO -> BLOQUEADO (retorno em 16ms)
[15ms - 16ms] CPU Ociosa (todos os processos bloqueados aguardando E/S)
[16ms]      Evento E/S: PID 102 encerra leitura -> Estado BLOQUEADO -> PRONTO
[16ms - 18ms] Executando PID 102 | Execução concluída -> Estado EXECUTANDO -> CONCLUÍDO

================ METRICAS FINAIS DO ESCALONADOR ================
PID   NOME                 PRIORIDADE   CHEGADA   BURST     TAT       WT        RT        FAULTS 
-----------------------------------------------------------------------------------------------
101   App_Web              1 (Alta)     0ms       8ms       13ms      2ms       0ms       0      
102   App_Render           2 (Baixa)    0ms       5ms       18ms      12ms      7ms       5      
103   App_Batch            1 (Alta)     0ms       4ms       7ms       3ms       2ms       4      
-----------------------------------------------------------------------------------------------
Tempo Médio de Espera (AWT)     : 5.67 ms
Tempo Médio de Retorno (ATT)    : 12.67 ms
Tempo Médio de Resposta (ART)   : 3.00 ms
Trocas de Contexto Totais       : 10
Utilização Global da CPU        : 94.4% (Tempo Ocupado: 17ms / Total: 18ms)
```

Testes da Etapa 5:

```bash
javac -d out src/fsim/*.java test/fsim/*.java
java -ea -cp out fsim.ProcessTest
```

## Estrutura

```
src/fsim/Main.java                           ponto de entrada (CLI / roteiros)
src/fsim/Shell.java                          interpretador de comandos e demos
src/fsim/FileSystem.java                     árvore, caminhos, CRUD, mediação de segurança e desfragmentação
src/fsim/Node.java                           arquivo/diretório e metadados de proteção (POSIX, ACL e criptografia)
src/fsim/Disk.java                           disco lógico em blocos (usa o arranjo RAID)
src/fsim/Allocator.java                      base das estratégias de alocação
src/fsim/ContiguousAllocator.java            alocação contígua
src/fsim/FatAllocator.java                   alocação encadeada (FAT)
src/fsim/IndexedAllocator.java               alocação indexada (i-node)
src/fsim/Report.java                         árvore, mapas, fragmentação e relatórios de segurança/auditoria
src/fsim/Units.java                          conversão e formatação de tamanhos (base 1024) e alinhamento
src/fsim/RaidArray.java                      arranjo RAID 0/1/4/5 e reconstrução
src/fsim/PhysicalDisk.java                   disco físico: blocos, cabeça e fila de E/S
src/fsim/IoRequest.java                      requisição de E/S (operação e trilha)
src/fsim/ArmScheduler.java                   escalonamento do braço (FCFS, SSTF, SCAN, C-SCAN)
src/fsim/FlashSim.java                       SSD: FTL, coleta de lixo e balanceamento de desgaste
src/fsim/AuditLog.java                       trilha cronológica de auditoria de segurança em memória
src/fsim/AuthManager.java                    usuários, grupos, hash SHA-256 + salt, modos dual e sudo
src/fsim/AccessControl.java                  avaliação da matriz POSIX (rwx) e ACL estendida
src/fsim/CryptoEngine.java                   criptografia simétrica em repouso (AES-256-CTR + HMAC-SHA256)
src/fsim/IntegrityMonitor.java               hashes SHA-256 por bloco, varredura de malware e quarentena
src/fsim/MemoryManager.java                  fachada de coordenação do subsistema híbrido de memória
src/fsim/ReservedArena.java                  alocação contígua na área reservada (first/best/worst-fit e defrag)
src/fsim/PagedMemory.java                    memória virtual, molduras físicas na RAM e paginação sob demanda
src/fsim/PageReplacer.java                   algoritmos de substituição de páginas (LRU, Clock, FIFO, LFU e Ótimo)
src/fsim/SwapSpace.java                      área de swap em disco com requisições reais de E/S no RAID
src/fsim/SimProcess.java                     processo simulado (crítico contíguo vs normal paginado)
src/fsim/SimThread.java                      thread simulada com suporte a ações agendadas de mutex
src/fsim/MutexManager.java                   gerenciador de exclusão mútua e filas FIFO de bloqueio
src/fsim/CpuScheduler.java                   escalonador de CPU preemptivo (Priority_RR, RR, Priority, FCFS) e métricas
src/fsim/ProcessReport.java                  renderização de criação de processos, linha do tempo da CPU e PCB
src/fsim/MemoryReport.java                   renderização de mapas de memória, tabelas de páginas e diagnósticos
src/fsim/MemoryFullException.java            esgotamento de RAM ou fragmentação externa na área reservada
src/fsim/FSException.java                    exceção base de erros exibidos ao usuário
src/fsim/DiskFullException.java              erro de falta de blocos livres no disco
src/fsim/ExternalFragmentationException.java erro de espaço livre disperso na alocação contígua
src/fsim/AccessDeniedException.java          erro de negação de acesso pela matriz de permissões / ACL
test/fsim/IoTest.java                        testes da Etapa 2 (escalonamento, RAID, SSD e E/S)
test/fsim/SecurityTest.java                  testes da Etapa 3 (segurança, proteção, criptografia e demos)
test/fsim/MemoryTest.java                    testes da Etapa 4 (memória híbrida, paginação, swap e proteção)
test/fsim/ProcessTest.java                   testes da Etapa 5 (processos, threads, syscalls, mutex e escalonador)
```
