package fsim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
              ls [caminho]                     lista um diretório
              tree [caminho]                   exibe a árvore hierárquica

            CRUD
              mkdir [-p] <dir>...              cria diretório(s)
              create <arq> [--size N] [--content TEXTO]
                                               cria arquivo (N: 512B, 10KB, 1MB...)
              cat <arq>                        lê o conteúdo (lido dos blocos do disco)
              stat <caminho>                   metadados e blocos ocupados
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
              demo [indexada|contigua|encadeada|raid5]
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
    }

    private final FileSystem fs;
    private final Report.Palette pal;
    private final PrintStream out;
    private final Map<String, Consumer<List<String>>> commands = new HashMap<>();
    private ArmScheduler.Policy autoPolicy; // escalonamento automático após comandos com E/S (null = desligado)

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
        while (true) {
            out.print(fs.cwd.path() + " > ");
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
        List<Node> entries = fs.listdir(args.isEmpty() ? "." : args.get(0));
        if (entries.isEmpty()) {
            out.println("(vazio)");
        }
        for (Node n : entries) {
            if (n.isDir) {
                out.printf("  %-24s %10s  [DIR]%n", n.name + "/", "");
            } else {
                out.printf("  %-24s %10s  [ARQ]%n", n.name, Units.fmtSize(n.size));
            }
        }
    }

    private void tree(List<String> args) {
        Node start = args.isEmpty() ? fs.root : fs.resolve(args.get(0));
        if (!start.isDir) {
            throw new FSException("'" + start.path() + "' não é um diretório");
        }
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
        need(args, 1, "cat <arquivo>");
        byte[] data = fs.read(args.get(0));
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
        out.println("Caminho  : " + node.path());
        out.println("Tipo     : " + (node.isDir ? "Diretório" : "Arquivo"));
        if (node.isDir) {
            out.println("Entradas : " + node.children.size());
            List<Integer> meta = fs.alloc.metaBlocks(node);
            out.println("Metadados: " + (meta.isEmpty()
                    ? "tabela de diretórios (bloco 00)"
                    : String.format("i-node no bloco %02d", meta.get(0))));
            return;
        }
        FileSystem.FileStats st = fs.fileStats(node);
        out.println("Tamanho  : " + node.size + " bytes (" + Units.fmtSize(node.size) + ")");
        out.println("Alocado  : " + st.allocated() + " bytes (" + st.blocks().size() + " bloco(s) de "
                + Units.fmtSize(fs.disk.blockSize) + ")");
        out.println("Frag. int: " + st.internal() + " bytes");
        out.println("Alocação : " + fs.alloc.describe(node));
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
        need(args, 1, "fail <disco>");
        int d = diskNumber(args.get(0));
        fs.disk.array.fail(d);
        out.println("Disco " + (d + 1) + ": falha simulada, conteúdo perdido. Arranjo em modo degradado.");
    }

    private void rebuild(List<String> args) {
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

    // ------------------------------------------------------------ auxiliares
    private static byte[] text(String s) {
        return s.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void reportAlloc(Node node) {
        out.println("'" + node.path() + "' (" + Units.fmtSize(node.size) + ") → " + fs.alloc.describe(node));
    }
}
