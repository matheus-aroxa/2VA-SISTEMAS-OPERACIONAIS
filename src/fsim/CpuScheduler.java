package fsim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Escalonador de CPU e simulador de ciclos de instrução do sistema operacional.
 *
 * Gerencia o ciclo de vida dos processos, executa instruções sincronizadas com a
 * memória virtual paginada e exclusão mútua concorrente, e implementa múltiplos
 * algoritmos de escalonamento preemptivo e por prioridades.
 */
final class CpuScheduler {

    static final long DEFAULT_QUANTUM_MS = 2L;

    enum Algorithm {
        PRIORITY_RR("Priority_RR", "Prioridade + Round-Robin"),
        ROUND_ROBIN("RR", "Round-Robin"),
        PRIORITY("Priority", "Prioridade Preemptiva"),
        FCFS("FCFS", "FCFS (First-Come, First-Served)");

        final String key;
        final String label;

        Algorithm(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static Algorithm parse(String text) {
            if (text == null || text.isBlank()) {
                return PRIORITY_RR;
            }
            String s = text.trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "priority_rr", "prr", "prioridade_rr", "prioridade+rr", "prioridade_round_robin" -> PRIORITY_RR;
                case "rr", "round_robin", "roundrobin", "circular" -> ROUND_ROBIN;
                case "priority", "prioridade", "prio" -> PRIORITY;
                case "fcfs", "fifo", "first_come_first_served" -> FCFS;
                default -> throw new FSException("algoritmo de escalonamento inválido: '" + text
                        + "' (use 'Priority_RR', 'RR', 'Priority' ou 'FCFS')");
            };
        }
    }

    enum SliceOutcome {
        QUANTUM_EXPIRED,
        SYSCALL_BLOCKED,
        MUTEX_BLOCKED,
        PREEMPTED,
        COMPLETED,
        IDLE
    }

    record StepRecord(int stepIndex, long timeMs, SimProcess process, Integer logicalPage,
                      PagedMemory.AccessResult memoryAccess, SimProcess.SyscallEvent syscallTriggered,
                      long syscallUnblockMs, List<MutexManager.LockEvent> mutexEvents) {
    }

    record SimulationResult(List<StepRecord> steps, List<Integer> touchedFrames,
                            Map<Integer, String> frameStates) {
    }

    record TimelineSlice(long startMs, long endMs, SimProcess process, int priorityQueue,
                         SliceOutcome outcome, SimProcess.SyscallEvent syscall,
                         long unblockAtMs, String mutexName) {
    }

    record IoCompletionEvent(long timeMs, SimProcess process) {
    }

    record ProcessMetrics(SimProcess process, int priority, long arrivalMs, long burstMs,
                          long waitingTimeMs, long turnaroundTimeMs, long responseTimeMs,
                          int pageFaults, int pageHits) {
    }

    record ScheduleResult(Algorithm algorithm, long quantumMs,
                          Map<Integer, List<SimProcess>> initialQueues,
                          List<TimelineSlice> slices,
                          List<IoCompletionEvent> ioEvents,
                          List<MutexManager.LockEvent> mutexEvents,
                          List<ProcessMetrics> metrics,
                          double avgWaitingTime, double avgTurnaroundTime, double avgResponseTime,
                          int contextSwitches, double cpuUtilizationPct,
                          long totalElapsedMs, long cpuBusyMs) {
    }

    long clockMs;

    CpuScheduler() {
        this.clockMs = 0L;
    }

    void reset() {
        this.clockMs = 0L;
    }

    void resetAllProcesses(FileSystem fs) {
        if (fs != null && fs.memory != null) {
            for (SimProcess p : fs.memory.processes()) {
                if (!p.systemBase) {
                    p.resetExecution(0L);
                }
            }
        }
        if (fs != null && fs.mutexes != null) {
            fs.mutexes.reset();
        }
        reset();
    }

    // ------------------------------------------------------------- simulação de ciclos

    SimulationResult runSimulation(FileSystem fs, String processRef, int steps, List<Integer> overridePages) {
        if (fs == null || fs.memory == null) {
            throw new FSException("gerenciador de memória não disponível");
        }
        SimProcess target = fs.memory.findProcess(processRef);
        if (target == null) {
            throw new FSException("processo não encontrado: '" + processRef + "'");
        }

        if (overridePages != null && !overridePages.isEmpty()) {
            target.plannedPages.clear();
            target.plannedPages.addAll(overridePages);
            target.pageCursor = 0;
        }

        if (!target.cpuConfigured || target.burstMs <= 0L) {
            target.burstMs = Math.max(8L, (long) steps);
            target.priority = 1;
            target.cpuConfigured = true;
            if (target.state == SimProcess.State.NOVO) {
                target.transitionTo(SimProcess.State.PRONTO, clockMs, "Admissão");
            }
        } else if (target.state == SimProcess.State.NOVO) {
            target.transitionTo(SimProcess.State.PRONTO, clockMs, "Admissão");
        }

        List<StepRecord> stepsList = new ArrayList<>();
        Set<Integer> touchedFrames = new LinkedHashSet<>();
        SimProcess current = (target.state == SimProcess.State.PRONTO || target.state == SimProcess.State.EXECUTANDO)
                ? target : null;

        for (int step = 1; step <= steps; step++) {
            clockMs++;

            // 1. Processar desbloqueios de E/S agendados para este tick
            for (SimProcess proc : fs.memory.processes()) {
                if (proc.state == SimProcess.State.BLOQUEADO && proc.unblockAtMs > 0L && proc.unblockAtMs <= clockMs) {
                    proc.transitionTo(SimProcess.State.PRONTO, clockMs, "Retorno de E/S");
                    proc.unblockAtMs = -1L;
                    proc.blockReason = null;
                }
            }

            // 2. Determinar qual processo assume a CPU neste passo
            if (current == null || current.state != SimProcess.State.EXECUTANDO) {
                if (target.state == SimProcess.State.PRONTO) {
                    current = target;
                    current.transitionTo(SimProcess.State.EXECUTANDO, clockMs, "Despachado");
                } else if (target.state == SimProcess.State.EXECUTANDO) {
                    current = target;
                } else {
                    current = findReadyProcess(fs, target);
                    if (current != null) {
                        current.transitionTo(SimProcess.State.EXECUTANDO, clockMs, "Despachado");
                    }
                }
            }

            if (current != null && current.firstStartMs < 0L) {
                current.firstStartMs = clockMs;
            }

            if (current == null) {
                // CPU ociosa neste passo
                stepsList.add(new StepRecord(step, clockMs, null, null, null, null, -1L, List.of()));
                continue;
            }

            // 3. Processar ações concorrentes de Mutex agendadas nas threads do processo ativo
            List<MutexManager.LockEvent> stepMutexEvents = new ArrayList<>();
            boolean blockedByMutex = false;
            if (fs.mutexes != null && current.threads != null) {
                for (SimThread th : current.threads) {
                    List<SimThread.MutexAction> acts = th.pollDueActions(current.executedCpuMs);
                    for (SimThread.MutexAction act : acts) {
                        if (act.kind() == SimThread.ActionKind.LOCK) {
                            MutexManager.LockEvent ev = fs.mutexes.lock(th, act.mutexName(), clockMs);
                            stepMutexEvents.add(ev);
                            if (!ev.succeeded() && current.allActiveThreadsBlocked()) {
                                current.blockReason = "Mutex: " + act.mutexName();
                                current.transitionTo(SimProcess.State.BLOQUEADO, clockMs, "Mutex: " + act.mutexName());
                                blockedByMutex = true;
                                break;
                            }
                        } else if (act.kind() == SimThread.ActionKind.UNLOCK) {
                            MutexManager.LockEvent ev = fs.mutexes.unlock(th, act.mutexName(), clockMs);
                            stepMutexEvents.add(ev);
                            if (ev.wokenThread() != null) {
                                SimProcess wp = ev.wokenThread().process;
                                if (wp != null && wp.state == SimProcess.State.BLOQUEADO
                                        && !wp.allActiveThreadsBlocked() && wp.unblockAtMs < 0L) {
                                    wp.blockReason = null;
                                    wp.transitionTo(SimProcess.State.PRONTO, clockMs, "Mutex liberado");
                                }
                            }
                        }
                    }
                    if (blockedByMutex) {
                        break;
                    }
                }
            }

            if (blockedByMutex) {
                stepsList.add(new StepRecord(step, clockMs, current, null, null, null, -1L, stepMutexEvents));
                current = null;
                continue;
            }

            // 4. Acesso à memória virtual paginada
            Integer page = null;
            PagedMemory.AccessResult memAccess = null;
            if (current.type == SimProcess.Type.NORMAL && current.numPages() > 0) {
                page = current.nextLogicalPage();
                memAccess = fs.memory.paged.accessPageAt(current, page, "CPU t=" + clockMs + "ms", false,
                        fs.disk != null ? fs.disk.array : null, clockMs);
                touchedFrames.add(memAccess.frame());
            }

            // 5. Consumo de ciclo de CPU
            current.executedCpuMs++;

            // 6. Verificação de chamada de sistema (Syscall)
            SimProcess.SyscallEvent sys = current.pollDueSyscall();
            long unblockAt = -1L;
            if (sys != null) {
                unblockAt = clockMs + sys.durationMs();
                current.unblockAtMs = unblockAt;
                current.blockReason = "Syscall: " + sys.op();
                executeSyscallIo(fs, current, sys);
                current.transitionTo(SimProcess.State.BLOQUEADO, clockMs, "Syscall: " + sys.op());
                if (fs.audit != null) {
                    String user = current.owner != null ? current.owner
                            : (fs.auth != null && fs.auth.currentUser() != null ? fs.auth.currentUser().name() : "sistema");
                    String mode = (fs.auth != null && fs.auth.mode() != null) ? fs.auth.mode().name() : "MODO_USUARIO";
                    fs.audit.record("PROCESSO", user, mode, "PID " + current.pid + " disparou syscall " + sys.op()
                            + " (" + sys.durationMs() + "ms)");
                }
            }

            // 7. Registro do passo executado
            stepsList.add(new StepRecord(step, clockMs, current, page, memAccess, sys, unblockAt, stepMutexEvents));

            // 8. Verificação de término do burst
            if (sys != null) {
                current = null;
            } else if (current.executedCpuMs >= current.burstMs) {
                current.completionMs = clockMs;
                current.transitionTo(SimProcess.State.CONCLUIDO, clockMs, "Burst concluído");
                if (fs.mutexes != null) {
                    fs.mutexes.releaseAllOwnedBy(current, clockMs);
                }
                current = null;
            }
        }

        // 9. Coleta de estado das molduras de RAM acessadas
        Map<Integer, String> frameStates = new LinkedHashMap<>();
        for (int f : touchedFrames) {
            String st = null;
            if (fs.memory != null && fs.memory.paged != null && fs.memory.paged.lastFrameStatus != null
                    && f < fs.memory.paged.lastFrameStatus.length) {
                st = fs.memory.paged.lastFrameStatus[f];
            }
            if (st == null) {
                st = "PRESENTE";
            }
            frameStates.put(f, st);
        }

        return new SimulationResult(Collections.unmodifiableList(stepsList),
                new ArrayList<>(touchedFrames), frameStates);
    }

    private SimProcess findReadyProcess(FileSystem fs, SimProcess exclude) {
        if (fs.memory == null) {
            return null;
        }
        SimProcess best = null;
        for (SimProcess p : fs.memory.processes()) {
            if (!p.systemBase && p != exclude && p.state == SimProcess.State.PRONTO) {
                if (best == null || p.priority < best.priority || (p.priority == best.priority && p.arrivalMs < best.arrivalMs)) {
                    best = p;
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------- escalonamento completo

    ScheduleResult schedule(FileSystem fs, Algorithm algorithm, long quantumMs) {
        if (fs == null || fs.memory == null) {
            throw new FSException("gerenciador de memória não disponível");
        }
        Algorithm algo = algorithm != null ? algorithm : Algorithm.PRIORITY_RR;
        long qMs = quantumMs > 0L ? quantumMs : DEFAULT_QUANTUM_MS;

        List<SimProcess> procs = new ArrayList<>();
        for (SimProcess p : fs.memory.processes()) {
            if (!p.systemBase && (p.cpuConfigured || p.burstMs > 0L)) {
                procs.add(p);
            }
        }
        if (procs.isEmpty()) {
            throw new FSException("nenhum processo configurado para escalonamento");
        }

        if (fs.mutexes != null) {
            fs.mutexes.reset();
        }

        for (SimProcess p : procs) {
            p.resetExecution(0L);
            p.executedCpuMs = 0L;
            p.readyWaitMs = 0L;
            p.ioBlockedMs = 0L;
            p.firstStartMs = -1L;
            p.completionMs = -1L;
            p.unblockAtMs = -1L;
            p.blockReason = null;
        }

        Map<Integer, List<SimProcess>> initialQueues = new TreeMap<>();
        for (SimProcess p : procs) {
            int qKey = (algo == Algorithm.ROUND_ROBIN || algo == Algorithm.FCFS) ? 0 : p.priority;
            initialQueues.computeIfAbsent(qKey, k -> new ArrayList<>()).add(p);
        }

        Map<Integer, Deque<SimProcess>> readyQueues = new TreeMap<>();
        for (SimProcess p : procs) {
            if (p.arrivalMs <= 0L) {
                p.transitionTo(SimProcess.State.PRONTO, 0L, "Admissão inicial");
                int qKey = (algo == Algorithm.ROUND_ROBIN || algo == Algorithm.FCFS) ? 0 : p.priority;
                readyQueues.computeIfAbsent(qKey, k -> new ArrayDeque<>()).addLast(p);
            } else {
                p.state = SimProcess.State.NOVO;
            }
        }

        List<TimelineSlice> slices = new ArrayList<>();
        List<IoCompletionEvent> ioEvents = new ArrayList<>();
        List<MutexManager.LockEvent> allMutexEvents = new ArrayList<>();
        Map<SimProcess, Integer> procHits = new LinkedHashMap<>();
        Map<SimProcess, Integer> procFaults = new LinkedHashMap<>();
        for (SimProcess p : procs) {
            procHits.put(p, 0);
            procFaults.put(p, 0);
        }

        long currentClock = 0L;
        long cpuBusyMs = 0L;
        int contextSwitches = 0;
        SimProcess currentProcess = null;
        long sliceStartMs = -1L;
        long sliceElapsedMs = 0L;
        long idleStartMs = -1L;

        while (hasIncomplete(procs)) {
            long t = currentClock;

            // a) Admissão de processos que chegaram no tempo t
            for (SimProcess p : procs) {
                if (p.state == SimProcess.State.NOVO && p.arrivalMs <= t) {
                    p.transitionTo(SimProcess.State.PRONTO, t, "Admissão");
                    int qKey = (algo == Algorithm.ROUND_ROBIN || algo == Algorithm.FCFS) ? 0 : p.priority;
                    readyQueues.computeIfAbsent(qKey, k -> new ArrayDeque<>()).addLast(p);
                }
            }

            // b) Retorno de processos em E/S cujo desbloqueio venceu em t
            for (SimProcess p : procs) {
                if (p.state == SimProcess.State.BLOQUEADO && p.unblockAtMs > 0L && p.unblockAtMs <= t) {
                    p.transitionTo(SimProcess.State.PRONTO, t, "Retorno de E/S");
                    ioEvents.add(new IoCompletionEvent(t, p));
                    p.unblockAtMs = -1L;
                    p.blockReason = null;
                    int qKey = (algo == Algorithm.ROUND_ROBIN || algo == Algorithm.FCFS) ? 0 : p.priority;
                    readyQueues.computeIfAbsent(qKey, k -> new ArrayDeque<>()).addLast(p);
                }
            }

            // c) Preempção do processo corrente se entrou outro com prioridade estritamente superior
            if (currentProcess != null && (algo == Algorithm.PRIORITY_RR || algo == Algorithm.PRIORITY)) {
                if (hasHigherPriorityReady(readyQueues, currentProcess.priority)) {
                    slices.add(new TimelineSlice(sliceStartMs, t, currentProcess, currentProcess.priority,
                            SliceOutcome.PREEMPTED, null, -1L, null));
                    currentProcess.transitionTo(SimProcess.State.PRONTO, t, "Preempção por prioridade superior");
                    readyQueues.computeIfAbsent(currentProcess.priority, k -> new ArrayDeque<>()).addFirst(currentProcess);
                    currentProcess = null;
                    sliceElapsedMs = 0L;
                }
            }

            // d) Fim do quantum se aplicável
            if (currentProcess != null && (algo == Algorithm.ROUND_ROBIN || algo == Algorithm.PRIORITY_RR)) {
                if (sliceElapsedMs >= qMs) {
                    int curQueue = (algo == Algorithm.ROUND_ROBIN) ? 0 : currentProcess.priority;
                    Deque<SimProcess> sameQueue = readyQueues.get(curQueue);
                    boolean hasOtherReady = hasAnyReady(readyQueues);

                    if (hasOtherReady) {
                        slices.add(new TimelineSlice(sliceStartMs, t, currentProcess, currentProcess.priority,
                                SliceOutcome.QUANTUM_EXPIRED, null, -1L, null));
                        currentProcess.transitionTo(SimProcess.State.PRONTO, t, "Fim do quantum (" + qMs + "ms)");
                        if (sameQueue == null) {
                            sameQueue = new ArrayDeque<>();
                            readyQueues.put(curQueue, sameQueue);
                        }
                        sameQueue.addLast(currentProcess);
                        currentProcess = null;
                        sliceElapsedMs = 0L;
                    } else {
                        // Não há outro processo pronto; registra término de fatia e renova quantum do mesmo
                        slices.add(new TimelineSlice(sliceStartMs, t, currentProcess, currentProcess.priority,
                                SliceOutcome.QUANTUM_EXPIRED, null, -1L, null));
                        sliceStartMs = t;
                        sliceElapsedMs = 0L;
                    }
                }
            }

            // e) Seleção de processo se CPU estiver livre
            if (currentProcess == null) {
                SimProcess next = pickNextReady(readyQueues);
                if (next != null) {
                    if (idleStartMs >= 0L) {
                        slices.add(new TimelineSlice(idleStartMs, t, null, -1, SliceOutcome.IDLE, null, -1L, null));
                        idleStartMs = -1L;
                    }
                    currentProcess = next;
                    if (currentProcess.firstStartMs < 0L) {
                        currentProcess.firstStartMs = t;
                    }
                    currentProcess.transitionTo(SimProcess.State.EXECUTANDO, t, "Despachado");
                    sliceStartMs = t;
                    sliceElapsedMs = 0L;
                    contextSwitches++;
                } else {
                    // CPU ociosa neste ms
                    if (idleStartMs < 0L) {
                        idleStartMs = t;
                    }
                    updateWaitAndBlockedTimes(procs);
                    currentClock++;

                    // Salvaguarda contra deadlock total
                    if (!canAnyProcessWake(procs)) {
                        break;
                    }
                    continue;
                }
            }

            // f) Execução de 1 ms do processo atual na CPU
            boolean mutexBlocked = false;
            String mutexBlockedName = null;
            if (fs.mutexes != null && currentProcess.threads != null) {
                for (SimThread th : currentProcess.threads) {
                    List<SimThread.MutexAction> acts = th.pollDueActions(currentProcess.executedCpuMs);
                    for (SimThread.MutexAction act : acts) {
                        if (act.kind() == SimThread.ActionKind.LOCK) {
                            MutexManager.LockEvent ev = fs.mutexes.lock(th, act.mutexName(), t);
                            allMutexEvents.add(ev);
                            if (!ev.succeeded() && currentProcess.allActiveThreadsBlocked()) {
                                mutexBlocked = true;
                                mutexBlockedName = act.mutexName();
                                break;
                            }
                        } else if (act.kind() == SimThread.ActionKind.UNLOCK) {
                            MutexManager.LockEvent ev = fs.mutexes.unlock(th, act.mutexName(), t);
                            allMutexEvents.add(ev);
                            if (ev.wokenThread() != null) {
                                SimProcess wp = ev.wokenThread().process;
                                if (wp != null && wp.state == SimProcess.State.BLOQUEADO
                                        && !wp.allActiveThreadsBlocked() && wp.unblockAtMs < 0L) {
                                    wp.blockReason = null;
                                    wp.transitionTo(SimProcess.State.PRONTO, t, "Mutex liberado");
                                    int wpKey = (algo == Algorithm.ROUND_ROBIN || algo == Algorithm.FCFS) ? 0 : wp.priority;
                                    readyQueues.computeIfAbsent(wpKey, k -> new ArrayDeque<>()).addLast(wp);
                                }
                            }
                        }
                    }
                    if (mutexBlocked) {
                        break;
                    }
                }
            }

            if (mutexBlocked) {
                slices.add(new TimelineSlice(sliceStartMs, t, currentProcess, currentProcess.priority,
                        SliceOutcome.MUTEX_BLOCKED, null, -1L, mutexBlockedName));
                currentProcess.blockReason = "Mutex: " + mutexBlockedName;
                currentProcess.transitionTo(SimProcess.State.BLOQUEADO, t, "Mutex: " + mutexBlockedName);
                currentProcess = null;
                sliceElapsedMs = 0L;
                updateWaitAndBlockedTimes(procs);
                currentClock++;
                continue;
            }

            // Memória Virtual Paginada
            if (currentProcess.type == SimProcess.Type.NORMAL && currentProcess.numPages() > 0) {
                int page = currentProcess.nextLogicalPage();
                PagedMemory.AccessResult ar = fs.memory.paged.accessPageAt(currentProcess, page,
                        "CPU t=" + t + "ms", false, fs.disk != null ? fs.disk.array : null, t);
                if (ar.hit()) {
                    procHits.put(currentProcess, procHits.get(currentProcess) + 1);
                } else {
                    procFaults.put(currentProcess, procFaults.get(currentProcess) + 1);
                }
            }

            currentProcess.executedCpuMs++;
            cpuBusyMs++;
            sliceElapsedMs++;

            // Verificação de Syscall ao atingir tempo configurado
            SimProcess.SyscallEvent sys = currentProcess.pollDueSyscall();
            if (sys != null) {
                long unblockAt = t + sys.durationMs();
                currentProcess.unblockAtMs = unblockAt;
                currentProcess.blockReason = "Syscall: " + sys.op();
                executeSyscallIo(fs, currentProcess, sys);
                currentProcess.transitionTo(SimProcess.State.BLOQUEADO, t + 1, "Syscall: " + sys.op());
                slices.add(new TimelineSlice(sliceStartMs, t + 1, currentProcess, currentProcess.priority,
                        SliceOutcome.SYSCALL_BLOCKED, sys, unblockAt, null));
                if (fs.audit != null) {
                    String user = currentProcess.owner != null ? currentProcess.owner
                            : (fs.auth != null && fs.auth.currentUser() != null ? fs.auth.currentUser().name() : "sistema");
                    String mode = (fs.auth != null && fs.auth.mode() != null) ? fs.auth.mode().name() : "MODO_USUARIO";
                    fs.audit.record("PROCESSO", user, mode, "PID " + currentProcess.pid + " disparou syscall " + sys.op()
                            + " (" + sys.durationMs() + "ms)");
                }
                currentProcess = null;
                sliceElapsedMs = 0L;
            } else if (currentProcess.executedCpuMs >= currentProcess.burstMs) {
                currentProcess.completionMs = t + 1;
                currentProcess.transitionTo(SimProcess.State.CONCLUIDO, t + 1, "Concluído");
                if (fs.mutexes != null) {
                    allMutexEvents.addAll(fs.mutexes.releaseAllOwnedBy(currentProcess, t + 1));
                }
                slices.add(new TimelineSlice(sliceStartMs, t + 1, currentProcess, currentProcess.priority,
                        SliceOutcome.COMPLETED, null, -1L, null));
                currentProcess = null;
                sliceElapsedMs = 0L;
            }

            updateWaitAndBlockedTimes(procs);
            currentClock++;
        }

        if (idleStartMs >= 0L) {
            slices.add(new TimelineSlice(idleStartMs, currentClock, null, -1, SliceOutcome.IDLE, null, -1L, null));
        }

        long totalElapsedMs = currentClock;

        // Métricas finais consolidadas
        List<ProcessMetrics> metrics = new ArrayList<>();
        double sumWait = 0.0;
        double sumTat = 0.0;
        double sumRt = 0.0;

        for (SimProcess p : procs) {
            long tat = Math.max(0L, p.completionMs - p.arrivalMs);
            long wt = p.readyWaitMs;
            if (wt == 0L && tat > p.burstMs && p.ioBlockedMs == 0L) {
                wt = tat - p.burstMs;
            }
            long rt = (p.firstStartMs >= 0L ? p.firstStartMs : p.arrivalMs) - p.arrivalMs;
            int faults = procFaults.getOrDefault(p, 0);
            int hits = procHits.getOrDefault(p, 0);

            metrics.add(new ProcessMetrics(p, p.priority, p.arrivalMs, p.burstMs, wt, tat, rt, faults, hits));
            sumWait += wt;
            sumTat += tat;
            sumRt += rt;
        }

        double avgWaitingTime = procs.isEmpty() ? 0.0 : sumWait / procs.size();
        double avgTurnaroundTime = procs.isEmpty() ? 0.0 : sumTat / procs.size();
        double avgResponseTime = procs.isEmpty() ? 0.0 : sumRt / procs.size();
        double cpuUtilizationPct = totalElapsedMs > 0L ? (double) cpuBusyMs * 100.0 / totalElapsedMs : 0.0;

        return new ScheduleResult(algo, qMs, initialQueues, Collections.unmodifiableList(slices),
                Collections.unmodifiableList(ioEvents), Collections.unmodifiableList(allMutexEvents),
                Collections.unmodifiableList(metrics), avgWaitingTime, avgTurnaroundTime, avgResponseTime,
                contextSwitches, cpuUtilizationPct, totalElapsedMs, cpuBusyMs);
    }

    private boolean hasIncomplete(List<SimProcess> procs) {
        for (SimProcess p : procs) {
            if (p.state != SimProcess.State.CONCLUIDO) {
                return true;
            }
        }
        return false;
    }

    private boolean canAnyProcessWake(List<SimProcess> procs) {
        for (SimProcess p : procs) {
            if (p.state == SimProcess.State.PRONTO || p.state == SimProcess.State.NOVO) {
                return true;
            }
            if (p.state == SimProcess.State.BLOQUEADO && p.unblockAtMs > 0L) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHigherPriorityReady(Map<Integer, Deque<SimProcess>> readyQueues, int currentPriority) {
        for (Map.Entry<Integer, Deque<SimProcess>> entry : readyQueues.entrySet()) {
            if (entry.getKey() < currentPriority) {
                Deque<SimProcess> q = entry.getValue();
                if (q != null && !q.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAnyReady(Map<Integer, Deque<SimProcess>> readyQueues) {
        for (Deque<SimProcess> q : readyQueues.values()) {
            if (q != null && !q.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private SimProcess pickNextReady(Map<Integer, Deque<SimProcess>> readyQueues) {
        for (Deque<SimProcess> q : readyQueues.values()) {
            if (q != null && !q.isEmpty()) {
                return q.pollFirst();
            }
        }
        return null;
    }

    private void updateWaitAndBlockedTimes(List<SimProcess> procs) {
        for (SimProcess p : procs) {
            if (p.state == SimProcess.State.PRONTO) {
                p.readyWaitMs++;
            } else if (p.state == SimProcess.State.BLOQUEADO) {
                p.ioBlockedMs++;
            }
        }
    }

    private void executeSyscallIo(FileSystem fs, SimProcess p, SimProcess.SyscallEvent sys) {
        if (sys == null || fs == null) {
            return;
        }
        if (sys.targetPath() != null && fs.exists(sys.targetPath())) {
            try {
                fs.read(sys.targetPath());
            } catch (FSException ex) {
                throw ex;
            }
        } else {
            IoRequest.Op op = sys.isWrite() ? IoRequest.Op.GRAVACAO : IoRequest.Op.LEITURA;
            if (fs.disk != null && fs.disk.array != null && fs.disk.array.disks != null
                    && fs.disk.array.disks.length > 0) {
                PhysicalDisk d = fs.disk.array.disks[0];
                int track = (int) (Math.abs((long) p.pid * 7L + sys.triggerCpuMs()) % Math.max(1, d.blocks.length));
                d.queue.add(new IoRequest(op, track));
            }
        }
    }
}
