package fsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Arranjo RAID. Expõe ao disco lógico um espaço contíguo de blocos e traduz cada bloco nas operações dos discos
 * físicos, conforme o nível:
 * <ul>
 *   <li>RAID 0: listras sem redundância; o bloco L vai para o disco L mod N;</li>
 *   <li>RAID 1: espelhamento; cada bloco é gravado em todos os discos e lido de um deles;</li>
 *   <li>RAID 4: listras com paridade em disco dedicado (sempre o último);</li>
 *   <li>RAID 5: listras com paridade distribuída, que gira de disco a cada faixa.</li>
 * </ul>
 * Em RAID 4 e 5 a paridade de uma faixa é o XOR dos blocos de dados; um disco com falha é recuperado pelo XOR
 * dos demais discos da mesma faixa. Uma escrita pequena usa leitura-modificação-escrita: nova paridade =
 * paridade antiga XOR dado antigo XOR dado novo.
 */
final class RaidArray {
    enum Level { RAID0, RAID1, RAID4, RAID5 }

    final Level level;
    final PhysicalDisk[] disks;
    final int blockSize;
    final int logicalBlocks;
    final int blocksPerDisk;

    RaidArray(Level level, int numDisks, int blockSize, int logicalBlocks) {
        int minDisks = level == Level.RAID0 ? 1 : level == Level.RAID1 ? 2 : 3;
        if (numDisks < minDisks) {
            throw new FSException(label(level) + " exige pelo menos " + minDisks + " discos (informado: " + numDisks + ")");
        }
        this.level = level;
        this.blockSize = blockSize;
        this.logicalBlocks = logicalBlocks;
        int width = dataDisks(level, numDisks);
        this.blocksPerDisk = (logicalBlocks + width - 1) / width;
        this.disks = new PhysicalDisk[numDisks];
        for (int i = 0; i < numDisks; i++) {
            disks[i] = new PhysicalDisk(i, blocksPerDisk, blockSize);
        }
    }

    static RaidArray.Level parseLevel(String text) {
        return switch (text.replaceAll("(?i)raid\\s*", "").toUpperCase(Locale.ROOT)) {
            case "0" -> Level.RAID0;
            case "1" -> Level.RAID1;
            case "4" -> Level.RAID4;
            case "5" -> Level.RAID5;
            default -> throw new FSException("nível RAID inválido: '" + text + "' (use 0, 1, 4 ou 5)");
        };
    }

    static String label(Level level) {
        return "RAID " + level.name().substring(4);
    }

    String label() {
        return label(level) + " (" + disks.length + " discos)";
    }

    /** Número de discos que guardam dados em cada faixa. */
    private static int dataDisks(Level level, int numDisks) {
        return switch (level) {
            case RAID0 -> numDisks;
            case RAID1 -> 1;
            case RAID4, RAID5 -> numDisks - 1;
        };
    }

    int dataDisks() {
        return dataDisks(level, disks.length);
    }

    /** Disco de paridade da faixa (RAID 4: sempre o último; RAID 5: gira a cada faixa). */
    int parityDisk(int stripe) {
        int n = disks.length;
        return level == Level.RAID4 ? n - 1 : (n - 1) - stripe % n;
    }

    /** Disco físico que guarda a k-ésima unidade de dados da faixa, pulando o disco de paridade. */
    private int dataDisk(int stripe, int k) {
        if (level == Level.RAID0) {
            return k;
        }
        int p = parityDisk(stripe);
        return k < p ? k : k + 1;
    }

    /** Localização de um bloco lógico: {disco, bloco físico}. */
    private int[] locate(int lb) {
        int width = dataDisks();
        int stripe = lb / width;
        int k = lb % width;
        return switch (level) {
            case RAID0 -> new int[]{k, stripe};
            case RAID1 -> new int[]{0, lb};
            case RAID4, RAID5 -> new int[]{dataDisk(stripe, k), stripe};
        };
    }

    /** Bloco lógico guardado em (disco, bloco físico), ou -1 quando a célula é paridade. */
    int logicalAt(int disk, int block) {
        int width = dataDisks();
        return switch (level) {
            case RAID0 -> block * width + disk;
            case RAID1 -> block;
            case RAID4, RAID5 -> {
                int p = parityDisk(block);
                yield disk == p ? -1 : block * width + (disk < p ? disk : disk - 1);
            }
        };
    }

    // ------------------------------------------------------------- E/S lógica

    /** Lê um bloco lógico; com disco falho, RAID 1 usa o espelho e RAID 4/5 reconstroem por XOR. */
    byte[] read(int lb, boolean enqueue) {
        checkRange(lb);
        int[] loc = locate(lb);
        return switch (level) {
            case RAID0 -> {
                if (disks[loc[0]].failed) {
                    throw new FSException("perda de dados: disco " + (loc[0] + 1)
                            + " falhou e o RAID 0 não tem redundância");
                }
                yield disks[loc[0]].read(loc[1], enqueue);
            }
            case RAID1 -> {
                for (int i = 0; i < disks.length; i++) {
                    PhysicalDisk d = disks[(lb + i) % disks.length];
                    if (!d.failed) {
                        yield d.read(lb, enqueue);
                    }
                }
                throw new FSException("perda de dados: todos os espelhos falharam");
            }
            case RAID4, RAID5 -> disks[loc[0]].failed
                    ? reconstruct(loc[0], loc[1], enqueue)
                    : disks[loc[0]].read(loc[1], enqueue);
        };
    }

    /** Grava um bloco lógico (`data` tem exatamente `blockSize` bytes). */
    void write(int lb, byte[] data, boolean enqueue) {
        checkRange(lb);
        int[] loc = locate(lb);
        switch (level) {
            case RAID0 -> {
                if (disks[loc[0]].failed) {
                    throw new FSException("disco " + (loc[0] + 1) + " falhou: RAID 0 não aceita a gravação");
                }
                disks[loc[0]].write(loc[1], data, enqueue);
            }
            case RAID1 -> {
                boolean any = false;
                for (PhysicalDisk d : disks) {
                    if (!d.failed) {
                        d.write(lb, data, enqueue);
                        any = true;
                    }
                }
                if (!any) {
                    throw new FSException("todos os espelhos falharam: gravação impossível");
                }
            }
            case RAID4, RAID5 -> writeParity(loc[0], loc[1], data, enqueue);
        }
    }

    private void writeParity(int d, int block, byte[] data, boolean enqueue) {
        int pd = parityDisk(block);
        if (disks[pd].failed) {
            // sem paridade disponível: grava só o dado; a paridade será refeita na reconstrução
            disks[d].write(block, data, enqueue);
            return;
        }
        if (disks[d].failed) {
            // dado perdido: a nova paridade é o XOR do novo dado com os demais dados da faixa
            byte[] parity = data.clone();
            for (int other = 0; other < disks.length; other++) {
                if (other != d && other != pd) {
                    xor(parity, disks[other].read(block, enqueue));
                }
            }
            disks[pd].write(block, parity, enqueue);
            return;
        }
        // leitura-modificação-escrita: paridade' = paridade XOR dado_antigo XOR dado_novo
        byte[] oldData = disks[d].read(block, enqueue);
        byte[] parity = disks[pd].read(block, enqueue);
        xor(parity, oldData);
        xor(parity, data);
        disks[d].write(block, data, enqueue);
        disks[pd].write(block, parity, enqueue);
    }

    /** Descarta as filas pendentes e volta as cabeças para a trilha 0 (usado na formatação). */
    void resetQueues() {
        for (PhysicalDisk d : disks) {
            d.queue.clear();
            d.head = 0;
        }
    }

    // -------------------------------------------------------- falha e recuperação

    void fail(int disk) {
        disks[disk].fail();
    }

    /**
     * Reconstrói o disco substituído: RAID 1 copia do espelho; RAID 4/5 recalcula cada bloco pelo XOR dos demais.
     * A reconstrução não entra na fila de E/S (é operação de manutenção). Retorna os blocos recalculados.
     */
    int rebuild(int disk) {
        PhysicalDisk target = disks[disk];
        if (!target.failed) {
            throw new FSException("o disco " + (disk + 1) + " não está com falha");
        }
        if (level == Level.RAID0) {
            throw new FSException("RAID 0 não tem como reconstruir o disco " + (disk + 1));
        }
        List<byte[]> contents = new ArrayList<>();
        for (int p = 0; p < blocksPerDisk; p++) {
            contents.add(level == Level.RAID1 ? mirror(disk, p) : reconstruct(disk, p, false));
        }
        target.failed = false;
        for (int p = 0; p < blocksPerDisk; p++) {
            target.write(p, contents.get(p), false);
        }
        return blocksPerDisk;
    }

    private byte[] mirror(int disk, int block) {
        for (PhysicalDisk d : disks) {
            if (d.id != disk && !d.failed) {
                return d.read(block, false);
            }
        }
        throw new FSException("todos os espelhos falharam");
    }

    /** XOR de todas as outras unidades da faixa: recupera a unidade do disco `missing`. */
    private byte[] reconstruct(int missing, int block, boolean enqueue) {
        byte[] out = new byte[blockSize];
        for (PhysicalDisk d : disks) {
            if (d.id == missing) {
                continue;
            }
            if (d.failed) {
                throw new FSException("dados irrecuperáveis: mais de um disco falhou na mesma faixa");
            }
            xor(out, d.read(block, enqueue));
        }
        return out;
    }

    private static void xor(byte[] target, byte[] other) {
        for (int i = 0; i < target.length; i++) {
            target[i] ^= other[i];
        }
    }

    private void checkRange(int lb) {
        if (lb < 0 || lb >= logicalBlocks) {
            throw new FSException("bloco lógico fora do arranjo: " + lb);
        }
    }
}
