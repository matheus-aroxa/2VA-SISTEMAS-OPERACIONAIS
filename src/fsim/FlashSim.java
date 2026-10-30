package fsim;

import java.util.Arrays;
import java.util.Random;

/**
 * Simulação de SSD (memória flash) com mapeamento de páginas (FTL).
 *
 * Diferente do disco magnético, a flash não sobrescreve no lugar: uma página só volta a ser gravada depois que o
 * bloco de apagamento ao qual pertence é apagado inteiro. Por isso cada escrita lógica vai para a próxima página
 * livre, a versão anterior é invalidada e o coletor de lixo (GC) recicla o bloco com menos páginas válidas,
 * copiando essas páginas antes do apagamento. Leitura e escrita são feitas por página; o apagamento, por bloco.
 *
 * Balanceamento de desgaste (wear leveling): sem ele, o bloco livre escolhido é sempre o de menor índice e alguns
 * blocos acumulam apagamentos. Com ele, o bloco livre escolhido é o de menos apagamentos, e a vítima do GC é
 * desempatada pelo desgaste. (Não há migração de dados frios, então o balanceamento é dinâmico, não estático.)
 */
final class FlashSim {
    private static final int RESERVED_BLOCKS = 2; // overprovisioning: blocos fora da capacidade lógica

    private enum Page { LIVRE, VALIDA, INVALIDA }

    record Wear(int min, int max, double mean, double stdDev) {
    }

    final int numBlocks;
    final int pagesPerBlock;
    final int logicalPages;
    final boolean wearLeveling;

    private final int[] logicalToPhysical; // página lógica -> página física (-1 = nunca gravada)
    private final int[] physicalToLogical; // página física -> página lógica (-1 = nenhuma)
    private final Page[] pages;
    private final int[] erases;            // apagamentos por bloco (desgaste)
    private final int[] validPages;        // páginas válidas por bloco
    private final boolean[] inUse;
    private int active = -1;               // bloco que está sendo preenchido
    private int nextPage;
    private boolean collecting;

    long hostReads;
    long hostWrites;
    long flashWrites; // gravações físicas, incluindo as cópias do GC
    long gcCopies;
    long erasures;

    FlashSim(int numBlocks, int pagesPerBlock, boolean wearLeveling) {
        if (numBlocks <= RESERVED_BLOCKS || pagesPerBlock < 2) {
            throw new FSException("SSD: são necessários mais de 2 blocos e pelo menos 2 páginas por bloco");
        }
        this.numBlocks = numBlocks;
        this.pagesPerBlock = pagesPerBlock;
        this.wearLeveling = wearLeveling;
        this.logicalPages = (numBlocks - RESERVED_BLOCKS) * pagesPerBlock;
        int physicalPages = numBlocks * pagesPerBlock;
        this.logicalToPhysical = new int[logicalPages];
        this.physicalToLogical = new int[physicalPages];
        Arrays.fill(logicalToPhysical, -1);
        Arrays.fill(physicalToLogical, -1);
        this.pages = new Page[physicalPages];
        Arrays.fill(pages, Page.LIVRE);
        this.erases = new int[numBlocks];
        this.validPages = new int[numBlocks];
        this.inUse = new boolean[numBlocks];
    }

    /** Leitura de uma página lógica; devolve a página física ou -1 se ela nunca foi gravada. */
    int read(int lpn) {
        checkLpn(lpn);
        hostReads++;
        return logicalToPhysical[lpn];
    }

    /** Escrita de uma página lógica: sempre em página nova (out-of-place). */
    void write(int lpn) {
        checkLpn(lpn);
        hostWrites++;
        int old = logicalToPhysical[lpn];
        if (old >= 0) {
            invalidate(old);
        }
        program(lpn);
    }

    /**
     * Carga de trabalho: grava todas as páginas uma vez (dados frios) e depois `updates` atualizações, 90% delas
     * em 10% das páginas (dados quentes). A mesma semente gera a mesma sequência para cada configuração.
     */
    void replay(int updates, long seed) {
        for (int lpn = 0; lpn < logicalPages; lpn++) {
            write(lpn);
        }
        Random rnd = new Random(seed);
        int hot = Math.max(1, logicalPages / 10);
        for (int i = 0; i < updates; i++) {
            write(rnd.nextInt(100) < 90 ? rnd.nextInt(hot) : rnd.nextInt(logicalPages));
        }
    }

    int[] eraseCounts() {
        return erases.clone();
    }

    Wear wear() {
        int min = Integer.MAX_VALUE;
        int max = 0;
        double sum = 0;
        for (int e : erases) {
            min = Math.min(min, e);
            max = Math.max(max, e);
            sum += e;
        }
        double mean = sum / numBlocks;
        double variance = 0;
        for (int e : erases) {
            variance += (e - mean) * (e - mean);
        }
        return new Wear(min, max, mean, Math.sqrt(variance / numBlocks));
    }

    double writeAmplification() {
        return hostWrites == 0 ? 0 : flashWrites / (double) hostWrites;
    }

    // ------------------------------------------------------------- internos

    private void invalidate(int ppn) {
        pages[ppn] = Page.INVALIDA;
        validPages[ppn / pagesPerBlock]--;
    }

    private void program(int lpn) {
        if (active < 0 || nextPage == pagesPerBlock) {
            if (!collecting && freeBlocks() <= 1) {
                collect(); // mantém um bloco livre de reserva para as cópias do GC
            }
            if (active < 0 || nextPage == pagesPerBlock) {
                active = takeFreeBlock();
                nextPage = 0;
            }
        }
        int ppn = active * pagesPerBlock + nextPage++;
        pages[ppn] = Page.VALIDA;
        physicalToLogical[ppn] = lpn;
        logicalToPhysical[lpn] = ppn;
        validPages[active]++;
        flashWrites++;
    }

    private int takeFreeBlock() {
        int pick = -1;
        for (int b = 0; b < numBlocks; b++) {
            if (!inUse[b] && (pick < 0 || wearLeveling && erases[b] < erases[pick])) {
                pick = b;
            }
        }
        if (pick < 0) {
            throw new FSException("SSD sem blocos livres para gravar");
        }
        inUse[pick] = true;
        return pick;
    }

    /** Coleta de lixo: recicla o bloco com menos páginas válidas (exceto o que está sendo preenchido). */
    private void collect() {
        int victim = -1;
        for (int b = 0; b < numBlocks; b++) {
            if (!inUse[b] || b == active) {
                continue;
            }
            boolean better = victim < 0 || validPages[b] < validPages[victim]
                    || wearLeveling && validPages[b] == validPages[victim] && erases[b] < erases[victim];
            if (better) {
                victim = b;
            }
        }
        if (victim < 0) {
            throw new FSException("SSD: o GC não encontrou bloco para reciclar");
        }
        collecting = true;
        int first = victim * pagesPerBlock;
        for (int ppn = first; ppn < first + pagesPerBlock; ppn++) {
            if (pages[ppn] == Page.VALIDA) {
                int lpn = physicalToLogical[ppn];
                invalidate(ppn);
                gcCopies++;
                program(lpn);
            }
        }
        Arrays.fill(pages, first, first + pagesPerBlock, Page.LIVRE);
        Arrays.fill(physicalToLogical, first, first + pagesPerBlock, -1);
        erases[victim]++;
        erasures++;
        inUse[victim] = false;
        validPages[victim] = 0;
        collecting = false;
    }

    private int freeBlocks() {
        int free = 0;
        for (boolean used : inUse) {
            if (!used) {
                free++;
            }
        }
        return free;
    }

    private void checkLpn(int lpn) {
        if (lpn < 0 || lpn >= logicalPages) {
            throw new FSException("página lógica fora da capacidade do SSD: " + lpn);
        }
    }
}
