package fsim;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Testes da Etapa 4: modelo híbrido de memória, paginação sob demanda, substituição de páginas, swap em RAID e proteção. */
public final class MemoryTest {

    private MemoryTest() {
    }

    public static void main(String[] args) {
        reservedArenaExample41AndCompaction();
        virtualMemoryDemandPagingAndInternalFragmentation();
        lruReplacementSwapBytesAndRaidIo();
        secondChanceFifoLfuAndOfflineOpt();
        securityModesAuditAndFileLoading();
        periodicWatchAndAllDemosPass();
        System.out.println("Todos os testes da Etapa 4 passaram.");
    }

    /**
     * Exemplo 4.1 do enunciado: Área Reservada de 4MB (1MB Núcleo Base) com alocação contígua de processos
     * críticos, detecção de fragmentação externa, recusa com MemoryFullException e compactação com fusão de lacunas.
     */
    static void reservedArenaExample41AndCompaction() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        // Núcleo Base do SO ocupa [0.0 MB - 1.0 MB]
        check(fs.memory.reserved.kernelBaseProcess != null, "núcleo base do SO deve estar ativo");
        ReservedArena.Segment baseSeg = fs.memory.reserved.segmentOf(fs.memory.reserved.kernelBaseProcess);
        check(baseSeg != null && baseSeg.startByte() == 0 && baseSeg.lengthBytes() == 1024L * 1024,
                "núcleo base do SO deve ocupar [0.0 MB - 1.0 MB]");

        // Cria Kernel_DiskDriver (512 KB), Kernel_NetDriver (1024 KB) e Kernel_CryptoEngine (512 KB)
        SimProcess diskDriver = fs.memory.createProcess(fs, "Kernel_DiskDriver", SimProcess.Type.CRITICAL, 512L * 1024, null);
        ReservedArena.Segment seg1 = fs.memory.reserved.segmentOf(diskDriver);
        check(seg1 != null && seg1.startByte() == 1024L * 1024 && seg1.lengthBytes() == 512L * 1024,
                "Kernel_DiskDriver deve ocupar [1.0 MB - 1.5 MB], obtido: " + (seg1 != null ? seg1.startByte() : null));

        SimProcess netDriver = fs.memory.createProcess(fs, "Kernel_NetDriver", SimProcess.Type.CRITICAL, 1024L * 1024, null);
        ReservedArena.Segment seg2 = fs.memory.reserved.segmentOf(netDriver);
        check(seg2 != null && seg2.startByte() == 1536L * 1024 && seg2.lengthBytes() == 1024L * 1024,
                "Kernel_NetDriver deve ocupar [1.5 MB - 2.5 MB], obtido: " + (seg2 != null ? seg2.startByte() : null));

        SimProcess cryptoEngine = fs.memory.createProcess(fs, "Kernel_CryptoEngine", SimProcess.Type.CRITICAL, 512L * 1024, null);
        ReservedArena.Segment seg3 = fs.memory.reserved.segmentOf(cryptoEngine);
        check(seg3 != null && seg3.startByte() == 2560L * 1024 && seg3.lengthBytes() == 512L * 1024,
                "Kernel_CryptoEngine deve ocupar [2.5 MB - 3.0 MB], obtido: " + (seg3 != null ? seg3.startByte() : null));

        // Diagnóstico antes do término: 3.0 MB ocupados, 1.0 MB livre contíguo [3.0 MB - 4.0 MB]
        ReservedArena.FragReport fragBefore = fs.memory.reserved.fragmentation();
        check(!fragBefore.fragmented(), "não deve haver fragmentação externa com apenas uma lacuna contígua");
        check(fragBefore.freeHolesCount() == 1, "deve haver exatamente 1 lacuna livre");
        check(fragBefore.totalFreeBytes() == 1024L * 1024, "deve haver 1024 KB livres");
        check(fragBefore.largestFreeBytes() == 1024L * 1024, "maior lacuna contígua de 1024 KB");

        // Finaliza Kernel_NetDriver: libera [1.5 MB - 2.5 MB] (1024 KB)
        MemoryManager.TerminationResult term = fs.memory.terminateProcess(fs, "Kernel_NetDriver");
        check(term.freedSegment() != null && term.freedSegment().startByte() == 1536L * 1024,
                "segmento liberado deve iniciar em 1.5 MB");

        // Checa fragmentação externa pós-término
        ReservedArena.FragReport fragAfter = fs.memory.reserved.fragmentation();
        check(fragAfter.fragmented(), "deve haver fragmentação externa detectada");
        check(fragAfter.freeHolesCount() == 2, "deve haver exatamente 2 fragmentos livres");
        check(fragAfter.totalFreeBytes() == 2048L * 1024, "total de memória livre deve ser 2048 KB (2.0 MB)");
        check(fragAfter.largestFreeBytes() == 1024L * 1024, "maior bloco contíguo disponível deve ser de 1024 KB");
        check(fragAfter.externalFragBytes() == 1024L * 1024, "fragmentação externa deve ser de 1024 KB");

        // Tentativa de alocar 1536 KB contíguos deve falhar com MemoryFullException e externalFragmentation == true
        try {
            fs.memory.createProcess(fs, "Kernel_BigDriver", SimProcess.Type.CRITICAL, 1536L * 1024, null);
            throw new AssertionError("alocação contígua de 1536 KB deveria ter falhado por fragmentação externa");
        } catch (MemoryFullException e) {
            check(e.externalFragmentation, "MemoryFullException deve indicar externalFragmentation == true");
            check(e.getMessage().contains("fragmentação externa"), "mensagem deve citar fragmentação externa");
        }

        // Executa compactação da área reservada
        List<ReservedArena.Move> moves = fs.memory.compactReserved(fs);
        check(moves.size() == 1, "deve haver exatamente 1 processo realocado");
        ReservedArena.Move move = moves.get(0);
        check(move.process().name.equals("Kernel_CryptoEngine"), "processo realocado deve ser Kernel_CryptoEngine");
        check(move.oldStart() == 2560L * 1024 && move.newStart() == 1536L * 1024,
                "Kernel_CryptoEngine deve ter sido movido de 2.5 MB para 1.5 MB");
        check(move.newEnd() == 2048L * 1024, "Kernel_CryptoEngine deve terminar em 2.0 MB");

        // Valida consolidação do espaço livre para [2.0 MB - 4.0 MB] (2048 KB contíguos)
        ReservedArena.FragReport fragCompact = fs.memory.reserved.fragmentation();
        check(!fragCompact.fragmented(), "fragmentação externa deve ser eliminada após compactação");
        check(fragCompact.freeHolesCount() == 1, "deve haver apenas 1 lacuna livre consolidada");
        check(fragCompact.totalFreeBytes() == 2048L * 1024, "total livre deve permanecer 2048 KB");
        check(fragCompact.largestFreeBytes() == 2048L * 1024, "maior lacuna deve ser 2048 KB contíguos");

        // Agora a alocação de 1536 KB contíguos deve ter sucesso
        SimProcess bigDriver = fs.memory.createProcess(fs, "Kernel_BigDriver", SimProcess.Type.CRITICAL, 1536L * 1024, null);
        ReservedArena.Segment bigSeg = fs.memory.reserved.segmentOf(bigDriver);
        check(bigSeg != null && bigSeg.startByte() == 2048L * 1024 && bigSeg.lengthBytes() == 1536L * 1024,
                "Kernel_BigDriver deve ser alocado com sucesso em [2.0 MB - 3.5 MB]");

        // Testa políticas de alocação de particionamento dinâmico: First-Fit, Best-Fit e Worst-Fit
        testAllocationPolicies();
    }

    /** Valida o comportamento comparativo de First-Fit, Best-Fit e Worst-Fit na Área Reservada. */
    private static void testAllocationPolicies() {
        // Criamos 3 lacunas separadas:
        // Lacuna 1: 300 B (início 100)
        // Lacuna 2: 200 B (início 500)
        // Lacuna 3: 500 B (início 800)
        // Para uma requisição de 150 B:
        // - First-Fit escolhe a primeira que cabe: Lacuna 1 (início 100)
        // - Best-Fit escolhe a menor lacuna suficiente: Lacuna 2 (início 500, sobra 50B)
        // - Worst-Fit escolhe a maior lacuna: Lacuna 3 (início 800, sobra 350B)

        // 1. First-Fit
        ReservedArena arenaFirst = new ReservedArena(1400, 0, "first");
        setupHoles(arenaFirst);
        SimProcess qFirst = new SimProcess(10, "QFirst", SimProcess.Type.CRITICAL, 150, "root", null, false, 0);
        ReservedArena.Segment segFirst = arenaFirst.allocate(qFirst);
        check(segFirst.startByte() == 100, "First-Fit deve selecionar a primeira lacuna (offset 100)");

        // 2. Best-Fit
        ReservedArena arenaBest = new ReservedArena(1400, 0, "best");
        setupHoles(arenaBest);
        SimProcess qBest = new SimProcess(11, "QBest", SimProcess.Type.CRITICAL, 150, "root", null, false, 0);
        ReservedArena.Segment segBest = arenaBest.allocate(qBest);
        check(segBest.startByte() == 500, "Best-Fit deve selecionar a menor lacuna suficiente (offset 500)");

        // 3. Worst-Fit
        ReservedArena arenaWorst = new ReservedArena(1400, 0, "worst");
        setupHoles(arenaWorst);
        SimProcess qWorst = new SimProcess(12, "QWorst", SimProcess.Type.CRITICAL, 150, "root", null, false, 0);
        ReservedArena.Segment segWorst = arenaWorst.allocate(qWorst);
        check(segWorst.startByte() == 800, "Worst-Fit deve selecionar a maior lacuna disponível (offset 800)");
    }

    private static void setupHoles(ReservedArena arena) {
        SimProcess p1 = new SimProcess(1, "P1", SimProcess.Type.CRITICAL, 100, "root", null, false, 0);
        SimProcess p2 = new SimProcess(2, "P2", SimProcess.Type.CRITICAL, 300, "root", null, false, 0);
        SimProcess p3 = new SimProcess(3, "P3", SimProcess.Type.CRITICAL, 100, "root", null, false, 0);
        SimProcess p4 = new SimProcess(4, "P4", SimProcess.Type.CRITICAL, 200, "root", null, false, 0);
        SimProcess p5 = new SimProcess(5, "P5", SimProcess.Type.CRITICAL, 100, "root", null, false, 0);
        SimProcess p6 = new SimProcess(6, "P6", SimProcess.Type.CRITICAL, 500, "root", null, false, 0);
        SimProcess p7 = new SimProcess(7, "P7", SimProcess.Type.CRITICAL, 100, "root", null, false, 0);

        arena.allocate(p1); // [0..100]
        arena.allocate(p2); // [100..400]
        arena.allocate(p3); // [400..500]
        arena.allocate(p4); // [500..700]
        arena.allocate(p5); // [700..800]
        arena.allocate(p6); // [800..1300]
        arena.allocate(p7); // [1300..1400]

        arena.free(p2); // Lacuna 1: 300B [100..400]
        arena.free(p4); // Lacuna 2: 200B [500..700]
        arena.free(p6); // Lacuna 3: 500B [800..1300]
    }

    /**
     * Exemplo 4.2 do enunciado: Memória Virtual com paginação sob demanda, tabela de páginas,
     * fragmentação interna exata e tradução de endereços virtuais pela MMU.
     */
    static void virtualMemoryDemandPagingAndInternalFragmentation() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        // Cria processo normal 'App_Navegador' de 10 KB (10240 Bytes) com página de 4 KB (4096 Bytes)
        SimProcess nav = fs.memory.createProcess(fs, "App_Navegador", SimProcess.Type.NORMAL, 10L * 1024, null);
        check(nav.type == SimProcess.Type.NORMAL, "App_Navegador deve ser processo normal");
        check(nav.numPages() == 3, "10 KB / 4 KB = 2.5 -> requer exatamente 3 páginas lógicas (0, 1 e 2)");
        check(nav.pageTable.length == 3, "tabela de páginas deve conter 3 entradas");

        // Valida fragmentação interna exata de 2048 Bytes na página 2 (50% desperdício)
        check(nav.pageTable[0].usedBytes == 4096, "página 0 ocupa 4096 bytes (100%)");
        check(nav.pageTable[1].usedBytes == 4096, "página 1 ocupa 4096 bytes (100%)");
        check(nav.pageTable[2].usedBytes == 2048, "página 2 ocupa 2048 bytes (50%)");
        check(nav.internalFragmentation(4096) == 2048, "fragmentação interna total deve ser 2048 Bytes");

        // Valida paginação sob demanda: inicialmente 0 molduras físicas ocupadas na RAM
        check(nav.residentCount() == 0, "processo recém-criado deve ter 0 páginas residentes");
        check(fs.memory.paged.usedFrames() == 0, "RAM dinâmica deve ter 0 molduras ocupadas");
        for (int p = 0; p < nav.numPages(); p++) {
            check(!nav.pageTable[p].present, "página " + p + " não deve estar presente inicialmente");
            check(nav.pageTable[p].frame == -1, "moldura da página " + p + " deve ser -1");
        }

        // Tradução sem carga (loadOnMiss = false) indica MISS / não residente
        PagedMemory.Translation tMiss = fs.memory.translate(fs, "App_Navegador", 1, 100, null, false);
        check(!tMiss.hit(), "translate sem load deve indicar MISS");
        check(tMiss.frame() == -1, "moldura deve ser -1 quando em MISS");
        check(tMiss.physicalAddress() == -1, "endereço físico deve ser -1 quando ausente na RAM");
        check(tMiss.virtualAddress() == 1L * 4096 + 100, "endereço virtual deve ser página * 4096 + offset (4196)");

        // Execução de instrução de acesso carrega a página 1 e provoca Page Fault
        PagedMemory.AccessResult ar = fs.memory.executeStep(fs, "App_Navegador", "LOAD_DATA Página 1", 1, false);
        check(!ar.hit(), "primeiro acesso à página 1 deve resultar em Page Fault");
        check(ar.frame() >= 0, "deve ter alocado uma moldura válida na RAM");
        check(nav.residentCount() == 1, "processo passa a ter 1 página residente");
        check(nav.pageTable[1].present, "página 1 agora está presente");
        check(nav.pageTable[1].frame == ar.frame(), "moldura mapeada deve ser a mesma retornada pelo acesso");

        // Tradução agora indica HIT na moldura física
        PagedMemory.Translation tHit = fs.memory.translate(fs, "App_Navegador", 1, 100, null, false);
        check(tHit.hit(), "tradução subsequente da página 1 deve indicar HIT");
        check(tHit.frame() == ar.frame(), "moldura traduzida deve corresponder");
        long expectedPaddr = (long) ar.frame() * 4096 + 100;
        check(tHit.physicalAddress() == expectedPaddr,
                "endereço físico deve ser moldura * 4096 + offset (" + expectedPaddr + ")");

        // Tradução por endereço virtual linear direto (vaddr = 2 * 4096 + 250 = 8442) com loadOnMiss = true
        long vaddr = 2L * 4096 + 250;
        PagedMemory.Translation tVaddr = fs.memory.translate(fs, "App_Navegador", null, 0, vaddr, true);
        check(tVaddr.logicalPage() == 2, "deve calcular página lógica 2");
        check(tVaddr.offset() == 250, "deve calcular offset 250");
        check(tVaddr.virtualAddress() == vaddr, "endereço virtual conferido");
        check(tVaddr.frame() >= 0, "deve carregar a página sob demanda e atribuir moldura");
        check(tVaddr.physicalAddress() == (long) tVaddr.frame() * 4096 + 250, "cálculo de endereço físico correto");
        check(nav.residentCount() == 2, "processo agora possui 2 páginas residentes");
    }

    /**
     * Substituição LRU, cálculo de ociosidade, área de troca (swap), preservação integral
     * de bytes e integração com requisições reais de E/S no arranjo RAID 5.
     */
    static void lruReplacementSwapBytesAndRaidIo() {
        // Configura arranjo RAID 5 com 3 discos e RAM dinâmica com 4 molduras de 4 KB (16 KB)
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID5, 3);
        fs.memory.configure(fs, 2L * 1024 * 1024, 16L * 1024, 4096, "first", PageReplacer.Policy.LRU);

        check(fs.memory.paged.numFrames == 4, "deve ter exatamente 4 molduras físicas");

        // Cria processo P1 de 16 KB (4 páginas lógicas: 0, 1, 2, 3) e P2 de 8 KB (2 páginas: 0, 1)
        SimProcess p1 = fs.memory.createProcess(fs, "P1", SimProcess.Type.NORMAL, 16L * 1024, null);
        SimProcess p2 = fs.memory.createProcess(fs, "P2", SimProcess.Type.NORMAL, 8L * 1024, null);

        // Carrega as 4 páginas de P1 ocupando todas as molduras da RAM
        fs.memory.executeStep(fs, "P1", "LOAD Página 0", 0, false);
        fs.memory.executeStep(fs, "P1", "LOAD Página 1", 1, false);
        fs.memory.executeStep(fs, "P1", "LOAD Página 2", 2, false);
        fs.memory.executeStep(fs, "P1", "LOAD Página 3", 3, false);

        check(fs.memory.paged.freeFrames() == 0, "todas as 4 molduras da RAM dinâmica devem estar ocupadas");
        int frameP1P0 = p1.pageTable[0].frame;

        // Escreve bytes conhecidos na moldura física onde reside a página 0 de P1
        fs.memory.paged.frames[frameP1P0][0] = (byte) 0xDE;
        fs.memory.paged.frames[frameP1P0][1] = (byte) 0xAD;
        fs.memory.paged.frames[frameP1P0][2] = (byte) 0xBE;
        fs.memory.paged.frames[frameP1P0][3] = (byte) 0xEF;

        // Limpa filas de E/S do RAID antes de provocar o swap-out
        for (PhysicalDisk d : fs.disk.array.disks) {
            d.queue.clear();
        }

        // Acessa uma nova página (página 0 do processo P2).
        // Como a RAM está cheia e a página mais antiga / ociosa é a 0 de P1, o algoritmo LRU a seleciona como vítima.
        PagedMemory.AccessResult swapOutResult = fs.memory.executeStep(fs, "P2", "LOAD Página 0", 0, false);
        check(swapOutResult.ramWasFull(), "deve sinalizar que a memória RAM estava cheia");
        check(swapOutResult.eviction() != null, "deve gerar registro de substituição de página");
        PagedMemory.EvictionInfo evict = swapOutResult.eviction();
        check(evict.victimPid() == p1.pid, "vítima deve ser o processo P1");
        check(evict.victimPage() == 0, "página vítima deve ser a página lógica 0");
        check(evict.frame() == frameP1P0, "moldura desocupada deve ser a antiga de P1");
        check(evict.idleMs() > 0, "deve ter calculado ociosidade positiva para o LRU");
        check(p1.pageTable[0].inSwap, "página 0 de P1 deve ter sido movida para o swap");
        check(!p1.pageTable[0].present, "página 0 de P1 não deve mais estar presente na RAM");
        int swapSlot = evict.swapSlot();
        check(swapSlot >= 0, "deve ter sido atribuído um slot de swap válido");

        // Valida que os bytes foram preservados com fidelidade no SwapSpace
        byte[] swappedBytes = fs.memory.paged.swap.slots.get(swapSlot);
        check(swappedBytes != null, "bytes devem existir no SwapSpace");
        check(swappedBytes[0] == (byte) 0xDE && swappedBytes[1] == (byte) 0xAD
                        && swappedBytes[2] == (byte) 0xBE && swappedBytes[3] == (byte) 0xEF,
                "bytes do processo devem estar idênticos no swap");

        // Valida que o swap-out gerou requisição real de GRAVACAO na fila do disco físico no RAID
        int targetDiskId = swapSlot % fs.disk.array.disks.length;
        PhysicalDisk disk = fs.disk.array.disks[targetDiskId];
        check(!disk.queue.isEmpty(), "disco físico do arranjo RAID deve conter requisição de E/S enfileirada");
        IoRequest lastIo = disk.queue.get(disk.queue.size() - 1);
        check(lastIo.op() == IoRequest.Op.GRAVACAO, "operação gerada no swap-out deve ser GRAVACAO");

        // Agora limpa a fila do disco para inspecionar o swap-in
        disk.queue.clear();

        // Re-acessa a página 0 de P1: deve provocar swap-in com restauração automática e requisição de LEITURA
        PagedMemory.AccessResult swapInResult = fs.memory.executeStep(fs, "P1", "LOAD Página 0", 0, false);
        check(swapInResult.restoredFromSwap(), "deve sinalizar restauração automática a partir do swap");
        check(swapInResult.restoredSwapSlot() == swapSlot, "slot restaurado deve corresponder ao anterior");
        check(p1.pageTable[0].present, "página 0 de P1 volta a estar presente na RAM");
        check(!p1.pageTable[0].inSwap, "página 0 de P1 não deve mais constar como em swap");

        // Valida que os bytes na nova moldura restaurada continuam íntegros
        int restoredFrame = p1.pageTable[0].frame;
        byte[] restoredData = fs.memory.paged.frames[restoredFrame];
        check(restoredData[0] == (byte) 0xDE && restoredData[1] == (byte) 0xAD
                        && restoredData[2] == (byte) 0xBE && restoredData[3] == (byte) 0xEF,
                "bytes restaurados do swap na moldura física devem estar perfeitamente íntegros");

        // Valida requisição de LEITURA enfileirada no disco pelo swap-in
        check(!disk.queue.isEmpty(), "swap-in deve gerar requisição física no disco");
        IoRequest readIo = disk.queue.get(disk.queue.size() - 1);
        check(readIo.op() == IoRequest.Op.LEITURA, "operação gerada no swap-in deve ser LEITURA");
    }

    /**
     * Valida os algoritmos de substituição Segunda Chance (Clock), FIFO, LFU
     * e o Teorema do Algoritmo Ótimo de Belady via simulação offline.
     */
    static void secondChanceFifoLfuAndOfflineOpt() {
        // 1. Algoritmo Segunda Chance (Clock):
        // Página com R=1 ganha segunda chance (R zerado e ponteiro avança); página com R=0 é escolhida
        PagedMemory pmClock = new PagedMemory(4096, 3, PageReplacer.Policy.SEGUNDA_CHANCE);
        SimProcess pClock = new SimProcess(101, "PClock", SimProcess.Type.NORMAL, 16L * 1024, "root", null, false, 4096);
        pmClock.initProcessPages(pClock, new byte[16384]);
        pmClock.accessPage(pClock, 0, "load 0", false, null); // moldura 0, R=1
        pmClock.accessPage(pClock, 1, "load 1", false, null); // moldura 1, R=1
        pmClock.accessPage(pClock, 2, "load 2", false, null); // moldura 2, R=1

        // Zera explicitamente o bit de referência da moldura 1 (página 1)
        pmClock.frameTable[1] = new PagedMemory.FrameInfo(1, pClock, 1, false, false, 0, 0, 1);
        pClock.pageTable[1].referenced = false;

        // Ao acessar a página 3:
        // Ponteiro do relógio inicia em 0: vê moldura 0 com R=1 -> zera R para 0 e avança para 1.
        // Vê moldura 1 com R=0 -> escolhe a moldura 1 como vítima!
        PagedMemory.AccessResult arClock = pmClock.accessPage(pClock, 3, "load 3", false, null);
        check(arClock.eviction() != null && arClock.eviction().victimPage() == 1,
                "Segunda Chance deve conceder chance à moldura 0 e vitimar moldura 1 (R=0)");
        check(arClock.eviction().frame() == 1, "moldura da vítima deve ser 1");
        check(!pmClock.frameTable[0].referenced(), "moldura 0 deve ter tido o bit R zerado ao receber segunda chance");

        // 2. Algoritmo FIFO (First-In, First-Out): vitima a página residente há mais tempo
        PagedMemory pmFifo = new PagedMemory(4096, 3, PageReplacer.Policy.FIFO);
        SimProcess pFifo = new SimProcess(102, "PFifo", SimProcess.Type.NORMAL, 16L * 1024, "root", null, false, 4096);
        pmFifo.initProcessPages(pFifo, new byte[16384]);
        pmFifo.accessPage(pFifo, 0, "load 0", false, null); // carregada no tempo T1
        pmFifo.accessPage(pFifo, 1, "load 1", false, null); // carregada no tempo T2
        pmFifo.accessPage(pFifo, 2, "load 2", false, null); // carregada no tempo T3

        // Acessamos a página 0 várias vezes recentemente para garantir que não é a menos recentemente usada
        pmFifo.accessPage(pFifo, 0, "hit 0", false, null);
        pmFifo.accessPage(pFifo, 0, "hit 0", false, null);

        // Acessa página 3: FIFO deve descartar a página 0 pois foi a primeira a entrar (menor loadedAtMs)
        PagedMemory.AccessResult arFifo = pmFifo.accessPage(pFifo, 3, "load 3", false, null);
        check(arFifo.eviction() != null && arFifo.eviction().victimPage() == 0,
                "FIFO deve vitimar a página 0 que foi a primeira carregada, mesmo acessada recentemente");

        // 3. Algoritmo LFU (Least Frequently Used): vitima a página com menor contador de acessos
        PagedMemory pmLfu = new PagedMemory(4096, 3, PageReplacer.Policy.LFU);
        SimProcess pLfu = new SimProcess(103, "PLfu", SimProcess.Type.NORMAL, 16L * 1024, "root", null, false, 4096);
        pmLfu.initProcessPages(pLfu, new byte[16384]);
        pmLfu.accessPage(pLfu, 0, "load 0", false, null);
        pmLfu.accessPage(pLfu, 1, "load 1", false, null);
        pmLfu.accessPage(pLfu, 2, "load 2", false, null);

        // Página 0 recebe 4 acessos adicionais, página 2 recebe 2 acessos, página 1 fica apenas com 1 acesso
        for (int i = 0; i < 4; i++) {
            pmLfu.accessPage(pLfu, 0, "hit 0", false, null);
        }
        for (int i = 0; i < 2; i++) {
            pmLfu.accessPage(pLfu, 2, "hit 2", false, null);
        }

        // Acessa página 3: LFU deve descartar a página 1 (apenas 1 acesso registrado)
        PagedMemory.AccessResult arLfu = pmLfu.accessPage(pLfu, 3, "load 3", false, null);
        check(arLfu.eviction() != null && arLfu.eviction().victimPage() == 1,
                "LFU deve vitimar a página 1 por ter o menor número de acessos");

        // 4. Simulação Offline e Teorema de Belady (Algoritmo Ótimo)
        // Sequência clássica de literatura com 3 molduras: 7,0,1,2,0,3,0,4,2,3,0,3,2,1,2,0,1,7,0,1
        List<Integer> refs = List.of(7, 0, 1, 2, 0, 3, 0, 4, 2, 3, 0, 3, 2, 1, 2, 0, 1, 7, 0, 1);
        PageReplacer.SimResult simOpt = PageReplacer.simulate(PageReplacer.Policy.OTIMO, 3, refs);
        PageReplacer.SimResult simLru = PageReplacer.simulate(PageReplacer.Policy.LRU, 3, refs);
        PageReplacer.SimResult simFifo = PageReplacer.simulate(PageReplacer.Policy.FIFO, 3, refs);
        PageReplacer.SimResult simClock = PageReplacer.simulate(PageReplacer.Policy.SEGUNDA_CHANCE, 3, refs);
        PageReplacer.SimResult simLfu = PageReplacer.simulate(PageReplacer.Policy.LFU, 3, refs);

        check(simOpt.hits() == 11 && simOpt.faults() == 9,
                "Ótimo com 3 molduras deve ter 11 hits e 9 faltas, obtido: " + simOpt.hits() + "/" + simOpt.faults());
        check(simLru.hits() == 8 && simLru.faults() == 12,
                "LRU com 3 molduras deve ter 8 hits e 12 faltas, obtido: " + simLru.hits() + "/" + simLru.faults());
        check(simFifo.hits() == 5 && simFifo.faults() == 15,
                "FIFO com 3 molduras deve ter 5 hits e 15 faltas, obtido: " + simFifo.hits() + "/" + simFifo.faults());
        check(simClock.hits() == 6 && simClock.faults() == 14,
                "Segunda Chance com 3 molduras deve ter 6 hits e 14 faltas");
        check(simLfu.hits() == 6 && simLfu.faults() == 14,
                "LFU com 3 molduras deve ter 6 hits e 14 faltas");

        // Comprova matematicamente que o Ótimo possui menos ou igual faltas que qualquer algoritmo real
        check(simOpt.faults() <= simLru.faults(), "Ótimo deve ter <= faltas que LRU");
        check(simOpt.faults() <= simFifo.faults(), "Ótimo deve ter <= faltas que FIFO");
        check(simOpt.faults() <= simClock.faults(), "Ótimo deve ter <= faltas que Segunda Chance");
        check(simOpt.faults() <= simLfu.faults(), "Ótimo deve ter <= faltas que LFU");

        // Valida casos de borda do simulador
        PageReplacer.SimResult emptySim = PageReplacer.simulate(PageReplacer.Policy.LRU, 3, List.of());
        check(emptySim.hits() == 0 && emptySim.faults() == 0, "sequência vazia não deve gerar eventos");

        try {
            PageReplacer.simulate(PageReplacer.Policy.LRU, 0, refs);
            throw new AssertionError("molduras <= 0 deveria falhar");
        } catch (FSException expected) {
        }

        try {
            PageReplacer.Policy.parseLive("OTIMO");
            throw new AssertionError("parseLive de OTIMO deve falhar pois exige futuro");
        } catch (FSException expected) {
        }
    }

    /**
     * Valida controle de segurança: isolamento de privilégios entre MODO_KERNEL e MODO_USUARIO,
     * registro na trilha de auditoria e criação de processo a partir de arquivo com validação de quarentena/permissões.
     */
    static void securityModesAuditAndFileLoading() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.auth.addUser("alice", "SenhaAlice!42", "devs", false);
        fs.auth.login("alice", "SenhaAlice!42");
        check(fs.auth.mode() == AuthManager.Mode.MODO_USUARIO, "alice deve estar operando em MODO_USUARIO");

        // 1. Tentativa de criar processo crítico em MODO_USUARIO
        int auditBefore = fs.audit.entries().size();
        try {
            fs.memory.createProcess(fs, "Kernel_Malware", SimProcess.Type.CRITICAL, 512L * 1024, null);
            throw new AssertionError("criação de processo crítico em MODO_USUARIO deveria falhar");
        } catch (FSException expected) {
            // esperado
        }
        List<AuditLog.Entry> auditEntries = fs.audit.entries();
        check(auditEntries.size() == auditBefore + 1, "bloqueio de privilégio deve gravar no AuditLog");
        check(auditEntries.get(auditBefore).category().equals("PRIVILEGIO"),
                "categoria de auditoria esperada: PRIVILEGIO");

        // 2. Tentativa de compactar memória reservada em MODO_USUARIO
        try {
            fs.memory.compactReserved(fs);
            throw new AssertionError("memory_compact deveria falhar em MODO_USUARIO");
        } catch (FSException expected) {
        }

        // 3. Tentativa de reconfigurar memória em MODO_USUARIO
        try {
            fs.memory.configure(fs, 4L * 1024 * 1024, 64L * 1024, 4096, "first", PageReplacer.Policy.LRU);
            throw new AssertionError("memory_format deveria falhar em MODO_USUARIO");
        } catch (FSException expected) {
        }

        // 4. Tentativa de alterar política em MODO_USUARIO
        try {
            fs.memory.setPolicy(fs, PageReplacer.Policy.FIFO);
            throw new AssertionError("page_policy deveria falhar em MODO_USUARIO");
        } catch (FSException expected) {
        }

        // 5. Tentativa de finalizar processo de outro usuário sem privilégios administrativos
        fs.auth.logout(); // retorna para root (MODO_KERNEL)
        SimProcess rootApp = fs.memory.createProcess(fs, "RootApp", SimProcess.Type.NORMAL, 8192L, null);
        check(rootApp.owner.equals("root"), "proprietário deve ser root");

        fs.auth.login("alice", "SenhaAlice!42"); // alice em MODO_USUARIO
        try {
            fs.memory.terminateProcess(fs, "RootApp");
            throw new AssertionError("alice não pode finalizar processo do root sem privilégio");
        } catch (FSException expected) {
        }

        // Com MODO_KERNEL (root / sudo), finalização é permitida
        fs.auth.logout(); // volta a root
        fs.memory.terminateProcess(fs, "RootApp");
        check(fs.memory.processes().stream().noneMatch(p -> p.name.equals("RootApp")),
                "processo RootApp deve ter sido finalizado pelo root");

        // 6. Criação de processo a partir de arquivo (--file)
        fs.mkdir("/bin", false);
        fs.mkdir("/root", false);
        byte[] code = "EXECUTAVEL_BINARIO_DO_SISTEMA_V4".getBytes(StandardCharsets.UTF_8);
        fs.create("/bin/app", code);
        SimProcess appProc = fs.memory.createProcess(fs, "AppDoDisco", SimProcess.Type.NORMAL, null, "/bin/app");
        check(appProc.sizeBytes == code.length, "tamanho do processo deve coincidir com o arquivo");
        check(appProc.sourcePath.equals("/bin/app"), "origem deve ser /bin/app");

        // Execução do primeiro passo carrega os bytes reais do arquivo na RAM
        PagedMemory.AccessResult stepExec = fs.memory.executeStep(fs, "AppDoDisco", "EXEC", 0, false);
        byte[] ramBytes = fs.memory.paged.frames[stepExec.frame()];
        for (int i = 0; i < code.length; i++) {
            check(ramBytes[i] == code[i], "conteúdo carregado na moldura física deve conferir com o arquivo do disco");
        }

        // 7. Arquivo em quarentena deve recusar criação de processo
        fs.create("/bin/virus.exe", "TEST-X5O-EICAR-STANDARD-ANTIVIRUS-TEST-FILE-TEST".getBytes(StandardCharsets.UTF_8));
        fs.scanIntegrity("/bin/virus.exe", true);
        check(fs.integrity.isQuarantined(fs.resolve("/bin/virus.exe")), "virus.exe deve estar em quarentena");
        try {
            fs.memory.createProcess(fs, "AppInfectado", SimProcess.Type.NORMAL, null, "/bin/virus.exe");
            throw new AssertionError("arquivo em quarentena não pode originar processo");
        } catch (FSException expected) {
            check(expected.getMessage().contains("quarentena"), "mensagem deve indicar quarentena");
        }

        // 8. Arquivo sem permissão de leitura para usuário comum deve recusar criação de processo
        fs.create("/root/privado.bin", new byte[]{1, 2, 3});
        fs.chmod("/root/privado.bin", "600");
        fs.auth.login("alice", "SenhaAlice!42");
        try {
            fs.memory.createProcess(fs, "AppInvasor", SimProcess.Type.NORMAL, null, "/root/privado.bin");
            throw new AssertionError("arquivo sem permissão de leitura deve bloquear criação");
        } catch (FSException expected) {
            check(expected.getMessage().contains("permissão negada"), "mensagem deve indicar permissão negada");
        }
    }

    /**
     * Valida o monitor periódico (memwatch) e garante que todas as demonstrações
     * de todas as etapas (indexada, contigua, encadeada, raid5, seguranca, memoria) continuam passando sem regressões.
     */
    static void periodicWatchAndAllDemosPass() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);

        // Monitor periódico a cada 3 operações
        fs.memory.setWatchInterval(fs, 3);
        check(fs.memory.watchInterval == 3, "watchInterval deve ser 3");
        check(!fs.memory.tickWatch(), "op 1 não deve disparar monitor");
        check(!fs.memory.tickWatch(), "op 2 não deve disparar monitor");
        check(fs.memory.tickWatch(), "op 3 deve disparar monitor");
        check(!fs.memory.tickWatch(), "op 4 não deve disparar monitor");
        check(!fs.memory.tickWatch(), "op 5 não deve disparar monitor");
        check(fs.memory.tickWatch(), "op 6 deve disparar monitor");

        // Executa todas as demos prontas e valida a ausência de exceções não tratadas
        DemoRun indexada = runDemo("indexada");
        check(!indexada.output().contains("Erro:"), "demo indexada não deve conter erros: " + indexada.output());

        DemoRun contigua = runDemo("contigua");
        check(contigua.output().contains("Compactação concluída:"), "demo contigua deve concluir compactação");
        check(contigua.fs().exists("/home/user/grande.bin"), "demo contigua deve alocar grande.bin após defrag");

        DemoRun encadeada = runDemo("encadeada");
        check(!encadeada.output().contains("Erro:"), "demo encadeada não deve conter erros: " + encadeada.output());

        DemoRun raid5 = runDemo("raid5");
        check(!raid5.output().contains("Erro:"), "demo raid5 não deve conter erros: " + raid5.output());
        check(raid5.output().contains("Disco 2 reconstruído:"), "demo raid5 deve reconstruir Disco 2");

        DemoRun seguranca = runDemo("seguranca");
        check(!seguranca.output().contains("Erro:"), "demo seguranca não deve conter erros: " + seguranca.output());
        check(seguranca.output().contains("[OK - Criptografado e Íntegro]"), "demo seguranca deve passar na integridade");

        DemoRun memoria = runDemo("memoria");
        check(!memoria.output().contains("Erro:"), "demo memoria não deve conter erros: " + memoria.output());
        check(memoria.output().contains("Realocando blocos contíguos para eliminar a fragmentação externa..."),
                "demo memoria deve executar compactação na área reservada");
        check(memoria.output().contains("Fragmentação Interna Total: 2048 Bytes"),
                "demo memoria deve diagnosticar fragmentação interna");
        check(memoria.output().contains("Substituição de Página (Algoritmo LRU)"),
                "demo memoria deve executar substituição de página LRU");
        check(memoria.output().contains("RESULTADO DA SIMULAÇÃO DE SUBSTITUIÇÃO DE PÁGINAS"),
                "demo memoria deve exibir o resultado de page_sim");
    }

    // ---------------------------------------------------------------- auxiliares
    private record DemoRun(FileSystem fs, String output) {
    }

    private static DemoRun runDemo(String name) {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);
        shell.execute("demo " + name, false);
        return new DemoRun(fs, buf.toString(StandardCharsets.UTF_8));
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
