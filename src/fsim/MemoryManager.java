package fsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fachada de coordenação do subsistema de memória híbrida, unificando a área reservada
 * de alocação contígua (processos críticos e núcleo) e a memória virtual paginada sob demanda.
 */
final class MemoryManager {
    static final long DEFAULT_RESERVED_BYTES = 4L * 1024 * 1024; // 4 MB
    static final long DEFAULT_KERNEL_BASE_BYTES = 1024L * 1024;  // 1 MB
    static final long DEFAULT_DYNAMIC_BYTES = 64L * 1024;        // 64 KB
    static final int DEFAULT_PAGE_SIZE = 4096;                   // 4 KB

    private static final Pattern PAGE_PATTERN = Pattern.compile("(?i)(?:página|pagina|page)\\s*(\\d+)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b");

    record TerminationResult(SimProcess process, ReservedArena.Segment freedSegment,
                             int freedFrames, int freedSwapSlots) {
    }

    ReservedArena reserved;
    PagedMemory paged;
    int watchInterval = 0; // 0 = desligado; N > 0 = a cada N operações
    long opCounter = 0;

    private int nextPid = 101;
    private final Map<Integer, SimProcess> processesByPid = new LinkedHashMap<>();
    private final Map<String, SimProcess> processesByName = new LinkedHashMap<>();

    MemoryManager() {
        this.reserved = new ReservedArena(DEFAULT_RESERVED_BYTES, DEFAULT_KERNEL_BASE_BYTES, "first");
        int frames = (int) (DEFAULT_DYNAMIC_BYTES / DEFAULT_PAGE_SIZE);
        this.paged = new PagedMemory(DEFAULT_PAGE_SIZE, frames, PageReplacer.Policy.LRU);
    }

    void configure(FileSystem fs, long reservedBytes, long dynamicBytes,
                   int pageSize, String fit, PageReplacer.Policy policy) {
        fs.auth.requireKernel("memory_format");
        if (pageSize < 64 || pageSize > 65536) {
            throw new FSException("tamanho de página inválido (use 64B a 64KB)");
        }
        if (reservedBytes < 0 || dynamicBytes < pageSize) {
            throw new FSException("tamanhos de memória inválidos");
        }
        // limpa processos de usuários
        processesByPid.clear();
        processesByName.clear();
        nextPid = 101;
        long kBase = Math.min(DEFAULT_KERNEL_BASE_BYTES, reservedBytes);
        this.reserved = new ReservedArena(reservedBytes, kBase, fit != null ? fit : "first");
        int frames = (int) (dynamicBytes / pageSize);
        this.paged = new PagedMemory(pageSize, frames, policy != null ? policy : PageReplacer.Policy.LRU);
        fs.audit.record("MEMORIA", fs.auth.currentUser().name(), fs.auth.mode().name(),
                "memória reconfigurada: reservada=" + Units.fmtSize(reservedBytes)
                        + ", dinamica=" + Units.fmtSize(dynamicBytes) + ", pagina=" + pageSize);
    }

    void setPolicy(FileSystem fs, PageReplacer.Policy policy) {
        fs.auth.requireKernel("page_policy");
        paged.policy = policy;
        fs.audit.record("MEMORIA", fs.auth.currentUser().name(), fs.auth.mode().name(),
                "política de substituição alterada para " + policy.label);
    }

    void setWatchInterval(FileSystem fs, int interval) {
        fs.auth.requireKernel("memwatch");
        this.watchInterval = Math.max(0, interval);
    }

    boolean tickWatch() {
        opCounter++;
        return watchInterval > 0 && (opCounter % watchInterval == 0);
    }

    SimProcess createProcess(FileSystem fs, String name, SimProcess.Type type,
                             Long sizeBytes, String filePath) {
        if (name == null || name.isBlank()) {
            throw new FSException("o nome do processo não pode ser vazio");
        }
        if (processesByName.containsKey(name)
                || (reserved.kernelBaseProcess != null && name.equals(reserved.kernelBaseProcess.name))) {
            throw new FSException("processo já existe: '" + name + "'");
        }
        if (type == SimProcess.Type.CRITICAL) {
            fs.auth.requireKernel("create_process critical");
        }
        byte[] fileData = null;
        if (filePath != null) {
            fileData = fs.read(filePath); // valida permissões, decriptografia e quarentena do FS!
            sizeBytes = (long) fileData.length;
        } else {
            if (sizeBytes == null || sizeBytes <= 0) {
                throw new FSException("informe --size ou --file com tamanho maior que zero");
            }
        }
        int pid = nextPid++;
        String owner = fs.auth.currentUser().name();
        SimProcess proc = new SimProcess(pid, name, type, sizeBytes, owner, filePath, false, paged.pageSize);
        if (type == SimProcess.Type.CRITICAL) {
            reserved.allocate(proc);
        } else {
            paged.initProcessPages(proc, fileData != null ? fileData : new byte[sizeBytes.intValue()]);
        }
        processesByPid.put(pid, proc);
        processesByName.put(name, proc);
        fs.audit.record("MEMORIA", owner, fs.auth.mode().name(),
                "processo criado: PID " + pid + " '" + name + "' (" + type.label + ", " + Units.fmtSize(sizeBytes) + ")");
        return proc;
    }

    TerminationResult terminateProcess(FileSystem fs, String nameOrPid) {
        SimProcess proc = findProcess(nameOrPid);
        if (proc.systemBase) {
            throw new FSException("não é permitido finalizar o 'Núcleo Base do SO'");
        }
        if (!fs.auth.isKernel() && !fs.auth.currentUser().name().equals(proc.owner)) {
            throw new FSException("permissão negada: apenas o proprietário ('" + proc.owner + "') ou root pode finalizar '" + proc.name + "'");
        }
        if (proc.type == SimProcess.Type.CRITICAL) {
            fs.auth.requireKernel("terminate_process critical");
            ReservedArena.Segment freed = reserved.free(proc);
            processesByPid.remove(proc.pid);
            processesByName.remove(proc.name);
            fs.audit.record("MEMORIA", fs.auth.currentUser().name(), fs.auth.mode().name(),
                    "processo crítico finalizado: PID " + proc.pid + " '" + proc.name + "'");
            return new TerminationResult(proc, freed, 0, 0);
        } else {
            int[] rel = paged.releaseProcess(proc);
            processesByPid.remove(proc.pid);
            processesByName.remove(proc.name);
            fs.audit.record("MEMORIA", fs.auth.currentUser().name(), fs.auth.mode().name(),
                    "processo finalizado: PID " + proc.pid + " '" + proc.name + "'");
            return new TerminationResult(proc, null, rel[0], rel[1]);
        }
    }

    SimProcess findProcess(String nameOrPid) {
        if (nameOrPid == null || nameOrPid.isBlank()) {
            throw new FSException("processo não especificado");
        }
        if (reserved.kernelBaseProcess != null
                && (nameOrPid.equals(reserved.kernelBaseProcess.name) || nameOrPid.equals("0"))) {
            return reserved.kernelBaseProcess;
        }
        try {
            int pid = Integer.parseInt(nameOrPid);
            SimProcess p = processesByPid.get(pid);
            if (p != null) {
                return p;
            }
        } catch (NumberFormatException ignored) {
        }
        SimProcess p = processesByName.get(nameOrPid);
        if (p == null) {
            throw new FSException("processo não encontrado: '" + nameOrPid + "'");
        }
        return p;
    }

    List<SimProcess> processes() {
        List<SimProcess> list = new ArrayList<>();
        if (reserved.kernelBaseProcess != null) {
            list.add(reserved.kernelBaseProcess);
        }
        list.addAll(processesByPid.values());
        return Collections.unmodifiableList(list);
    }

    PagedMemory.Translation translate(FileSystem fs, String processRef, Integer page,
                                      int offset, Long vaddr, boolean loadOnMiss) {
        SimProcess proc = findProcess(processRef);
        if (proc.type == SimProcess.Type.CRITICAL) {
            throw new FSException("processos críticos usam endereçamento físico contíguo sem paginação");
        }
        int p = page != null ? page : (int) (vaddr / paged.pageSize);
        int off = page != null ? offset : (int) (vaddr % paged.pageSize);
        return paged.translate(proc, p, off, loadOnMiss, fs.disk.array);
    }

    PagedMemory.AccessResult executeStep(FileSystem fs, String processRef, String instruction,
                                         Integer explicitPage, boolean explicitWrite) {
        SimProcess proc = findProcess(processRef);
        if (proc.type == SimProcess.Type.CRITICAL) {
            throw new FSException("processos críticos operam na área reservada sem paginação");
        }
        int page = explicitPage != null ? explicitPage : parsePageFromInstruction(instruction);
        boolean isWrite = explicitWrite || isWriteInstruction(instruction);
        return paged.accessPage(proc, page, instruction != null ? instruction : "Página " + page, isWrite, fs.disk.array);
    }

    List<ReservedArena.Move> compactReserved(FileSystem fs) {
        fs.auth.requireKernel("memory_compact");
        List<ReservedArena.Move> moves = reserved.compact();
        fs.audit.record("MEMORIA", fs.auth.currentUser().name(), fs.auth.mode().name(),
                "compactação da área reservada: " + moves.size() + " processos movidos");
        return moves;
    }

    private int parsePageFromInstruction(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = PAGE_PATTERN.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        Matcher m2 = NUMBER_PATTERN.matcher(text);
        if (m2.find()) {
            return Integer.parseInt(m2.group(1));
        }
        return 0;
    }

    private boolean isWriteInstruction(String text) {
        if (text == null) {
            return false;
        }
        String s = text.toUpperCase(Locale.ROOT);
        return s.contains("STORE") || s.contains("WRITE") || s.contains("GRAVAR") || s.contains("ESCREVER");
    }
}
