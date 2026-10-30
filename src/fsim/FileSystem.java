package fsim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Camada lógica: árvore de diretórios, resolução de caminhos e operações CRUD. */
final class FileSystem {
    record FileStats(List<Integer> blocks, long allocated, long internal, int lastBlock,
                     double wastePct, boolean contiguous) {
    }

    Disk disk;
    Allocator alloc;
    Node root;
    Node cwd;

    FileSystem(int blockSize, int numBlocks, String strategy, String fit) {
        format(blockSize, numBlocks, strategy, fit);
    }

    // ------------------------------------------------------------------ disco
    void format(long blockSize, long numBlocks, String strategy, String fit) {
        if (!Allocator.FITS.contains(fit)) {
            throw new FSException("política de encaixe inválida: '" + fit + "' (use first, best ou worst)");
        }
        if (blockSize < 64 || blockSize > 65536) {
            throw new FSException("o tamanho do bloco deve estar entre 64 B e 64 KB");
        }
        if (numBlocks < 2 || numBlocks > 4096) {
            throw new FSException("o número de blocos deve estar entre 2 e 4096");
        }
        Disk newDisk = new Disk((int) blockSize, (int) numBlocks);
        Allocator newAlloc = Allocator.create(strategy, newDisk, fit);
        disk = newDisk;
        alloc = newAlloc;
        root = new Node("", true, null);
        alloc.createNode(root);
        cwd = root;
    }

    // ---------------------------------------------------------------- caminhos
    Node resolve(String path) {
        if (path.isEmpty()) {
            throw new FSException("caminho vazio");
        }
        Node node = path.startsWith("/") ? root : cwd;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                node = node.parent != null ? node.parent : node;
                continue;
            }
            if (!node.isDir) {
                throw new FSException("'" + node.path() + "' não é um diretório");
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
        cwd = node;
    }

    List<Node> sortedChildren(Node node) {
        List<Node> kids = new ArrayList<>(node.children.values());
        kids.sort(Comparator.comparing((Node c) -> !c.isDir).thenComparing(c -> c.name));
        return kids;
    }

    List<Node> listdir(String path) {
        Node node = resolve(path);
        return node.isDir ? sortedChildren(node) : List.of(node);
    }

    /** Percorre a árvore em pré-ordem. */
    List<Node> walk() {
        List<Node> out = new ArrayList<>();
        walk(root, out);
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
            return attach(pn.parent(), pn.name(), true);
        }
        Node node = path.startsWith("/") ? root : cwd;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                node = node.parent != null ? node.parent : node;
            } else if (node.children.containsKey(part)) {
                node = node.children.get(part);
                if (!node.isDir) {
                    throw new FSException("'" + node.path() + "' não é um diretório");
                }
            } else {
                node = attach(node, part, true);
            }
        }
        return node;
    }

    Node create(String path, long size) {
        checkFits(size);
        return create(path, new byte[(int) size]);
    }

    Node create(String path, byte[] content) {
        ParentAndName pn = parentAndName(path);
        Node node = attach(pn.parent(), pn.name(), false);
        try {
            store(node, content);
        } catch (FSException e) {
            destroy(node);
            throw e;
        }
        return node;
    }

    byte[] read(String path) {
        return load(file(path));
    }

    Node write(String path, byte[] content, boolean append) {
        if (!exists(path)) {
            return create(path, content);
        }
        Node node = file(path);
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
        Node node = file(path);
        checkFits(size);
        store(node, java.util.Arrays.copyOf(load(node), (int) size));
        return node;
    }

    Node move(String src, String dst) {
        Node node = resolve(src);
        if (node == root) {
            throw new FSException("não é possível mover o diretório raiz");
        }
        Node parent;
        String name;
        if (exists(dst) && resolve(dst).isDir) {
            parent = resolve(dst);
            name = node.name;
        } else {
            ParentAndName pn = parentAndName(dst);
            parent = pn.parent();
            name = pn.name();
        }
        if (node.isAncestorOf(parent)) {
            throw new FSException("não é possível mover um diretório para dentro dele mesmo");
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
        if (!node.children.isEmpty()) {
            throw new FSException("diretório não vazio: '" + node.path() + "' (use 'rm -r')");
        }
        destroy(node);
    }

    private void destroy(Node node) {
        for (Node child : new ArrayList<>(node.children.values())) {
            destroy(child);
        }
        alloc.deleteNode(node);
        if (node.parent != null && node.parent.children.get(node.name) == node) {
            node.parent.children.remove(node.name);
        }
        if (node.isAncestorOf(cwd)) {
            cwd = node.parent != null ? node.parent : root;
        }
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
            System.arraycopy(disk.data[b], 0, out, offset, len);
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
    }

    List<Integer> blocksOf(Node node) {
        List<Integer> all = new ArrayList<>(alloc.metaBlocks(node));
        all.addAll(alloc.dataBlocks(node));
        return all;
    }

    /** Compacta o disco realocando tudo em ordem; retorna quantos blocos mudaram de posição. */
    int defrag() {
        List<Node> nodes = walk();
        Map<Node, List<Integer>> before = new HashMap<>();
        Map<Node, byte[]> contents = new HashMap<>();
        for (Node n : nodes) {
            before.put(n, blocksOf(n));
            if (!n.isDir) {
                contents.put(n, load(n));
            }
        }
        disk = new Disk(disk.blockSize, disk.numBlocks);
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
