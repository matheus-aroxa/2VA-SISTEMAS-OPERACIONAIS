package fsim;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Testes da Etapa 2: escalonamento do braço, arranjos RAID, SSD e integração com o sistema de arquivos. */
public final class IoTest {
    private IoTest() {
    }

    public static void main(String[] args) {
        sstfExample();
        fcfsSscanCscan();
        raid5Layout();
        raid5ReconstructsFailedDisk();
        raid0And1();
        fileSystemOnRaid5SurvivesFailure();
        flashKeepsLatestVersion();
        wearLevelingSpreadsErases();
        System.out.println("Todos os testes da Etapa 2 passaram.");
    }

    /** Exemplo 2.1 do enunciado: SSTF com cabeça na trilha 53 → 208 trilhas. */
    static void sstfExample() {
        ArmScheduler.Plan plan = ArmScheduler.plan(ArmScheduler.Policy.SSTF, 53, List.of(98, 183, 37, 122, 14), 199);
        check(plan.order().equals(List.of(37, 14, 98, 122, 183)), "ordem SSTF " + plan.order());
        check(ArmScheduler.length(plan.path()) == 208, "SSTF deve somar 208 trilhas");
    }

    static void fcfsSscanCscan() {
        List<Integer> tracks = List.of(98, 183, 37, 122, 14);
        ArmScheduler.Plan fcfs = ArmScheduler.plan(ArmScheduler.Policy.FCFS, 53, tracks, 199);
        check(ArmScheduler.length(fcfs.path()) == 469, "FCFS deve somar 469, deu " + ArmScheduler.length(fcfs.path()));

        ArmScheduler.Plan scan = ArmScheduler.plan(ArmScheduler.Policy.SCAN, 53, tracks, 199);
        check(scan.order().equals(List.of(98, 122, 183, 37, 14)), "ordem SCAN " + scan.order());
        check(ArmScheduler.length(scan.path()) == 331, "SCAN até o fim do disco deve somar 331");

        ArmScheduler.Plan cscan = ArmScheduler.plan(ArmScheduler.Policy.CSCAN, 53, tracks, 199);
        check(cscan.order().equals(List.of(98, 122, 183, 14, 37)), "ordem C-SCAN " + cscan.order());
        check(cscan.returnMove() == 199, "C-SCAN deve contar o retorno 199 → 0");
        // 53→98→122→183→199 (45+24+61+16), retorno 199→0 (199), 0→14→37 (14+23): 382 no total
        check(ArmScheduler.length(cscan.path()) == 382, "C-SCAN deve somar 382, deu " + ArmScheduler.length(cscan.path()));
    }

    /** Exemplo 2.2: RAID 5 com 3 discos — paridade rotativa e dados nos demais discos. */
    static void raid5Layout() {
        RaidArray arr = new RaidArray(RaidArray.Level.RAID5, 3, 4096, 6);
        check(arr.blocksPerDisk == 3, "RAID 5 com 3 discos e 6 blocos lógicos deve ter 3 blocos físicos por disco");
        check(arr.parityDisk(0) == 2, "faixa 0: paridade no Disco 3");
        check(arr.parityDisk(1) == 1 && arr.parityDisk(2) == 0, "paridade gira a cada faixa");
        check(arr.logicalAt(0, 0) == 0 && arr.logicalAt(1, 0) == 1 && arr.logicalAt(2, 0) == -1,
                "faixa 0: Disco 1 = bloco 0, Disco 2 = bloco 1, Disco 3 = paridade");
    }

    static void raid5ReconstructsFailedDisk() {
        RaidArray arr = new RaidArray(RaidArray.Level.RAID5, 3, 512, 6);
        byte[] a = block(0xA1);
        byte[] b = block(0x5C);
        arr.write(0, a, true);
        arr.write(1, b, true);
        arr.fail(1); // Disco 2 com falha: bloco 1 é recuperado por XOR(Disco 1, Disco 3)
        check(Arrays.equals(arr.read(1, true), b), "bloco 1 deve ser reconstruído por XOR");
        check(Arrays.equals(arr.read(0, true), a), "bloco 0 deve continuar legível");
        int rebuilt = arr.rebuild(1);
        check(rebuilt == arr.blocksPerDisk, "reconstrução deve recalcular todos os blocos do disco");
        check(Arrays.equals(arr.disks[1].read(0, false), b), "disco substituído deve voltar com o conteúdo");
    }

    static void raid0And1() {
        RaidArray stripe = new RaidArray(RaidArray.Level.RAID0, 2, 512, 4);
        stripe.write(0, block(1), true);
        stripe.write(1, block(2), true);
        check(stripe.disks[0].queue.size() == 1 && stripe.disks[1].queue.size() == 1, "RAID 0 espalha blocos");
        stripe.fail(0);
        try {
            stripe.read(0, true);
            throw new AssertionError("RAID 0 sem redundância deve perder dados");
        } catch (FSException expected) {
            // esperado
        }

        RaidArray mirror = new RaidArray(RaidArray.Level.RAID1, 2, 512, 4);
        mirror.write(2, block(7), true);
        mirror.fail(0);
        check(Arrays.equals(mirror.read(2, true), block(7)), "RAID 1 deve ler do espelho após falha");
    }

    /** Integração: arquivo gravado pelo FS em RAID 5 continua legível com um disco falho. */
    static void fileSystemOnRaid5SurvivesFailure() {
        FileSystem fs = new FileSystem(512, 24, "indexada", "first", RaidArray.Level.RAID5, 3);
        String text = "linha do CSV;".repeat(60);
        fs.create("/dados.csv", text.getBytes(StandardCharsets.UTF_8));
        check(!fs.disk.array.disks[0].queue.isEmpty(), "gravação do FS deve gerar requisições de E/S");
        fs.disk.array.fail(0);
        String read = new String(fs.read("/dados.csv"), StandardCharsets.UTF_8);
        check(read.equals(text), "conteúdo deve ser recuperado com o disco 1 em falha");
    }

    static void flashKeepsLatestVersion() {
        FlashSim ssd = new FlashSim(8, 4, true);
        for (int round = 0; round < 50; round++) {
            for (int lpn = 0; lpn < ssd.logicalPages; lpn++) {
                ssd.write(lpn);
            }
        }
        for (int lpn = 0; lpn < ssd.logicalPages; lpn++) {
            check(ssd.read(lpn) >= 0, "página lógica " + lpn + " deve continuar mapeada");
        }
        check(ssd.erasures > 0, "GC deve ter apagado blocos");
        check(ssd.writeAmplification() >= 1.0, "amplificação de escrita não pode ser menor que 1");

        // com dados frios (páginas que nunca mudam), o GC precisa copiar páginas válidas antes de apagar
        FlashSim mixed = new FlashSim(8, 4, false);
        mixed.replay(400, 3);
        check(mixed.gcCopies > 0, "carga com dados frios deve gerar cópias do GC");
        for (int lpn = 0; lpn < mixed.logicalPages; lpn++) {
            check(mixed.read(lpn) >= 0, "página lógica " + lpn + " perdida após GC");
        }
    }

    static void wearLevelingSpreadsErases() {
        FlashSim sem = new FlashSim(16, 8, false);
        FlashSim com = new FlashSim(16, 8, true);
        sem.replay(3000, 42);
        com.replay(3000, 42);
        check(com.wear().max() - com.wear().min() < sem.wear().max() - sem.wear().min(),
                "balanceamento de desgaste deve reduzir a diferença entre blocos");
    }

    private static byte[] block(int fill) {
        byte[] b = new byte[512];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
