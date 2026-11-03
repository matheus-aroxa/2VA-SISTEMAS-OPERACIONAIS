package fsim;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Interpretador de comandos do simulador. */
final class Shell {
    private static final long SSD_SEED = 42;

    static final String HELP = """
            Navegação
              pwd                              diretório atual
              cd <caminho>                     muda de diretório (aceita absoluto, relativo, . e ..)
              ls [-l] [caminho]                lista um diretório (-l exibe permissões, dono e grupo)
              tree [caminho]                   exibe a árvore hierárquica

            CRUD
              mkdir [-p] <dir>...              cria diretório(s)
              create <arq> [--size N] [--content TEXTO]
                                               cria arquivo (N: 512B, 10KB, 1MB...)
              cat <arq> [--key CHAVE]          lê o conteúdo dos blocos (apelido: read_file)
              stat <caminho>                   metadados, permissões e blocos ocupados
              write <arq> <texto>              sobrescreve o conteúdo (cria se não existir; \\n = quebra de linha)
              append <arq> <texto>             acrescenta texto ao final
              resize <arq> --size N            altera o tamanho (trunca ou completa com zeros)
              mv <origem> <destino>            move / renomeia
              rm [-r] <caminho>                remove arquivo (ou diretório com -r)
              rmdir <dir>                      remove diretório vazio

            Disco
              fs_status [--details]            mapa do disco e relatório de fragmentação
              map                              apenas o mapa gráfico
              defrag                           compacta / desfragmenta o disco
              format [--strategy contigua|encadeada|indexada] [--block-size 4KB] [--blocks 16] [--fit first|best|worst]
                     [--raid 0|1|4|5] [--disks N]
                                               formata o disco (apaga tudo)
              demo [indexada|contigua|encadeada|raid5|seguranca]
                                               executa um cenário de demonstração

            E/S e arranjo RAID
              array                            layout do arranjo: blocos lógicos por disco físico
              io                               requisições pendentes em cada disco
              disk_sched FCFS|SSTF|SCAN|C-SCAN [--head N] [--trilhas 98,183,37]
                                               escalona as filas agora (ou as trilhas informadas) e mostra o deslocamento
              iosched FCFS|SSTF|SCAN|C-SCAN|off
                                               escalona automaticamente após cada comando com E/S
              fail <disco>                     simula a falha de um disco físico (1..N)
              rebuild <disco>                  reconstrói o disco substituído (espelho ou XOR)
              ssd_demo [--writes N]            compara SSD sem e com balanceamento de desgaste

            Segurança e proteção
              login <usuario> [--password S]   autentica usuário e alterna o modo da sessão
              logout                           encerra a sessão e retorna a root (MODO_KERNEL)
              whoami                           exibe usuário, grupos e modo atual (apelido: id)
              sudo [--password S] <cmd> ...    executa um comando temporariamente em MODO_KERNEL
              useradd <usr> [--group G] [--password S] [--admin]
                                               cria usuário com salt aleatório + hash SHA-256
              userdel <usuario>                remove um usuário cadastrado
              groupadd <grupo>                 cria um novo grupo
              usermod <usr> --add-group <grp>  adiciona usuário a um grupo suplementar
              passwd [usuario] [--password S]  altera a senha do usuário
              users                            lista usuários, grupos e hashes (apelido: groups)
              chmod <modo> <caminho>           altera permissões POSIX (ex.: 750, u=rwx,g=rx,o=)
              chown <dono>[:<grp>] <caminho>   altera proprietário (e grupo opcional)
              chgrp <grupo> <caminho>          altera o grupo associado ao objeto
              setfacl -m <u|g:nome:perm> | -x <u|g:nome> | -b <caminho>
                                               define, remove ou limpa entradas da ACL estendida
              getfacl <caminho>                exibe permissões POSIX e ACL (apelido: acl)
              exec <arq>                       simula execução verificando permissão 'x'
              encrypt_file <arq> --key CHAVE   cifra arquivo em repouso (AES-256-CTR)
              decrypt_file <arq> --key CHAVE   decifra arquivo em repouso mediante chave
              antivirus_scan [caminho] [--quarantine]
                                               verifica hashes SHA-256 e assinaturas maliciosas
              tamper_block <bloco> [dados]     simula adulteração direta em bloco físico
              audit_log [--clear]              exibe (ou limpa) a trilha de auditoria

            Outros
              echo <texto>  |  help  |  exit""";

    static final Map<String, List<String>> DEMOS = new LinkedHashMap<>();

    static {
        DEMOS.put("indexada", List.of(
                "format --strategy indexada --block-size 4KB --blocks 16",
                "mkdir /sistema",
                "mkdir /sistema/logs",
                "create /sistema/logs/app.log --size 10KB",
                "tree",
                "fs_status --details"));
        DEMOS.put("contigua", List.of(
                "format --strategy contigua --block-size 4KB --blocks 16",
                "mkdir -p /home/user",
                "cd /home/user",
                "create a.bin --size 8KB",
                "create b.bin --size 6KB",
                "create c.bin --size 8KB",
                "create d.bin --size 7KB",
                "create e.bin --size 8KB",
                "create f.bin --size 8KB",
                "create g.bin --size 1KB",
                "rm b.bin",
                "rm d.bin",
                "rm f.bin",
                "echo # 8 blocos livres, mas a maior lacuna tem 2 -> arquivo de 16KB não cabe",
                "create grande.bin --size 16KB",
                "fs_status --details",
                "defrag",
                "create grande.bin --size 16KB",
                "fs_status --details"));
        DEMOS.put("raid5", List.of(
                "format --strategy indexada --block-size 4KB --blocks 12 --raid 5 --disks 3",
                "iosched SSTF",
                "mkdir /dados",
                "create /dados/planilha.csv --size 8KB",
                "array",
                "fail 2",
                "create /dados/resumo.txt --content \"Relatório gerado com o disco 2 em falha.\"",
                "rebuild 2",
                "array"));
        DEMOS.put("encadeada", List.of(
                "format --strategy encadeada --block-size 1KB --blocks 32",
                "mkdir -p /home/user",
                "create /home/user/a.txt --size 3KB",
                "create /home/user/b.txt --size 2KB",
                "create /home/user/c.txt --size 3KB",
                "rm /home/user/b.txt",
                "echo # o novo arquivo reaproveita a lacuna de b.txt e continua após c.txt",
                "create /home/user/d.txt --size 4500B",
                "write /home/user/nota.txt \"Olá, sistema de arquivos!\\nSegunda linha.\"",
                "cat /home/user/nota.txt",
                "stat /home/user/d.txt",
                "fs_status --details"));
        DEMOS.put("seguranca", List.of(
                "format --strategy indexada --block-size 4KB --blocks 32",
                "mkdir /sistema",
                "mkdir /sistema/logs",
                "create /sistema/config.sys --content \"kernel_mode=strict\\naudit=enabled\"",
                "chmod 750 /sistema/config.sys",
                "create /sistema/logs/app.log --content \"2026-11-03 INFO Sistema inicializado normalmente\"",
                "groupadd devs",
                "useradd carlos --group devs --password segredo123",
                "mkdir -p /home/carlos",
                "chown carlos:devs /home/carlos",
                "chmod 700 /home/carlos",
                "login --user carlos --password segredo123",
                "read_file /sistema/config.sys",
                "create /home/carlos/segredo.txt --content \" Segredo confidencial do projeto \"",
                "encrypt_file /home/carlos/segredo.txt --key \"MinhaChave123\"",
                "sudo --password root tamper_block 6 \"BLOCO_CORROMPIDO\"",
                "create /home/carlos/suspeito.tmp --content "
                        + "\"X5O!P%@AP[4\\\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*\"",
                "antivirus_scan / --quarantine",
                "logout",
                "audit_log"));
    }

    private final FileSystem fs;
    private final Report.Palette pal;
    private final PrintStream out;
    private final Map<String, Consumer<List<String>>> commands = new HashMap<>();
    private ArmScheduler.Policy autoPolicy; // escalonamento automático após comandos com E/S (null = desligado)
    private BufferedReader input;

    Shell(FileSystem fs, boolean color, PrintStream out) {
        this.fs = fs;
        this.pal = new Report.Palette(color);
        this.out = out;
        commands.put("help", a -> out.println(HELP));
        commands.put("echo", a -> out.println(String.join(" ", a)));
        commands.put("pwd", a -> out.println(fs.cwd.path()));
        commands.put("cd", a -> fs.cd(a.isEmpty() ? "/" : a.get(0)));
        commands.put("ls", this::ls);
        commands.put("tree", this::tree);
        commands.put("mkdir", this::mkdir);
        commands.put("create", this::create);
        commands.put("touch", this::create);
        commands.put("cat", this::cat);
        commands.put("read_file", this::cat);
        commands.put("stat", this::stat);
        commands.put("write", a -> write(a, false));
        commands.put("append", a -> write(a, true));
        commands.put("resize", this::resize);
        commands.put("mv", this::mv);
        commands.put("rm", this::rm);
        commands.put("rmdir", this::rmdir);
        commands.put("fs_status", this::status);
        commands.put("status", this::status);
        commands.put("map", a -> out.println(Report.graphic(fs, pal)));
        commands.put("defrag", this::defrag);
        commands.put("compact", this::defrag);
        commands.put("format", this::format);
        commands.put("demo", this::demo);
        commands.put("array", this::array);
        commands.put("io", this::io);
        commands.put("disk_sched", this::diskSched);
        commands.put("iosched", this::ioSched);
        commands.put("fail", this::fail);
        commands.put("rebuild", this::rebuild);
        commands.put("ssd_demo", this::ssdDemo);
        commands.put("login", this::login);
        commands.put("logout", this::logout);
        commands.put("whoami", this::whoami);
        commands.put("id", this::whoami);
        commands.put("sudo", this::sudo);
        commands.put("useradd", this::useradd);
        commands.put("userdel", this::userdel);
        commands.put("groupadd", this::groupadd);
        commands.put("usermod", this::usermod);
        commands.put("passwd", this::passwd);
        commands.put("users", this::users);
        commands.put("groups", this::users);
        commands.put("chmod", this::chmod);
        commands.put("chown", this::chown);
        commands.put("chgrp", this::chgrp);
        commands.put("setfacl", this::setfacl);
        commands.put("getfacl", this::getfacl);
        commands.put("acl", this::getfacl);
        commands.put("exec", this::exec);
        commands.put("encrypt_file", this::encryptFile);
        commands.put("decrypt_file", this::decryptFile);
        commands.put("antivirus_scan", this::antivirusScan);
        commands.put("tamper_block", this::tamperBlock);
        commands.put("audit_log", this::auditLog);
    }

    // ------------------------------------------------------------- execução

    /** Executa uma linha; retorna false quando o usuário pede para sair. */
    boolean execute(String line, boolean echo) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return true;
        }
        if (echo) {
            out.println("> " + line);
        }
        List<String> argv;
        try {
            argv = tokenize(line);
        } catch (FSException e) {
            out.println("Erro: " + e.getMessage());
            return true;
        }
        String cmd = argv.get(0);
        List<String> args = argv.subList(1, argv.size());
        if (cmd.equals("exit") || cmd.equals("quit") || cmd.equals("sair")) {
            return false;
        }
        Consumer<List<String>> handler = commands.get(cmd);
        if (handler == null) {
            out.println("Comando desconhecido: '" + cmd + "'. Digite 'help'.");
            return true;
        }
        try {
            handler.accept(args);
        } catch (AccessDeniedException e) {
            out.println(Report.aclDenied(e));
        } catch (FSException e) {
            out.println("Erro: " + e.getMessage());
        }
        if (autoPolicy != null && pendingIo()) {
            dispatch(autoPolicy, null);
        }
        return true;
    }

    void runScript(List<String> lines) {
        for (String line : lines) {
            if (!execute(line, true)) {
                break;
            }
        }
    }

    void repl(BufferedReader in) throws IOException {
        this.input = in;
        try {
            while (true) {
                String promptChar = fs.auth.isKernel() ? "#" : "$";
                out.print(fs.auth.currentUser().name() + ":" + fs.cwd.path() + " " + promptChar + " ");
                out.flush();
                String line = in.readLine();
                if (line == null) {
                    out.println();
                    break;
                }
                if (!execute(line, false)) {
                    break;
                }
            }
        } finally {
            this.input = null;
        }
    }

    /** Divide a linha em argumentos, respeitando aspas simples e duplas. */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && quote != '\''
                    && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\'
                    || (quote == 0 && line.charAt(i + 1) == ' '))) {
                cur.append(line.charAt(++i));
                inToken = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new FSException("aspas não fechadas");
        }
        if (inToken) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    private record Parsed(List<String> pos, Map<String, String> opts) {
    }

    /** Separa argumentos posicionais de opções (--opt valor / flags). */
    private static Parsed opts(List<String> args, Set<String> valued, Set<String> flags) {
        List<String> pos = new ArrayList<>();
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (valued.contains(a)) {
                if (i + 1 >= args.size()) {
                    throw new FSException("a opção " + a + " exige um valor");
                }
                opts.put(a, args.get(++i));
            } else if (flags.contains(a)) {
                opts.put(a, "true");
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new FSException("opção desconhecida: " + a);
            } else {
                pos.add(a);
            }
        }
        return new Parsed(pos, opts);
    }

    private static void need(List<String> pos, int n, String usage) {
        if (pos.size() < n) {
            throw new FSException("uso: " + usage);
        }
    }

    // ------------------------------------------------------------ comandos
    private void ls(List<String> args) {
        Parsed p = opts(args, Set.of(), Set.of("-l"));
        List<Node> entries = fs.listdir(p.pos().isEmpty() ? "." : p.pos().get(0));
        if (entries.isEmpty()) {
            out.println("(vazio)");
            return;
        }
        if (!p.opts().containsKey("-l")) {
            for (Node n : entries) {
                if (n.isDir) {
                    out.printf("  %-24s %10s  [DIR]%n", n.name + "/", "");
                } else {
                    out.printf("  %-24s %10s  [ARQ]%n", n.name, Units.fmtSize(n.size));
                }
            }
            return;
        }
        int ownerPad = Math.max(6, entries.stream().mapToInt(n -> n.owner.length()).max().orElse(6));
        int groupPad = Math.max(6, entries.stream().mapToInt(n -> n.group.length()).max().orElse(6));
        for (Node n : entries) {
            String perms = AccessControl.lsPermissions(n);
            String tag = fs.integrity.isQuarantined(n) ? "[QUAR]" : (n.crypto != null ? "[ENC]" : "");
            String size = n.isDir ? "-" : Units.fmtSize(n.size);
            String name = n.isDir ? n.name + "/" : n.name;
            out.printf("  %s %-" + ownerPad + "s %-" + groupPad + "s %-6s %10s  %s%n",
                    perms, n.owner, n.group, tag, size, name);
        }
    }

    private void tree(List<String> args) {
        Node start = args.isEmpty() ? fs.root : fs.resolve(args.get(0));
        if (!start.isDir) {
            throw new FSException("'" + start.path() + "' não é um diretório");
        }
        AccessControl.check(start, fs.auth, fs.audit, AccessControl.Action.READ);
        out.println(Report.tree(fs, start));
    }

    private void mkdir(List<String> args) {
        Parsed p = opts(args, Set.of(), Set.of("-p"));
        need(p.pos(), 1, "mkdir [-p] <dir>...");
        for (String path : p.pos()) {
            fs.mkdir(path, p.opts().containsKey("-p"));
        }
    }

    private void create(List<String> args) {
        Parsed p = opts(args, Set.of("--size", "--content"), Set.of());
        need(p.pos(), 1, "create <arquivo> [--size N] [--content TEXTO]");
        Node node = p.opts().containsKey("--content")
                ? fs.create(p.pos().get(0), text(p.opts().get("--content")))
                : fs.create(p.pos().get(0), Units.parseSize(p.opts().getOrDefault("--size", "0")));
        reportAlloc(node);
    }

    private void cat(List<String> args) {
        Parsed p = opts(args, Set.of("--key", "-k"), Set.of());
        need(p.pos(), 1, "cat <arquivo> [--key CHAVE]");
        String key = p.opts().getOrDefault("--key", p.opts().get("-k"));
        byte[] data = fs.read(p.pos().get(0), key);
        Node node = fs.resolve(p.pos().get(0));
        if (node.crypto != null && key == null) {
            out.println("Conteúdo cifrado nos blocos: \"" + CryptoEngine.hexPreview(data)
                    + "\" (informe '--key <chave>' ou use 'decrypt_file')");
            return;
        }
        boolean allZero = true;
        for (byte b : data) {
            allZero &= b == 0;
        }
        if (data.length == 0) {
            out.println("(arquivo vazio)");
        } else if (allZero) {
            out.println("(" + data.length + " bytes de dados binários zerados)");
        } else {
            String s = new String(data, StandardCharsets.UTF_8);
            out.println(s.length() <= 4000 ? s : s.substring(0, 4000) + "\n... (" + data.length + " bytes no total)");
        }
    }

    private void stat(List<String> args) {
        need(args, 1, "stat <caminho>");
        Node node = fs.resolve(args.get(0));
        out.println("Caminho     : " + node.path());
        out.println("Tipo        : " + (node.isDir ? "Diretório" : "Arquivo"));
        out.println("Proprietário: " + node.owner + " (Grupo: " + node.group + ")");
        out.println("Permissões  : " + AccessControl.formatPermissions(node));
        if (node.isDir) {
            out.println("Entradas    : " + node.children.size());
            List<Integer> meta = fs.alloc.metaBlocks(node);
            out.println("Metadados   : " + (meta.isEmpty()
                    ? "tabela de diretórios (bloco 00)"
                    : String.format(Locale.ROOT, "i-node no bloco %02d", meta.get(0))));
            return;
        }
        String protection;
        if (fs.integrity.isQuarantined(node)) {
            protection = "Isolado em quarentena";
        } else if (node.crypto != null) {
            protection = "Criptografado (AES-256-CTR)";
        } else {
            protection = "Texto claro";
        }
        out.println("Proteção    : " + protection);
        FileSystem.FileStats st = fs.fileStats(node);
        out.println("Tamanho     : " + node.size + " bytes (" + Units.fmtSize(node.size) + ")");
        out.println("Alocado     : " + st.allocated() + " bytes (" + st.blocks().size() + " bloco(s) de "
                + Units.fmtSize(fs.disk.blockSize) + ")");
        out.println("Frag. int   : " + st.internal() + " bytes");
        out.println("Alocação    : " + fs.alloc.describe(node));
    }

    private void write(List<String> args, boolean append) {
        need(args, 2, (append ? "append" : "write") + " <arquivo> <texto>");
        String content = String.join(" ", args.subList(1, args.size()));
        reportAlloc(fs.write(args.get(0), text(content), append));
    }

    private void resize(List<String> args) {
        Parsed p = opts(args, Set.of("--size"), Set.of());
        if (p.pos().isEmpty() || !p.opts().containsKey("--size")) {
            throw new FSException("uso: resize <arquivo> --size N");
        }
        reportAlloc(fs.truncate(p.pos().get(0), Units.parseSize(p.opts().get("--size"))));
    }

    private void mv(List<String> args) {
        need(args, 2, "mv <origem> <destino>");
        fs.move(args.get(0), args.get(1));
    }

    private void rm(List<String> args) {
        Parsed p = opts(args, Set.of(), Set.of("-r", "-rf"));
        need(p.pos(), 1, "rm [-r] <caminho>");
        for (String path : p.pos()) {
            fs.remove(path, !p.opts().isEmpty());
        }
    }

    private void rmdir(List<String> args) {
        need(args, 1, "rmdir <dir>");
        for (String path : args) {
            fs.rmdir(path);
        }
    }

    private void status(List<String> args) {
        Parsed p = opts(args, Set.of(), Set.of("--details", "-d"));
        out.println(Report.status(fs, !p.opts().isEmpty(), pal));
    }

    private void defrag(List<String> args) {
        out.println("Compactação concluída: " + fs.defrag() + " bloco(s) realocado(s).");
    }

    private void format(List<String> args) {
        Parsed p = opts(args, Set.of("--strategy", "-s", "--block-size", "--blocks", "--fit", "--raid", "--disks"),
                Set.of());
        Map<String, String> o = p.opts();
        String strategy = o.getOrDefault("--strategy", o.getOrDefault("-s", fs.alloc.key()));
        long blockSize = o.containsKey("--block-size") ? Units.parseSize(o.get("--block-size")) : fs.disk.blockSize;
        long blocks;
        int disks;
        try {
            blocks = o.containsKey("--blocks") ? Long.parseLong(o.get("--blocks")) : fs.disk.numBlocks;
            disks = o.containsKey("--disks") ? Integer.parseInt(o.get("--disks")) : fs.disk.array.disks.length;
        } catch (NumberFormatException e) {
            throw new FSException("--blocks e --disks devem ser números inteiros");
        }
        RaidArray.Level level = o.containsKey("--raid")
                ? RaidArray.parseLevel(o.get("--raid")) : fs.disk.array.level;
        fs.format(blockSize, blocks, strategy, o.getOrDefault("--fit", fs.alloc.fit), level, disks);
        out.println("Disco formatado: " + fs.alloc.title() + " | " + fs.disk.array.label() + " | "
                + fs.disk.numBlocks + " blocos de "
                + Units.fmtSize(fs.disk.blockSize) + " (" + Units.fmtSize(fs.disk.capacity()) + ")");
    }

    private void demo(List<String> args) {
        String name = args.isEmpty() ? "indexada" : args.get(0);
        if (!DEMOS.containsKey(name)) {
            throw new FSException("demos disponíveis: " + String.join(", ", DEMOS.keySet()));
        }
        runScript(DEMOS.get(name));
    }

    // ------------------------------------------------------------ E/S e arranjo
    private boolean pendingIo() {
        for (PhysicalDisk d : fs.disk.array.disks) {
            if (!d.queue.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void array(List<String> args) {
        RaidArray arr = fs.disk.array;
        out.println("Arranjo: " + arr.label() + " | " + arr.blocksPerDisk + " bloco(s) físico(s) por disco | "
                + Units.fmtSize(arr.blockSize) + " por bloco | " + arr.logicalBlocks + " blocos lógicos");
        StringBuilder head = new StringBuilder("Bloco  ");
        for (PhysicalDisk d : arr.disks) {
            head.append(String.format("%-16s", "Disco " + (d.id + 1) + (d.failed ? " [FALHOU]" : "")));
        }
        out.println(head.toString().stripTrailing());
        for (int p = 0; p < arr.blocksPerDisk; p++) {
            StringBuilder row = new StringBuilder(String.format("%02d     ", p));
            for (PhysicalDisk d : arr.disks) {
                row.append(String.format("%-16s", cell(arr, d, p)));
            }
            out.println(row.toString().stripTrailing());
        }
    }

    private String cell(RaidArray arr, PhysicalDisk d, int p) {
        if (d.failed) {
            return "(perdido)";
        }
        int lb = arr.logicalAt(d.id, p);
        if (lb < 0) {
            return "PARIDADE";
        }
        if (lb >= arr.logicalBlocks) {
            return "(vazio)";
        }
        Disk.BlockInfo info = fs.disk.info[lb];
        return info == null ? String.format("L%02d livre", lb) : String.format("L%02d %s", lb, info.kind);
    }

    private void io(List<String> args) {
        if (!pendingIo()) {
            out.println("Nenhuma requisição pendente. (L = leitura, G = gravação; números = trilhas)");
            return;
        }
        for (PhysicalDisk d : fs.disk.array.disks) {
            String reqs = d.queue.stream()
                    .map(r -> (r.op() == IoRequest.Op.LEITURA ? "L" : "G") + r.track())
                    .collect(Collectors.joining(", "));
            out.printf("Disco %d (cabeça na trilha %d): %s%n", d.id + 1, d.head, reqs.isEmpty() ? "(vazia)" : reqs);
        }
    }

    private void diskSched(List<String> args) {
        Parsed p = opts(args, Set.of("--head", "--trilhas"), Set.of());
        need(p.pos(), 1, "disk_sched FCFS|SSTF|SCAN|C-SCAN [--head N] [--trilhas 98,183,37]");
        ArmScheduler.Policy policy = ArmScheduler.Policy.parse(p.pos().get(0));
        Integer head = p.opts().containsKey("--head") ? track(p.opts().get("--head")) : null;
        if (p.opts().containsKey("--trilhas")) {
            // simulação isolada: as trilhas informadas viram a fila, sem tocar nas filas reais
            List<Integer> tracks = Arrays.stream(p.opts().get("--trilhas").split(","))
                    .map(t -> track(t.strip())).toList();
            schedule(policy, head == null ? 0 : head, tracks);
            return;
        }
        if (!pendingIo()) {
            out.println("Nenhuma requisição pendente.");
            return;
        }
        dispatch(policy, head);
    }

    private void ioSched(List<String> args) {
        fs.auth.requireKernel("iosched");
        need(args, 1, "iosched FCFS|SSTF|SCAN|C-SCAN|off");
        if (args.get(0).equalsIgnoreCase("off")) {
            autoPolicy = null;
            out.println("Escalonamento automático desligado.");
            return;
        }
        autoPolicy = ArmScheduler.Policy.parse(args.get(0));
        out.println("Escalonamento automático: " + autoPolicy.label + " (após cada comando com E/S)");
    }

    private void fail(List<String> args) {
        fs.auth.requireKernel("fail");
        need(args, 1, "fail <disco>");
        int d = diskNumber(args.get(0));
        fs.disk.array.fail(d);
        out.println("Disco " + (d + 1) + ": falha simulada, conteúdo perdido. Arranjo em modo degradado.");
    }

    private void rebuild(List<String> args) {
        fs.auth.requireKernel("rebuild");
        need(args, 1, "rebuild <disco>");
        int d = diskNumber(args.get(0));
        RaidArray arr = fs.disk.array;
        int n = arr.rebuild(d);
        String source = arr.level == RaidArray.Level.RAID1 ? "cópia do espelho" : "XOR dos demais discos";
        out.println("Disco " + (d + 1) + " reconstruído: " + n + " bloco(s) recalculados por " + source + " [OK]");
    }

    /** Escalona a fila de cada disco físico, mostra a ordem de atendimento e o deslocamento e esvazia as filas. */
    private void dispatch(ArmScheduler.Policy policy, Integer head) {
        for (PhysicalDisk d : fs.disk.array.disks) {
            if (head != null) {
                d.head = head;
            }
            if (d.queue.isEmpty()) {
                continue;
            }
            List<Integer> tracks = d.queue.stream().map(IoRequest::track).toList();
            out.println("[E/S] Disco " + (d.id + 1) + " — requisições pendentes nas trilhas: " + tracks);
            d.head = schedule(policy, d.head, tracks);
            d.queue.clear();
        }
    }

    /** Mostra a ordem de atendimento e o deslocamento do braço; devolve a trilha final da cabeça. */
    private int schedule(ArmScheduler.Policy policy, int head, List<Integer> tracks) {
        ArmScheduler.Plan plan = ArmScheduler.plan(policy, head, tracks, fs.disk.array.blocksPerDisk - 1);
        List<Integer> path = plan.path();
        StringBuilder order = new StringBuilder().append(head);
        plan.order().forEach(t -> order.append(" -> ").append(t));
        StringBuilder moves = new StringBuilder();
        StringBuilder trajeto = new StringBuilder().append(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            long step = Math.abs(path.get(i) - path.get(i - 1));
            moves.append(i > 1 ? " + " : "").append(step);
            trajeto.append(" -> ").append(path.get(i));
        }
        out.println("Posição atual da cabeça: Trilha " + head);
        out.println("--- ESCALONAMENTO DE DISCO: " + policy.label + " ---");
        out.println("Ordem de Atendimento : " + order);
        out.println("Trajeto da Cabeça    : " + trajeto);
        out.println("Total de Movimento   : " + moves + " = " + ArmScheduler.length(path) + " trilhas");
        if (plan.returnMove() > 0) {
            out.println("Retorno do C-SCAN    : " + plan.returnMove() + " trilhas (incluso no total)");
        }
        out.println();
        return path.get(path.size() - 1);
    }

    private void ssdDemo(List<String> args) {
        Parsed p = opts(args, Set.of("--writes"), Set.of());
        int writes = p.opts().containsKey("--writes") ? count(p.opts().get("--writes")) : 2000;
        FlashSim semWl = new FlashSim(16, 8, false);
        FlashSim comWl = new FlashSim(16, 8, true);
        semWl.replay(writes, SSD_SEED);
        comWl.replay(writes, SSD_SEED);
        out.println("--- SSD (flash): 16 blocos de apagamento × 8 páginas, " + semWl.logicalPages
                + " páginas lógicas, carga inicial + " + writes + " atualizações (90% em 10% das páginas) ---");
        ssdRow("", "sem WL", "com WL (dinâmico)");
        ssdRow("Escritas do host", semWl.hostWrites, comWl.hostWrites);
        ssdRow("Gravações físicas", semWl.flashWrites, comWl.flashWrites);
        ssdRow("Cópias do GC", semWl.gcCopies, comWl.gcCopies);
        ssdRow("Apagamentos (total)", semWl.erasures, comWl.erasures);
        ssdRow("Amplificação de escrita", String.format("%.2f", semWl.writeAmplification()),
                String.format("%.2f", comWl.writeAmplification()));
        FlashSim.Wear a = semWl.wear();
        FlashSim.Wear b = comWl.wear();
        ssdRow("Desgaste mín / máx", a.min() + " / " + a.max(), b.min() + " / " + b.max());
        ssdRow("Desgaste média / desvio", String.format("%.1f / %.2f", a.mean(), a.stdDev()),
                String.format("%.1f / %.2f", b.mean(), b.stdDev()));
        out.println("Apagamentos por bloco (sem WL): " + Arrays.toString(semWl.eraseCounts()));
        out.println("Apagamentos por bloco (com WL): " + Arrays.toString(comWl.eraseCounts()));
        out.println("Sem WL, o GC reaproveita sempre os blocos de menor índice; com WL, o bloco livre escolhido é o menos desgastado.");
    }

    private void ssdRow(String label, Object sem, Object com) {
        out.printf("%-28s %16s %22s%n", label, sem, com);
    }

    private int diskNumber(String text) {
        int n = count(text);
        if (n < 1 || n > fs.disk.array.disks.length) {
            throw new FSException("disco inválido: '" + text + "' (use 1 a " + fs.disk.array.disks.length + ")");
        }
        return n - 1;
    }

    private int track(String text) {
        int max = fs.disk.array.blocksPerDisk - 1;
        int t = count(text);
        if (t < 0 || t > max) {
            throw new FSException("trilha fora do disco (use 0 a " + max + ")");
        }
        return t;
    }

    private static int count(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new FSException("'" + text + "' deve ser um número inteiro");
        }
    }

    // --------------------------------------------------- segurança e proteção
    private void login(List<String> args) {
        Parsed p = opts(args, Set.of("--user", "-u", "--password"), Set.of());
        String user = p.opts().getOrDefault("--user", p.opts().get("-u"));
        if (user == null) {
            need(p.pos(), 1, "login [--user] <usuario> [--password <senha>]");
            user = p.pos().get(0);
        }
        String pw = readPassword(p.opts(), "Senha: ", true);
        AuthManager.User u = fs.auth.login(user, pw);
        out.println("Autenticado com sucesso. Usuário: '" + u.name() + "' | Grupo: '" + u.primaryGroup()
                + "' | Modo: " + fs.auth.mode());
    }

    private void logout(List<String> args) {
        String prev = fs.auth.currentUser().name();
        fs.auth.logout();
        out.println("Sessão de '" + prev + "' encerrada. Retornando para 'root' em MODO_KERNEL.");
    }

    private void whoami(List<String> args) {
        AuthManager.User u = fs.auth.currentUser();
        out.println("Usuário: " + u.name() + " | Grupo: " + u.primaryGroup()
                + " | Grupos: [" + String.join(", ", u.allGroups()) + "] | Modo: " + fs.auth.mode());
    }

    private void sudo(List<String> args) {
        Map<String, String> sudoOpts = new HashMap<>();
        int idx = 0;
        if (!args.isEmpty() && args.get(0).equals("--password")) {
            if (args.size() < 2) {
                throw new FSException("a opção --password exige um valor");
            }
            sudoOpts.put("--password", args.get(1));
            idx = 2;
        }
        if (idx >= args.size()) {
            throw new FSException("uso: sudo [--password <senha>] <comando> [args...]");
        }
        String innerCmd = args.get(idx);
        List<String> innerArgs = args.subList(idx + 1, args.size());
        Consumer<List<String>> inner = commands.get(innerCmd);
        if (inner == null) {
            throw new FSException("comando desconhecido: '" + innerCmd + "'");
        }
        if (fs.auth.isKernel()) {
            inner.accept(innerArgs);
            return;
        }
        String pw = readPassword(sudoOpts, "[sudo] Senha: ", false);
        fs.auth.runElevated(pw, () -> inner.accept(innerArgs));
    }

    private void useradd(List<String> args) {
        Parsed p = opts(args, Set.of("--group", "-g", "--password"), Set.of("--admin"));
        need(p.pos(), 1, "useradd <usuario> [--group <grupo>] [--password <senha>] [--admin]");
        fs.auth.requireKernel("useradd");
        String name = p.pos().get(0);
        String group = p.opts().getOrDefault("--group", p.opts().get("-g"));
        boolean admin = p.opts().containsKey("--admin");
        String pw = readPassword(p.opts(), "Nova senha: ", false);
        AuthManager.User u = fs.auth.addUser(name, pw, group, admin);
        out.println("Usuário '" + u.name() + "' criado (grupo: '" + u.primaryGroup() + "', modo: "
                + (u.superuser() ? AuthManager.Mode.MODO_KERNEL : AuthManager.Mode.MODO_USUARIO) + ").");
    }

    private void userdel(List<String> args) {
        need(args, 1, "userdel <usuario>");
        String name = args.get(0);
        fs.auth.removeUser(name);
        out.println("Usuário '" + name + "' removido.");
    }

    private void groupadd(List<String> args) {
        need(args, 1, "groupadd <grupo>");
        String group = args.get(0);
        fs.auth.addGroup(group);
        out.println("Grupo '" + group + "' criado.");
    }

    private void usermod(List<String> args) {
        Parsed p = opts(args, Set.of("--add-group", "-G", "-aG"), Set.of());
        String group = p.opts().getOrDefault("--add-group",
                p.opts().getOrDefault("-G", p.opts().get("-aG")));
        if (p.pos().isEmpty() || group == null) {
            throw new FSException("uso: usermod <usuario> --add-group <grupo>");
        }
        String user = p.pos().get(0);
        fs.auth.addUserToGroup(user, group);
        out.println("Usuário '" + user + "' adicionado ao grupo '" + group + "'.");
    }

    private void passwd(List<String> args) {
        Parsed p = opts(args, Set.of("--password"), Set.of());
        String target = p.pos().isEmpty() ? fs.auth.currentUser().name() : p.pos().get(0);
        if (!fs.auth.isKernel() && !fs.auth.currentUser().name().equals(target)) {
            fs.auth.requireKernel("passwd");
        }
        String pw = readPassword(p.opts(), "Nova senha: ", false);
        fs.auth.changePassword(target, pw);
        out.println("Senha alterada com sucesso para '" + target + "'.");
    }

    private void users(List<String> args) {
        out.println(Report.usersTable(fs.auth));
    }

    private void chmod(List<String> args) {
        need(args, 2, "chmod <modo> <caminho>");
        String mode = args.get(0);
        String path = args.get(1);
        fs.chmod(path, mode);
        Node node = fs.resolve(path, false);
        out.println("Permissões de '" + node.path() + "' alteradas para " + AccessControl.formatPermissions(node) + ".");
    }

    private void chown(List<String> args) {
        need(args, 2, "chown <dono>[:<grupo>] <caminho>");
        String spec = args.get(0);
        String path = args.get(1);
        int colon = spec.indexOf(':');
        String owner = colon >= 0 ? spec.substring(0, colon) : spec;
        String group = colon >= 0 ? spec.substring(colon + 1) : null;
        fs.chown(path, owner, group);
        Node node = fs.resolve(path, false);
        out.println("Proprietário de '" + node.path() + "' definido como '" + node.owner
                + "' (Grupo: '" + node.group + "').");
    }

    private void chgrp(List<String> args) {
        need(args, 2, "chgrp <grupo> <caminho>");
        String group = args.get(0);
        String path = args.get(1);
        fs.chgrp(path, group);
        Node node = fs.resolve(path, false);
        out.println("Grupo de '" + node.path() + "' alterado para '" + node.group + "'.");
    }

    private void setfacl(List<String> args) {
        Parsed p = opts(args, Set.of("-m", "-x"), Set.of("-b"));
        need(p.pos(), 1, "setfacl (-m <u|g:nome:perm> | -x <u|g:nome> | -b) <caminho>");
        String path = p.pos().get(0);
        int actions = (p.opts().containsKey("-m") ? 1 : 0)
                + (p.opts().containsKey("-x") ? 1 : 0)
                + (p.opts().containsKey("-b") ? 1 : 0);
        if (actions != 1) {
            throw new FSException("uso: setfacl (-m <u|g:nome:perm> | -x <u|g:nome> | -b) <caminho>");
        }
        if (p.opts().containsKey("-m")) {
            fs.setAcl(path, p.opts().get("-m"));
            Node node = fs.resolve(path, false);
            out.println("ACL atualizada em '" + node.path() + "' (" + AccessControl.formatPermissions(node) + ").");
        } else if (p.opts().containsKey("-x")) {
            fs.removeAcl(path, p.opts().get("-x"));
            Node node = fs.resolve(path, false);
            out.println("Entrada ACL removida de '" + node.path() + "' ("
                    + AccessControl.formatPermissions(node) + ").");
        } else {
            fs.clearAcl(path);
            Node node = fs.resolve(path, false);
            out.println("ACL estendida removida de '" + node.path() + "' ("
                    + AccessControl.formatPermissions(node) + ").");
        }
    }

    private void getfacl(List<String> args) {
        need(args, 1, "getfacl <caminho>");
        out.println(Report.aclDetails(fs.resolve(args.get(0))));
    }

    private void exec(List<String> args) {
        need(args, 1, "exec <arquivo>");
        byte[] code = fs.execute(args.get(0));
        Node node = fs.resolve(args.get(0), false);
        out.println("Execução concluída: '" + node.path() + "' (" + Units.fmtSize(code.length)
                + ") | Usuário: '" + fs.auth.currentUser().name() + "' | Modo: " + fs.auth.mode());
    }

    private void encryptFile(List<String> args) {
        Parsed p = opts(args, Set.of("--key", "-k"), Set.of());
        String key = p.opts().getOrDefault("--key", p.opts().get("-k"));
        if (p.pos().isEmpty() || key == null) {
            throw new FSException("uso: encrypt_file <arquivo> --key <chave>");
        }
        String path = p.pos().get(0);
        byte[] cipher = fs.encryptFile(path, key);
        Node node = fs.resolve(path);
        out.println("Arquivo '" + node.path() + "' criptografado com sucesso.");
        out.println("Conteúdo gravado nos blocos físicos: \"" + CryptoEngine.hexPreview(cipher) + "\"");
    }

    private void decryptFile(List<String> args) {
        Parsed p = opts(args, Set.of("--key", "-k"), Set.of());
        String key = p.opts().getOrDefault("--key", p.opts().get("-k"));
        if (p.pos().isEmpty() || key == null) {
            throw new FSException("uso: decrypt_file <arquivo> --key <chave>");
        }
        String path = p.pos().get(0);
        fs.decryptFile(path, key);
        Node node = fs.resolve(path);
        out.println("Arquivo '" + node.path() + "' descriptografado com sucesso.");
    }

    private void antivirusScan(List<String> args) {
        Parsed p = opts(args, Set.of(), Set.of("--quarantine", "-q"));
        String target = p.pos().isEmpty() ? "/" : p.pos().get(0);
        boolean quarantine = !p.opts().isEmpty();
        IntegrityMonitor.ScanReport report = fs.scanIntegrity(target, quarantine);
        out.println(Report.integrityScan(report));
    }

    private void tamperBlock(List<String> args) {
        need(args, 1, "tamper_block <bloco> [dados]");
        int block = count(args.get(0));
        byte[] payload = args.size() > 1
                ? text(String.join(" ", args.subList(1, args.size())))
                : null;
        fs.tamperBlock(block, payload);
        out.println("Bloco lógico " + Units.pad(block, 2)
                + ": adulteração física direta simulada (fora do sistema de arquivos).");
    }

    private void auditLog(List<String> args) {
        fs.auth.requireKernel("audit_log");
        Parsed p = opts(args, Set.of(), Set.of("--clear", "-c"));
        if (!p.opts().isEmpty()) {
            fs.audit.clear();
            out.println("Log de auditoria limpo.");
            return;
        }
        out.println(Report.auditLog(fs.audit.entries()));
    }

    // ------------------------------------------------------------ auxiliares
    private String readPassword(Map<String, String> opts, String promptLabel, boolean echoMaskWhenOpt) {
        if (opts.containsKey("--password")) {
            if (echoMaskWhenOpt) {
                out.println(promptLabel + "**********");
            }
            return opts.get("--password");
        }
        String pw = null;
        try {
            if (input != null && input.ready()) {
                out.print(promptLabel);
                out.flush();
                pw = input.readLine();
            } else if (input != null && isTerminalConsole()) {
                out.print(promptLabel);
                out.flush();
                char[] chars = System.console().readPassword();
                pw = chars == null ? null : new String(chars);
            } else if (input != null) {
                out.print(promptLabel);
                out.flush();
                pw = input.readLine();
            }
        } catch (IOException e) {
            throw new FSException("falha ao ler a senha: " + e.getMessage());
        }
        if (pw == null) {
            throw new FSException("informe '--password <senha>' no modo não interativo");
        }
        return pw;
    }

    private static boolean isTerminalConsole() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private static byte[] text(String s) {
        return s.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void reportAlloc(Node node) {
        out.println("'" + node.path() + "' (" + Units.fmtSize(node.size) + ") → " + fs.alloc.describe(node));
    }
}
