package fsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Algoritmos de substituição de páginas da memória virtual: LRU, Segunda Chance (Clock), FIFO, LFU e Ótimo (Belady).
 */
final class PageReplacer {

    enum Policy {
        LRU("LRU"),
        SEGUNDA_CHANCE("Segunda Chance"),
        FIFO("FIFO"),
        LFU("LFU"),
        OTIMO("Ótimo");

        final String label;

        Policy(String label) {
            this.label = label;
        }

        static Policy parse(String text) {
            if (text == null) {
                throw new FSException("política de substituição não informada");
            }
            String s = text.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
            return switch (s) {
                case "LRU" -> LRU;
                case "SEGUNDA_CHANCE", "CLOCK", "2CHANCE", "SC" -> SEGUNDA_CHANCE;
                case "FIFO" -> FIFO;
                case "LFU" -> LFU;
                case "OTIMO", "OPT", "OPTIMAL" -> OTIMO;
                default -> throw new FSException("política desconhecida: '" + text
                        + "' (use LRU, Segunda Chance, FIFO, LFU ou Ótimo)");
            };
        }

        static Policy parseLive(String text) {
            Policy p = parse(text);
            if (p == OTIMO) {
                throw new FSException("o algoritmo Ótimo exige conhecimento prévio de futuras referências (use 'page_sim --policy OTIMO')");
            }
            return p;
        }
    }

    record StepTrace(int step, int page, boolean hit, int frame, Integer evictedPage, List<Integer> framesSnapshot) {
    }

    record SimResult(Policy policy, int numFrames, List<Integer> references, int hits, int faults,
                     double hitRatio, List<StepTrace> steps) {
    }

    private PageReplacer() {
    }

    /** Simula a execução offline de substituição de páginas para a sequência de referências e molduras informadas. */
    static SimResult simulate(Policy policy, int numFrames, List<Integer> references) {
        if (policy == null) {
            throw new FSException("política de substituição não informada");
        }
        if (numFrames <= 0) {
            throw new FSException("quantidade de molduras deve ser positiva: " + numFrames);
        }
        if (references == null) {
            throw new FSException("referências de páginas não informadas");
        }
        if (references.isEmpty()) {
            return new SimResult(policy, numFrames, List.of(), 0, 0, 0.0, List.of());
        }
        for (Integer ref : references) {
            if (ref == null || ref < 0) {
                throw new FSException("referência de página inválida: " + ref);
            }
        }

        Integer[] frames = new Integer[numFrames];
        int hits = 0;
        int faults = 0;
        List<StepTrace> steps = new ArrayList<>();

        int[] lastAccess = new int[numFrames];
        int[] loadedAt = new int[numFrames];
        int[] accessCount = new int[numFrames];
        boolean[] refBit = new boolean[numFrames];
        int clockHand = 0;

        for (int i = 0; i < references.size(); i++) {
            int page = references.get(i);
            int stepNum = i + 1;
            int hitFrame = -1;
            for (int f = 0; f < numFrames; f++) {
                if (frames[f] != null && frames[f] == page) {
                    hitFrame = f;
                    break;
                }
            }

            boolean hit = (hitFrame >= 0);
            int targetFrame = -1;
            Integer evictedPage = null;

            if (hit) {
                hits++;
                targetFrame = hitFrame;
                lastAccess[hitFrame] = stepNum;
                accessCount[hitFrame]++;
                refBit[hitFrame] = true;
            } else {
                faults++;
                int freeFrame = -1;
                for (int f = 0; f < numFrames; f++) {
                    if (frames[f] == null) {
                        freeFrame = f;
                        break;
                    }
                }

                if (freeFrame >= 0) {
                    targetFrame = freeFrame;
                } else {
                    int victim = -1;
                    switch (policy) {
                        case LRU -> {
                            victim = 0;
                            int min = lastAccess[0];
                            for (int f = 1; f < numFrames; f++) {
                                if (lastAccess[f] < min) {
                                    min = lastAccess[f];
                                    victim = f;
                                }
                            }
                        }
                        case FIFO -> {
                            victim = 0;
                            int min = loadedAt[0];
                            for (int f = 1; f < numFrames; f++) {
                                if (loadedAt[f] < min) {
                                    min = loadedAt[f];
                                    victim = f;
                                }
                            }
                        }
                        case LFU -> {
                            victim = 0;
                            int min = accessCount[0];
                            for (int f = 1; f < numFrames; f++) {
                                if (accessCount[f] < min) {
                                    min = accessCount[f];
                                    victim = f;
                                }
                            }
                        }
                        case SEGUNDA_CHANCE -> {
                            while (refBit[clockHand]) {
                                refBit[clockHand] = false;
                                clockHand = (clockHand + 1) % numFrames;
                            }
                            victim = clockHand;
                            clockHand = (clockHand + 1) % numFrames;
                        }
                        case OTIMO -> {
                            victim = 0;
                            int farthest = -1;
                            for (int f = 0; f < numFrames; f++) {
                                int pageInFrame = frames[f];
                                int nextUse = Integer.MAX_VALUE;
                                for (int j = i + 1; j < references.size(); j++) {
                                    if (references.get(j).equals(pageInFrame)) {
                                        nextUse = j;
                                        break;
                                    }
                                }
                                if (nextUse > farthest) {
                                    farthest = nextUse;
                                    victim = f;
                                }
                            }
                        }
                    }
                    evictedPage = frames[victim];
                    targetFrame = victim;
                }

                frames[targetFrame] = page;
                lastAccess[targetFrame] = stepNum;
                loadedAt[targetFrame] = stepNum;
                accessCount[targetFrame] = 1;
                refBit[targetFrame] = true;
            }

            List<Integer> snapshot = new ArrayList<>(numFrames);
            for (Integer f : frames) {
                snapshot.add(f);
            }
            steps.add(new StepTrace(stepNum, page, hit, targetFrame, evictedPage,
                    Collections.unmodifiableList(snapshot)));
        }

        double hitRatio = (double) hits / references.size();
        return new SimResult(policy, numFrames, List.copyOf(references), hits, faults,
                hitRatio, Collections.unmodifiableList(steps));
    }

    static List<Policy> allPolicies() {
        return List.of(Policy.LRU, Policy.SEGUNDA_CHANCE, Policy.FIFO, Policy.LFU, Policy.OTIMO);
    }
}
