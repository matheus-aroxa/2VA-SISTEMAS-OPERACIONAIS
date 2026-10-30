package fsim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Renderização textual: árvore, mapa de blocos, mapa gráfico e relatório de fragmentação. */
final class Report {
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int[] COLORS = {32, 34, 35, 36, 33, 92, 94, 95, 96, 93, 31, 91};
    private static final int CELLS_PER_ROW = 16;

    private Report() {
    }

    static final class Palette {
        final boolean enabled;

        Palette(boolean enabled) {
            this.enabled = enabled;
        }

        String paint(String text, int... codes) {
            if (!enabled || codes.length == 0) {
                return text;
            }
            String seq = java.util.Arrays.stream(codes).mapToObj(String::valueOf).collect(Collectors.joining(";"));
            return "\033[" + seq + "m" + text + "\033[0m";
        }
    }

    private static String pct(double value) {
        return Math.abs(value - Math.round(value)) < 0.05
                ? String.format(Locale.ROOT, "%.0f%%", value)
                : String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String fillGlyph(int used, int blockSize) {
        double ratio = (double) used / blockSize;
        if (ratio >= 1) {
            return "█";
        }
        if (ratio >= 0.75) {
            return "▆";
        }
        if (ratio >= 0.5) {
            return "▄";
        }
        if (ratio >= 0.25) {
            return "▂";
        }
        return "▁";
    }

    private static int width(FileSystem fs) {
        return Math.max(2, String.valueOf(fs.disk.numBlocks - 1).length());
    }

    // ------------------------------------------------------------------- árvore
    static String tree(FileSystem fs, Node start) {
        List<String> lines = new ArrayList<>();
        lines.add("[ÁRVORE DO SISTEMA DE ARQUIVOS]");
        lines.add(start == fs.root ? "/" : start.path() + "/");
        treeRec(fs, start, "", lines);
        return String.join("\n", lines);
    }

    private static void treeRec(FileSystem fs, Node node, String prefix, List<String> lines) {
        List<Node> kids = fs.sortedChildren(node);
        for (int i = 0; i < kids.size(); i++) {
            Node child = kids.get(i);
            boolean last = i == kids.size() - 1;
            String label = child.isDir
                    ? child.name + "/ [DIR]"
                    : child.name + " (" + Units.fmtSize(child.size) + ") [ARQ]";
            lines.add(prefix + (last ? "└── " : "├── ") + label);
            if (child.isDir) {
                treeRec(fs, child, prefix + (last ? "    " : "│   "), lines);
            }
        }
    }

    // ------------------------------------------------------ mapa bloco a bloco
    private static String inodeLabel(Node node) {
        if (node.parent == null) {
            return "INODE_RAIZ";
        }
        String stem = node.name.split("\\.")[0];
        return "INODE_" + (stem.isEmpty() ? node.name : stem).toUpperCase(Locale.ROOT);
    }

    static String blockList(FileSystem fs) {
        Disk disk = fs.disk;
        int w = width(fs);
        Allocator.ExternalReport ext = fs.alloc.externalReport();
        List<String[]> rows = new ArrayList<>();
        int i = 0;
        while (i < disk.numBlocks) {
            Disk.BlockInfo info = disk.info[i];
            if (info == null) {
                int j = i;
                while (j + 1 < disk.numBlocks && disk.info[j + 1] == null) {
                    j++;
                }
                String left = i == j ? "Bloco " + Units.pad(i, w) : "Bloco " + Units.pad(i, w) + ".." + Units.pad(j, w);
                String desc = "[LIVRE]";
                if (ext.scattered().contains(i)) {
                    desc += " (lacuna de " + (j - i + 1) + " bloco(s) — fragmentação externa)";
                }
                rows.add(new String[] {left, desc});
                i = j + 1;
                continue;
            }
            Node node = info.node;
            String left = "Bloco " + Units.pad(i, w);
            switch (info.kind) {
                case Disk.RESERVED -> rows.add(new String[] {left + " [" + info.label + "]", fs.alloc.reservedDesc()});
                case Disk.INODE -> {
                    String desc = (node.isDir ? "Diretório '" : "Arquivo '") + node.path() + "'";
                    if (!node.isDir) {
                        desc += " | índice: [" + Allocator.fmtBlocks(fs.alloc.dataBlocks(node), w) + "]";
                    }
                    rows.add(new String[] {left + " [" + inodeLabel(node) + "]", desc});
                }
                default -> rows.add(new String[] {left + " [DADO]", "'" + node.path() + "' (Bloco " + info.seq
                        + " - " + info.used + " bytes ocupados)" + fs.alloc.blockNote(i)});
            }
            i++;
        }

        int pad = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0) + 2;
        List<String> lines = new ArrayList<>();
        lines.add("[MAPA DE ALOCAÇÃO DO DISCO (" + fs.alloc.mapTitle() + ")]");
        for (String[] r : rows) {
            lines.add(String.format("%-" + pad + "s: %s", r[0], r[1]));
        }
        return String.join("\n", lines);
    }

    // ----------------------------------------------------------- mapa gráfico
    static String graphic(FileSystem fs, Palette pal) {
        Disk disk = fs.disk;
        int w = width(fs);
        List<Node> owners = fs.walk().stream().filter(n -> !fs.blocksOf(n).isEmpty()).toList();
        Map<Node, Character> sym = new HashMap<>();
        Map<Node, Integer> color = new HashMap<>();
        for (int k = 0; k < owners.size(); k++) {
            sym.put(owners.get(k), SYMBOLS.charAt(k % SYMBOLS.length()));
            color.put(owners.get(k), COLORS[k % COLORS.length]);
        }
        Set<Integer> scattered = fs.alloc.externalReport().scattered();

        List<String> lines = new ArrayList<>();
        lines.add("[MAPA GRÁFICO DO DISCO]");
        StringBuilder header = new StringBuilder(" ".repeat(w + 1));
        for (int c = 0; c < Math.min(CELLS_PER_ROW, disk.numBlocks); c++) {
            header.append(String.format(" %-3d", c));
        }
        lines.add(pal.paint(header.toString(), 2));
        for (int row = 0; row < disk.numBlocks; row += CELLS_PER_ROW) {
            StringBuilder sb = new StringBuilder(Units.pad(row, w)).append(' ');
            for (int i = row; i < Math.min(row + CELLS_PER_ROW, disk.numBlocks); i++) {
                Disk.BlockInfo info = disk.info[i];
                if (info == null) {
                    sb.append(scattered.contains(i) ? pal.paint("[░░]", 31) : pal.paint("[··]", 2));
                } else if (info.kind.equals(Disk.RESERVED)) {
                    sb.append(pal.paint("[##]", 1, 37));
                } else if (info.kind.equals(Disk.INODE)) {
                    sb.append(pal.paint("[" + sym.get(info.node) + "i]", 1, color.get(info.node)));
                } else {
                    sb.append(pal.paint("[" + sym.get(info.node) + fillGlyph(info.used, disk.blockSize) + "]",
                            color.get(info.node)));
                }
            }
            lines.add(sb.toString());
        }

        lines.add("");
        lines.add("Legenda:");
        for (Node n : owners) {
            String name = pal.paint(String.valueOf(sym.get(n)), 1, color.get(n));
            if (n.isDir) {
                lines.add("  " + name + " = " + n.path() + " [DIR] (i-node)");
                continue;
            }
            FileSystem.FileStats st = fs.fileStats(n);
            String extra = fs.alloc.metaBlocks(n).isEmpty() ? "" : ", i-node";
            String frag = st.internal() > 0 ? ", frag. interna " + st.internal() + " B" : "";
            lines.add("  " + name + " = " + n.path() + " (" + Units.fmtSize(n.size) + "; " + st.blocks().size()
                    + " bloco(s) de dados" + extra + frag + ")");
        }
        lines.add("  █ cheio  ▆ ≥75%  ▄ ≥50%  ▂ ≥25%  ▁ <25%  |  i = i-node  |  ## = reservado  |  "
                + pal.paint("··", 2) + " livre  |  " + pal.paint("░░", 31) + " livre disperso (frag. externa)");
        return String.join("\n", lines);
    }

    // --------------------------------------------------------- fragmentação
    static String fragmentation(FileSystem fs, boolean details) {
        int bs = fs.disk.blockSize;
        List<Node> files = fs.walk().stream().filter(n -> !n.isDir).toList();
        List<String> lines = new ArrayList<>();
        lines.add("--- RELATÓRIO DE FRAGMENTAÇÃO ---");
        long totalInternal = 0;
        int nonContiguous = 0;
        int fragFiles = 0;
        for (Node n : files) {
            FileSystem.FileStats st = fs.fileStats(n);
            totalInternal += st.internal();
            nonContiguous += st.contiguous() ? 0 : 1;
            fragFiles += st.internal() > 0 ? 1 : 0;
            if (!details) {
                continue;
            }
            int nblocks = st.blocks().size();
            lines.add("Arquivo '" + n.path() + "'");
            lines.add("  Tamanho Real do Arquivo : " + n.size + " bytes (" + Units.fmtSize(n.size) + ")");
            lines.add("  Espaço Alocado no Disco : " + st.allocated() + " bytes (" + nblocks + " bloco(s) de "
                    + Units.fmtSize(bs) + ")");
            if (nblocks == 0) {
                lines.add("  Fragmentação Interna    : 0 bytes (arquivo vazio, sem blocos de dados)");
            } else if (st.internal() > 0) {
                lines.add("  Fragmentação Interna    : " + st.internal() + " bytes (" + pct(st.wastePct())
                        + " de desperdício no Bloco " + Units.pad(st.lastBlock(), 2) + ")");
            } else {
                lines.add("  Fragmentação Interna    : 0 bytes (último bloco totalmente ocupado)");
            }
            lines.add("  Alocação                : " + fs.alloc.describe(n));
            lines.add("");
        }

        Allocator.ExternalReport ext = fs.alloc.externalReport();
        lines.add("Fragmentação Interna Total : " + totalInternal + " bytes (" + Units.fmtSize(totalInternal) + ") em "
                + fragFiles + " arquivo(s)");
        if (ext.holes().size() > 1 || ext.bytes() > 0) {
            String holes = ext.holes().stream()
                    .map(h -> h.length() == 1 ? Units.pad(h.start(), 2)
                            : Units.pad(h.start(), 2) + ".." + Units.pad(h.start() + h.length() - 1, 2))
                    .collect(Collectors.joining(", "));
            lines.add("Lacunas Livres             : " + ext.holes().size() + " (" + holes + ")");
        }
        lines.add("Fragmentação Externa       : " + ext.bytes() + " bytes (" + Units.fmtSize(ext.bytes()) + ")");
        if (!fs.alloc.key().equals("contigua")) {
            lines.add("Arquivos Não Contíguos     : " + nonContiguous + " (afeta só desempenho de leitura sequencial)");
        }
        lines.add("Necessidade de Compactação : " + ext.reason());
        if (fs.alloc.lastFailure != null) {
            lines.add("ALERTA                     : " + fs.alloc.lastFailure + " — compactação necessária!");
        }
        return String.join("\n", lines);
    }

    static String status(FileSystem fs, boolean details, Palette pal) {
        Disk disk = fs.disk;
        int used = disk.numBlocks - disk.freeCount();
        String fit = fs.alloc.key().equals("contigua") ? " | Política: " + fs.alloc.fit + "-fit" : "";
        List<String> parts = new ArrayList<>(List.of(
                "Tamanho do Bloco de Disco: " + Units.fmtSize(disk.blockSize) + " | Total de Blocos: "
                        + disk.numBlocks + " (" + Units.fmtSize(disk.capacity()) + ")",
                "Estratégia: " + fs.alloc.title() + fit,
                "Ocupação: " + used + "/" + disk.numBlocks + " blocos (" + pct(used * 100.0 / disk.numBlocks)
                        + ") | Livre: " + disk.freeCount() + " blocos ("
                        + Units.fmtSize((long) disk.freeCount() * disk.blockSize) + ")",
                ""));
        if (details) {
            parts.add(blockList(fs));
            parts.add("");
        }
        parts.add(graphic(fs, pal));
        parts.add("");
        parts.add(fragmentation(fs, details));
        return String.join("\n", parts);
    }
}
