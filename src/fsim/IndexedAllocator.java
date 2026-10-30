package fsim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alocação indexada: cada arquivo/diretório tem um i-node (bloco de índice) gravado no disco
 * com 4 bytes de contador seguidos dos ponteiros (4 bytes cada) para os blocos de dados.
 */
final class IndexedAllocator extends Allocator {
    private Map<Node, Integer> inodes; // nó -> bloco do i-node

    IndexedAllocator(Disk disk, String fit) {
        super(disk, fit);
    }

    @Override
    String key() {
        return "indexada";
    }

    @Override
    String title() {
        return "Alocação Indexada (i-node)";
    }

    @Override
    String mapTitle() {
        return "Indexado";
    }

    @Override
    String noCompactionReason() {
        return "NÃO (Blocos alocados via i-node indexado)";
    }

    @Override
    void format() {
        inodes = new HashMap<>();
    }

    /** i-node de nível único: 4 bytes de contador + 4 bytes por ponteiro. */
    int maxEntries() {
        return (disk.blockSize - 4) / 4;
    }

    @Override
    void createNode(Node node) {
        List<Integer> free = disk.freeBlocks();
        if (free.isEmpty()) {
            throw new DiskFullException("disco cheio: não há bloco livre para o i-node");
        }
        disk.claim(free.get(0), Disk.INODE, node, "");
        inodes.put(node, free.get(0));
        writeIndex(node, List.of());
    }

    @Override
    void deleteNode(Node node) {
        if (inodes.containsKey(node)) {
            resize(node, 0);
            disk.release(inodes.remove(node));
        }
    }

    private void writeIndex(Node node, List<Integer> blocks) {
        int b = inodes.get(node);
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 * blocks.size()).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(blocks.size());
        blocks.forEach(buf::putInt);
        disk.write(b, buf.array(), 0, buf.capacity());
        disk.info[b].used = buf.capacity();
    }

    @Override
    List<Integer> dataBlocks(Node node) {
        Integer b = inodes.get(node);
        List<Integer> blocks = new ArrayList<>();
        if (b == null) {
            return blocks;
        }
        ByteBuffer buf = ByteBuffer.wrap(disk.data[b]).order(ByteOrder.LITTLE_ENDIAN);
        int count = buf.getInt();
        for (int k = 0; k < count; k++) {
            blocks.add(buf.getInt());
        }
        return blocks;
    }

    @Override
    List<Integer> metaBlocks(Node node) {
        Integer b = inodes.get(node);
        return b == null ? List.of() : List.of(b);
    }

    @Override
    void resize(Node node, int n) {
        if (n > maxEntries()) {
            throw new FSException("arquivo excede a capacidade do i-node: máx. " + maxEntries() + " blocos de dados ("
                    + Units.fmtSize((long) maxEntries() * disk.blockSize) + ") com índice de nível único");
        }
        List<Integer> blocks = dataBlocks(node);
        if (n < blocks.size()) {
            for (int b : blocks.subList(n, blocks.size())) {
                disk.release(b);
            }
            blocks = new ArrayList<>(blocks.subList(0, n));
        } else if (n > blocks.size()) {
            needFree(n - blocks.size());
            List<Integer> fresh = disk.freeBlocks().subList(0, n - blocks.size());
            for (int b : fresh) {
                disk.claim(b, Disk.DATA, node, "");
            }
            blocks.addAll(fresh);
        }
        writeIndex(node, blocks);
    }

    @Override
    String describe(Node node) {
        return String.format("i-node no bloco %02d → índice [%s]", inodes.get(node), fmtBlocks(dataBlocks(node), 2));
    }
}
