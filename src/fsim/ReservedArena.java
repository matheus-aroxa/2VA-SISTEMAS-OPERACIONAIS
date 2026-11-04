package fsim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Gerenciador de alocação de memória contígua para a Área Reservada da RAM.
 *
 * Destinada a processos críticos e ao núcleo base do sistema operacional,
 * utiliza estratégias de particionamento dinâmico (First-Fit, Best-Fit ou Worst-Fit)
 * com coalescência e compactação para mitigar a fragmentação externa.
 */
final class ReservedArena {

    static final String KERNEL_BASE_NAME = "Núcleo Base do SO";

    record Segment(long startByte, long lengthBytes, SimProcess process, int holeIndex) {
        long endByte() {
            return startByte + lengthBytes;
        }

        boolean isFree() {
            return process == null;
        }
    }

    record Move(SimProcess process, long oldStart, long oldEnd, long newStart, long newEnd) {
    }

    record FragReport(long totalBytes, long usedBytes, long totalFreeBytes,
                      long largestFreeBytes, long externalFragBytes,
                      int freeHolesCount, boolean fragmented) {
    }

    private static final class SegmentNode {
        long startByte;
        long lengthBytes;
        SimProcess process; // null se livre

        SegmentNode(long startByte, long lengthBytes, SimProcess process) {
            this.startByte = startByte;
            this.lengthBytes = lengthBytes;
            this.process = process;
        }

        boolean isFree() {
            return process == null;
        }

        long endByte() {
            return startByte + lengthBytes;
        }
    }

    final long totalBytes;
    final long kernelBaseBytes;
    final String fit; // "first", "best", "worst"
    final SimProcess kernelBaseProcess;

    private final List<SegmentNode> nodes = new ArrayList<>();

    ReservedArena(long totalBytes, long kernelBaseBytes, String fit) {
        if (totalBytes < 0) {
            throw new FSException("tamanho total da área reservada inválido: " + totalBytes);
        }
        if (kernelBaseBytes < 0 || kernelBaseBytes > totalBytes) {
            throw new FSException("tamanho do núcleo base do SO inválido: " + kernelBaseBytes);
        }
        this.totalBytes = totalBytes;
        this.kernelBaseBytes = kernelBaseBytes;
        this.fit = fit == null || fit.isBlank() ? "first" : fit.toLowerCase(Locale.ROOT);
        if (!this.fit.equals("first") && !this.fit.equals("best") && !this.fit.equals("worst")) {
            throw new FSException("política de alocação inválida: '" + fit + "' (use 'first', 'best' ou 'worst')");
        }

        if (kernelBaseBytes > 0) {
            this.kernelBaseProcess = new SimProcess(
                    0, KERNEL_BASE_NAME, SimProcess.Type.CRITICAL, kernelBaseBytes, "root", null, true, 0);
            nodes.add(new SegmentNode(0, kernelBaseBytes, this.kernelBaseProcess));
            if (totalBytes > kernelBaseBytes) {
                nodes.add(new SegmentNode(kernelBaseBytes, totalBytes - kernelBaseBytes, null));
            }
        } else {
            this.kernelBaseProcess = null;
            if (totalBytes > 0) {
                nodes.add(new SegmentNode(0, totalBytes, null));
            }
        }
    }

    Segment allocate(SimProcess process) {
        if (process == null) {
            throw new FSException("processo nulo não pode ser alocado");
        }
        if (process.sizeBytes <= 0) {
            throw new FSException("tamanho de processo deve ser maior que zero: " + process.sizeBytes);
        }
        if (segmentOf(process) != null) {
            throw new FSException("processo já alocado na área reservada: '" + process.name + "'");
        }

        long totalFreeBytes = 0;
        long largestFree = 0;
        List<SegmentNode> candidates = new ArrayList<>();

        for (SegmentNode node : nodes) {
            if (node.isFree()) {
                totalFreeBytes += node.lengthBytes;
                if (node.lengthBytes > largestFree) {
                    largestFree = node.lengthBytes;
                }
                if (node.lengthBytes >= process.sizeBytes) {
                    candidates.add(node);
                }
            }
        }

        if (candidates.isEmpty()) {
            if (totalFreeBytes >= process.sizeBytes) {
                throw new MemoryFullException(
                        "fragmentação externa na área reservada: há " + Units.fmtSize(totalFreeBytes)
                                + " livres, mas a maior lacuna contígua tem apenas " + Units.fmtSize(largestFree)
                                + " (necessários " + Units.fmtSize(process.sizeBytes)
                                + "). Execute 'memory_compact --reserved'.",
                        true);
            } else {
                throw new MemoryFullException(
                        "área reservada cheia: necessários " + Units.fmtSize(process.sizeBytes)
                                + ", livres " + Units.fmtSize(totalFreeBytes));
            }
        }

        SegmentNode chosen = switch (fit) {
            case "best" -> candidates.stream()
                    .min(Comparator.comparingLong(n -> n.lengthBytes))
                    .orElseThrow();
            case "worst" -> candidates.stream()
                    .max(Comparator.comparingLong(n -> n.lengthBytes))
                    .orElseThrow();
            default -> candidates.get(0);
        };

        int idx = nodes.indexOf(chosen);
        long allocatedStart = chosen.startByte;
        long allocatedLength = process.sizeBytes;
        long remainingLength = chosen.lengthBytes - process.sizeBytes;

        if (remainingLength > 0) {
            chosen.lengthBytes = allocatedLength;
            chosen.process = process;
            SegmentNode rest = new SegmentNode(allocatedStart + allocatedLength, remainingLength, null);
            nodes.add(idx + 1, rest);
        } else {
            chosen.process = process;
        }

        return new Segment(allocatedStart, allocatedLength, process, 0);
    }

    Segment free(SimProcess process) {
        if (process == null) {
            throw new FSException("processo nulo não pode ser liberado");
        }
        if (process.systemBase) {
            throw new FSException("não é permitido liberar o 'Núcleo Base do SO'");
        }
        int idx = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).process == process) {
                idx = i;
                break;
            }
        }
        if (idx == -1) {
            throw new FSException("processo não encontrado na área reservada: '" + process.name + "'");
        }

        SegmentNode target = nodes.get(idx);
        long freedStart = target.startByte;
        long freedLength = target.lengthBytes;
        target.process = null;

        // Coalescência com lacuna posterior
        if (idx + 1 < nodes.size() && nodes.get(idx + 1).isFree()) {
            SegmentNode next = nodes.remove(idx + 1);
            target.lengthBytes += next.lengthBytes;
        }
        // Coalescência com lacuna anterior
        if (idx - 1 >= 0 && nodes.get(idx - 1).isFree()) {
            SegmentNode prev = nodes.get(idx - 1);
            prev.lengthBytes += target.lengthBytes;
            nodes.remove(idx);
        }

        return new Segment(freedStart, freedLength, null, 0);
    }

    Segment segmentOf(SimProcess process) {
        if (process == null) {
            return null;
        }
        for (SegmentNode node : nodes) {
            if (node.process == process) {
                return new Segment(node.startByte, node.lengthBytes, node.process, 0);
            }
        }
        return null;
    }

    List<Segment> segments() {
        List<Segment> result = new ArrayList<>(nodes.size());
        int holeIndex = 1;
        for (SegmentNode node : nodes) {
            if (node.isFree()) {
                result.add(new Segment(node.startByte, node.lengthBytes, null, holeIndex++));
            } else {
                result.add(new Segment(node.startByte, node.lengthBytes, node.process, 0));
            }
        }
        return List.copyOf(result);
    }

    FragReport fragmentation() {
        long usedBytes = 0;
        long totalFreeBytes = 0;
        long largestFreeBytes = 0;
        int freeHolesCount = 0;

        for (SegmentNode node : nodes) {
            if (node.isFree()) {
                freeHolesCount++;
                totalFreeBytes += node.lengthBytes;
                if (node.lengthBytes > largestFreeBytes) {
                    largestFreeBytes = node.lengthBytes;
                }
            } else {
                usedBytes += node.lengthBytes;
            }
        }

        long externalFragBytes = freeHolesCount > 1 ? (totalFreeBytes - largestFreeBytes) : 0L;
        boolean fragmented = freeHolesCount > 1;

        return new FragReport(totalBytes, usedBytes, totalFreeBytes,
                largestFreeBytes, externalFragBytes, freeHolesCount, fragmented);
    }

    List<Move> compact() {
        List<Move> moves = new ArrayList<>();
        List<SegmentNode> newNodes = new ArrayList<>();

        long currentOffset = 0;
        if (kernelBaseBytes > 0 && kernelBaseProcess != null) {
            newNodes.add(new SegmentNode(0, kernelBaseBytes, kernelBaseProcess));
            currentOffset = kernelBaseBytes;
        }

        for (SegmentNode node : nodes) {
            if (!node.isFree() && node.process != kernelBaseProcess) {
                long oldStart = node.startByte;
                long oldEnd = node.endByte();
                long length = node.lengthBytes;
                long newStart = currentOffset;
                long newEnd = newStart + length;

                if (newStart != oldStart) {
                    moves.add(new Move(node.process, oldStart, oldEnd, newStart, newEnd));
                }
                newNodes.add(new SegmentNode(newStart, length, node.process));
                currentOffset = newEnd;
            }
        }

        long remainingFree = totalBytes - currentOffset;
        if (remainingFree > 0) {
            newNodes.add(new SegmentNode(currentOffset, remainingFree, null));
        }

        nodes.clear();
        nodes.addAll(newNodes);
        return List.copyOf(moves);
    }
}
