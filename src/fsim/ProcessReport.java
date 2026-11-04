package fsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renderização textual e relatórios visuais de processos, threads, mutexes e escalonamento de CPU.
 */
final class ProcessReport {

    private ProcessReport() {
    }

    /**
     * Relatório de criação de processo configurado para CPU / escalonamento (Exemplo 5.1).
     */
    static String processCreated(SimProcess proc, SimProcess.SyscallEvent syscall, boolean randomSyscall) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CRIAÇÃO DE PROCESSO] PID: ").append(proc.pid)
                .append(" | Nome: ").append(proc.name)
                .append(" | Prioridade: ").append(proc.priority).append(" (").append(proc.priorityLabel()).append(")\n");
        if (proc.type == SimProcess.Type.CRITICAL) {
            sb.append("Alocação     : Área Reservada (Alocação Contígua)\n");
        } else {
            sb.append("Alocação     : Memória Virtual Paginada (Tabela de Páginas criada)\n");
        }
        sb.append("Syscall/I/O  : ");
        if (randomSyscall || proc.randomSyscall) {
            sb.append("Evento Aleatório Habilitado\n");
        } else if (syscall != null) {
            sb.append("Agendada para t=").append(syscall.triggerCpuMs())
                    .append("ms (Duração do Bloqueio: ").append(syscall.durationMs()).append("ms)\n");
        } else if (!proc.syscalls.isEmpty()) {
            SimProcess.SyscallEvent first = proc.syscalls.get(0);
            sb.append("Agendada para t=").append(first.triggerCpuMs())
                    .append("ms (Duração do Bloqueio: ").append(first.durationMs()).append("ms)\n");
        } else {
            sb.append("Nenhuma\n");
        }
        String initial = proc.arrivalMs == 0 ? "PRONTO" : (proc.state != null ? proc.state.label : "PRONTO");
        sb.append("Estado Inicial: ").append(initial);
        return sb.toString();
    }

    static String processCreated(SimProcess proc) {
        SimProcess.SyscallEvent sc = proc.syscalls.isEmpty() ? null : proc.syscalls.get(0);
        return processCreated(proc, sc, proc.randomSyscall);
    }

    /**
     * Relatório passo a passo de simulação de ciclos de instrução, memória, syscall e threads (Exemplos 5.2 e 5.3).
     */
    static String simulationReport(CpuScheduler.SimulationResult sim, PagedMemory paged, Report.Palette pal) {
        StringBuilder sb = new StringBuilder();
        for (CpuScheduler.StepRecord st : sim.steps()) {
            sb.append("--- STEP ").append(st.stepIndex()).append(" (t=").append(st.timeMs())
                    .append("ms) | Executando PID ").append(st.process().pid).append(" ---\n");
            if (st.logicalPage() != null && st.memoryAccess() != null) {
                String pStr = String.format(Locale.ROOT, "%02d", st.logicalPage());
                sb.append("CPU acessa: Página Lógica ").append(pStr).append(" -> ");
                PagedMemory.AccessResult ma = st.memoryAccess();
                if (ma.hit()) {
                    String fStr = String.format(Locale.ROOT, "%02d", ma.frame());
                    sb.append("[RAM: Moldura ").append(fStr).append(" - HIT] | Histórico LRU: Pág ")
                            .append(pStr).append(" atualizada (t=").append(st.timeMs()).append("ms)\n");
                } else {
                    String fStr = String.format(Locale.ROOT, "%02d", ma.frame());
                    sb.append("[PAGE FAULT DETECTADO: Moldura ").append(fStr).append(" alocada");
                    if (ma.eviction() != null) {
                        sb.append(" (Vítima: Pág ").append(String.format(Locale.ROOT, "%02d", ma.eviction().victimPage()))
                                .append(" do PID ").append(ma.eviction().victimPid()).append(" -> Swap)");
                    }
                    if (ma.restoredFromSwap()) {
                        sb.append(" (Restaurada do Swap slot ").append(ma.restoredSwapSlot()).append(")");
                    }
                    sb.append("] | Histórico LRU: Pág ").append(pStr).append(" atualizada (t=")
                            .append(st.timeMs()).append("ms)\n");
                }
            }

            // Syscall detectada neste passo
            if (st.syscallTriggered() != null) {
                SimProcess.SyscallEvent sc = st.syscallTriggered();
                String opDesc = sc.isWrite() ? "escrita de disco" : "leitura de disco";
                if (sc.targetPath() != null) {
                    opDesc = (sc.isWrite() ? "escrita no arquivo '" : "leitura do arquivo '") + sc.targetPath() + "'";
                }
                sb.append("\n[SYSCALL DETECTADA]: PID ").append(st.process().pid)
                        .append(" atingiu a instrução de ").append(opDesc)
                        .append(" agendada para t=").append(sc.triggerCpuMs()).append("ms!\n");
                sb.append("- Mudança de Estado: EXECUTANDO -> BLOQUEADO (E/S de Disco).\n");
                sb.append("- Tempo de Retorno Previsto do Bloqueio: ").append(sc.durationMs())
                        .append("ms (Desbloqueio em t=").append(st.syscallUnblockMs()).append("ms).\n");
                sb.append("- CPU liberada -> Escalonador seleciona próximo processo da Fila de Pronto.\n");
            }

            // Eventos concorrentes de Mutex / Threads neste passo
            if (st.mutexEvents() != null && !st.mutexEvents().isEmpty()) {
                sb.append("\n[CONCORRÊNCIA DE THREADS EM PID ").append(st.process().pid).append("]\n");
                for (MutexManager.LockEvent le : st.mutexEvents()) {
                    String tName = le.thread() != null ? le.thread().name : "Thread";
                    if (le.action() == SimThread.ActionKind.LOCK) {
                        if (le.succeeded()) {
                            sb.append("- Thread ").append(tName).append(" requisitou Lock no Mutex '")
                                    .append(le.mutexName()).append("' em t=").append(le.timeMs())
                                    .append("ms -> Concedido com Sucesso.\n");
                        } else {
                            String holderName = le.holder() != null ? le.holder().name : "outra thread";
                            sb.append("- Thread ").append(tName).append(" requisitou Lock no Mutex '")
                                    .append(le.mutexName()).append("' em t=").append(le.timeMs())
                                    .append("ms -> Mutex OCUPADO por ").append(holderName).append("!\n");
                            sb.append("  * Thread ").append(tName)
                                    .append(": Mudança de Estado: EXECUTANDO -> BLOQUEADO (Aguardando Mutex '")
                                    .append(le.mutexName()).append("').\n");
                            sb.append("  * Inserida na Fila de Espera FIFO do Mutex.\n");
                        }
                    } else if (le.action() == SimThread.ActionKind.UNLOCK) {
                        if (le.wokenThread() != null) {
                            sb.append("- Thread ").append(tName).append(" liberou Unlock no Mutex '")
                                    .append(le.mutexName()).append("' em t=").append(le.timeMs())
                                    .append("ms -> Posse transferida para ").append(le.wokenThread().name).append(".\n");
                            sb.append("  * Thread ").append(le.wokenThread().name)
                                    .append(": Mudança de Estado: BLOQUEADO -> PRONTO.\n");
                        } else {
                            sb.append("- Thread ").append(tName).append(" liberou Unlock no Mutex '")
                                    .append(le.mutexName()).append("' em t=").append(le.timeMs())
                                    .append("ms -> Mutex agora LIVRE.\n");
                        }
                    }
                }
            }
        }

        // Tabela resumo da RAM Dinâmica
        sb.append("\n[RESUMO DA GERÊNCIA DE MEMÓRIA (RAM DINÂMICA)]\n");
        sb.append("+------------------+---------------+-------------------+---------------------+\n");
        sb.append("| Moldura RAM (F)  | Processo/Pág  | Estado de Acesso  | Último Acesso (LRU) |\n");
        sb.append("+------------------+---------------+-------------------+---------------------+\n");
        List<PagedMemory.FrameInfo> frames = paged.framesSnapshot();
        for (int f = 0; f < paged.numFrames; f++) {
            String molduraStr = String.format(Locale.ROOT, "Moldura %02d", f);
            PagedMemory.FrameInfo fi = (f < frames.size()) ? frames.get(f) : null;
            String procPag;
            String estado;
            String ultimo;
            if (fi != null && fi.owner() != null) {
                procPag = String.format(Locale.ROOT, "PID %d / P%02d", fi.owner().pid, fi.logicalPage());
                String st = sim.frameStates() != null ? sim.frameStates().get(f) : null;
                if (st == null && paged.lastFrameStatus != null && f < paged.lastFrameStatus.length) {
                    st = paged.lastFrameStatus[f];
                }
                if (st == null) {
                    st = "PRESENTE";
                }
                estado = st;
                ultimo = String.format(Locale.ROOT, "t=%dms", fi.lastAccessMs());
            } else {
                procPag = "[LIVRE]";
                estado = "[LIVRE]";
                ultimo = "-";
            }
            sb.append(String.format(Locale.ROOT, "| %-16s | %-13s | %-17s | %-19s |%n",
                    molduraStr, procPag, estado, ultimo));
        }
        sb.append("+------------------+---------------+-------------------+---------------------+");
        return sb.toString();
    }

    /**
     * Relatório de escalonamento completo com linha do tempo e métricas consolidadas (Exemplo 5.4).
     */
    static String scheduleReport(CpuScheduler.ScheduleResult res) {
        StringBuilder sb = new StringBuilder();

        // Filas iniciais de pronto
        sb.append("Fila de Pronto Inicial:\n");
        if (res.initialQueues() == null || res.initialQueues().isEmpty()) {
            sb.append("- (vazia)\n");
        } else {
            for (Map.Entry<Integer, List<SimProcess>> entry : res.initialQueues().entrySet()) {
                int prio = entry.getKey();
                String pLabel = prio == 0 ? "Crítica" : prio == 1 ? "Alta" : "Baixa";
                List<String> procs = new ArrayList<>();
                for (SimProcess sp : entry.getValue()) {
                    procs.add(String.format(Locale.ROOT, "PID %d (Burst %dms)", sp.pid, sp.burstMs));
                }
                sb.append(String.format(Locale.ROOT, "- Prioridade %d (%s) : [%s]%n",
                        prio, pLabel, String.join(", ", procs)));
            }
        }
        sb.append("\n--- LINHA DO TEMPO DA CPU ---\n");

        int ioIdx = 0;
        List<CpuScheduler.IoCompletionEvent> ioList = res.ioEvents() != null ? res.ioEvents() : List.of();
        for (CpuScheduler.TimelineSlice slice : res.slices()) {
            // Emite eventos de IO concluídos antes ou no início desta fatia
            while (ioIdx < ioList.size() && ioList.get(ioIdx).timeMs() <= slice.startMs()) {
                CpuScheduler.IoCompletionEvent io = ioList.get(ioIdx++);
                String op = "leitura";
                if (io.process() != null && !io.process().syscalls.isEmpty()) {
                    SimProcess.SyscallEvent sc = io.process().syscalls.get(0);
                    op = sc.isWrite() ? "escrita" : "leitura";
                }
                String tag = "[" + io.timeMs() + "ms]";
                sb.append(String.format(Locale.ROOT, "%-11s Evento E/S: PID %d encerra %s -> Estado BLOQUEADO -> PRONTO%n",
                        tag, io.process().pid, op));
            }

            String tag = "[" + slice.startMs() + "ms - " + slice.endMs() + "ms]";
            String left = String.format(Locale.ROOT, "%-11s", tag);
            sb.append(left).append(" ");
            if (slice.outcome() == CpuScheduler.SliceOutcome.IDLE) {
                sb.append("CPU Ociosa (todos os processos bloqueados aguardando E/S)\n");
            } else {
                sb.append("Executando PID ").append(slice.process().pid).append(" | ");
                switch (slice.outcome()) {
                    case QUANTUM_EXPIRED ->
                        sb.append("Quantum ").append(res.quantumMs()).append("ms expirou -> Volta p/ Fila P")
                                .append(slice.priorityQueue()).append("\n");
                    case SYSCALL_BLOCKED -> {
                        String op = (slice.syscall() != null && slice.syscall().isWrite()) ? "escrita" : "leitura";
                        sb.append("Syscall de ").append(op).append(" atingida -> Estado EXECUTANDO -> BLOQUEADO (retorno em ")
                                .append(slice.unblockAtMs()).append("ms)\n");
                    }
                    case MUTEX_BLOCKED ->
                        sb.append("Contenção no Mutex '").append(slice.mutexName())
                                .append("' -> Estado EXECUTANDO -> BLOQUEADO\n");
                    case PREEMPTED ->
                        sb.append("Preempção por processo de prioridade superior -> Volta p/ Fila P")
                                .append(slice.priorityQueue()).append("\n");
                    case COMPLETED ->
                        sb.append("Execução concluída -> Estado EXECUTANDO -> CONCLUÍDO\n");
                    default -> sb.append("Executado\n");
                }
            }
        }

        // Emite quaisquer eventos de IO restantes
        while (ioIdx < ioList.size()) {
            CpuScheduler.IoCompletionEvent io = ioList.get(ioIdx++);
            String op = "leitura";
            if (io.process() != null && !io.process().syscalls.isEmpty()) {
                SimProcess.SyscallEvent sc = io.process().syscalls.get(0);
                op = sc.isWrite() ? "escrita" : "leitura";
            }
            String tag = "[" + io.timeMs() + "ms]";
            sb.append(String.format(Locale.ROOT, "%-11s Evento E/S: PID %d encerra %s -> Estado BLOQUEADO -> PRONTO%n",
                    tag, io.process().pid, op));
        }

        // Tabela final de métricas do escalonador
        sb.append("\n================ METRICAS FINAIS DO ESCALONADOR ================\n");
        sb.append(String.format(Locale.ROOT, "%-5s %-20s %-12s %-9s %-9s %-9s %-9s %-9s %-7s%n",
                "PID", "NOME", "PRIORIDADE", "CHEGADA", "BURST", "TAT", "WT", "RT", "FAULTS"));
        sb.append("-".repeat(95)).append("\n");
        if (res.metrics() != null) {
            for (CpuScheduler.ProcessMetrics m : res.metrics()) {
                String prio = m.process() != null ? (m.process().priority + " (" + m.process().priorityLabel() + ")") : String.valueOf(m.priority());
                String name = m.process() != null ? m.process().name : "-";
                int pid = m.process() != null ? m.process().pid : 0;
                sb.append(String.format(Locale.ROOT, "%-5d %-20s %-12s %-9s %-9s %-9s %-9s %-9s %-7d%n",
                        pid, name, prio, m.arrivalMs() + "ms", m.burstMs() + "ms",
                        m.turnaroundTimeMs() + "ms", m.waitingTimeMs() + "ms", m.responseTimeMs() + "ms",
                        m.pageFaults()));
            }
        }
        sb.append("-".repeat(95)).append("\n");
        sb.append(String.format(Locale.ROOT, "Tempo Médio de Espera (AWT)     : %.2f ms%n", res.avgWaitingTime()));
        sb.append(String.format(Locale.ROOT, "Tempo Médio de Retorno (ATT)    : %.2f ms%n", res.avgTurnaroundTime()));
        sb.append(String.format(Locale.ROOT, "Tempo Médio de Resposta (ART)   : %.2f ms%n", res.avgResponseTime()));
        sb.append(String.format(Locale.ROOT, "Trocas de Contexto Totais       : %d%n", res.contextSwitches()));
        sb.append(String.format(Locale.ROOT, "Utilização Global da CPU        : %.1f%% (Tempo Ocupado: %dms / Total: %dms)",
                res.cpuUtilizationPct(), res.cpuBusyMs(), res.totalElapsedMs()));

        return sb.toString();
    }

    /**
     * Exibe o Bloco de Controle de Processo (PCB) completo com ciclo de vida, syscalls e threads.
     */
    static String processStatus(SimProcess proc) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- BLOCO DE CONTROLE DE PROCESSO (PCB) ---\n");
        sb.append(String.format(Locale.ROOT, "PID                 : %d%n", proc.pid));
        sb.append(String.format(Locale.ROOT, "Nome                : %s%n", proc.name));
        sb.append(String.format(Locale.ROOT, "Tipo                : %s%n", proc.type.label));
        sb.append(String.format(Locale.ROOT, "Dono                : %s%n", proc.owner));
        sb.append(String.format(Locale.ROOT, "Estado Atual        : %s%n", proc.state != null ? proc.state.label : "-"));
        sb.append(String.format(Locale.ROOT, "Prioridade          : %d (%s)%n", proc.priority, proc.priorityLabel()));
        sb.append(String.format(Locale.ROOT, "Tempo de Chegada    : %d ms%n", proc.arrivalMs));
        sb.append(String.format(Locale.ROOT, "Tempo de Burst      : %d ms%n", proc.burstMs));
        sb.append(String.format(Locale.ROOT, "Tempo de CPU Usado  : %d ms (Restante: %d ms)%n",
                proc.executedCpuMs, proc.remainingBurstMs()));
        sb.append(String.format(Locale.ROOT, "Tempo em Espera (WT): %d ms%n", proc.readyWaitMs));
        sb.append(String.format(Locale.ROOT, "Tempo Bloqueado (IO): %d ms%n", proc.ioBlockedMs));
        if (proc.type == SimProcess.Type.CRITICAL) {
            sb.append(String.format(Locale.ROOT, "Memória Alocada     : %s na Área Reservada%n", Units.fmtSize(proc.sizeBytes)));
        } else {
            sb.append(String.format(Locale.ROOT, "Memória Alocada     : %s (%d páginas, %d residentes, %d swap)%n",
                Units.fmtSize(proc.sizeBytes), proc.numPages(), proc.residentCount(), proc.swappedCount()));
        }
        sb.append("Syscalls Agendadas  :\n");
        if (proc.randomSyscall) {
            sb.append("  - Geração Aleatória de Syscalls Habilitada\n");
        }
        if (proc.syscalls.isEmpty() && !proc.randomSyscall) {
            sb.append("  - Nenhuma\n");
        } else {
            for (int i = 0; i < proc.syscalls.size(); i++) {
                SimProcess.SyscallEvent sc = proc.syscalls.get(i);
                String status = proc.firedSyscalls.contains(i) ? "[DISPARADA]" : "[PENDENTE]";
                String target = sc.targetPath() != null ? " (" + sc.targetPath() + ")" : "";
                sb.append(String.format(Locale.ROOT, "  - t=%dms: %s%s (duração: %dms) %s%n",
                        sc.triggerCpuMs(), sc.op(), target, sc.durationMs(), status));
            }
        }
        sb.append("Threads Registradas :\n");
        if (proc.threads.isEmpty()) {
            sb.append("  - Nenhuma\n");
        } else {
            for (SimThread t : proc.threads) {
                String w = t.waitingMutex != null ? " (aguardando mutex '" + t.waitingMutex + "')" : "";
                sb.append(String.format(Locale.ROOT, "  - %s (TID %d): Estado %s%s%n",
                        t.name, t.tid, t.state != null ? t.state.label : "-", w));
            }
        }
        sb.append("Histórico de Transições:\n");
        if (proc.transitions.isEmpty()) {
            sb.append("  - Nenhuma transição registrada\n");
        } else {
            for (SimProcess.StateTransition st : proc.transitions) {
                String fromStr = st.from() != null ? st.from().label : "*";
                String reason = (st.reason() != null && !st.reason().isBlank()) ? " (" + st.reason() + ")" : "";
                sb.append(String.format(Locale.ROOT, "  - [t=%dms] %s -> %s%s%n",
                        st.timeMs(), fromStr, st.to().label, reason));
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Exibe o estado de exclusão mútua de todos os mutexes gerenciados.
     */
    static String mutexStatus(MutexManager mm) {
        List<MutexManager.Mutex> list = mm.mutexes();
        StringBuilder sb = new StringBuilder();
        sb.append("--- EXCLUSÃO MÚTUA E SINCRONIZAÇÃO (MUTEXES) ---\n");
        if (list.isEmpty()) {
            sb.append("Nenhum mutex ativo ou registrado no sistema.");
            return sb.toString();
        }
        sb.append("Total de Mutexes Registrados: ").append(list.size()).append("\n\n");
        for (int i = 0; i < list.size(); i++) {
            MutexManager.Mutex m = list.get(i);
            sb.append("Mutex '").append(m.name).append("':\n");
            sb.append("  Estado            : ").append(m.isLocked() ? "OCUPADO" : "LIVRE").append("\n");
            if (m.owner != null) {
                String procName = m.owner.process != null ? " (PID " + m.owner.process.pid + " - " + m.owner.process.name + ")" : "";
                sb.append("  Dono Atual        : Thread ").append(m.owner.name).append(procName).append("\n");
            } else {
                sb.append("  Dono Atual        : nenhum\n");
            }
            if (m.waitQueue.isEmpty()) {
                sb.append("  Fila de Espera    : (vazia)\n");
            } else {
                List<String> waiters = new ArrayList<>();
                for (SimThread t : m.waitQueue) {
                    String pInfo = t.process != null ? " (PID " + t.process.pid + ")" : "";
                    waiters.add("Thread " + t.name + pInfo);
                }
                sb.append("  Fila de Espera    : [").append(String.join(", ", waiters))
                        .append("] (").append(m.waitQueue.size()).append(" aguardando)\n");
            }
            sb.append("  Aquisições Totais : ").append(m.acquisitions).append("\n");
            sb.append("  Contenções Totais : ").append(m.contentions).append("\n");
            if (i < list.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
