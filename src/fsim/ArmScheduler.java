package fsim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Escalonamento do braço de um disco magnético. Recebe as trilhas pendentes e a posição atual da cabeça e calcula
 * a ordem de atendimento e o trajeto completo. SCAN e C-SCAN sobem até a última trilha do disco: o SCAN volta
 * descendo atendendo os pedidos; o C-SCAN volta ao início sem atender nada (esse salto entra no total).
 */
final class ArmScheduler {
    enum Policy {
        FCFS("FCFS"), SSTF("SSTF"), SCAN("SCAN"), CSCAN("C-SCAN");

        final String label;

        Policy(String label) {
            this.label = label;
        }

        static Policy parse(String text) {
            String key = text.toUpperCase(Locale.ROOT).replace("-", "");
            for (Policy p : values()) {
                if (p.name().equals(key)) {
                    return p;
                }
            }
            throw new FSException("política desconhecida: '" + text + "' (use FCFS, SSTF, SCAN ou C-SCAN)");
        }
    }

    /**
     * @param order      trilhas na ordem de atendimento
     * @param path       posições percorridas pela cabeça, começando na posição inicial
     * @param returnMove deslocamento do salto de retorno (só no C-SCAN); já está incluído em `path`
     */
    record Plan(List<Integer> order, List<Integer> path, long returnMove) {
    }

    private ArmScheduler() {
    }

    static Plan plan(Policy policy, int head, List<Integer> tracks, int maxTrack) {
        List<Integer> order = new ArrayList<>();
        List<Integer> path = new ArrayList<>(List.of(head));
        long returnMove = 0;
        switch (policy) {
            case FCFS -> {
                order.addAll(tracks);
                path.addAll(tracks);
            }
            case SSTF -> {
                List<Integer> left = new ArrayList<>(tracks);
                int pos = head;
                while (!left.isEmpty()) {
                    int best = 0;
                    for (int i = 1; i < left.size(); i++) {
                        if (Math.abs(left.get(i) - pos) < Math.abs(left.get(best) - pos)) {
                            best = i;
                        }
                    }
                    pos = left.remove(best);
                    order.add(pos);
                }
                path.addAll(order);
            }
            case SCAN, CSCAN -> {
                List<Integer> up = tracks.stream().filter(t -> t >= head).sorted().toList();
                path.addAll(up);
                path.add(maxTrack);
                if (policy == Policy.SCAN) {
                    List<Integer> down = tracks.stream().filter(t -> t < head)
                            .sorted(Comparator.reverseOrder()).toList();
                    order.addAll(up);
                    order.addAll(down);
                    path.addAll(down);
                } else {
                    List<Integer> down = tracks.stream().filter(t -> t < head).sorted().toList();
                    order.addAll(up);
                    order.addAll(down);
                    path.add(0);
                    path.addAll(down);
                    returnMove = maxTrack;
                }
            }
        }
        return new Plan(order, path, returnMove);
    }

    /** Soma dos deslocamentos entre posições consecutivas do trajeto. */
    static long length(List<Integer> path) {
        long total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += Math.abs(path.get(i) - path.get(i - 1));
        }
        return total;
    }
}
