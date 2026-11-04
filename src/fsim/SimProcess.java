package fsim;

/**
 * Processo simulado no sistema operacional.
 *
 * Processos críticos residem contiguamente na Área Reservada da RAM,
 * enquanto processos normais residem na Área Paginada gerenciados por paginação sob demanda.
 */
final class SimProcess {

    enum Type {
        CRITICAL("critical", "CRÍTICO"),
        NORMAL("normal", "NORMAL");

        final String key;
        final String label;

        Type(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static Type parse(String text) {
            if (text == null || text.isBlank() || text.equalsIgnoreCase("normal")
                    || text.equalsIgnoreCase("user") || text.equalsIgnoreCase("usuario")
                    || text.equalsIgnoreCase("usuário")) {
                return NORMAL;
            }
            if (text.equalsIgnoreCase("critical") || text.equalsIgnoreCase("critico")
                    || text.equalsIgnoreCase("crítico") || text.equalsIgnoreCase("kernel")) {
                return CRITICAL;
            }
            throw new FSException("tipo de processo inválido: '" + text + "' (use 'critical' ou 'normal')");
        }
    }

    static final class PageEntry {
        final int logicalPage;
        final int usedBytes;
        int frame = -1;
        boolean present;
        boolean referenced;
        boolean modified;
        boolean inSwap;
        int swapSlot = -1;
        long loadedAtMs;
        long lastAccessMs;
        int accessCount;

        PageEntry(int logicalPage, int usedBytes) {
            this.logicalPage = logicalPage;
            this.usedBytes = usedBytes;
        }
    }

    final int pid;
    final String name;
    final Type type;
    final long sizeBytes;
    final String owner;
    final String sourcePath; // null se sintético
    final boolean systemBase;
    final PageEntry[] pageTable;

    SimProcess(int pid, String name, Type type, long sizeBytes, String owner,
               String sourcePath, boolean systemBase, int pageSize) {
        this.pid = pid;
        this.name = name;
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.owner = owner;
        this.sourcePath = sourcePath;
        this.systemBase = systemBase;
        if (type == Type.NORMAL && pageSize > 0) {
            int numPages = sizeBytes == 0 ? 0 : (int) ((sizeBytes + pageSize - 1) / pageSize);
            this.pageTable = new PageEntry[numPages];
            for (int i = 0; i < numPages; i++) {
                int used = (i == numPages - 1 && sizeBytes % pageSize != 0)
                        ? (int) (sizeBytes % pageSize)
                        : pageSize;
                this.pageTable[i] = new PageEntry(i, used);
            }
        } else {
            this.pageTable = new PageEntry[0];
        }
    }

    int numPages() {
        return pageTable.length;
    }

    long internalFragmentation(int pageSize) {
        if (type == Type.NORMAL && numPages() > 0) {
            return pageSize - pageTable[pageTable.length - 1].usedBytes;
        }
        return 0L;
    }

    int residentCount() {
        int count = 0;
        for (PageEntry p : pageTable) {
            if (p.present) {
                count++;
            }
        }
        return count;
    }

    int swappedCount() {
        int count = 0;
        for (PageEntry p : pageTable) {
            if (p.inSwap) {
                count++;
            }
        }
        return count;
    }
}
