package fsim;

import java.util.Locale;
import java.util.Map;

/**
 * Controle de acesso discricionário (DAC): combina as permissões POSIX tradicionais (`rwx` para dono, grupo e outros)
 * com uma Lista de Controle de Acesso estendida (ACL POSIX.1e por usuário e por grupo). Operações em modo kernel
 * (superusuário) contornam a matriz de acesso; negações em modo usuário alimentam a trilha de auditoria.
 */
final class AccessControl {
    enum Action {
        READ(4, "LEITURA", "leitura"),
        WRITE(2, "ESCRITA", "escrita"),
        EXEC(1, "EXECUÇÃO", "execução");

        final int mask;
        final String label;
        final String noun;

        Action(int mask, String label, String noun) {
            this.mask = mask;
            this.label = label;
            this.noun = noun;
        }
    }

    private AccessControl() {
    }

    // --------------------------------------------------------------- avaliação
    /** Avalia se o contexto atual possui permissão na matriz POSIX + ACL, sem registrar auditoria. */
    static boolean hasPermission(Node node, AuthManager auth, Action action) {
        if (auth.isKernel()) {
            return true;
        }
        AuthManager.User user = auth.currentUser();
        int bits;
        if (user.name().equals(node.owner)) {
            bits = node.userAcl.getOrDefault(user.name(), (node.mode >> 6) & 7);
        } else if (node.userAcl.containsKey(user.name())) {
            bits = node.userAcl.get(user.name());
        } else {
            boolean matchedGroup = false;
            int groupBits = 0;
            if (user.inGroup(node.group)) {
                matchedGroup = true;
                groupBits |= (node.mode >> 3) & 7;
            }
            for (Map.Entry<String, Integer> entry : node.groupAcl.entrySet()) {
                if (user.inGroup(entry.getKey())) {
                    matchedGroup = true;
                    groupBits |= entry.getValue() & 7;
                }
            }
            bits = matchedGroup ? groupBits : (node.mode & 7);
        }
        return (bits & action.mask) != 0;
    }

    /** Verifica permissão; em caso de negação, registra `ACL_NEGADO` na auditoria e lança `AccessDeniedException`. */
    static void check(Node node, AuthManager auth, AuditLog audit, Action action) {
        if (hasPermission(node, auth, action)) {
            return;
        }
        AuthManager.User u = auth.currentUser();
        String perms = formatPermissions(node);
        if (audit != null) {
            audit.record("ACL_NEGADO", u.name(), auth.mode().name(),
                    action.label + " negada em '" + node.path() + "' (permissões: " + perms + ")");
        }
        throw new AccessDeniedException(node.path(), node.owner, node.group, perms,
                action.label, action.noun, u.name(), u.primaryGroup());
    }

    // ----------------------------------------------------------- interpretação
    /** Interpreta modo octal (`750`, `0644`) ou simbólico (`u=rwx,g=rx,o=`, `g+w`, `o-rwx`, `+x`, `a+r`). */
    static int parseMode(String spec, int currentMode) {
        if (spec == null || spec.isEmpty()) {
            throw new FSException("modo de permissão inválido: '" + (spec == null ? "" : spec)
                    + "' (use octal como 750 ou simbólico como u=rwx,g=rx,o=)");
        }
        if (Character.isDigit(spec.charAt(0))) {
            return parseOctalMode(spec);
        }
        int mode = currentMode & 0777;
        for (String clause : spec.split(",", -1)) {
            if (clause.isEmpty()) {
                throw new FSException("modo simbólico inválido: '" + spec + "'");
            }
            mode = applySymbolicClause(clause, mode, spec);
        }
        return mode;
    }

    private static int parseOctalMode(String spec) {
        if (spec.length() != 3 && spec.length() != 4) {
            throw new FSException("modo octal inválido: '" + spec + "' (use 3 ou 4 dígitos entre 000 e 0777)");
        }
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c < '0' || c > '7') {
                throw new FSException("modo octal inválido: '" + spec + "' (dígitos devem estar entre 0 e 7)");
            }
        }
        int mode = Integer.parseInt(spec, 8);
        if (mode < 0 || mode > 0777) {
            throw new FSException("modo octal fora do intervalo permitido: '" + spec + "' (use entre 000 e 0777)");
        }
        return mode;
    }

    private static int applySymbolicClause(String clause, int currentMode, String fullSpec) {
        boolean u = false;
        boolean g = false;
        boolean o = false;
        int idx = 0;
        while (idx < clause.length()) {
            char c = clause.charAt(idx);
            if (c == 'u') {
                u = true;
            } else if (c == 'g') {
                g = true;
            } else if (c == 'o') {
                o = true;
            } else if (c == 'a') {
                u = true;
                g = true;
                o = true;
            } else {
                break;
            }
            idx++;
        }
        if (idx == 0) {
            u = true;
            g = true;
            o = true;
        }
        if (idx >= clause.length()) {
            throw new FSException("modo simbólico inválido: '" + fullSpec + "'");
        }
        char op = clause.charAt(idx);
        if (op != '=' && op != '+' && op != '-') {
            throw new FSException("modo simbólico inválido: '" + fullSpec + "' (operadores aceitos: =, +, -)");
        }
        String permPart = clause.substring(idx + 1);
        if (op != '=' && permPart.isEmpty()) {
            throw new FSException("modo simbólico inválido: '" + fullSpec + "' (informe r, w ou x após '" + op + "')");
        }
        int bits = 0;
        for (int i = 0; i < permPart.length(); i++) {
            bits |= switch (permPart.charAt(i)) {
                case 'r' -> 4;
                case 'w' -> 2;
                case 'x' -> 1;
                default -> throw new FSException(
                        "permissão inválida em '" + fullSpec + "' (use apenas r, w ou x)");
            };
        }
        int classMask = (u ? 0700 : 0) | (g ? 0070 : 0) | (o ? 0007 : 0);
        int expandedBits = (u ? (bits << 6) : 0) | (g ? (bits << 3) : 0) | (o ? bits : 0);
        return switch (op) {
            case '=' -> (currentMode & ~classMask) | expandedBits;
            case '+' -> currentMode | expandedBits;
            case '-' -> currentMode & ~expandedBits;
            default -> throw new IllegalStateException("operador inesperado: " + op);
        };
    }

    /** Interpreta bits de uma entrada ACL (`0`..`7` ou combinação de `r`, `w`, `x`, `-`). */
    static int parseBits(String spec) {
        if (spec == null || spec.isEmpty()) {
            throw new FSException("permissões ACL inválidas: '" + (spec == null ? "" : spec)
                    + "' (use rwx, rw-, --- ou 0..7)");
        }
        if (spec.length() == 1 && spec.charAt(0) >= '0' && spec.charAt(0) <= '7') {
            return spec.charAt(0) - '0';
        }
        if (spec.length() > 3) {
            throw new FSException("permissões ACL inválidas: '" + spec + "' (use rwx, rw-, --- ou 0..7)");
        }
        int bits = 0;
        for (int i = 0; i < spec.length(); i++) {
            int bit = switch (spec.charAt(i)) {
                case 'r' -> 4;
                case 'w' -> 2;
                case 'x' -> 1;
                case '-' -> 0;
                default -> throw new FSException(
                        "permissões ACL inválidas: '" + spec + "' (use rwx, rw-, --- ou 0..7)");
            };
            if (bit != 0 && (bits & bit) != 0) {
                throw new FSException("permissões ACL inválidas: '" + spec + "' (bit repetido)");
            }
            bits |= bit;
        }
        return bits;
    }

    // -------------------------------------------------------------- formatação
    static String formatBits(int bits) {
        return ((bits & 4) != 0 ? "r" : "-")
                + ((bits & 2) != 0 ? "w" : "-")
                + ((bits & 1) != 0 ? "x" : "-");
    }

    static String symbolic(int mode) {
        return formatBits((mode >> 6) & 7)
                + formatBits((mode >> 3) & 7)
                + formatBits(mode & 7);
    }

    static String octal(int mode) {
        return String.format(Locale.ROOT, "%03o", mode & 0777);
    }

    static String formatPermissions(Node node) {
        return symbolic(node.mode) + (node.hasExtendedAcl() ? "+" : "") + " (" + octal(node.mode) + ")";
    }

    static String lsPermissions(Node node) {
        return (node.isDir ? "d" : "-") + symbolic(node.mode) + (node.hasExtendedAcl() ? "+" : " ");
    }
}
