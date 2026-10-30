package fsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Camada física: um disco dividido em blocos de tamanho fixo. */
final class Disk {
    static final String RESERVED = "RESERVADO"; // superbloco / tabela de diretórios / FAT
    static final String INODE = "INODE";        // bloco de índice (i-node)
    static final String DATA = "DADO";          // bloco de dados de um arquivo

    static final class BlockInfo {
        final String kind;
        final Node node;
        final String label; // rótulo de blocos reservados
        int seq;            // posição do bloco dentro do arquivo (1 = primeiro)
        int used;           // bytes efetivamente ocupados no bloco

        BlockInfo(String kind, Node node, String label) {
            this.kind = kind;
            this.node = node;
            this.label = label;
        }
    }

    /** Lacuna de blocos livres consecutivos. */
    record Run(int start, int length) {
    }

    final int blockSize;
    final int numBlocks;
    final byte[][] data;
    final BlockInfo[] info;

    Disk(int blockSize, int numBlocks) {
        this.blockSize = blockSize;
        this.numBlocks = numBlocks;
        this.data = new byte[numBlocks][blockSize];
        this.info = new BlockInfo[numBlocks];
    }

    long capacity() {
        return (long) blockSize * numBlocks;
    }

    boolean isFree(int i) {
        return info[i] == null;
    }

    List<Integer> freeBlocks() {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < numBlocks; i++) {
            if (info[i] == null) {
                free.add(i);
            }
        }
        return free;
    }

    int freeCount() {
        int count = 0;
        for (BlockInfo b : info) {
            if (b == null) {
                count++;
            }
        }
        return count;
    }

    /** Lacunas livres; `extraFree` faz blocos ocupados contarem como livres. */
    List<Run> freeRuns(Set<Integer> extraFree) {
        List<Run> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < numBlocks; i++) {
            if (isFree(i) || extraFree.contains(i)) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                runs.add(new Run(start, i - start));
                start = -1;
            }
        }
        if (start >= 0) {
            runs.add(new Run(start, numBlocks - start));
        }
        return runs;
    }

    BlockInfo claim(int i, String kind, Node node, String label) {
        if (info[i] != null) {
            throw new IllegalStateException("bloco " + i + " já está ocupado");
        }
        info[i] = new BlockInfo(kind, node, label);
        return info[i];
    }

    void release(int i) {
        info[i] = null;
        Arrays.fill(data[i], (byte) 0);
    }

    void write(int i, byte[] src, int offset, int length) {
        Arrays.fill(data[i], (byte) 0);
        System.arraycopy(src, offset, data[i], 0, length);
    }
}
