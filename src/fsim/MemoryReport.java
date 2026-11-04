package fsim;

import java.util.List;
import java.util.Locale;

/**
 * Renderização textual e relatórios visuais de memória física, paginação e fragmentação.
 */
final class MemoryReport {

    private MemoryReport() {
    }

    static String fmtMb(long bytes) {
        double mb = (double) bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private static String padRight(String s, int n) {
        if (s == null) {
            s = "";
        }
        if (s.length() >= n) {
            return s;
        }
        return s + " ".repeat(n - s.length());
    }

    private static String pagesList(int n) {
        if (n <= 0) {
            return "";
        }
        if (n == 1) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                sb.append(i);
            } else if (i == n - 1) {
                sb.append(" e ").append(i);
            } else {
                sb.append(", ").append(i);
            }
        }
        return sb.toString();
    }

    static String criticalCreated(SimProcess proc, ReservedArena.Segment seg) {
        return "- Alocado de forma contígua na Área Reservada: [" + fmtMb(seg.startByte()) + " até "
                + fmtMb(seg.endByte()) + "] (" + Units.fmtSize(seg.lengthBytes()) + ")";
    }

    static String normalCreated(SimProcess proc, int pageSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CRIAÇÃO DE PROCESSO NÃO CRÍTICO - MEMÓRIA VIRTUAL]\n");
        sb.append("Tamanho do Processo : ").append(Units.fmtSize(proc.sizeBytes)).append(" (").append(proc.sizeBytes).append(" Bytes)\n");
        sb.append("Tamanho da Página   : ").append(Units.fmtSize(pageSize)).append(" (").append(pageSize).append(" Bytes)\n");
        double exactPages = (double) proc.sizeBytes / pageSize;
        sb.append("Cálculo de Páginas  : ").append(Units.fmtSize(proc.sizeBytes)).append(" / ").append(Units.fmtSize(pageSize))
                .append(" = ").append(String.format(Locale.ROOT, "%.1f", exactPages))
                .append(" -> Requer ").append(proc.numPages()).append(" Páginas Lógicas (Páginas ")
                .append(pagesList(proc.numPages())).append(").\n\n");
        sb.append("--- ANÁLISE DE FRAGMENTAÇÃO INTERNA ---\n");
        for (int i = 0; i < proc.numPages(); i++) {
            SimProcess.PageEntry pe = proc.pageTable[i];
            double pct = (double) pe.usedBytes * 100.0 / pageSize;
            sb.append("Página Lógica ").append(i).append(" : ").append(pe.usedBytes).append(" Bytes ocupados (")
                    .append(String.format(Locale.ROOT, "%.1f%%", pct)).append(")\n");
        }
        long frag = proc.internalFragmentation(pageSize);
        int lastPage = proc.numPages() > 0 ? proc.numPages() - 1 : 0;
        sb.append("Fragmentação Interna Total: ").append(frag).append(" Bytes de desperdício na Página Lógica ")
                .append(lastPage).append(".\n");
        sb.append("Status: Tabela de Páginas gerada sem alocar molduras na RAM (Alocação por demanda).");
        return sb.toString();
    }

    static String processTerminated(MemoryManager.TerminationResult res, ReservedArena arena) {
        if (res.process().type == SimProcess.Type.CRITICAL) {
            return "[ENCERRAMENTO DE PROCESSO CRÍTICO] Processo '" + res.process().name + "' finalizado.\n"
                    + "Área de " + Units.fmtSize(res.freedSegment().lengthBytes()) + " no intervalo ["
                    + fmtMb(res.freedSegment().startByte()) + " a " + fmtMb(res.freedSegment().endByte())
                    + "] liberada na Área Reservada da RAM.";
        } else {
            return "[ENCERRAMENTO DE PROCESSO] Processo '" + res.process().name + "' finalizado.\n"
                    + "Molduras liberadas na RAM: " + res.freedFrames() + " | Páginas removidas do Swap: "
                    + res.freedSwapSlots() + ".";
        }
    }

    static String reservedStatus(ReservedArena arena) {
        ReservedArena.FragReport report = arena.fragmentation();
        StringBuilder sb = new StringBuilder();
        sb.append("--- MAPA DA ÁREA CRÍTICA/RESERVADA DA RAM (Total: ").append(fmtMb(arena.totalBytes))
                .append(" - Alocação Fixa) ---\n");
        for (ReservedArena.Segment seg : arena.segments()) {
            String label = seg.isFree() ? "FRAGMENTO LIVRE " + seg.holeIndex() : padRight(seg.process().name, 20);
            String state = seg.isFree() ? "LIVRE - LACUNA" : "OCUPADO";
            sb.append("[").append(fmtMb(seg.startByte())).append(" - ").append(fmtMb(seg.endByte())).append("] : ")
                    .append(label).append(" (").append(Units.fmtSize(seg.lengthBytes())).append(")  [").append(state).append("]\n");
        }
        sb.append("\n--- DIAGNÓSTICO DE FRAGMENTAÇÃO EXTERNA ---\n");
        sb.append("Total de Memória Livre na Área Reservada : ").append(Units.fmtSize(report.totalFreeBytes()))
                .append(" (").append(fmtMb(report.totalFreeBytes())).append(")\n");
        sb.append("Maior Bloco Contíguo Disponível          : ").append(Units.fmtSize(report.largestFreeBytes()))
                .append(" (").append(fmtMb(report.largestFreeBytes())).append(")\n");
        sb.append("Fragmentação Externa Detectada           : ")
                .append(report.fragmented() ? "SIM (" + report.freeHolesCount() + " fragmentos não contíguos)" : "NÃO")
                .append("\n");
        if (report.fragmented()) {
            sb.append("Status: Uma requisição contígua de ").append(Units.fmtSize(report.largestFreeBytes() + 512 * 1024))
                    .append(" falhará por ausência de bloco único.");
        } else {
            sb.append("Status: Espaço livre consolidado em bloco único.");
        }
        return sb.toString();
    }

    static String compactionReport(List<ReservedArena.Move> moves, ReservedArena arena) {
        StringBuilder sb = new StringBuilder();
        sb.append("[EXECUTANDO COMPACTAÇÃO DA ÁREA RESERVADA DA RAM]\n");
        sb.append("Realocando blocos contíguos para eliminar a fragmentação externa...\n");
        for (ReservedArena.Move move : moves) {
            sb.append("- Mapeamento de '").append(move.process().name).append("': [")
                    .append(fmtMb(move.oldStart())).append(" a ").append(fmtMb(move.oldEnd()))
                    .append("] -> Ajustado para: [").append(fmtMb(move.newStart())).append(" a ")
                    .append(fmtMb(move.newEnd())).append("]\n");
        }
        sb.append("\n--- MAPA APÓS COMPACTAÇÃO ---\n");
        for (ReservedArena.Segment seg : arena.segments()) {
            String label = seg.isFree() ? "Bloco Livre Contíguo" : padRight(seg.process().name, 20);
            String state = seg.isFree() ? "LIVRE" : "OCUPADO";
            sb.append("[").append(fmtMb(seg.startByte())).append(" - ").append(fmtMb(seg.endByte())).append("] : ")
                    .append(label).append(" (").append(Units.fmtSize(seg.lengthBytes())).append(") [").append(state).append("]\n");
        }
        sb.append("Resultado: Fragmentação externa eliminada. Maior bloco contíguo: ")
                .append(Units.fmtSize(arena.fragmentation().largestFreeBytes())).append(".");
        return sb.toString();
    }

    static String translationReport(PagedMemory.Translation tr, int pageSize, boolean showAddressMath) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TRADUÇÃO DE ENDEREÇO: PÁGINA LÓGICA -> MOLDURA FÍSICA]\n");
        sb.append("Solicitação: Página Lógica ").append(tr.logicalPage()).append(" -> ")
                .append(tr.hit() ? "Mapeada na Moldura Física " + tr.frame() + " (RAM Dinâmica) [HIT]" : "[MISS / AUSENTE NA RAM]");
        if (tr.hit() && showAddressMath) {
            sb.append("\nEndereço Físico: Moldura ").append(tr.frame()).append(" × ").append(pageSize)
                    .append(" + Deslocamento ").append(tr.offset()).append(" = 0x")
                    .append(Long.toHexString(tr.physicalAddress()).toUpperCase(Locale.ROOT))
                    .append(" (").append(tr.physicalAddress()).append(" B)");
        }
        return sb.toString();
    }

    static String executionStepReport(PagedMemory.AccessResult res) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CPU - REFERÊNCIA DE MEMÓRIA EM EXECUÇÃO]\n");
        sb.append(res.process().name).append(" requisita acesso à Página Lógica ").append(res.logicalPage()).append("...\n");
        if (res.hit()) {
            sb.append("- Consulta Tabela de Páginas -> HIT na Moldura Física ").append(res.frame()).append(" (RAM Dinâmica).");
        } else {
            sb.append("- Consulta Tabela de Páginas -> Página Lógica ").append(res.logicalPage()).append(" NÃO ESTÁ NA RAM!\n\n");
            sb.append("[PAGE FAULT DETECTADO]: Página Lógica ").append(res.logicalPage()).append(" ausente na RAM!\n");
            if (res.ramWasFull() && res.eviction() != null) {
                sb.append("Memória RAM cheia. Executando Substituição de Página (Algoritmo ")
                        .append(res.policyUsed().label).append("):\n");
                if (res.policyUsed() == PageReplacer.Policy.SEGUNDA_CHANCE) {
                    sb.append("- Algoritmo Segunda Chance (Clock): Página Lógica ").append(res.eviction().victimPage())
                            .append(" do PID ").append(res.eviction().victimPid())
                            .append(" selecionada pelo ponteiro com bit R=0.\n");
                } else {
                    sb.append("- Analisando histórico de acesso: Página Lógica ").append(res.eviction().victimPage())
                            .append(" do PID ").append(res.eviction().victimPid())
                            .append(" não é usada há ").append(res.eviction().idleMs()).append("ms (Mais antiga).\n");
                }
                sb.append("- Removendo Página Lógica ").append(res.eviction().victimPage())
                        .append(" do PID ").append(res.eviction().victimPid())
                        .append(" da RAM -> Salvando no Swap (Disco).\n");
            }
            if (res.restoredFromSwap()) {
                sb.append("- Restaurando Página Lógica ").append(res.logicalPage())
                        .append(" do Swap (slot ").append(res.restoredSwapSlot())
                        .append(") para a Moldura Física ").append(res.frame()).append(" (RAM Dinâmica).\n");
            } else {
                sb.append("- Carregando Página Lógica ").append(res.logicalPage())
                        .append(" do ").append(res.process().name)
                        .append(" na Moldura Física ").append(res.frame()).append(" (RAM Dinâmica).\n");
            }
            sb.append("- Tabela de Páginas atualizada: Página Lógica ").append(res.logicalPage())
                    .append(" ---> Moldura Física ").append(res.frame()).append(".");
        }
        return sb.toString();
    }

    static String virtualStatus(MemoryManager mm, boolean details, Report.Palette pal) {
        PagedMemory pm = mm.paged;
        StringBuilder sb = new StringBuilder();
        sb.append("--- MEMÓRIA DINÂMICA / PAGINAÇÃO ---\n");
        sb.append(String.format(Locale.ROOT, "Tamanho Total       : %s (%d molduras de %s)%n",
                Units.fmtSize(pm.totalBytes()), pm.numFrames, Units.fmtSize(pm.pageSize)));
        sb.append(String.format(Locale.ROOT, "Molduras Ocupadas   : %d / %d (Livres: %d)%n",
                pm.usedFrames(), pm.numFrames, pm.freeFrames()));
        sb.append(String.format(Locale.ROOT, "Política Atual      : %s%n", pm.policy.label));
        long totalRefs = pm.hits + pm.pageFaults;
        double hitRate = totalRefs == 0 ? 0.0 : ((double) pm.hits * 100.0 / totalRefs);
        sb.append(String.format(Locale.ROOT, "Hits / Page Faults  : %d / %d (Taxa de Acerto: %.1f%%)%n",
                pm.hits, pm.pageFaults, hitRate));
        sb.append(String.format(Locale.ROOT, "Espaço de Swap      : %d páginas (%s ocupados)%n",
                pm.swap.activeSlots(), Units.fmtSize(pm.swap.usedBytes())));

        if (details) {
            sb.append("\n[MAPA DAS MOLDURAS FÍSICAS DA RAM DINÂMICA]\n");
            List<PagedMemory.FrameInfo> frames = pm.framesSnapshot();
            for (int i = 0; i < frames.size(); i++) {
                PagedMemory.FrameInfo fi = frames.get(i);
                String idxStr = String.format(Locale.ROOT, "[Moldura %02d]", i);
                if (fi != null && fi.owner() != null) {
                    String procInfo = fi.owner().name + " (Pág. " + fi.logicalPage() + ")";
                    String flags = (fi.referenced() ? "R" : "-") + (fi.modified() ? "M" : "-");
                    String colored = pal != null ? pal.paint(procInfo + " [" + flags + "]", 36)
                            : procInfo + " [" + flags + "]";
                    sb.append(idxStr).append(" : ").append(colored).append("\n");
                } else {
                    String freeStr = pal != null ? pal.paint("[LIVRE]", 32) : "[LIVRE]";
                    sb.append(idxStr).append(" : ").append(freeStr).append("\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    static String hybridOverview(MemoryManager mm, boolean details, Report.Palette pal) {
        StringBuilder sb = new StringBuilder();
        long totalRam = mm.reserved.totalBytes + mm.paged.totalBytes();
        long usedRam = mm.reserved.fragmentation().usedBytes() + ((long) mm.paged.usedFrames() * mm.paged.pageSize);
        double ramPct = totalRam == 0 ? 0.0 : ((double) usedRam * 100.0 / totalRam);

        sb.append("=== VISÃO GERAL DA MEMÓRIA DO SISTEMA ===\n");
        sb.append(String.format(Locale.ROOT, "RAM Física Total    : %s (Reservada: %s | Dinâmica: %s)%n",
                Units.fmtSize(totalRam), fmtMb(mm.reserved.totalBytes), Units.fmtSize(mm.paged.totalBytes())));
        sb.append(String.format(Locale.ROOT, "RAM em Uso          : %s (%.1f%% ocupada)%n",
                Units.fmtSize(usedRam), ramPct));
        sb.append("\n");
        sb.append(reservedStatus(mm.reserved)).append("\n\n");
        sb.append(virtualStatus(mm, details, pal));
        return sb.toString();
    }

    static String periodicSummary(MemoryManager mm) {
        return "[MONITOR DE MEMÓRIA (memwatch)] " + mm.paged.usedFrames() + "/" + mm.paged.numFrames
                + " molduras (" + Units.fmtSize(mm.paged.totalBytes()) + ") | Faults: " + mm.paged.pageFaults
                + " | Swap: " + mm.paged.swap.activeSlots() + " pág. | Frag. Ext. Reservada: "
                + Units.fmtSize(mm.reserved.fragmentation().externalFragBytes());
    }

    static String processTable(MemoryManager mm, int pageSize) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-5s %-22s %-10s %-10s %-10s %-8s %-10s %-6s%n",
                "PID", "NOME", "TIPO", "DONO", "TAMANHO", "PÁGINAS", "RESIDENTES", "SWAP"));
        sb.append("-".repeat(85)).append("\n");
        for (SimProcess p : mm.processes()) {
            String numPagesStr = p.type == SimProcess.Type.CRITICAL ? "-" : String.valueOf(p.numPages());
            String resStr = p.type == SimProcess.Type.CRITICAL ? "FIXO" : String.valueOf(p.residentCount());
            String swapStr = p.type == SimProcess.Type.CRITICAL ? "-" : String.valueOf(p.swappedCount());
            sb.append(String.format(Locale.ROOT, "%-5d %-22s %-10s %-10s %-10s %-8s %-10s %-6s%n",
                    p.pid, p.name, p.type.label, p.owner, Units.fmtSize(p.sizeBytes), numPagesStr, resStr, swapStr));
        }
        return sb.toString().stripTrailing();
    }

    static String pageTableReport(SimProcess proc, int pageSize) {
        if (proc.type == SimProcess.Type.CRITICAL) {
            return "Processo crítico '" + proc.name + "' alocado contiguamente na Área Reservada (sem tabela de páginas).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TABELA DE PÁGINAS — Processo '").append(proc.name).append("' (PID ").append(proc.pid)
                .append(", ").append(Units.fmtSize(proc.sizeBytes)).append(")\n");
        sb.append(String.format(Locale.ROOT, "%-6s %-10s %-5s %-5s %-5s %-8s %-10s %-12s%n",
                "PÁG", "MOLDURA", "V", "R", "M", "SWAP", "OCUPADO", "FRAG.INT"));
        sb.append("-".repeat(68)).append("\n");
        for (int i = 0; i < proc.numPages(); i++) {
            SimProcess.PageEntry pe = proc.pageTable[i];
            String frameStr = pe.present ? String.valueOf(pe.frame) : "-";
            String vStr = pe.present ? "1" : "0";
            String rStr = pe.referenced ? "1" : "0";
            String mStr = pe.modified ? "1" : "0";
            String swapStr = pe.inSwap ? ("#" + pe.swapSlot) : "-";
            long internalFrag = (long) pageSize - pe.usedBytes;
            sb.append(String.format(Locale.ROOT, "%-6d %-10s %-5s %-5s %-5s %-8s %-10s %-12s%n",
                    pe.logicalPage, frameStr, vStr, rStr, mStr, swapStr, pe.usedBytes + " B", internalFrag + " B"));
        }
        sb.append("Fragmentação Interna Total: ").append(proc.internalFragmentation(pageSize)).append(" B.");
        return sb.toString();
    }

    static String simulationReport(List<PageReplacer.SimResult> results, boolean showSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- RESULTADO DA SIMULAÇÃO DE SUBSTITUIÇÃO DE PÁGINAS ---\n");
        sb.append(String.format(Locale.ROOT, "%-16s %-10s %-8s %-8s %-12s%n",
                "POLÍTICA", "MOLDURAS", "HITS", "FAULTS", "TAXA ACERTO"));
        sb.append("-".repeat(58)).append("\n");
        for (PageReplacer.SimResult r : results) {
            sb.append(String.format(Locale.ROOT, "%-16s %-10d %-8d %-8d %-12s%n",
                    r.policy().label, r.numFrames(), r.hits(), r.faults(),
                    String.format(Locale.ROOT, "%.1f%%", r.hitRatio() * 100.0)));
        }
        if (showSteps && !results.isEmpty()) {
            for (PageReplacer.SimResult r : results) {
                sb.append("\nPasso a passo (").append(r.policy().label).append("):\n");
                for (PageReplacer.StepTrace st : r.steps()) {
                    String hitStr = st.hit() ? "[HIT]" : "[FAULT]";
                    String evStr = st.evictedPage() != null ? " (Vítima: Pág " + st.evictedPage() + ")" : "";
                    sb.append("  Passo ").append(st.step()).append(": Ref Pág ").append(st.page())
                            .append(" -> Moldura ").append(st.frame()).append(" ").append(hitStr).append(evStr)
                            .append(" | Molduras: ").append(st.framesSnapshot()).append("\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }
}
