package fsim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thread simulada dentro de um processo no sistema operacional.
 *
 * Representa uma linha de execução que compartilha o mesmo espaço de endereçamento
 * e recursos do processo pai, capaz de concorrer por mutexes.
 */
final class SimThread {

    enum ActionKind {
        LOCK,
        UNLOCK
    }

    record MutexAction(ActionKind kind, String mutexName, long triggerProcessCpuMs, long holdCpuMs) {}

    final int tid;
    final String name;
    final SimProcess process;
    SimProcess.State state;
    String waitingMutex;
    final List<MutexAction> actions = new ArrayList<>();
    final Set<Integer> firedActions = new HashSet<>();

    SimThread(int tid, String name, SimProcess process) {
        this.tid = tid;
        this.name = name;
        this.process = process;
        this.state = (process != null && process.state != null) ? process.state : SimProcess.State.NOVO;
    }

    void scheduleLock(String mutexName, long triggerProcessCpuMs, long holdCpuMs) {
        actions.add(new MutexAction(ActionKind.LOCK, mutexName, triggerProcessCpuMs, holdCpuMs));
    }

    void scheduleUnlock(String mutexName, long triggerProcessCpuMs) {
        actions.add(new MutexAction(ActionKind.UNLOCK, mutexName, triggerProcessCpuMs, 0L));
    }

    List<MutexAction> pollDueActions(long processExecutedCpuMs) {
        List<MutexAction> due = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            if (!firedActions.contains(i)) {
                MutexAction action = actions.get(i);
                if (processExecutedCpuMs >= action.triggerProcessCpuMs) {
                    firedActions.add(i);
                    due.add(action);
                }
            }
        }
        return due;
    }

    void reset() {
        this.state = (process != null && process.state != null) ? process.state : SimProcess.State.NOVO;
        this.waitingMutex = null;
        this.firedActions.clear();
    }

    @Override
    public String toString() {
        return name != null ? name : ("T" + tid);
    }
}
