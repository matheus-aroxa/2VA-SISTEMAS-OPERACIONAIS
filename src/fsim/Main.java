package fsim;

import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simulador de Sistema de Arquivos — ponto de entrada.
 *
 * <pre>
 * java -cp out fsim.Main                                  # modo interativo
 * java -cp out fsim.Main exemplos/indexada.txt            # executa um roteiro de comandos
 * java -cp out fsim.Main --strategy contigua --block-size 1KB --blocks 32
 * </pre>
 */
public final class Main {
    private static final String USAGE = """
            uso: java -cp out fsim.Main [opções] [roteiro.txt]

              -s, --strategy S   contigua | encadeada | indexada (padrão: indexada)
              --block-size B     tamanho do bloco (padrão: 4KB)
              --blocks N         quantidade de blocos (padrão: 16)
              --fit F            first | best | worst (só alocação contígua)
              --raid N           0 | 1 | 4 | 5 (padrão: 0)
              --disks N          discos físicos do arranjo (padrão: 1)
              --ram-reserved R   tamanho da área reservada contígua (padrão: 4MB)
              --ram-dynamic D    tamanho da área dinâmica paginada (padrão: 64KB)
              --page-size P      tamanho da página na memória virtual (padrão: 4KB)
              --page-policy POL  política de substituição (LRU, Segunda_Chance, FIFO, LFU; padrão: LRU)
              --no-color         desativa cores ANSI
              -h, --help         mostra esta ajuda""";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        String strategy = "indexada";
        String blockSize = "4KB";
        String blocks = "16";
        String fit = "first";
        String raid = "0";
        String disks = "1";
        String ramReserved = null;
        String ramDynamic = null;
        String pageSize = null;
        String pagePolicy = null;
        String script = null;
        boolean noColor = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-s", "--strategy" -> strategy = value(args, ++i, out);
                case "--block-size" -> blockSize = value(args, ++i, out);
                case "--blocks" -> blocks = value(args, ++i, out);
                case "--fit" -> fit = value(args, ++i, out);
                case "--raid" -> raid = value(args, ++i, out);
                case "--disks" -> disks = value(args, ++i, out);
                case "--ram-reserved" -> ramReserved = value(args, ++i, out);
                case "--ram-dynamic" -> ramDynamic = value(args, ++i, out);
                case "--page-size" -> pageSize = value(args, ++i, out);
                case "--page-policy" -> pagePolicy = value(args, ++i, out);
                case "--no-color" -> noColor = true;
                case "-h", "--help" -> {
                    out.println(USAGE);
                    return;
                }
                default -> {
                    if (args[i].startsWith("-")) {
                        fail(out, "opção desconhecida: " + args[i]);
                    }
                    script = args[i];
                }
            }
        }

        FileSystem fs = null;
        try {
            fs = new FileSystem((int) Units.parseSize(blockSize), Integer.parseInt(blocks), strategy, fit,
                    RaidArray.parseLevel(raid), Integer.parseInt(disks));
        } catch (FSException | NumberFormatException e) {
            fail(out, e instanceof NumberFormatException ? "--blocks e --disks devem ser inteiros" : e.getMessage());
        }

        if (ramReserved != null || ramDynamic != null || pageSize != null || pagePolicy != null) {
            try {
                long rBytes = ramReserved != null ? Units.parseSize(ramReserved) : MemoryManager.DEFAULT_RESERVED_BYTES;
                long dBytes = ramDynamic != null ? Units.parseSize(ramDynamic) : MemoryManager.DEFAULT_DYNAMIC_BYTES;
                int pSize = pageSize != null ? (int) Units.parseSize(pageSize) : MemoryManager.DEFAULT_PAGE_SIZE;
                PageReplacer.Policy pol = pagePolicy != null ? PageReplacer.Policy.parseLive(pagePolicy) : PageReplacer.Policy.LRU;
                fs.memory.configure(fs, rBytes, dBytes, pSize, fit, pol);
            } catch (FSException e) {
                fail(out, e.getMessage());
            }
        }

        Shell shell = new Shell(fs, !noColor && isTerminal() && System.getenv("NO_COLOR") == null, out);
        if (script != null) {
            shell.runScript(Files.readAllLines(Path.of(script), StandardCharsets.UTF_8));
            return;
        }
        out.println("Simulador de Sistema de Arquivos");
        out.println(fs.alloc.title() + " | " + fs.disk.numBlocks + " blocos de " + fs.disk.blockSize
                + " B. Digite 'help' para ver os comandos.\n");
        shell.repl(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    private static String value(String[] args, int i, PrintStream out) {
        if (i >= args.length) {
            fail(out, "a opção " + args[i - 1] + " exige um valor");
        }
        return args[i];
    }

    private static void fail(PrintStream out, String message) {
        out.println(USAGE);
        out.println("erro: " + message);
        System.exit(2);
    }

    /** No JDK 22+, System.console() existe mesmo com saída redirecionada; isTerminal() distingue. */
    private static boolean isTerminal() {
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
}
