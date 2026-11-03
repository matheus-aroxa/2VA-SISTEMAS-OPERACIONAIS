package fsim;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Arquivo ou diretório da árvore. A identidade do objeto é usada como chave pelos alocadores. */
final class Node {
    static final int DEFAULT_DIR_MODE = 0755;
    static final int DEFAULT_FILE_MODE = 0644;

    String name;
    final boolean isDir;
    Node parent;
    final Map<String, Node> children = new HashMap<>();
    int size;

    String owner = "root";
    String group = "admin";
    int mode;
    final Map<String, Integer> userAcl = new LinkedHashMap<>();
    final Map<String, Integer> groupAcl = new LinkedHashMap<>();
    CryptoEngine.Meta crypto;

    Node(String name, boolean isDir, Node parent) {
        this.name = name;
        this.isDir = isDir;
        this.parent = parent;
        this.mode = isDir ? DEFAULT_DIR_MODE : DEFAULT_FILE_MODE;
    }

    boolean hasExtendedAcl() {
        return !userAcl.isEmpty() || !groupAcl.isEmpty();
    }

    String path() {
        if (parent == null) {
            return "/";
        }
        String p = parent.path();
        return p.equals("/") ? p + name : p + "/" + name;
    }

    boolean isAncestorOf(Node other) {
        for (Node n = other; n != null; n = n.parent) {
            if (n == this) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return (isDir ? "<Dir " : "<Arq ") + path() + ">";
    }
}
