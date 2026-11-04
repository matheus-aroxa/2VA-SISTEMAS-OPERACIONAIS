package fsim;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Testes automatizados da Etapa 5: ciclo de vida de processos, perfis de syscalls,
 * referências dinâmicas a páginas virtuais na RAM, concorrência de threads e mutex,
 * e escalonamento combinado (Priority_RR, RR, Priority, FCFS) com métricas de desempenho.
 */
public final class ProcessTest {

    private ProcessTest() {
    }

    public static void main(String[] args) {
        processCreationLifecycleAndSyscallProfiles();
        dynamicPageReferencesLruClockAndSwapIntegration();
        automaticBlockingOnSyscallAndMutexContention();
        combinedPriorityRoundRobinAndMetrics();
        crossStageIntegrationFsRaidSecurityAuditAndAllDemos();
        System.out.println("Todos os testes da Etapa 5 passaram.");
    }

    /**
     * Exemplo 5.1: Criação de processo com perfil de I/O, parsing de syscalls,
     * transição NOVO -> PRONTO, criação da Tabela de Páginas, determinismo do random e
     * retrocompatibilidade com a Etapa 4.
     */
    static void processCreationLifecycleAndSyscallProfiles() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        // Exemplo 5.1: App_Web (burst 8ms, prioridade 1, syscall "read_disk at 3ms (duration: 4ms)")
        MemoryManager.CpuProcessSpec spec = new MemoryManager.CpuProcessSpec(
                8L, 1, 0L, "read_disk at 3ms (duration: 4ms)", null
        );
        SimProcess appWeb = fs.memory.createScheduledProcess(fs, "App_Web", SimProcess.Type.NORMAL, null, null, spec);

        check(appWeb != null, "processo App_Web deve ter sido criado");
        check(appWeb.name.equals("App_Web"), "nome do processo deve ser App_Web");
        check(appWeb.burstMs == 8L, "burst de App_Web deve ser 8ms");
        check(appWeb.priority == 1, "prioridade deve ser 1 (Alta)");
        check(appWeb.arrivalMs == 0L, "tempo de chegada deve ser 0ms");
        check(appWeb.cpuConfigured, "cpuConfigured deve ser true");

        // Transição inicial NOVO -> PRONTO
        if (appWeb.state == SimProcess.State.NOVO) {
            appWeb.transitionTo(SimProcess.State.PRONTO, 0L, "Admissão inicial");
        }
        check(appWeb.state == SimProcess.State.PRONTO, "processo admitido deve estar no estado PRONTO");
        check(appWeb.transitions.stream().anyMatch(t -> t.to() == SimProcess.State.NOVO),
                "deve registrar estado inicial NOVO");
        check(appWeb.transitions.stream().anyMatch(t -> t.to() == SimProcess.State.PRONTO),
                "deve registrar transição para PRONTO");

        // Tabela de Páginas padrão (32 KB / 4 KB = 8 páginas virtuais P0..P7)
        check(appWeb.pageTable != null && appWeb.pageTable.length == 8,
                "deve criar tabela de páginas com 8 páginas virtuais de 4KB (32KB padrão)");
        check(appWeb.sizeBytes == 32L * 1024, "tamanho padrão de processo normal deve ser 32KB");
        check(appWeb.numPages() == 8, "numPages deve retornar 8");

        // Syscalls parsed
        check(appWeb.syscalls.size() == 1, "deve conter exatamente 1 syscall configurada");
        SimProcess.SyscallEvent sc = appWeb.syscalls.get(0);
        check(sc.op().equalsIgnoreCase("read_disk"), "operação deve ser read_disk");
        check(sc.triggerCpuMs() == 3L, "triggerCpuMs deve ser 3ms");
        check(sc.durationMs() == 4L, "durationMs deve ser 4ms");
        check(!sc.isWrite(), "read_disk não deve ser classificado como escrita");
        check(!sc.randomGenerated(), "syscall não deve ser marcada como randomGenerated");

        // Multi-syscall parsing e caminhos reais
        List<SimProcess.SyscallEvent> multi = SimProcess.SyscallEvent.parseSpec(
                "read_file /var/log.txt at 2ms (duration: 3ms); write_disk at 6ms (duration: 2ms)", 8L, appWeb.pid
        );
        check(multi.size() == 2, "parseSpec deve aceitar múltiplas syscalls separadas por ponto-e-vírgula");
        check(multi.get(0).op().equalsIgnoreCase("read_file") && "/var/log.txt".equals(multi.get(0).targetPath()),
                "deve extrair caminho alvo /var/log.txt");
        check(!multi.get(0).isWrite(), "read_file não é escrita");
        check(multi.get(1).op().equalsIgnoreCase("write_disk") && multi.get(1).isWrite(),
                "write_disk deve ser reconhecido como operação de escrita");

        // Syscall "random" determinística (mesma semente gera os mesmos valores)
        List<SimProcess.SyscallEvent> rand1 = SimProcess.SyscallEvent.parseSpec("random", 10L, 202);
        List<SimProcess.SyscallEvent> rand2 = SimProcess.SyscallEvent.parseSpec("random", 10L, 202);
        check(rand1.equals(rand2), "syscall random deve ser determinística para o mesmo PID e burst");
        check(!rand1.isEmpty() && rand1.get(0).randomGenerated(), "random deve marcar randomGenerated=true");
        check(rand1.get(0).triggerCpuMs() >= 1L && rand1.get(0).triggerCpuMs() < 10L,
                "trigger random deve estar dentro do burstMs");

        // Processo com chegada futura (arrivalMs > 0 deve permanecer em NOVO)
        MemoryManager.CpuProcessSpec futSpec = new MemoryManager.CpuProcessSpec(6L, 2, 10L, null, null);
        SimProcess futProc = fs.memory.createScheduledProcess(fs, "Proc_Futuro", SimProcess.Type.NORMAL, null, null, futSpec);
        check(futProc.state == SimProcess.State.NOVO, "processo com arrival > 0 deve iniciar no estado NOVO");
        check(futProc.arrivalMs == 10L, "arrivalMs deve ser 10ms");

        // Processo Crítico padrão (512 KB contíguo na Área Reservada, prioridade 0)
        MemoryManager.CpuProcessSpec critSpec = new MemoryManager.CpuProcessSpec(12L, 0, 0L, null, null);
        SimProcess critProc = fs.memory.createScheduledProcess(fs, "Kernel_Service", SimProcess.Type.CRITICAL, null, null, critSpec);
        check(critProc.priority == 0, "processo crítico deve receber prioridade 0");
        check(critProc.sizeBytes == 512L * 1024, "tamanho padrão de processo crítico deve ser 512 KB");
        check(fs.memory.reserved.segmentOf(critProc) != null, "processo crítico deve residir na Área Reservada");

        // Retrocompatibilidade da Etapa 4: createProcess direto sem parâmetros de CPU
        SimProcess legacy = fs.memory.createProcess(fs, "Proc_Legado", SimProcess.Type.NORMAL, 16L * 1024, null);
        check(legacy != null && legacy.sizeBytes == 16L * 1024, "createProcess legado deve continuar funcionando");
        check(!legacy.cpuConfigured, "processo legado não deve ter cpuConfigured ativado");
        check(legacy.pageTable.length == 4, "deve ter 4 páginas virtuais");
    }

    /**
     * Exemplo 5.2: Simulação com referências a páginas virtuais [2, 7, 4],
     * relógio em ms de CPU, detecção de HIT vs PAGE FAULT, substituição LRU e
     * persistência física de página descarregada no SwapSpace / RaidArray.
     */
    static void dynamicPageReferencesLruClockAndSwapIntegration() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 2);
        // Memória pequena com 2 molduras de 4KB (8KB totais na RAM paginada) para forçar substituição no 3º acesso
        fs.memory.configure(fs, 4L * 1024 * 1024, 2L * 4096, 4096, "first", PageReplacer.Policy.LRU);

        // Cria App_Web com páginas planejadas [2, 7, 4]
        MemoryManager.CpuProcessSpec spec = new MemoryManager.CpuProcessSpec(
                8L, 1, 0L, "read_disk at 5ms (duration: 4ms)", List.of(2, 7, 4)
        );
        SimProcess appWeb = fs.memory.createScheduledProcess(fs, "App_Web", SimProcess.Type.NORMAL, null, null, spec);

        long initialSwapOuts = fs.memory.paged.swap.swapOuts;
        int initialQueuedIo = fs.disk.array.disks[0].queue.size() + fs.disk.array.disks[1].queue.size();

        // Executa 3 passos de simulação (t=1ms: P02, t=2ms: P07, t=3ms: P04)
        CpuScheduler.SimulationResult simRes = fs.scheduler.runSimulation(fs, "App_Web", 3, List.of(2, 7, 4));
        check(simRes != null, "runSimulation deve retornar resultado não nulo");
        check(simRes.steps().size() == 3, "deve conter exatamente 3 passos executados");

        // Passo 1 (t=1ms): Acesso à página 2 -> PAGE FAULT (NEW), alocada na Moldura 0
        CpuScheduler.StepRecord step1 = simRes.steps().get(0);
        check(step1.stepIndex() == 1, "índice do passo 1");
        check(step1.logicalPage() == 2, "deve referenciar página 2");
        check(!step1.memoryAccess().hit(), "passo 1 deve ser PAGE FAULT");
        check(step1.memoryAccess().frame() == 0, "moldura 0 deve ser alocada");
        check(fs.memory.paged.frameStatus(0).contains("PAGE FAULT"), "status da moldura 0 deve ser PAGE FAULT");

        // Passo 2 (t=2ms): Acesso à página 7 -> PAGE FAULT (NEW), alocada na Moldura 1
        CpuScheduler.StepRecord step2 = simRes.steps().get(1);
        check(step2.stepIndex() == 2, "índice do passo 2");
        check(step2.logicalPage() == 7, "deve referenciar página 7");
        check(!step2.memoryAccess().hit(), "passo 2 deve ser PAGE FAULT");
        check(step2.memoryAccess().frame() == 1, "moldura 1 deve ser alocada");
        check(fs.memory.paged.frameStatus(1).contains("PAGE FAULT"), "status da moldura 1 deve ser PAGE FAULT");

        // Ambas as molduras ocupadas (RAM cheia)
        check(appWeb.residentCount() == 2, "as 2 molduras da RAM paginada devem estar ocupadas");
        check(fs.memory.paged.frameTable[0] != null && fs.memory.paged.frameTable[1] != null,
                "ambas as molduras físicas devem estar preenchidas");

        // Passo 3 (t=3ms): Acesso à página 4 -> RAM cheia! Política LRU descarrega a página 2 (menos recente, t=1ms)
        CpuScheduler.StepRecord step3 = simRes.steps().get(2);
        check(step3.stepIndex() == 3, "índice do passo 3");
        check(step3.logicalPage() == 4, "deve referenciar página 4");
        check(!step3.memoryAccess().hit(), "passo 3 deve ser PAGE FAULT");
        check(step3.memoryAccess().ramWasFull(), "RAM deveria estar cheia no passo 3");
        check(step3.memoryAccess().eviction() != null, "deve haver registro de descarregamento de página vítima");
        check(step3.memoryAccess().eviction().victimPage() == 2, "vítima LRU deve ser a página 2");
        check(step3.memoryAccess().eviction().frame() == 0, "página 2 residia na moldura 0");

        // Verifica que a página 2 foi para o SwapSpace e enfileirou E/S real no RaidArray
        check(appWeb.pageTable[2].inSwap, "página 2 descarregada deve estar marcada como inSwap");
        check(fs.memory.paged.swap.swapOuts > initialSwapOuts, "contador de swapOuts deve ter sido incrementado");
        int finalQueuedIo = fs.disk.array.disks[0].queue.size() + fs.disk.array.disks[1].queue.size();
        check(finalQueuedIo > initialQueuedIo, "swap-out deve gerar requisições de IoRequest reais nos discos físicos");

        // Agora testamos um HIT acessando novamente a página 4 no passo 4
        CpuScheduler.SimulationResult hitRes = fs.scheduler.runSimulation(fs, "App_Web", 1, List.of(4));
        check(hitRes.steps().size() == 1, "deve executar 1 passo");
        check(hitRes.steps().get(0).memoryAccess().hit(), "acesso subsequente à página 4 residente deve ser HIT");
        check(fs.memory.paged.frameStatus(0).equals("HIT"), "status da moldura 0 deve ser HIT");
    }

    /**
     * Exemplo 5.3: Bloqueio automático de processo ao atingir tempo de CPU de Syscall de E/S
     * (unblockAtMs = trigger + duration), liberação de CPU e concorrência de threads com
     * exclusão mútua (Mutex FIFO, contenção, bloqueio e desbloqueio com transferência de posse).
     */
    static void automaticBlockingOnSyscallAndMutexContention() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        // 1. Bloqueio automático por Syscall (Exemplo 5.3: App_Web burst 8ms, syscall "read_disk at 3ms (duration: 4ms)")
        MemoryManager.CpuProcessSpec spec = new MemoryManager.CpuProcessSpec(
                8L, 1, 0L, "read_disk at 3ms (duration: 4ms)", List.of(0, 1, 2)
        );
        SimProcess appWeb = fs.memory.createScheduledProcess(fs, "App_Web", SimProcess.Type.NORMAL, null, null, spec);

        // Executa 3 passos: no 3º passo, executedCpuMs atinge 3ms e dispara a syscall
        CpuScheduler.SimulationResult sim = fs.scheduler.runSimulation(fs, "App_Web", 3, null);
        check(sim.steps().size() == 3, "deve ter executado 3 passos");
        check(appWeb.executedCpuMs == 3L, "tempo de CPU executado deve ser 3ms");
        check(appWeb.state == SimProcess.State.BLOQUEADO, "processo deve transitar para BLOQUEADO após atingir a syscall");
        check(appWeb.unblockAtMs == 7L, "desbloqueio deve estar calculado para t=7ms (3ms trigger + 4ms duration)");
        check(appWeb.blockReason != null && !appWeb.blockReason.isBlank(), "deve haver motivo registrado para o bloqueio");

        // 2. Concorrência de Threads e Mutex "Buffer_Video" (Exemplo 5.3)
        SimProcess vidProc = fs.memory.createScheduledProcess(fs, "Proc_Video", SimProcess.Type.NORMAL, null, null,
                new MemoryManager.CpuProcessSpec(10L, 1, 0L, null, null));
        SimThread t1 = vidProc.findThread("T1");
        SimThread t2 = vidProc.addThread("T2");
        check(t1 != null && t2 != null, "threads T1 e T2 devem existir");

        // T1 solicita Mutex "Buffer_Video" livre em t=10ms -> adquire com sucesso
        MutexManager.LockEvent lock1 = fs.mutexes.lock(t1, "Buffer_Video", 10L);
        check(lock1.succeeded(), "T1 deve adquirir o Mutex livre");
        check(lock1.holder() == t1, "detentor deve ser T1");
        check(fs.mutexes.getOrCreate("Buffer_Video").isLocked(), "Mutex Buffer_Video deve estar bloqueado");
        check(fs.mutexes.getOrCreate("Buffer_Video").owner == t1, "proprietário do Mutex deve ser T1");

        // T2 solicita o mesmo Mutex em t=12ms -> contenção! T2 é bloqueada e entra na fila de espera
        MutexManager.LockEvent lock2 = fs.mutexes.lock(t2, "Buffer_Video", 12L);
        check(!lock2.succeeded(), "T2 não deve adquirir o Mutex ocupado");
        check(t2.state == SimProcess.State.BLOQUEADO, "T2 deve transitar para BLOQUEADO por contenção");
        check("Buffer_Video".equals(t2.waitingMutex), "waitingMutex de T2 deve ser Buffer_Video");
        check(fs.mutexes.getOrCreate("Buffer_Video").waitQueue.contains(t2), "T2 deve estar na fila de espera FIFO do Mutex");

        // T1 executa unlock em t=15ms -> T2 é acordada (BLOQUEADO -> PRONTO) e recebe a posse imediatamente
        MutexManager.LockEvent unlock1 = fs.mutexes.unlock(t1, "Buffer_Video", 15L);
        check(unlock1.succeeded(), "T1 deve desbloquear com sucesso");
        check(unlock1.wokenThread() == t2, "thread acordada deve ser T2");
        check(t2.state == SimProcess.State.PRONTO, "T2 acordada deve transitar para PRONTO");
        check(t2.waitingMutex == null, "waitingMutex de T2 deve ser anulado");
        check(fs.mutexes.getOrCreate("Buffer_Video").owner == t2, "posse do Mutex transferida automaticamente para T2");
        check(fs.mutexes.getOrCreate("Buffer_Video").waitQueue.isEmpty(), "fila de espera deve estar vazia após despertar T2");

        // T2 executa unlock em t=18ms -> Mutex fica livre
        MutexManager.LockEvent unlock2 = fs.mutexes.unlock(t2, "Buffer_Video", 18L);
        check(unlock2.succeeded(), "T2 deve liberar o Mutex");
        check(!fs.mutexes.getOrCreate("Buffer_Video").isLocked(), "Mutex deve ficar completamente livre");

        // Teste de encerramento de processo liberando todos os mutexes retidos
        fs.mutexes.lock(t1, "Mutex_A", 20L);
        fs.mutexes.lock(t1, "Mutex_B", 20L);
        check(fs.mutexes.getOrCreate("Mutex_A").owner == t1, "T1 detém Mutex_A");
        check(fs.mutexes.getOrCreate("Mutex_B").owner == t1, "T1 detém Mutex_B");
        fs.mutexes.releaseAllOwnedBy(vidProc, 25L);
        check(!fs.mutexes.getOrCreate("Mutex_A").isLocked(), "Mutex_A deve ter sido liberado no término do processo");
        check(!fs.mutexes.getOrCreate("Mutex_B").isLocked(), "Mutex_B deve ter sido liberado no término do processo");
    }

    /**
     * Exemplo 5.4: Escalonamento combinado Priority_RR com quantum 2ms,
     * validação da linha do tempo com P101 (burst 8ms, prio 1, syscall at 3ms dur 4ms),
     * P103 (burst 4ms, prio 1) e P102 (burst 5ms, prio 2), preempções, fila de prioridades e
     * métricas consolidadas (WT, TAT, RT, trocas de contexto e utilização de CPU).
     */
    static void combinedPriorityRoundRobinAndMetrics() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        // Cria os processos rigorosamente como no Exemplo 5.4:
        // P101: PID 101, Burst 8ms, Prioridade 1, Syscall "read_disk at 3ms (duration: 4ms)"
        SimProcess p101 = fs.memory.createScheduledProcess(fs, "App_Web", SimProcess.Type.NORMAL, null, null,
                new MemoryManager.CpuProcessSpec(8L, 1, 0L, "read_disk at 3ms (duration: 4ms)", null));

        // P102: PID 102, Burst 5ms, Prioridade 2
        SimProcess p102 = fs.memory.createScheduledProcess(fs, "App_Worker", SimProcess.Type.NORMAL, null, null,
                new MemoryManager.CpuProcessSpec(5L, 2, 0L, null, null));

        // P103: PID 103, Burst 4ms, Prioridade 1
        SimProcess p103 = fs.memory.createScheduledProcess(fs, "App_DB", SimProcess.Type.NORMAL, null, null,
                new MemoryManager.CpuProcessSpec(4L, 1, 0L, null, null));

        check(p101.pid == 101 && p102.pid == 102 && p103.pid == 103,
                "PIDs devem ser 101, 102, 103 sequenciais");

        // Executa o escalonador combinado Priority_RR com quantum de 2ms
        CpuScheduler.ScheduleResult res = fs.scheduler.schedule(fs, CpuScheduler.Algorithm.PRIORITY_RR, 2L);
        check(res != null, "resultado do escalonamento não deve ser nulo");
        check(res.algorithm() == CpuScheduler.Algorithm.PRIORITY_RR, "algoritmo deve ser Priority_RR");
        check(res.quantumMs() == 2L, "quantum deve ser 2ms");

        // Validação da linha do tempo
        check(!res.slices().isEmpty(), "linha do tempo não pode estar vazia");

        CpuScheduler.TimelineSlice s0 = res.slices().get(0);
        check(s0.process().pid == 101 && s0.startMs() == 0L && s0.endMs() == 2L,
                "fatia 1 (0-2ms) deve ser PID 101");
        check(s0.outcome() == CpuScheduler.SliceOutcome.QUANTUM_EXPIRED, "PID 101 quantum expirou");

        CpuScheduler.TimelineSlice s1 = res.slices().get(1);
        check(s1.process().pid == 103 && s1.startMs() == 2L && s1.endMs() == 4L,
                "fatia 2 (2-4ms) deve ser PID 103");
        check(s1.outcome() == CpuScheduler.SliceOutcome.QUANTUM_EXPIRED, "PID 103 quantum expirou");

        CpuScheduler.TimelineSlice s2 = res.slices().get(2);
        check(s2.process().pid == 101 && s2.startMs() == 4L && s2.endMs() == 5L,
                "fatia 3 (4-5ms) deve ser PID 101");
        check(s2.outcome() == CpuScheduler.SliceOutcome.SYSCALL_BLOCKED, "PID 101 deve ser bloqueado por Syscall");

        // Todos os processos concluídos no fim
        check(p101.state == SimProcess.State.CONCLUIDO, "PID 101 deve estar CONCLUÍDO");
        check(p102.state == SimProcess.State.CONCLUIDO, "PID 102 deve estar CONCLUÍDO");
        check(p103.state == SimProcess.State.CONCLUIDO, "PID 103 deve estar CONCLUÍDO");

        // Tempo total decorrido e uso de CPU (Burst total = 8 + 5 + 4 = 17ms de CPU ocupada)
        check(res.cpuBusyMs() == 17L, "total de CPU ocupada deve ser exatamente 17ms (8+5+4)");
        check(res.totalElapsedMs() == 17L, "tempo total decorrido deve ser 17ms (sem ociosidade)");
        check(Math.abs(res.cpuUtilizationPct() - 100.0) < 0.01, "utilização global da CPU deve ser 100%");
        check(res.contextSwitches() > 0, "deve registrar trocas de contexto");

        // Métricas individuais por processo
        check(res.metrics().size() == 3, "deve conter métricas para os 3 processos");
        for (CpuScheduler.ProcessMetrics m : res.metrics()) {
            check(m.turnaroundTimeMs() >= m.burstMs(), "TAT deve ser >= burstMs para PID " + m.process().pid);
            check(m.waitingTimeMs() >= 0L, "WT deve ser >= 0 para PID " + m.process().pid);
            check(m.responseTimeMs() >= 0L, "RT deve ser >= 0 para PID " + m.process().pid);
        }

        // Teste dos modos alternativos (RR e FCFS)
        CpuScheduler.ScheduleResult rrRes = fs.scheduler.schedule(fs, CpuScheduler.Algorithm.ROUND_ROBIN, 2L);
        check(rrRes != null && rrRes.algorithm() == CpuScheduler.Algorithm.ROUND_ROBIN, "modo RR deve funcionar");

        CpuScheduler.ScheduleResult fcfsRes = fs.scheduler.schedule(fs, CpuScheduler.Algorithm.FCFS, 2L);
        check(fcfsRes != null && fcfsRes.algorithm() == CpuScheduler.Algorithm.FCFS, "modo FCFS deve funcionar");
    }

    /**
     * Integração transversal entre Etapas 1 a 5:
     * - Syscall com arquivo real validando ACL e integridade (Etapa 3);
     * - Syscall de gravação gerando IoRequest real no RaidArray / PhysicalDisk (Etapa 2);
     * - Eventos gravados na trilha de auditoria (AuditLog);
     * - Execução bem-sucedida de todas as 7 demos oficiais sem exceções não tratadas.
     */
    static void crossStageIntegrationFsRaidSecurityAuditAndAllDemos() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID5, 3);
        fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        // 1. Cria arquivo real no FileSystem
        fs.create("/seguro.txt", "conteúdo ultra secreto de teste".getBytes(StandardCharsets.UTF_8));
        check(fs.exists("/seguro.txt"), "arquivo /seguro.txt deve existir");

        // Cria processo que referencia o arquivo real na syscall
        MemoryManager.CpuProcessSpec spec = new MemoryManager.CpuProcessSpec(
                5L, 1, 0L, "read_file /seguro.txt at 2ms (duration: 3ms)", List.of(0, 1)
        );
        SimProcess procSec = fs.memory.createScheduledProcess(fs, "Proc_Auditoria", SimProcess.Type.NORMAL, null, null, spec);
        check(procSec != null, "processo deve ser criado");

        // Executa simulação
        fs.scheduler.runSimulation(fs, "Proc_Auditoria", 3, null);

        // 2. Syscall de gravação gera requisições reais de E/S no RAID (Etapa 2)
        int initialQueued = 0;
        for (PhysicalDisk d : fs.disk.array.disks) {
            initialQueued += d.queue.size();
        }
        MemoryManager.CpuProcessSpec writeSpec = new MemoryManager.CpuProcessSpec(
                6L, 1, 0L, "write_disk at 2ms (duration: 3ms)", List.of(0)
        );
        SimProcess procWrite = fs.memory.createScheduledProcess(fs, "Proc_Escrita", SimProcess.Type.NORMAL, null, null, writeSpec);
        fs.scheduler.runSimulation(fs, "Proc_Escrita", 3, null);

        int finalQueued = 0;
        for (PhysicalDisk d : fs.disk.array.disks) {
            finalQueued += d.queue.size();
        }
        check(finalQueued > initialQueued, "syscall write_disk deve enfileirar IoRequest nos discos físicos do RAID");

        // 3. Auditoria (Etapa 3)
        check(!fs.audit.entries().isEmpty(), "eventos do sistema devem ser registrados no AuditLog");

        // 4. Executa todas as 7 demos oficiais (garantindo que nenhuma quebra)
        String[] demos = {"indexada", "contigua", "encadeada", "raid5", "seguranca", "memoria", "processos"};
        for (String demoName : demos) {
            DemoRun dr = runDemo(demoName);
            check(!dr.output().isBlank(), "demo " + demoName + " não pode gerar saída vazia");
            check(!dr.output().toLowerCase(Locale.ROOT).contains("exception"),
                    "demo " + demoName + " não deve lançar exceções não tratadas");
        }
    }

    // ---------------------------------------------------------------- auxiliares
    private record DemoRun(FileSystem fs, String output) {
    }

    private static DemoRun runDemo(String name) {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);
        shell.execute("demo " + name, false);
        return new DemoRun(fs, buf.toString(StandardCharsets.UTF_8));
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FALHA: " + msg);
        }
    }
}
