package fsim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processo simulado no sistema operacional.
 *
 * Processos críticos residem contiguamente na Área Reservada da RAM,
 * enquanto processos normais residem na Área Paginada gerenciados por paginação sob demanda.
 * Na Etapa 5, incorpora ciclo de vida completo (NOVO, PRONTO, EXECUTANDO, BLOQUEADO, CONCLUÍDO),
 * métricas de escalonamento de CPU, geração de syscalls e suporte a múltiplas threads.
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

    enum State {
        NOVO("NOVO"),
        PRONTO("PRONTO"),
        EXECUTANDO("EXECUTANDO"),
        BLOQUEADO("BLOQUEADO"),
        CONCLUIDO("CONCLUÍDO");

        final String label;

        State(String label) {
            this.label = label;
        }
    }

    record StateTransition(long timeMs, State from, State to, String reason) {}

    record SyscallEvent(String op, String targetPath, long triggerCpuMs, long durationMs, boolean randomGenerated) {
        boolean isWrite() {
            if (op == null) {
                return false;
            }
            String s = op.toLowerCase(Locale.ROOT);
            return s.contains("write") || s.contains("escrever") || s.contains("gravar");
        }

        static List<SyscallEvent> parseSpec(String spec, long burstMs, int pid) {
            if (spec == null || spec.isBlank()) {
                return List.of();
            }
            String trimmed = spec.trim();
            if (trimmed.equalsIgnoreCase("random")) {
                Random rnd = new Random(42L + pid);
                long maxTrigger = Math.max(1L, burstMs - 1L);
                long trigger = 1L;
                if (maxTrigger > 1L) {
                    trigger = 1L + (long) rnd.nextInt((int) Math.min((long) Integer.MAX_VALUE, maxTrigger));
                }
                long duration = 2L + (long) rnd.nextInt(4); // 2..5 ms
                return List.of(new SyscallEvent("read_disk", null, trigger, duration, true));
            }

            Pattern pattern = Pattern.compile(
                    "^\\s*([a-zA-Z0-9_-]+)(?:\\s+(?!at\\b)([^\\s]+))?\\s+at\\s+(\\d+)(?:ms)?(?:\\s*\\(?duration:\\s*(\\d+)(?:ms)?\\)?)?\\s*$",
                    Pattern.CASE_INSENSITIVE
            );

            List<SyscallEvent> list = new ArrayList<>();
            String[] parts = trimmed.split(";");
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                Matcher m = pattern.matcher(p);
                if (m.matches()) {
                    String op = m.group(1);
                    String target = m.group(2);
                    if (target != null && target.startsWith("\"") && target.endsWith("\"") && target.length() > 1) {
                        target = target.substring(1, target.length() - 1);
                    }
                    long trigger = Long.parseLong(m.group(3));
                    String durStr = m.group(4);
                    long duration = durStr != null ? Long.parseLong(durStr) : 3L;
                    list.add(new SyscallEvent(op, target, trigger, duration, false));
                } else {
                    throw new FSException("especificação de syscall inválida: '" + p
                            + "' (esperado: '<op> [caminho] at <cpu>ms (duration: <dur>ms)')");
                }
            }
            return list;
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

    // -------------------------------------------------------- metadados estáticos
    final int pid;
    final String name;
    final Type type;
    final long sizeBytes;
    final String owner;
    final String sourcePath; // null se sintético
    final boolean systemBase;
    final PageEntry[] pageTable;

    // ----------------------------------------------------- agendamento e execução
    boolean cpuConfigured;
    long burstMs;
    int priority = 2; // 0=crítica, 1=alta, 2=baixa
    long arrivalMs;
    boolean randomSyscall;
    final List<SyscallEvent> syscalls = new ArrayList<>();
    final Set<Integer> firedSyscalls = new HashSet<>();
    State state = State.NOVO;
    String blockReason;
    long unblockAtMs = -1L;
    long executedCpuMs;
    long readyWaitMs;
    long ioBlockedMs;
    long firstStartMs = -1L;
    long completionMs = -1L;
    final List<StateTransition> transitions = new ArrayList<>();
    final List<Integer> plannedPages = new ArrayList<>();
    int pageCursor;
    final List<SimThread> threads = new ArrayList<>();

    SimProcess(int pid, String name, Type type, long sizeBytes, String owner,
               String sourcePath, boolean systemBase, int pageSize) {
        this.pid = pid;
        this.name = name;
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.owner = owner;
        this.sourcePath = sourcePath;
        this.systemBase = systemBase;
        if (type == Type.CRITICAL) {
            this.priority = 0;
        }
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
        addThread("T1");
    }

    void configureCpu(long burstMs, int priority, long arrivalMs, List<SyscallEvent> syscalls,
                      boolean randomSyscall, List<Integer> pages, long currentClockMs) {
        this.cpuConfigured = true;
        this.burstMs = Math.max(1L, burstMs);
        this.priority = priority;
        this.arrivalMs = arrivalMs;
        this.randomSyscall = randomSyscall;
        this.syscalls.clear();
        if (syscalls != null) {
            this.syscalls.addAll(syscalls);
        }
        this.firedSyscalls.clear();
        this.plannedPages.clear();
        if (pages != null) {
            this.plannedPages.addAll(pages);
        }
        this.pageCursor = 0;
        this.executedCpuMs = 0L;
        this.readyWaitMs = 0L;
        this.ioBlockedMs = 0L;
        this.firstStartMs = -1L;
        this.completionMs = -1L;
        this.blockReason = null;
        this.unblockAtMs = -1L;
        this.state = State.NOVO;
        this.transitions.clear();
        this.transitions.add(new StateTransition(currentClockMs, null, State.NOVO, "Criação do processo"));
        this.threads.clear();
        addThread("T1");
    }

    void transitionTo(State next, long timeMs, String reason) {
        State from = this.state;
        this.state = next;
        this.transitions.add(new StateTransition(timeMs, from, next, reason));
        for (SimThread t : threads) {
            if (next == State.CONCLUIDO) {
                t.state = State.CONCLUIDO;
            } else if (next == State.BLOQUEADO && t.state == State.EXECUTANDO) {
                t.state = State.BLOQUEADO;
            } else if (next == State.EXECUTANDO && (t.state == State.PRONTO || t.state == State.NOVO)) {
                if (t.waitingMutex == null) {
                    t.state = State.EXECUTANDO;
                }
            } else if (next == State.PRONTO && t.state == State.EXECUTANDO) {
                t.state = State.PRONTO;
            }
        }
    }

    long remainingBurstMs() {
        return Math.max(0L, burstMs - executedCpuMs);
    }

    String priorityLabel() {
        return priority == 0 ? "Crítica" : priority == 1 ? "Alta" : "Baixa";
    }

    int nextLogicalPage() {
        if (!plannedPages.isEmpty()) {
            int page = plannedPages.get(pageCursor % plannedPages.size());
            pageCursor = (pageCursor + 1) % plannedPages.size();
            return page;
        }
        int total = Math.max(1, numPages());
        long val = Math.abs((long) pid * 31L + executedCpuMs);
        return (int) (val % total);
    }

    SyscallEvent pollDueSyscall() {
        for (int i = 0; i < syscalls.size(); i++) {
            if (!firedSyscalls.contains(i)) {
                SyscallEvent evt = syscalls.get(i);
                if (executedCpuMs >= evt.triggerCpuMs) {
                    firedSyscalls.add(i);
                    return evt;
                }
            }
        }
        return null;
    }

    SimThread addThread(String threadName) {
        int tid = threads.size() + 1;
        String nameToUse = (threadName == null || threadName.isBlank()) ? ("T" + tid) : threadName;
        SimThread thread = new SimThread(tid, nameToUse, this);
        threads.add(thread);
        return thread;
    }

    SimThread findThread(String threadName) {
        if (threadName == null) {
            return null;
        }
        for (SimThread t : threads) {
            if (t.name.equalsIgnoreCase(threadName)) {
                return t;
            }
        }
        return null;
    }

    boolean allActiveThreadsBlocked() {
        if (threads.isEmpty()) {
            return false;
        }
        boolean hasActive = false;
        for (SimThread t : threads) {
            if (t.state != State.CONCLUIDO) {
                hasActive = true;
                if (t.state != State.BLOQUEADO) {
                    return false;
                }
            }
        }
        return hasActive;
    }

    void resetExecution(long currentClockMs) {
        this.state = State.NOVO;
        this.blockReason = null;
        this.unblockAtMs = -1L;
        this.executedCpuMs = 0L;
        this.readyWaitMs = 0L;
        this.ioBlockedMs = 0L;
        this.firstStartMs = -1L;
        this.completionMs = -1L;
        this.pageCursor = 0;
        this.firedSyscalls.clear();
        this.transitions.clear();
        this.transitions.add(new StateTransition(currentClockMs, null, State.NOVO, "Reinício de execução"));
        for (SimThread t : threads) {
            t.reset();
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
