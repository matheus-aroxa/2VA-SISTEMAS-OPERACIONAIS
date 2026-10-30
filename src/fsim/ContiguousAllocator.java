package fsim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Alocação contígua: cada arquivo ocupa (início, comprimento) consecutivos. */
final class ContiguousAllocator extends Allocator {
    private Map<Node, int[]> extents; // nó -> {início, comprimento}

    ContiguousAllocator(Disk disk, String fit) {
        super(disk, fit);
    }

    @Override
    String key() {
        return "contigua";
    }

    @Override
    String title() {
        return "Alocação Contígua";
    }

    @Override
    String mapTitle() {
        return "Contíguo";
    }

    @Override
    String reservedDesc() {
        return "Tabela de diretórios / superbloco";
    }

    @Override
    void format() {
        disk.claim(0, Disk.RESERVED, null, "DIRETÓRIO");
        extents = new HashMap<>();
    }

    @Override
    List<Integer> dataBlocks(Node node) {
        int[] ext = extents.getOrDefault(node, new int[] {0, 0});
        List<Integer> blocks = new ArrayList<>();
        for (int i = ext[0]; i < ext[0] + ext[1]; i++) {
            blocks.add(i);
        }
        return blocks;
    }

    @Override
    void resize(Node node, int n) {
        int[] ext = extents.getOrDefault(node, new int[] {0, 0});
        int start = ext[0];
        int length = ext[1];
        if (n == length) {
            return;
        }
        if (n < length) {
            for (int i = start + n; i < start + length; i++) {
                disk.release(i);
            }
            set(node, start, n);
            return;
        }

        // 1) tenta crescer no próprio lugar
        if (length > 0 && start + n <= disk.numBlocks && allFree(start + length, start + n)) {
            for (int i = start + length; i < start + n; i++) {
                disk.claim(i, Disk.DATA, node, "");
            }
            set(node, start, n);
            lastFailure = null;
            return;
        }

        // 2) realoca o arquivo inteiro para uma lacuna (contando a extensão atual como livre)
        Set<Integer> own = new HashSet<>();
        for (int i = start; i < start + length; i++) {
            own.add(i);
        }
        List<Disk.Run> holes = disk.freeRuns(own);
        List<Disk.Run> fitting = holes.stream().filter(h -> h.length() >= n).toList();
        if (fitting.isEmpty()) {
            int freeTotal = disk.freeCount() + length;
            int largest = holes.stream().mapToInt(Disk.Run::length).max().orElse(0);
            if (freeTotal >= n) {
                lastFailure = "pedido de " + n + " blocos recusado: " + freeTotal
                        + " blocos livres, mas a maior lacuna contígua tem " + largest;
                throw new ExternalFragmentationException(
                        "fragmentação externa: há " + freeTotal + " blocos livres, mas a maior lacuna contígua tem apenas "
                                + largest + " (necessários " + n + "). Execute 'defrag' para compactar.");
            }
            throw new DiskFullException("disco cheio: necessários " + n + " blocos, livres " + freeTotal);
        }

        Disk.Run hole = switch (fit) {
            case "best" -> fitting.stream().min(Comparator.comparingInt(Disk.Run::length)).orElseThrow();
            case "worst" -> fitting.stream().max(Comparator.comparingInt(Disk.Run::length)).orElseThrow();
            default -> fitting.get(0);
        };
        for (int i : own) {
            disk.release(i);
        }
        for (int i = hole.start(); i < hole.start() + n; i++) {
            disk.claim(i, Disk.DATA, node, "");
        }
        set(node, hole.start(), n);
        lastFailure = null;
    }

    private boolean allFree(int from, int to) {
        for (int i = from; i < to; i++) {
            if (!disk.isFree(i)) {
                return false;
            }
        }
        return true;
    }

    private void set(Node node, int start, int length) {
        if (length > 0) {
            extents.put(node, new int[] {start, length});
        } else {
            extents.remove(node);
        }
    }

    @Override
    String describe(Node node) {
        int[] ext = extents.get(node);
        if (ext == null) {
            return "contígua: nenhum bloco de dados";
        }
        return String.format("contígua: início=bloco %02d, comprimento=%d (blocos %02d..%02d)",
                ext[0], ext[1], ext[0], ext[0] + ext[1] - 1);
    }

    @Override
    ExternalReport externalReport() {
        List<Disk.Run> holes = disk.freeRuns(Set.of());
        if (holes.isEmpty()) {
            return new ExternalReport(0, false, "NÃO (nenhum bloco livre)", Set.of(), holes);
        }
        if (holes.size() == 1) {
            return new ExternalReport(0, false,
                    "NÃO (todo o espaço livre está em uma única lacuna contígua)", Set.of(), holes);
        }
        int free = 0;
        int biggest = 0; // em empate, preserva a lacuna mais ao fim
        for (int k = 0; k < holes.size(); k++) {
            free += holes.get(k).length();
            if (holes.get(k).length() >= holes.get(biggest).length()) {
                biggest = k;
            }
        }
        Set<Integer> scattered = new HashSet<>();
        for (int k = 0; k < holes.size(); k++) {
            if (k != biggest) {
                Disk.Run h = holes.get(k);
                for (int i = h.start(); i < h.start() + h.length(); i++) {
                    scattered.add(i);
                }
            }
        }
        long bs = disk.blockSize;
        String reason = "SIM — " + free + " blocos livres (" + Units.fmtSize(free * bs) + ") dispersos em "
                + holes.size() + " lacunas; maior arquivo alocável hoje: "
                + Units.fmtSize(holes.get(biggest).length() * bs) + ", após compactação: "
                + Units.fmtSize(free * bs) + ". Execute 'defrag'.";
        return new ExternalReport(scattered.size() * bs, true, reason, scattered, holes);
    }
}
