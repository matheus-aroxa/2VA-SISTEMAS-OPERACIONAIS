package fsim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Interpretador de comandos do simulador. */
final class Shell {
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
                                               formata o disco (apaga tudo)
              demo [indexada|contigua|encadeada]
                                               executa um cenário de demonstração

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
        Parsed p = opts(args, Set.of("--strategy", "-s", "--block-size", "--blocks", "--fit"), Set.of());
        Map<String, String> o = p.opts();
        String strategy = o.getOrDefault("--strategy", o.getOrDefault("-s", fs.alloc.key()));
        long blockSize = o.containsKey("--block-size") ? Units.parseSize(o.get("--block-size")) : fs.disk.blockSize;
        long blocks;
        try {
            blocks = o.containsKey("--blocks") ? Long.parseLong(o.get("--blocks")) : fs.disk.numBlocks;
        } catch (NumberFormatException e) {
            throw new FSException("--blocks deve ser um número inteiro");
        }
        fs.format(blockSize, blocks, strategy, o.getOrDefault("--fit", fs.alloc.fit));
        out.println("Disco formatado: " + fs.alloc.title() + " | " + fs.disk.numBlocks + " blocos de "
                + Units.fmtSize(fs.disk.blockSize) + " (" + Units.fmtSize(fs.disk.capacity()) + ")");
    }

    private void demo(List<String> args) {
        String name = args.isEmpty() ? "indexada" : args.get(0);
        if (!DEMOS.containsKey(name)) {
            throw new FSException("demos disponíveis: " + String.join(", ", DEMOS.keySet()));
        }
        runScript(DEMOS.get(name));
    }

    // ------------------------------------------------------------ auxiliares
    private static byte[] text(String s) {
        return s.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void reportAlloc(Node node) {
        out.println("'" + node.path() + "' (" + Units.fmtSize(node.size) + ") → " + fs.alloc.describe(node));
    }
}
