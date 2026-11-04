package fsim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gerenciador de exclusão mútua (mutexes) do sistema operacional.
 *
 * Controla a aquisição, fila de espera por contenção e liberação de travas
 * concorrentes entre threads de processos.
 */
final class MutexManager {

    record LockEvent(long timeMs, SimThread thread, String mutexName, SimThread.ActionKind action,
                     boolean succeeded, SimThread holder, SimThread wokenThread) {}

    static final class Mutex {
        final String name;
        SimThread owner;
        final Deque<SimThread> waitQueue = new ArrayDeque<>();
        long acquisitions;
        long contentions;

        Mutex(String name) {
            this.name = name;
        }

        boolean isLocked() {
            return owner != null;
        }
    }

    final Map<String, Mutex> mutexes = new LinkedHashMap<>();

    Mutex getOrCreate(String name) {
        return mutexes.computeIfAbsent(name, Mutex::new);
    }

    LockEvent lock(SimThread thread, String mutexName, long timeMs) {
        Mutex m = getOrCreate(mutexName);
        if (m.owner == null) {
            m.owner = thread;
            m.acquisitions++;
            if (thread != null) {
                thread.waitingMutex = null;
            }
            return new LockEvent(timeMs, thread, mutexName, SimThread.ActionKind.LOCK, true, thread, null);
        }
        if (m.owner == thread) {
            m.acquisitions++;
            return new LockEvent(timeMs, thread, mutexName, SimThread.ActionKind.LOCK, true, thread, null);
        }
        m.contentions++;
        if (thread != null) {
            thread.state = SimProcess.State.BLOQUEADO;
            thread.waitingMutex = mutexName;
            m.waitQueue.add(thread);
        }
        return new LockEvent(timeMs, thread, mutexName, SimThread.ActionKind.LOCK, false, m.owner, null);
    }

    LockEvent unlock(SimThread thread, String mutexName, long timeMs) {
        Mutex m = mutexes.get(mutexName);
        if (m == null || m.owner != thread) {
            return new LockEvent(timeMs, thread, mutexName, SimThread.ActionKind.UNLOCK, false,
                    m != null ? m.owner : null, null);
        }
        m.owner = null;
        SimThread woken = null;
        if (!m.waitQueue.isEmpty()) {
            woken = m.waitQueue.poll();
            m.owner = woken;
            m.acquisitions++;
            woken.state = SimProcess.State.PRONTO;
            woken.waitingMutex = null;
        }
        return new LockEvent(timeMs, thread, mutexName, SimThread.ActionKind.UNLOCK, true, m.owner, woken);
    }

    List<LockEvent> releaseAllOwnedBy(SimProcess process, long timeMs) {
        if (process == null) {
            return List.of();
        }
        List<LockEvent> events = new ArrayList<>();
        for (Mutex m : mutexes.values()) {
            m.waitQueue.removeIf(t -> t.process == process);
            if (m.owner != null && m.owner.process == process) {
                events.add(unlock(m.owner, m.name, timeMs));
            }
        }
        return events;
    }

    List<Mutex> mutexes() {
        return new ArrayList<>(mutexes.values());
    }

    void reset() {
        mutexes.clear();
    }
}
