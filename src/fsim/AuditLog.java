package fsim;

import java.util.ArrayList;
import java.util.List;

/**
 * Trilha cronológica de auditoria de segurança do sistema operacional em memória.
 *
 * Registra sequencialmente eventos sensíveis (autenticação, gestão de identidades, elevação de privilégio e decisões
 * de controle de acesso/criptografia) para garantir rastreabilidade e responsabilização (accountability).
 */
final class AuditLog {
    record Entry(int id, String category, String user, String mode, String detail) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private int nextId = 1;

    /** Registra um evento na trilha de auditoria com identificador sequencial iniciando em 1. */
    void record(String category, String user, String mode, String detail) {
        entries.add(new Entry(nextId++, category, user, mode, detail));
    }

    /** Devolve uma cópia imutável das entradas registradas até o momento. */
    List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Limpa todas as entradas e reinicia a numeração sequencial em 1. */
    void clear() {
        entries.clear();
        nextId = 1;
    }
}
