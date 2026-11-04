package fsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gerenciador de memória virtual paginada: molduras físicas na RAM, paginação sob demanda e substituição de páginas.
 */
final class PagedMemory {

    static final long CLOCK_STEP_MS = 15L;

    record FrameInfo(int frame, SimProcess owner, int logicalPage, boolean referenced,
                     boolean modified, long loadedAtMs, long lastAccessMs, int accessCount) {
    }

    record Translation(SimProcess process, int logicalPage, int offset, long virtualAddress,
                       boolean hit, int frame, long physicalAddress, boolean inSwap, int swapSlot) {
    }

    record EvictionInfo(int victimPid, String victimName, int victimPage, int frame,
                        long idleMs, int accessCount, int swapSlot, List<String> policyNotes) {
    }

    record AccessResult(SimProcess process, int logicalPage, String instruction, boolean writeOp,
                        boolean hit, int frame, boolean restoredFromSwap, int restoredSwapSlot,
                        boolean ramWasFull, PageReplacer.Policy policyUsed, EvictionInfo eviction) {
    }

    final int pageSize;
    final int numFrames;
    final SwapSpace swap;
    PageReplacer.Policy policy;
    long hits;
    long pageFaults;

    final byte[][] frames;
    final FrameInfo[] frameTable;
    final String[] lastFrameStatus;
    final Map<SimProcess, byte[]> processBackingData = new HashMap<>();
    int clockHand = 0;
    long currentClockMs = 0L;

    PagedMemory(int pageSize, int numFrames, PageReplacer.Policy policy) {
        if (pageSize <= 0) {
            throw new FSException("tamanho de página deve ser positivo: " + pageSize);
        }
        if (numFrames <= 0) {
            throw new FSException("quantidade de molduras deve ser positiva: " + numFrames);
        }
        this.pageSize = pageSize;
        this.numFrames = numFrames;
        this.policy = policy != null ? policy : PageReplacer.Policy.LRU;
        this.frames = new byte[numFrames][pageSize];
        this.frameTable = new FrameInfo[numFrames];
        this.lastFrameStatus = new String[numFrames];
        this.swap = new SwapSpace(pageSize);
    }

    long totalBytes() {
        return (long) pageSize * numFrames;
    }

    int usedFrames() {
        int count = 0;
        for (int i = 0; i < numFrames; i++) {
            if (frameTable[i] != null && frameTable[i].owner() != null) {
                count++;
            }
        }
        return count;
    }

    int freeFrames() {
        return numFrames - usedFrames();
    }

    long clockMs() {
        return currentClockMs;
    }

    void setPolicy(PageReplacer.Policy policy) {
        if (policy == PageReplacer.Policy.OTIMO) {
            throw new FSException("o algoritmo Ótimo exige conhecimento prévio de futuras referências (use 'page_sim --policy OTIMO')");
        }
        this.policy = policy != null ? policy : PageReplacer.Policy.LRU;
    }

    double hitRatio() {
        long total = hits + pageFaults;
        return total == 0L ? 0.0 : (double) hits / total;
    }

    void initProcessPages(SimProcess process, byte[] initialContent) {
        if (process == null) {
            return;
        }
        if (initialContent != null) {
            processBackingData.put(process, initialContent.clone());
        } else {
            processBackingData.remove(process);
        }
    }

    Translation translate(SimProcess process, int logicalPage, int offset, boolean loadOnMiss, RaidArray array) {
        if (process == null) {
            throw new FSException("processo não informado");
        }
        if (process.type == SimProcess.Type.CRITICAL) {
            throw new FSException("processo crítico '" + process.name + "' (PID " + process.pid
                    + ") reside na área reservada contígua, não na memória paginada");
        }
        if (process.pageTable == null || logicalPage < 0 || logicalPage >= process.pageTable.length) {
            throw new FSException("página lógica inválida: " + logicalPage + " (processo " + process.pid
                    + " possui " + process.numPages() + " páginas)");
        }
        if (offset < 0 || offset >= pageSize) {
            throw new FSException("deslocamento inválido: " + offset + " (tamanho da página é " + pageSize + ")");
        }
        SimProcess.PageEntry pe = process.pageTable[logicalPage];
        long vaddr = (long) logicalPage * pageSize + offset;
        if (pe.present) {
            currentClockMs += CLOCK_STEP_MS;

            pe.lastAccessMs = currentClockMs;
            pe.referenced = true;
            pe.accessCount++;
            hits++;
            frameTable[pe.frame] = new FrameInfo(pe.frame, process, logicalPage, true, pe.modified,
                    pe.loadedAtMs, currentClockMs, pe.accessCount);
            lastFrameStatus[pe.frame] = "HIT";
            long paddr = (long) pe.frame * pageSize + offset;
            return new Translation(process, logicalPage, offset, vaddr, true, pe.frame, paddr, false, -1);
        }
        if (loadOnMiss) {
            AccessResult ar = accessPage(process, logicalPage, "TRANSLATE", false, array);
            long paddr = (long) ar.frame() * pageSize + offset;
            return new Translation(process, logicalPage, offset, vaddr, false, ar.frame(), paddr, false, -1);
        } else {
            return new Translation(process, logicalPage, offset, vaddr, false, -1, -1, pe.inSwap, pe.swapSlot);
        }
    }

    AccessResult accessPage(SimProcess process, int logicalPage, String instruction, boolean writeOp, RaidArray array) {
        return accessPageAt(process, logicalPage, instruction, writeOp, array, currentClockMs + CLOCK_STEP_MS);
    }

    AccessResult accessPageAt(SimProcess process, int logicalPage, String instruction, boolean writeOp,
                              RaidArray array, long explicitClockMs) {
        currentClockMs = explicitClockMs;

        if (process == null) {
            throw new FSException("processo não informado");
        }
        if (process.type == SimProcess.Type.CRITICAL) {
            throw new FSException("processo crítico '" + process.name + "' (PID " + process.pid
                    + ") reside na área reservada contígua, não na memória paginada");
        }
        if (process.pageTable == null || logicalPage < 0 || logicalPage >= process.pageTable.length) {
            throw new FSException("página lógica inválida: " + logicalPage + " (processo " + process.pid
                    + " possui " + process.numPages() + " páginas)");
        }
        SimProcess.PageEntry pe = process.pageTable[logicalPage];
        if (pe.present) {
            hits++;
            pe.referenced = true;
            pe.lastAccessMs = currentClockMs;
            pe.accessCount++;
            if (writeOp) {
                pe.modified = true;
            }
            frameTable[pe.frame] = new FrameInfo(pe.frame, process, logicalPage, true, pe.modified,
                    pe.loadedAtMs, currentClockMs, pe.accessCount);
            lastFrameStatus[pe.frame] = "HIT";
            return new AccessResult(process, logicalPage, instruction, writeOp, true, pe.frame,
                    false, -1, false, policy, null);
        }

        // Falta de página (Page Fault)
        pageFaults++;
        boolean ramFull = (freeFrames() == 0);
        EvictionInfo eviction = null;
        int targetFrame = -1;

        if (!ramFull) {
            for (int f = 0; f < numFrames; f++) {
                if (frameTable[f] == null) {
                    targetFrame = f;
                    break;
                }
            }
        } else {
            int victimFrame = chooseVictimFrame();
            FrameInfo vf = frameTable[victimFrame];
            SimProcess vOwner = vf.owner();
            SimProcess.PageEntry vPe = vOwner.pageTable[vf.logicalPage()];
            long idleMs = currentClockMs - vf.lastAccessMs();

            SwapSpace.SwapIo swapIo = swap.swapOut(vPe.swapSlot, frames[victimFrame], array);
            vPe.present = false;
            vPe.frame = -1;
            vPe.inSwap = true;
            vPe.swapSlot = swapIo.slot();

            List<String> notes = new ArrayList<>();
            notes.add("política: " + policy.label);
            if (policy == PageReplacer.Policy.LRU) {
                notes.add("ociosa há " + idleMs + " ms");
            } else if (policy == PageReplacer.Policy.FIFO) {
                notes.add("carregada há " + (currentClockMs - vf.loadedAtMs()) + " ms");
            } else if (policy == PageReplacer.Policy.LFU) {
                notes.add(vf.accessCount() + " acessos");
            } else if (policy == PageReplacer.Policy.SEGUNDA_CHANCE) {
                notes.add("segunda chance expirada");
            }

            eviction = new EvictionInfo(vOwner.pid, vOwner.name, vf.logicalPage(), victimFrame,
                    idleMs, vf.accessCount(), swapIo.slot(), Collections.unmodifiableList(notes));
            targetFrame = victimFrame;
        }

        boolean restored = false;
        int oldSwapSlot = -1;
        if (pe.inSwap) {
            byte[] data = swap.swapIn(pe.swapSlot, array);
            System.arraycopy(data, 0, frames[targetFrame], 0, Math.min(data.length, pageSize));
            if (data.length < pageSize) {
                Arrays.fill(frames[targetFrame], data.length, pageSize, (byte) 0);
            }
            oldSwapSlot = pe.swapSlot;
            swap.freeSlot(pe.swapSlot);
            pe.inSwap = false;
            pe.swapSlot = -1;
            restored = true;
        } else {
            byte[] back = processBackingData.get(process);
            int start = logicalPage * pageSize;
            if (back != null && start < back.length) {
                int len = Math.min(pageSize, back.length - start);
                System.arraycopy(back, start, frames[targetFrame], 0, len);
                if (len < pageSize) {
                    Arrays.fill(frames[targetFrame], len, pageSize, (byte) 0);
                }
            } else {
                Arrays.fill(frames[targetFrame], (byte) 0);
            }
        }

        pe.present = true;
        pe.frame = targetFrame;
        pe.referenced = true;
        pe.modified = writeOp;
        pe.loadedAtMs = currentClockMs;
        pe.lastAccessMs = currentClockMs;
        pe.accessCount = 1;
        frameTable[targetFrame] = new FrameInfo(targetFrame, process, logicalPage, true, writeOp,
                currentClockMs, currentClockMs, 1);

        if (!restored) {
            lastFrameStatus[targetFrame] = "PAGE FAULT (NEW)";
        } else {
            lastFrameStatus[targetFrame] = "PAGE FAULT (SWAP)";
        }

        return new AccessResult(process, logicalPage, instruction, writeOp, false, targetFrame,
                restored, oldSwapSlot, ramFull, policy, eviction);
    }

    int chooseVictimFrame() {
        return switch (policy) {
            case LRU -> {
                int victim = 0;
                long minAccess = Long.MAX_VALUE;
                for (int f = 0; f < numFrames; f++) {
                    FrameInfo fi = frameTable[f];
                    if (fi != null && fi.lastAccessMs() < minAccess) {
                        minAccess = fi.lastAccessMs();
                        victim = f;
                    }
                }
                yield victim;
            }
            case FIFO -> {
                int victim = 0;
                long minLoaded = Long.MAX_VALUE;
                for (int f = 0; f < numFrames; f++) {
                    FrameInfo fi = frameTable[f];
                    if (fi != null && fi.loadedAtMs() < minLoaded) {
                        minLoaded = fi.loadedAtMs();
                        victim = f;
                    }
                }
                yield victim;
            }
            case LFU -> {
                int victim = 0;
                int minCount = Integer.MAX_VALUE;
                for (int f = 0; f < numFrames; f++) {
                    FrameInfo fi = frameTable[f];
                    if (fi != null && fi.accessCount() < minCount) {
                        minCount = fi.accessCount();
                        victim = f;
                    }
                }
                yield victim;
            }
            case SEGUNDA_CHANCE -> {
                int inspected = 0;
                while (frameTable[clockHand] != null && frameTable[clockHand].referenced()) {
                    FrameInfo fi = frameTable[clockHand];
                    frameTable[clockHand] = new FrameInfo(fi.frame(), fi.owner(), fi.logicalPage(),
                            false, fi.modified(), fi.loadedAtMs(), fi.lastAccessMs(), fi.accessCount());
                    if (fi.owner() != null && fi.owner().pageTable != null
                            && fi.logicalPage() < fi.owner().pageTable.length) {
                        fi.owner().pageTable[fi.logicalPage()].referenced = false;
                    }
                    clockHand = (clockHand + 1) % numFrames;
                    inspected++;
                    if (inspected > numFrames * 2) {
                        break;
                    }
                }
                int victim = clockHand;
                clockHand = (clockHand + 1) % numFrames;
                yield victim;
            }
            case OTIMO -> throw new FSException(
                    "o algoritmo Ótimo exige conhecimento prévio de futuras referências (use 'page_sim --policy OTIMO')");
        };
    }

    int[] releaseProcess(SimProcess process) {
        if (process == null) {
            return new int[]{0, 0};
        }
        int freedFrames = 0;
        for (int f = 0; f < numFrames; f++) {
            if (frameTable[f] != null && frameTable[f].owner() == process) {
                Arrays.fill(frames[f], (byte) 0);
                frameTable[f] = null;
                lastFrameStatus[f] = null;
                freedFrames++;
            }
        }
        int freedSwapSlots = 0;
        if (process.pageTable != null) {
            for (SimProcess.PageEntry pe : process.pageTable) {
                if (pe.inSwap && pe.swapSlot >= 0) {
                    swap.freeSlot(pe.swapSlot);
                    pe.inSwap = false;
                    pe.swapSlot = -1;
                    freedSwapSlots++;
                }
                if (pe.present) {
                    pe.present = false;
                    pe.frame = -1;
                }
            }
        }
        processBackingData.remove(process);
        return new int[]{freedFrames, freedSwapSlots};
    }

    List<FrameInfo> framesSnapshot() {
        List<FrameInfo> list = new ArrayList<>(numFrames);
        for (int f = 0; f < numFrames; f++) {
            if (frameTable[f] != null) {
                list.add(frameTable[f]);
            } else {
                list.add(new FrameInfo(f, null, -1, false, false, 0L, 0L, 0));
            }
        }
        return Collections.unmodifiableList(list);
    }

    byte[] peekFrameBytes(int frame) {
        if (frame < 0 || frame >= numFrames) {
            throw new FSException("moldura inválida: " + frame + " (0.." + (numFrames - 1) + ")");
        }
        return frames[frame].clone();
    }

    void writeFrameBytes(int frame, int offset, byte[] data) {
        if (frame < 0 || frame >= numFrames) {
            throw new FSException("moldura inválida: " + frame);
        }
        if (offset < 0 || data == null || offset + data.length > pageSize) {
            throw new FSException("escrita fora dos limites da moldura");
        }
        System.arraycopy(data, 0, frames[frame], offset, data.length);
        if (frameTable[frame] != null) {
            FrameInfo fi = frameTable[frame];
            frameTable[frame] = new FrameInfo(fi.frame(), fi.owner(), fi.logicalPage(),
                    fi.referenced(), true, fi.loadedAtMs(), currentClockMs, fi.accessCount());
            if (fi.owner() != null && fi.owner().pageTable != null
                    && fi.logicalPage() < fi.owner().pageTable.length) {
                fi.owner().pageTable[fi.logicalPage()].modified = true;
            }
        }
    }

    String frameStatus(int frame) {
        if (frame < 0 || frame >= numFrames) {
            return "-";
        }
        String st = lastFrameStatus[frame];
        return st != null ? st : (frameTable[frame] != null ? "PRESENTE" : "LIVRE");
    }

    void reset() {
        for (int f = 0; f < numFrames; f++) {
            Arrays.fill(frames[f], (byte) 0);
            frameTable[f] = null;
        }
        Arrays.fill(lastFrameStatus, null);
        processBackingData.clear();
        clockHand = 0;
        currentClockMs = 0L;
        hits = 0;
        pageFaults = 0;
        swap.reset();
    }
}
