package fsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Camada lógica do sistema de arquivos: árvore de diretórios, resolução de caminhos, operações CRUD e mediação
 * atômica de segurança (controle de acesso POSIX/ACL, criptografia em repouso, integridade e auditoria).
 */
final class FileSystem {
    private static final String CAT_PERM = "PERMISSAO";
    private static final String CAT_EXEC = "EXECUCAO";
    private static final String CAT_CRYPTO = "CRIPTOGRAFIA";

    record FileStats(List<Integer> blocks, long allocated, long internal, int lastBlock,
                     double wastePct, boolean contiguous) {
    }

    final AuditLog audit = new AuditLog();
    final AuthManager auth = new AuthManager(audit);
    final IntegrityMonitor integrity = new IntegrityMonitor();
    final MemoryManager memory = new MemoryManager();

    Disk disk;
    Allocator alloc;
    Node root;
    Node cwd;

    FileSystem(int blockSize, int numBlocks, String strategy, String fit, RaidArray.Level level, int disks) {
        format(blockSize, numBlocks, strategy, fit, level, disks);
    }

    // ------------------------------------------------------------------ disco
    void format(long blockSize, long numBlocks, String strategy, String fit, RaidArray.Level level, int disks) {
        auth.requireKernel("format");
        if (!Allocator.FITS.contains(fit)) {
            throw new FSException("política de encaixe inválida: '" + fit + "' (use first, best ou worst)");
        }
        if (blockSize < 64 || blockSize > 65536) {
            throw new FSException("o tamanho do bloco deve estar entre 64 B e 64 KB");
        }
        if (numBlocks < 2 || numBlocks > 4096) {
            throw new FSException("o número de blocos deve estar entre 2 e 4096");
        }
        RaidArray array = new RaidArray(level, disks, (int) blockSize, (int) numBlocks);
        Disk newDisk = new Disk((int) blockSize, (int) numBlocks, array);
        Allocator newAlloc = Allocator.create(strategy, newDisk, fit);
        disk = newDisk;
        alloc = newAlloc;
        root = new Node("", true, null);
        alloc.createNode(root);
        cwd = root;
        integrity.reset();
        array.resetQueues(); // a formatação não conta como carga de trabalho
    }

    // ---------------------------------------------------------------- caminhos
    Node resolve(String path) {
        return resolve(path, true);
    }

    Node resolve(String path, boolean checkAccess) {
        if (path == null || path.isEmpty()) {
            throw new FSException("caminho vazio");
        }
        Node node = path.startsWith("/") ? root : cwd;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (checkAccess) {
                    AccessControl.check(node, auth, audit, AccessControl.Action.EXEC);
                }
                node = node.parent != null ? node.parent : node;
                continue;
            }
            if (!node.isDir) {
                throw new FSException("'" + node.path() + "' não é um diretório");
            }
            if (checkAccess) {
                AccessControl.check(node, auth, audit, AccessControl.Action.EXEC);
            }
            Node child = node.children.get(part);
            if (child == null) {
                throw new FSException("caminho não encontrado: '" + path + "'");
            }
            node = child;
        }
        return node;
    }

    private record ParentAndName(Node parent, String name) {
    }

    private ParentAndName parentAndName(String path) {
        if (path == null || path.isEmpty()) {
            throw new FSException("caminho vazio");
        }
        String stripped = path.replaceAll("/+$", "");
        int slash = stripped.lastIndexOf('/');
        String head = slash >= 0 ? stripped.substring(0, slash) : "";
        String name = stripped.substring(slash + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new FSException("nome inválido: '" + path + "'");
        }
        if (head.isEmpty()) {
            head = stripped.startsWith("/") ? "/" : ".";
        }
        Node parent = resolve(head);
        if (!parent.isDir) {
            throw new FSException("'" + parent.path() + "' não é um diretório");
        }
        return new ParentAndName(parent, name);
    }

    boolean exists(String path) {
        try {
            resolve(path);
            return true;
        } catch (AccessDeniedException e) {
            throw e;
        } catch (FSException e) {
            return false;
        }
    }

    private Node file(String path) {
        Node node = resolve(path);
        if (node.isDir) {
            throw new FSException("'" + node.path() + "' é um diretório");
        }
        return node;
    }

    private Node attach(Node parent, String name, boolean isDir) {
        if (parent.children.containsKey(name)) {
            throw new FSException("'" + parent.children.get(name).path() + "' já existe");
        }
        if (name.length() > 255) {
            throw new FSException("nome muito longo (máx. 255 caracteres)");
        }
        Node node = new Node(name, isDir, parent);
        node.owner = auth.currentUser().name();
        node.group = auth.currentUser().primaryGroup();
        alloc.createNode(node);
        parent.children.put(name, node);
        return node;
    }

    // ------------------------------------------------------------- navegação
    void cd(String path) {
        Node node = resolve(path);
        if (!node.isDir) {
            throw new FSException("'" + node.path() + "' não é um diretório");
        }
        AccessControl.check(node, auth, audit, AccessControl.Action.EXEC);
        cwd = node;
    }

    List<Node> sortedChildren(Node node) {
        List<Node> kids = new ArrayList<>(node.children.values());
        kids.sort(Comparator.comparing((Node c) -> !c.isDir).thenComparing(c -> c.name));
        return kids;
    }

    List<Node> listdir(String path) {
        Node node = resolve(path);
        if (node.isDir) {
            AccessControl.check(node, auth, audit, AccessControl.Action.READ);
            return sortedChildren(node);
        }
        return List.of(node);
    }

    /** Percorre a árvore a partir da raiz em pré-ordem. */
    List<Node> walk() {
        return walk(root);
    }

    /** Percorre a subárvore de `start` em pré-ordem sem verificação de permissões (diagnóstico e integridade). */
    List<Node> walk(Node start) {
        List<Node> out = new ArrayList<>();
        walk(start, out);
        return out;
    }

    private void walk(Node node, List<Node> out) {
        out.add(node);
        for (Node child : sortedChildren(node)) {
            walk(child, out);
        }
    }

    // ------------------------------------------------------------------- CRUD
    Node mkdir(String path, boolean parents) {
        if (!parents) {
            ParentAndName pn = parentAndName(path);
            AccessControl.check(pn.parent(), auth, audit, AccessControl.Action.EXEC);
            AccessControl.check(pn.parent(), auth, audit, AccessControl.Action.WRITE);
            return attach(pn.parent(), pn.name(), true);
        }
        if (path == null || path.isEmpty()) {
            throw new FSException("caminho vazio");
        }
        Node node = path.startsWith("/") ? root : cwd;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                AccessControl.check(node, auth, audit, AccessControl.Action.EXEC);
                node = node.parent != null ? node.parent : node;
            } else {
                if (!node.isDir) {
                    throw new FSException("'" + node.path() + "' não é um diretório");
                }
                AccessControl.check(node, auth, audit, AccessControl.Action.EXEC);
                if (node.children.containsKey(part)) {
                    node = node.children.get(part);
                    if (!node.isDir) {
                        throw new FSException("'" + node.path() + "' não é um diretório");
                    }
                } else {
                    AccessControl.check(node, auth, audit, AccessControl.Action.WRITE);
                    node = attach(node, part, true);
                }
            }
        }
        return node;
    }

    Node create(String path, long size) {
        if (size < 0) {
            throw new FSException("tamanho inválido: " + size);
        }
        ParentAndName pn = parentAndName(path);
        AccessControl.check(pn.parent(), auth, audit, AccessControl.Action.EXEC);
        AccessControl.check(pn.parent(), auth, audit, AccessControl.Action.WRITE);
        checkFits(size);
        return createInParent(pn.parent(), pn.name(), new byte[(int) size]);
    }

    Node create(String path, byte[] content) {
        ParentAndName pn = parentAndName(path);
        AccessControl.check(pn.parent(), auth, audit, AccessControl.Action.EXEC);
        AccessControl.check(pn.parent(), auth, audit, AccessControl.Action.WRITE);
        return createInParent(pn.parent(), pn.name(), content);
    }

    private Node createInParent(Node parent, String name, byte[] content) {
        Node node = attach(parent, name, false);
        try {
            store(node, content);
        } catch (FSException e) {
            destroy(node);
            throw e;
        }
        return node;
    }

    byte[] read(String path) {
        return read(path, null);
    }

    byte[] read(String path, String key) {
        Node node = file(path);
        AccessControl.check(node, auth, audit, AccessControl.Action.READ);
        if (integrity.isQuarantined(node)) {
            throw new FSException("acesso bloqueado: '" + node.path() + "' está isolado em quarentena");
        }
        if (node.crypto == null) {
            if (key != null) {
                throw new FSException("arquivo não está criptografado: '" + node.path() + "'");
            }
            return load(node);
        }
        byte[] cipher = load(node);
        if (key == null) {
            return cipher;
        }
        try {
            return CryptoEngine.decrypt(cipher, key, node.crypto);
        } catch (FSException e) {
            audit.record(CAT_CRYPTO, auth.currentUser().name(), auth.mode().name(),
                    "falha ao descriptografar na leitura de '" + node.path() + "': " + e.getMessage());
            throw e;
        }
    }

    Node write(String path, byte[] content, boolean append) {
        if (!exists(path)) {
            return create(path, content);
        }
        Node node = file(path);
        AccessControl.check(node, auth, audit, AccessControl.Action.WRITE);
        if (append) {
            AccessControl.check(node, auth, audit, AccessControl.Action.READ);
        }
        if (integrity.isQuarantined(node)) {
            throw new FSException("acesso bloqueado: '" + node.path() + "' está isolado em quarentena");
        }
        if (node.crypto != null) {
            throw new FSException(
                    "arquivo '" + node.path() + "' está criptografado (use 'decrypt_file' antes de modificar)");
        }
        if (append) {
            byte[] old = load(node);
            byte[] joined = new byte[old.length + content.length];
            System.arraycopy(old, 0, joined, 0, old.length);
            System.arraycopy(content, 0, joined, old.length, content.length);
            content = joined;
        }
        store(node, content);
        return node;
    }

    Node truncate(String path, long size) {
        if (size < 0) {
            throw new FSException("tamanho inválido: " + size);
        }
        Node node = file(path);
        AccessControl.check(node, auth, audit, AccessControl.Action.WRITE);
        if (integrity.isQuarantined(node)) {
            throw new FSException("acesso bloqueado: '" + node.path() + "' está isolado em quarentena");
        }
        if (node.crypto != null) {
            throw new FSException(
                    "arquivo '" + node.path() + "' está criptografado (use 'decrypt_file' antes de modificar)");
        }
        checkFits(size);
        store(node, Arrays.copyOf(load(node), (int) size));
        return node;
    }

    Node move(String src, String dst) {
        Node node = resolve(src);
        if (node == root) {
            throw new FSException("não é possível mover o diretório raiz");
        }
        Node dstNode = null;
        try {
            dstNode = resolve(dst);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (FSException ignored) {
            // destino ainda não existe: será resolvido pelo diretório pai
        }
        Node parent;
        String name;
        if (dstNode != null && dstNode.isDir) {
            parent = dstNode;
            name = node.name;
        } else {
            ParentAndName pn = parentAndName(dst);
            parent = pn.parent();
            name = pn.name();
        }
        if (node.isAncestorOf(parent)) {
            throw new FSException("não é possível mover um diretório para dentro dele mesmo");
        }
        AccessControl.check(node.parent, auth, audit, AccessControl.Action.EXEC);
        AccessControl.check(node.parent, auth, audit, AccessControl.Action.WRITE);
        if (parent != node.parent) {
            AccessControl.check(parent, auth, audit, AccessControl.Action.EXEC);
            AccessControl.check(parent, auth, audit, AccessControl.Action.WRITE);
        }
        if (parent.children.containsKey(name)) {
            throw new FSException("'" + parent.children.get(name).path() + "' já existe");
        }
        node.parent.children.remove(node.name);
        node.name = name;
        node.parent = parent;
        parent.children.put(name, node);
        return node;
    }

    void remove(String path, boolean recursive) {
        Node node = resolve(path);
        if (node == root) {
            throw new FSException("não é possível remover o diretório raiz");
        }
        if (node.isDir && !recursive) {
            throw new FSException("'" + node.path() + "' é um diretório (use 'rm -r' ou 'rmdir')");
        }
        AccessControl.check(node.parent, auth, audit, AccessControl.Action.EXEC);
        AccessControl.check(node.parent, auth, audit, AccessControl.Action.WRITE);
        if (node.isDir && !node.children.isEmpty()) {
            checkCanRemoveTree(node);
        }
        destroy(node);
    }

    void rmdir(String path) {
        Node node = resolve(path);
        if (!node.isDir) {
            throw new FSException("'" + node.path() + "' não é um diretório");
        }
        if (node == root) {
            throw new FSException("não é possível remover o diretório raiz");
        }
        AccessControl.check(node.parent, auth, audit, AccessControl.Action.EXEC);
        AccessControl.check(node.parent, auth, audit, AccessControl.Action.WRITE);
        if (!node.children.isEmpty()) {
            throw new FSException("diretório não vazio: '" + node.path() + "' (use 'rm -r')");
        }
        destroy(node);
    }

    private void checkCanRemoveTree(Node dir) {
        AccessControl.check(dir, auth, audit, AccessControl.Action.EXEC);
        AccessControl.check(dir, auth, audit, AccessControl.Action.WRITE);
        for (Node child : sortedChildren(dir)) {
            if (child.isDir) {
                checkCanRemoveTree(child);
            }
        }
    }

    private void destroy(Node node) {
        for (Node child : new ArrayList<>(node.children.values())) {
            destroy(child);
        }
        integrity.remove(node);
        alloc.deleteNode(node);
        if (node.parent != null && node.parent.children.get(node.name) == node) {
            node.parent.children.remove(node.name);
        }
        if (node.isAncestorOf(cwd)) {
            cwd = node.parent != null ? node.parent : root;
        }
    }

    // --------------------------------------------------------------- segurança
    void chmod(String path, String modeSpec) {
        Node node = resolve(path);
        requireOwnerOrKernel(node, "alterar permissões");
        int newMode = AccessControl.parseMode(modeSpec, node.mode);
        node.mode = newMode;
        if (newMode != 0) {
            integrity.unquarantine(node);
        }
        audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                "chmod " + AccessControl.octal(newMode) + " (" + AccessControl.symbolic(newMode)
                        + ") em '" + node.path() + "'");
    }

    void chown(String path, String owner, String group) {
        auth.requireKernel("chown");
        Node node = resolve(path);
        if (owner == null || !auth.userExists(owner)) {
            throw new FSException("usuário não encontrado: '" + (owner == null ? "" : owner) + "'");
        }
        boolean changeGroup = group != null && !group.isEmpty();
        if (changeGroup && !auth.groupExists(group)) {
            throw new FSException("grupo não encontrado: '" + group + "'");
        }
        node.owner = owner;
        if (changeGroup) {
            node.group = group;
        }
        audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                "chown '" + node.owner + ":" + node.group + "' em '" + node.path() + "'");
    }

    void chgrp(String path, String group) {
        Node node = resolve(path);
        if (group == null || !auth.groupExists(group)) {
            throw new FSException("grupo não encontrado: '" + (group == null ? "" : group) + "'");
        }
        if (!auth.isKernel()) {
            AuthManager.User caller = auth.currentUser();
            if (!caller.name().equals(node.owner)) {
                audit.record(CAT_PERM, caller.name(), auth.mode().name(),
                        "negado: tentativa de chgrp em '" + node.path()
                                + "' (proprietário: '" + node.owner + "')");
                throw new FSException("permissão negada: apenas o proprietário ('" + node.owner
                        + "') ou root pode alterar o grupo de '" + node.path() + "'");
            }
            if (!caller.inGroup(group)) {
                audit.record(CAT_PERM, caller.name(), auth.mode().name(),
                        "negado: chgrp para '" + group + "' em '" + node.path()
                                + "' (usuário não pertence ao grupo)");
                throw new FSException("permissão negada: o usuário '" + caller.name()
                        + "' não pertence ao grupo '" + group + "'");
            }
        }
        node.group = group;
        audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                "chgrp '" + group + "' em '" + node.path() + "'");
    }

    void setAcl(String path, String spec) {
        Node node = resolve(path);
        requireOwnerOrKernel(node, "alterar a ACL");
        if (spec == null || spec.isEmpty()) {
            throw new FSException(
                    "especificação ACL inválida: '' (use u:<usuário>:<permissões> ou g:<grupo>:<permissões>)");
        }
        String[] parts = spec.split(":", -1);
        if (parts.length != 3 || parts[1].isEmpty()) {
            throw new FSException("especificação ACL inválida: '" + spec
                    + "' (use u:<usuário>:<permissões> ou g:<grupo>:<permissões>)");
        }
        String kind = parts[0].toLowerCase(Locale.ROOT);
        String target = parts[1];
        int bits = AccessControl.parseBits(parts[2]);
        switch (kind) {
            case "u", "user", "usuario", "usuário" -> {
                if (!auth.userExists(target)) {
                    throw new FSException("usuário não encontrado: '" + target + "'");
                }
                node.userAcl.put(target, bits);
                audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                        "ACL u:" + target + ":" + AccessControl.formatBits(bits) + " definida em '" + node.path() + "'");
            }
            case "g", "group", "grupo" -> {
                if (!auth.groupExists(target)) {
                    throw new FSException("grupo não encontrado: '" + target + "'");
                }
                node.groupAcl.put(target, bits);
                audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                        "ACL g:" + target + ":" + AccessControl.formatBits(bits) + " definida em '" + node.path() + "'");
            }
            default -> throw new FSException(
                    "tipo de entrada ACL inválido em '" + spec + "' (use 'u' para usuário ou 'g' para grupo)");
        }
    }

    void removeAcl(String path, String spec) {
        Node node = resolve(path);
        requireOwnerOrKernel(node, "alterar a ACL");
        if (spec == null || spec.isEmpty()) {
            throw new FSException("especificação ACL inválida: '' (use u:<usuário> ou g:<grupo>)");
        }
        String[] parts = spec.split(":", -1);
        if (parts.length != 2 || parts[1].isEmpty()) {
            throw new FSException("especificação ACL inválida: '" + spec + "' (use u:<usuário> ou g:<grupo>)");
        }
        String kind = parts[0].toLowerCase(Locale.ROOT);
        String target = parts[1];
        switch (kind) {
            case "u", "user", "usuario", "usuário" -> {
                node.userAcl.remove(target);
                audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                        "ACL u:" + target + " removida de '" + node.path() + "'");
            }
            case "g", "group", "grupo" -> {
                node.groupAcl.remove(target);
                audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                        "ACL g:" + target + " removida de '" + node.path() + "'");
            }
            default -> throw new FSException(
                    "tipo de entrada ACL inválido em '" + spec + "' (use 'u' para usuário ou 'g' para grupo)");
        }
    }

    void clearAcl(String path) {
        Node node = resolve(path);
        requireOwnerOrKernel(node, "alterar a ACL");
        node.userAcl.clear();
        node.groupAcl.clear();
        audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                "ACL estendida limpa em '" + node.path() + "'");
    }

    byte[] execute(String path) {
        Node node = file(path);
        AccessControl.check(node, auth, audit, AccessControl.Action.EXEC);
        if (integrity.isQuarantined(node)) {
            throw new FSException("execução bloqueada: '" + node.path() + "' está isolado em quarentena");
        }
        if (node.crypto != null) {
            throw new FSException("não é possível executar arquivo criptografado em repouso: '"
                    + node.path() + "' (use 'decrypt_file' antes)");
        }
        byte[] code = load(node);
        audit.record(CAT_EXEC, auth.currentUser().name(), auth.mode().name(),
                "execução autorizada de '" + node.path() + "' (" + code.length + " bytes)");
        return code;
    }

    byte[] encryptFile(String path, String key) {
        Node node = file(path);
        AccessControl.check(node, auth, audit, AccessControl.Action.READ);
        AccessControl.check(node, auth, audit, AccessControl.Action.WRITE);
        if (integrity.isQuarantined(node)) {
            throw new FSException("acesso bloqueado: '" + node.path() + "' está isolado em quarentena");
        }
        if (node.crypto != null) {
            throw new FSException("arquivo já está criptografado: '" + node.path() + "'");
        }
        if (key == null || key.isEmpty()) {
            throw new FSException("a chave de criptografia não pode ser vazia");
        }
        byte[] plain = load(node);
        CryptoEngine.Encrypted enc = CryptoEngine.encrypt(plain, key);
        store(node, enc.cipher());
        node.crypto = enc.meta();
        audit.record(CAT_CRYPTO, auth.currentUser().name(), auth.mode().name(),
                "arquivo criptografado em repouso (AES-256-CTR): '" + node.path() + "'");
        return enc.cipher();
    }

    void decryptFile(String path, String key) {
        Node node = file(path);
        AccessControl.check(node, auth, audit, AccessControl.Action.READ);
        AccessControl.check(node, auth, audit, AccessControl.Action.WRITE);
        if (integrity.isQuarantined(node)) {
            throw new FSException("acesso bloqueado: '" + node.path() + "' está isolado em quarentena");
        }
        if (node.crypto == null) {
            throw new FSException("arquivo não está criptografado: '" + node.path() + "'");
        }
        if (key == null || key.isEmpty()) {
            audit.record(CAT_CRYPTO, auth.currentUser().name(), auth.mode().name(),
                    "falha ao descriptografar '" + node.path() + "': chave vazia");
            throw new FSException("a chave de criptografia não pode ser vazia");
        }
        byte[] cipher = load(node);
        byte[] plain;
        try {
            plain = CryptoEngine.decrypt(cipher, key, node.crypto);
        } catch (FSException e) {
            audit.record(CAT_CRYPTO, auth.currentUser().name(), auth.mode().name(),
                    "falha ao descriptografar '" + node.path() + "': " + e.getMessage());
            throw e;
        }
        store(node, plain);
        node.crypto = null;
        audit.record(CAT_CRYPTO, auth.currentUser().name(), auth.mode().name(),
                "arquivo descriptografado nos blocos físicos: '" + node.path() + "'");
    }

    IntegrityMonitor.ScanReport scanIntegrity(String path, boolean quarantine) {
        Node target = resolve(path == null || path.isEmpty() ? "/" : path, false);
        return integrity.scan(this, target, quarantine);
    }

    void tamperBlock(int logicalBlock, byte[] payload) {
        integrity.tamperBlock(disk, logicalBlock, payload, auth, audit);
    }

    private void requireOwnerOrKernel(Node node, String actionDesc) {
        if (auth.isKernel() || auth.currentUser().name().equals(node.owner)) {
            return;
        }
        audit.record(CAT_PERM, auth.currentUser().name(), auth.mode().name(),
                "negado: tentativa de " + actionDesc + " em '" + node.path()
                        + "' (proprietário: '" + node.owner + "')");
        throw new FSException("permissão negada: apenas o proprietário ('" + node.owner
                + "') ou root pode " + actionDesc + " de '" + node.path() + "'");
    }

    // ------------------------------------------------------- blocos de dados
    private void checkFits(long size) {
        if (size > disk.capacity()) {
            throw new DiskFullException("arquivo de " + Units.fmtSize(size) + " é maior que o disco ("
                    + Units.fmtSize(disk.capacity()) + ")");
        }
    }

    /** Lê o conteúdo seguindo os blocos de dados na ordem definida pelo alocador. */
    byte[] load(Node node) {
        byte[] out = new byte[node.size];
        int offset = 0;
        for (int b : alloc.dataBlocks(node)) {
            int len = Math.min(disk.blockSize, node.size - offset);
            System.arraycopy(disk.read(b), 0, out, offset, len);
            offset += len;
        }
        return out;
    }

    void store(Node node, byte[] content) {
        int bs = disk.blockSize;
        alloc.resize(node, (content.length + bs - 1) / bs);
        List<Integer> blocks = alloc.dataBlocks(node);
        for (int k = 0; k < blocks.size(); k++) {
            int b = blocks.get(k);
            int len = Math.min(bs, content.length - k * bs);
            disk.write(b, content, k * bs, len);
            disk.info[b].seq = k + 1;
            disk.info[b].used = len;
        }
        node.size = content.length;
        integrity.recordOfficial(node, disk, blocks);
    }

    List<Integer> blocksOf(Node node) {
        List<Integer> all = new ArrayList<>(alloc.metaBlocks(node));
        all.addAll(alloc.dataBlocks(node));
        return all;
    }

    /** Compacta o disco realocando tudo em ordem; retorna quantos blocos mudaram de posição. */
    int defrag() {
        auth.requireKernel("defrag");
        List<Node> nodes = walk();
        Map<Node, List<Integer>> before = new HashMap<>();
        Map<Node, byte[]> contents = new HashMap<>();
        for (Node n : nodes) {
            before.put(n, blocksOf(n));
            if (!n.isDir) {
                contents.put(n, load(n));
            }
        }
        disk = new Disk(disk.blockSize, disk.numBlocks, disk.array);
        disk.wipe();
        alloc = Allocator.create(alloc.key(), disk, alloc.fit);
        for (Node n : nodes) {
            alloc.createNode(n);
            if (!n.isDir) {
                store(n, contents.get(n));
            }
        }
        int moved = 0;
        for (Node n : nodes) {
            List<Integer> a = before.get(n);
            List<Integer> b = blocksOf(n);
            for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
                if (!a.get(k).equals(b.get(k))) {
                    moved++;
                }
            }
        }
        return moved;
    }

    // ------------------------------------------------------------ estatística
    FileStats fileStats(Node node) {
        int bs = disk.blockSize;
        List<Integer> blocks = alloc.dataBlocks(node);
        long allocated = (long) blocks.size() * bs;
        long internal = allocated - node.size;
        boolean contiguous = true;
        for (int k = 1; k < blocks.size(); k++) {
            contiguous &= blocks.get(k) == blocks.get(k - 1) + 1;
        }
        return new FileStats(blocks, allocated, internal,
                blocks.isEmpty() ? -1 : blocks.get(blocks.size() - 1),
                blocks.isEmpty() ? 0 : internal * 100.0 / bs, contiguous);
    }
}
