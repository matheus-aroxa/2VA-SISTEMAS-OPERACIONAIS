package fsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Subsistema de identidade, autenticação e separação de privilégios por modo dual de execução.
 *
 * Modela contas de usuários, grupos e credenciais armazenadas apenas como hash criptográfico SHA-256 combinado com
 * um salt aleatório de 16 bytes por senha (mitigando ataques de dicionário pré-computado / rainbow tables) e
 * verificadas em tempo constante. Também controla o modo de operação atual (MODO_USUARIO vs MODO_KERNEL) e a
 * elevação temporária de privilégio estilo sudo.
 */
final class AuthManager {
    private static final int SALT_BYTES = 16;
    private static final int MAX_NAME_LEN = 32;
    private static final String ROOT_USER = "root";
    private static final String ADMIN_GROUP = "admin";
    private static final String DEFAULT_GROUP = "users";

    private static final String CAT_IDENTITY = "IDENTIDADE";
    private static final String CAT_AUTH = "AUTENTICACAO";
    private static final String CAT_PRIVILEGE = "PRIVILEGIO";

    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();

    enum Mode { MODO_USUARIO, MODO_KERNEL }

    record User(
            String name,
            String primaryGroup,
            Set<String> extraGroups,
            byte[] salt,
            byte[] passwordHash,
            boolean superuser) {

        User {
            extraGroups = extraGroups == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(extraGroups));
            salt = salt.clone();
            passwordHash = passwordHash.clone();
        }

        boolean inGroup(String group) {
            return group != null && (primaryGroup.equals(group) || extraGroups.contains(group));
        }

        /** Conjunto não modificável preservando a ordem: `primaryGroup` seguido de `extraGroups`. */
        Set<String> allGroups() {
            Set<String> all = new LinkedHashSet<>();
            all.add(primaryGroup);
            all.addAll(extraGroups);
            return Collections.unmodifiableSet(all);
        }

        String saltHex() {
            return toHexLower(salt);
        }

        String hashHex() {
            return toHexLower(passwordHash);
        }
    }

    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> groups = new LinkedHashSet<>();
    private final Map<String, User> users = new LinkedHashMap<>();

    private User currentUser;
    private Mode currentMode;

    AuthManager(AuditLog audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
        groups.add(ADMIN_GROUP);
        groups.add(DEFAULT_GROUP);

        byte[] salt = generateSalt();
        byte[] hash = hashPassword(salt, ROOT_USER);
        User root = new User(ROOT_USER, ADMIN_GROUP, Set.of(), salt, hash, true);
        users.put(ROOT_USER, root);
        // inicializa em MODO_KERNEL como root sem poluir a trilha de auditoria
        this.currentUser = root;
        this.currentMode = Mode.MODO_KERNEL;
    }

    // --------------------------------------------------------- sessão e modo

    User currentUser() {
        return currentUser;
    }

    Mode mode() {
        return currentMode;
    }

    boolean isKernel() {
        return currentMode == Mode.MODO_KERNEL;
    }

    /** Exige `MODO_KERNEL` para chamadas administrativas do sistema; registra tentativa negada na auditoria. */
    void requireKernel(String operation) {
        if (!isKernel()) {
            audit.record(CAT_PRIVILEGE, currentUser.name(), currentMode.name(),
                    "negado: '" + operation + "' exige MODO_KERNEL");
            throw new FSException("operação privilegiada: '" + operation
                    + "' exige MODO_KERNEL (usuário '" + currentUser.name()
                    + "' está em MODO_USUARIO; use 'sudo' ou autentique-se como 'root')");
        }
    }

    // ---------------------------------------------------------------- grupos

    void addGroup(String group) {
        requireKernel("groupadd");
        validateIdentifier(group, "grupo");
        if (groups.contains(group)) {
            throw new FSException("grupo já existe: '" + group + "'");
        }
        groups.add(group);
        audit.record(CAT_IDENTITY, currentUser.name(), currentMode.name(),
                "grupo criado: '" + group + "'");
    }

    boolean groupExists(String group) {
        return groups.contains(group);
    }

    Set<String> groups() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(groups));
    }

    // -------------------------------------------------------------- usuários

    boolean userExists(String name) {
        return users.containsKey(name);
    }

    User getUser(String name) {
        User user = users.get(name);
        if (user == null) {
            throw new FSException("usuário não encontrado: '" + name + "'");
        }
        return user;
    }

    Collection<User> users() {
        return List.copyOf(users.values());
    }

    User addUser(String name, String password, String primaryGroup, boolean superuser) {
        requireKernel("useradd");
        validateIdentifier(name, "usuário");
        if (users.containsKey(name)) {
            throw new FSException("usuário já existe: '" + name + "'");
        }
        validatePassword(password);
        String group = (primaryGroup == null || primaryGroup.isEmpty()) ? DEFAULT_GROUP : primaryGroup;
        validateIdentifier(group, "grupo");

        groups.add(group);
        byte[] salt = generateSalt();
        byte[] hash = hashPassword(salt, password);
        Set<String> extra = (superuser && !ADMIN_GROUP.equals(group)) ? Set.of(ADMIN_GROUP) : Set.of();
        User user = new User(name, group, extra, salt, hash, superuser);
        users.put(name, user);
        audit.record(CAT_IDENTITY, currentUser.name(), currentMode.name(),
                "usuário criado: '" + name + "' (grupo='" + group + "', superuser=" + superuser + ")");
        return user;
    }

    void removeUser(String name) {
        requireKernel("userdel");
        if (ROOT_USER.equals(name)) {
            throw new FSException("não é permitido remover o superusuário 'root'");
        }
        if (currentUser.name().equals(name)) {
            throw new FSException("não é permitido remover o usuário autenticado: '" + name + "'");
        }
        if (!users.containsKey(name)) {
            throw new FSException("usuário não encontrado: '" + name + "'");
        }
        users.remove(name);
        audit.record(CAT_IDENTITY, currentUser.name(), currentMode.name(),
                "usuário removido: '" + name + "'");
    }

    void addUserToGroup(String name, String group) {
        requireKernel("usermod");
        User target = getUser(name);
        if (!groups.contains(group)) {
            throw new FSException("grupo não encontrado: '" + group + "'");
        }
        Set<String> updatedExtra = new LinkedHashSet<>(target.extraGroups());
        if (!target.primaryGroup().equals(group)) {
            updatedExtra.add(group);
        }
        User updated = new User(
                target.name(),
                target.primaryGroup(),
                updatedExtra,
                target.salt(),
                target.passwordHash(),
                target.superuser());
        users.put(name, updated);
        if (currentUser.name().equals(name)) {
            currentUser = updated;
        }
        audit.record(CAT_IDENTITY, currentUser.name(), currentMode.name(),
                "usuário '" + name + "' adicionado ao grupo '" + group + "'");
    }

    // -------------------------------------------- autenticação e privilégios

    void changePassword(String targetUser, String newPassword) {
        if (!isKernel() && !currentUser.name().equals(targetUser)) {
            requireKernel("passwd");
        }
        User target = getUser(targetUser);
        validatePassword(newPassword);

        byte[] salt = generateSalt();
        byte[] hash = hashPassword(salt, newPassword);
        User updated = new User(
                target.name(),
                target.primaryGroup(),
                target.extraGroups(),
                salt,
                hash,
                target.superuser());
        users.put(targetUser, updated);
        if (currentUser.name().equals(targetUser)) {
            currentUser = updated;
        }
        audit.record(CAT_AUTH, currentUser.name(), currentMode.name(),
                "senha alterada para o usuário '" + targetUser + "'");
    }

    /** Verifica `password` calculando `SHA-256(salt || password_utf8)` e comparando em tempo constante. */
    boolean verifyPassword(User user, String password) {
        if (user == null || password == null) {
            return false;
        }
        byte[] candidate = hashPassword(user.salt(), password);
        return MessageDigest.isEqual(user.passwordHash(), candidate);
    }

    User login(String name, String password) {
        User user = name == null ? null : users.get(name);
        if (user == null || !verifyPassword(user, password)) {
            String subject = (name == null || name.isEmpty()) ? currentUser.name() : name;
            audit.record(CAT_AUTH, subject, currentMode.name(),
                    "falha na autenticação para '" + name + "'");
            throw new FSException("falha na autenticação: usuário ou senha inválidos para '" + name + "'");
        }
        currentUser = user;
        currentMode = user.superuser() ? Mode.MODO_KERNEL : Mode.MODO_USUARIO;
        audit.record(CAT_AUTH, currentUser.name(), currentMode.name(),
                "login realizado para '" + name + "'");
        return user;
    }

    void logout() {
        String previousUser = currentUser.name();
        String previousMode = currentMode.name();
        currentUser = users.get(ROOT_USER);
        currentMode = Mode.MODO_KERNEL;
        audit.record(CAT_AUTH, previousUser, previousMode,
                "logout de '" + previousUser + "' (sessão restaurada para 'root' em MODO_KERNEL)");
    }

    /** Executa `action` temporariamente em `MODO_KERNEL` mediante senha de `root` ou de administrador. */
    void runElevated(String password, Runnable action) {
        if (isKernel()) {
            action.run();
            return;
        }
        User root = users.get(ROOT_USER);
        boolean canUseOwnPassword = currentUser.superuser() || currentUser.inGroup(ADMIN_GROUP);
        boolean valid = (root != null && verifyPassword(root, password))
                || (canUseOwnPassword && verifyPassword(currentUser, password));
        if (!valid) {
            audit.record(CAT_PRIVILEGE, currentUser.name(), currentMode.name(),
                    "falha no sudo: senha de elevação incorreta");
            throw new FSException("sudo: senha de elevação incorreta");
        }
        Mode previousMode = currentMode;
        String callerName = currentUser.name();
        currentMode = Mode.MODO_KERNEL;
        audit.record(CAT_PRIVILEGE, currentUser.name(), currentMode.name(),
                "elevação temporária via sudo para MODO_KERNEL");
        try {
            action.run();
        } finally {
            if (currentUser.name().equals(callerName)) {
                currentMode = previousMode;
            }
        }
    }

    // -------------------------------------------------------------- internos

    private byte[] generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return salt;
    }

    private static byte[] hashPassword(byte[] salt, String password) {
        // hash = SHA-256(salt || password_utf8)
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("algoritmo SHA-256 indisponível", e);
        }
    }

    private static void validateIdentifier(String value, String kind) {
        if (value == null || value.isEmpty()) {
            throw new FSException("nome de " + kind + " não pode ser vazio");
        }
        if (value.length() > MAX_NAME_LEN) {
            throw new FSException("nome de " + kind + " muito longo: '" + value + "' (máximo 32 caracteres)");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || ch == '/' || ch == ':') {
                throw new FSException("nome de " + kind + " inválido: '" + value
                        + "' (não use espaços, '/' ou ':')");
            }
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new FSException("a senha não pode ser vazia");
        }
    }

    private static String toHexLower(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_LOWER[v >>> 4];
            out[i * 2 + 1] = HEX_LOWER[v & 0x0F];
        }
        return new String(out);
    }
}
