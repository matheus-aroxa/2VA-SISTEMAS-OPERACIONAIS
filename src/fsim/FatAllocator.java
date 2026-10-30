package fsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Alocação encadeada via FAT: cada bloco aponta para o próximo até EOF. */
final class FatAllocator extends Allocator {
    static final int FREE = -2;
    static final int EOF = -1;

    private int[] fat;                 // FREE, EOF ou número do próximo bloco
    private Map<Node, Integer> first;  // nó -> primeiro bloco da cadeia

    FatAllocator(Disk disk, String fit) {
        super(disk, fit);
    }

    @Override
    String key() {
        return "encadeada";
    }

    @Override
    String title() {
        return "Alocação Encadeada (FAT)";
    }

    @Override
    String mapTitle() {
        return "Encadeado / FAT";
    }

    @Override
    String reservedDesc() {
        return "Tabela de Alocação de Arquivos (FAT)";
    }

    @Override
    String noCompactionReason() {
        return "NÃO (blocos encadeados via FAT: qualquer bloco livre pode ser usado)";
    }

    @Override
    void format() {
        disk.claim(0, Disk.RESERVED, null, "FAT");
        fat = new int[disk.numBlocks];
        Arrays.fill(fat, FREE);
        first = new HashMap<>();
    }

    @Override
    List<Integer> dataBlocks(Node node) {
        List<Integer> chain = new ArrayList<>();
        Integer b = first.get(node);
        while (b != null) {
            chain.add(b);
            b = fat[b] == EOF ? null : fat[b];
        }
        return chain;
    }

    @Override
    void resize(Node node, int n) {
        List<Integer> chain = dataBlocks(node);
        if (n < chain.size()) {
            for (int b : chain.subList(n, chain.size())) {
                fat[b] = FREE;
                disk.release(b);
            }
            chain = new ArrayList<>(chain.subList(0, n));
        } else if (n > chain.size()) {
            needFree(n - chain.size());
            List<Integer> fresh = disk.freeBlocks().subList(0, n - chain.size());
            for (int b : fresh) {
                disk.claim(b, Disk.DATA, node, "");
            }
            chain.addAll(fresh);
        }
        if (chain.isEmpty()) {
            first.remove(node);
            return;
        }
        for (int k = 0; k + 1 < chain.size(); k++) {
            fat[chain.get(k)] = chain.get(k + 1);
        }
        fat[chain.get(chain.size() - 1)] = EOF;
        first.put(node, chain.get(0));
    }

    @Override
    String describe(Node node) {
        List<Integer> chain = dataBlocks(node);
        if (chain.isEmpty()) {
            return "FAT: nenhum bloco de dados";
        }
        return "cadeia FAT: " + chain.stream().map(b -> Units.pad(b, 2)).collect(Collectors.joining(" → ")) + " → EOF";
    }

    @Override
    String blockNote(int block) {
        int next = fat[block];
        if (next == FREE) {
            return "";
        }
        return next == EOF ? " → EOF" : String.format(" → próx.: %02d", next);
    }
}
