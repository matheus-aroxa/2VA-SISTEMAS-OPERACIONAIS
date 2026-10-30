package fsim;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Estratégia de alocação física de blocos.
 *
 * Cada alocador guarda sua própria estrutura (extensões, tabela FAT ou blocos de índice)
 * e sabe responder quais blocos de dados pertencem a um arquivo, na ordem lógica.
 */
abstract class Allocator {
    static final List<String> FITS = List.of("first", "best", "worst");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("contigua", "contigua"), Map.entry("contígua", "contigua"),
            Map.entry("contiguous", "contigua"), Map.entry("cont", "contigua"),
            Map.entry("encadeada", "encadeada"), Map.entry("fat", "encadeada"),
            Map.entry("linked", "encadeada"), Map.entry("lista", "encadeada"),
            Map.entry("indexada", "indexada"), Map.entry("indexed", "indexada"),
            Map.entry("inode", "indexada"), Map.entry("i-node", "indexada"));

    record ExternalReport(long bytes, boolean needsCompaction, String reason,
                          Set<Integer> scattered, List<Disk.Run> holes) {
    }

    final Disk disk;
    final String fit;
    String lastFailure;

    Allocator(Disk disk, String fit) {
        this.disk = disk;
        this.fit = fit;
    }

    /** Cria e formata o alocador da estratégia pedida (aceita apelidos como "fat" ou "inode"). */
    static Allocator create(String strategy, Disk disk, String fit) {
        String key = ALIASES.get(strategy.toLowerCase(Locale.ROOT));
        if (key == null) {
            throw new FSException("estratégia desconhecida: '" + strategy + "' (use contigua, encadeada ou indexada)");
        }
        Allocator alloc = switch (key) {
            case "contigua" -> new ContiguousAllocator(disk, fit);
            case "encadeada" -> new FatAllocator(disk, fit);
            default -> new IndexedAllocator(disk, fit);
        };
        alloc.format();
        return alloc;
    }

    abstract String key();

    abstract String title();

    abstract String mapTitle();

    String reservedDesc() {
        return "";
    }

    String noCompactionReason() {
        return "";
    }

    void format() {
    }

    /** Reserva os metadados de um novo arquivo/diretório. */
    void createNode(Node node) {
    }

    void deleteNode(Node node) {
        resize(node, 0);
    }

    /** Garante que `node` tenha exatamente `nblocks` blocos de dados (atômico: falha sem alterar nada). */
    abstract void resize(Node node, int nblocks);

    abstract List<Integer> dataBlocks(Node node);

    List<Integer> metaBlocks(Node node) {
        return List.of();
    }

    abstract String describe(Node node);

    String blockNote(int block) {
        return "";
    }

    ExternalReport externalReport() {
        return new ExternalReport(0, false, noCompactionReason(), Set.of(), disk.freeRuns(Set.of()));
    }

    protected void needFree(int needed) {
        int free = disk.freeCount();
        if (free < needed) {
            throw new DiskFullException("disco cheio: necessários " + needed + " blocos livres, disponíveis " + free);
        }
    }

    static String fmtBlocks(List<Integer> blocks, int width) {
        return blocks.stream().map(b -> Units.pad(b, width)).collect(Collectors.joining(", "));
    }
}
