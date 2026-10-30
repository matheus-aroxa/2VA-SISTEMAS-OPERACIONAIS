package fsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Disco físico de um arranjo: blocos próprios, posição da cabeça e fila de requisições pendentes. */
final class PhysicalDisk {
    final int id;
    final byte[][] blocks;
    final List<IoRequest> queue = new ArrayList<>();
    boolean failed;
    int head;

    PhysicalDisk(int id, int numBlocks, int blockSize) {
        this.id = id;
        this.blocks = new byte[numBlocks][blockSize];
    }

    /** Lê um bloco físico; `enqueue` registra a requisição na fila do braço. */
    byte[] read(int track, boolean enqueue) {
        checkHealthy();
        if (enqueue) {
            queue.add(new IoRequest(IoRequest.Op.LEITURA, track));
        }
        return blocks[track].clone();
    }

    void write(int track, byte[] data, boolean enqueue) {
        checkHealthy();
        if (enqueue) {
            queue.add(new IoRequest(IoRequest.Op.GRAVACAO, track));
        }
        System.arraycopy(data, 0, blocks[track], 0, blocks[track].length);
    }

    /** Simula a falha física: o conteúdo é perdido. */
    void fail() {
        failed = true;
        for (byte[] b : blocks) {
            Arrays.fill(b, (byte) 0);
        }
        queue.clear();
    }

    private void checkHealthy() {
        if (failed) {
            throw new FSException("Disco " + (id + 1) + " com falha: operação física impossível");
        }
    }
}
