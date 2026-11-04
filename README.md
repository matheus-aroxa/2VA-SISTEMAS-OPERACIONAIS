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
```

Dentro do simulador, `demo indexada`, `demo contigua`, `demo encadeada`, `demo raid5`, `demo seguranca` e `demo memoria` executam
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
| `ps` | Lista processos em execução, PID, tipo, dono, tamanho e páginas |
| `page_table [--process N]` | Exibe a tabela de páginas, bits V/R/M, moldura física e fragmentação interna |
| `translate_address [--process N] [--page P] [--offset O] [--vaddr V]` | Simula a MMU: calcula endereço físico e indica HIT ou Page Fault |
| `execute_step [--process N] [--instruction I] [--page P] [--write]` | Simula ciclo do processador, trata Page Faults e descarrega swap (apelido: `step`) |
| `memory_status [--reserved] [--virtual] [--details]` | Diagnóstico de fragmentação externa/interna e mapa das molduras (apelido: `free`) |
| `memory_compact [--reserved]` | Compacta a área reservada eliminando lacunas de fragmentação externa |
| `memory_format [--reserved R] [--dynamic D] [--page-size P] [--fit F] [--policy POL]` | Reconfigura a memória física, tamanho de página e política |
| `page_policy <LRU\|SEGUNDA_CHANCE\|FIFO\|LFU>` | Altera a política de substituição de páginas da RAM dinâmica (apelido: `mem_policy`) |
| `page_sim [--frames N] [--refs R] [--policy P\|ALL] [--steps]` | Simula e compara algoritmos de substituição de páginas (inclui Ótimo de Belady) |
| `memwatch <N\|off>` | Ativa monitoramento periódico de memória a cada N operações |

Exemplo 4.1 — Área Reservada, processos críticos, fragmentação externa e compactação contígua (`demo memoria`):

```
> memory_format --reserved 4MB --dynamic 64KB --page-size 4KB --fit first
Memória reconfigurada: Área Reservada = 4 MB (fit: first), RAM Dinâmica = 64 KB (16 molduras de 4 KB), Política = LRU.
> create_process --name Kernel_DiskDriver --size 1MB --type critical
[CRIAÇÃO DE PROCESSO CRÍTICO] Processo 'Kernel_DiskDriver' criado (PID 101, 1 MB).
- Alocado de forma contígua na Área Reservada: [1.0 MB até 2.0 MB] (1 MB)
> create_process --name Kernel_NetDriver --size 512KB --type critical
[CRIAÇÃO DE PROCESSO CRÍTICO] Processo 'Kernel_NetDriver' criado (PID 102, 512 KB).
- Alocado de forma contígua na Área Reservada: [2.0 MB até 2.5 MB] (512 KB)
> create_process --name Kernel_CryptoEngine --size 512KB --type critical
[CRIAÇÃO DE PROCESSO CRÍTICO] Processo 'Kernel_CryptoEngine' criado (PID 103, 512 KB).
- Alocado de forma contígua na Área Reservada: [2.5 MB até 3.0 MB] (512 KB)
> memory_status --reserved
--- MAPA DA ÁREA CRÍTICA/RESERVADA DA RAM (Total: 4.0 MB - Alocação Fixa) ---
[0.0 MB - 1.0 MB] : Núcleo Base do SO    (1 MB)  [OCUPADO]
[1.0 MB - 2.0 MB] : Kernel_DiskDriver    (1 MB)  [OCUPADO]
[2.0 MB - 2.5 MB] : Kernel_NetDriver     (512 KB)  [OCUPADO]
[2.5 MB - 3.0 MB] : Kernel_CryptoEngine  (512 KB)  [OCUPADO]
[3.0 MB - 4.0 MB] : FRAGMENTO LIVRE 1 (1 MB)  [LIVRE - LACUNA]

--- DIAGNÓSTICO DE FRAGMENTAÇÃO EXTERNA ---
Total de Memória Livre na Área Reservada : 1 MB (1.0 MB)
Maior Bloco Contíguo Disponível          : 1 MB (1.0 MB)
Fragmentação Externa Detectada           : NÃO
Status: Espaço livre consolidado em bloco único.
> terminate_process --name Kernel_NetDriver
[ENCERRAMENTO DE PROCESSO CRÍTICO] Processo 'Kernel_NetDriver' finalizado.
Área de 512 KB no intervalo [2.0 MB a 2.5 MB] liberada na Área Reservada da RAM.
> memory_status --reserved
--- MAPA DA ÁREA CRÍTICA/RESERVADA DA RAM (Total: 4.0 MB - Alocação Fixa) ---
[0.0 MB - 1.0 MB] : Núcleo Base do SO    (1 MB)  [OCUPADO]
[1.0 MB - 2.0 MB] : Kernel_DiskDriver    (1 MB)  [OCUPADO]
[2.0 MB - 2.5 MB] : FRAGMENTO LIVRE 1 (512 KB)  [LIVRE - LACUNA]
[2.5 MB - 3.0 MB] : Kernel_CryptoEngine  (512 KB)  [OCUPADO]
[3.0 MB - 4.0 MB] : FRAGMENTO LIVRE 2 (1 MB)  [LIVRE - LACUNA]

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
[0.0 MB - 1.0 MB] : Núcleo Base do SO    (1 MB) [OCUPADO]
[1.0 MB - 2.0 MB] : Kernel_DiskDriver    (1 MB) [OCUPADO]
[2.0 MB - 2.5 MB] : Kernel_CryptoEngine  (512 KB) [OCUPADO]
[2.5 MB - 4.0 MB] : Bloco Livre Contíguo (1.5 MB) [LIVRE]
Resultado: Fragmentação externa eliminada. Maior bloco contíguo: 1.5 MB.
```

Exemplo 4.2 — Memória Virtual, paginação sob demanda, fragmentação interna e tradução MMU:

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
TABELA DE PÁGINAS — Processo 'App_Navegador' (PID 104, 10 KB)
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
src/fsim/MemoryReport.java                   renderização de mapas de memória, tabelas de páginas e diagnósticos
src/fsim/MemoryFullException.java            esgotamento de RAM ou fragmentação externa na área reservada
src/fsim/FSException.java                    exceção base de erros exibidos ao usuário
src/fsim/DiskFullException.java              erro de falta de blocos livres no disco
src/fsim/ExternalFragmentationException.java erro de espaço livre disperso na alocação contígua
src/fsim/AccessDeniedException.java          erro de negação de acesso pela matriz de permissões / ACL
test/fsim/IoTest.java                        testes da Etapa 2 (escalonamento, RAID, SSD e E/S)
test/fsim/SecurityTest.java                  testes da Etapa 3 (segurança, proteção, criptografia e demos)
test/fsim/MemoryTest.java                    testes da Etapa 4 (memória híbrida, paginação, swap e proteção)
```
