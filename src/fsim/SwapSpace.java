package fsim;

import java.util.HashMap;
import java.util.Map;

/**
 * Área de troca (swap) em disco: persiste páginas descarregadas da memória física gerando requisições reais de E/S.
 */
final class SwapSpace {

    record SwapIo(int slot, int diskId, int track, IoRequest.Op op) {
    }

    final int pageSize;
    long swapOuts;
    long swapIns;
    final Map<Integer, byte[]> slots = new HashMap<>();
    int nextSlot = 0;

    SwapSpace(int pageSize) {
        if (pageSize <= 0) {
            throw new FSException("tamanho da página no swap deve ser positivo: " + pageSize);
        }
        this.pageSize = pageSize;
    }

    SwapIo swapOut(int existingSlot, byte[] pageData, RaidArray array) {
        int slot = existingSlot >= 0 ? existingSlot : nextSlot++;
        if (slot >= nextSlot) {
            nextSlot = slot + 1;
        }
        byte[] copy = new byte[pageSize];
        if (pageData != null) {
            System.arraycopy(pageData, 0, copy, 0, Math.min(pageData.length, pageSize));
        }
        slots.put(slot, copy);

        int d = -1;
        int t = -1;
        if (array != null && array.disks != null && array.disks.length > 0 && array.blocksPerDisk > 0) {
            d = slot % array.disks.length;
            t = (slot / array.disks.length) % array.blocksPerDisk;
            byte[] diskBlock = copy;
            if (diskBlock.length != array.blockSize) {
                diskBlock = new byte[array.blockSize];
                System.arraycopy(copy, 0, diskBlock, 0, Math.min(copy.length, diskBlock.length));
            }
            array.disks[d].write(t, diskBlock, true);
        }
        swapOuts++;
        return new SwapIo(slot, d, t, IoRequest.Op.GRAVACAO);
    }

    byte[] swapIn(int slot, RaidArray array) {
        byte[] data = slots.get(slot);
        if (data == null) {
            throw new FSException("página não encontrada no swap: slot " + slot);
        }
        if (array != null && array.disks != null && array.disks.length > 0 && array.blocksPerDisk > 0) {
            int d = slot % array.disks.length;
            int t = (slot / array.disks.length) % array.blocksPerDisk;
            array.disks[d].read(t, true);
        }
        swapIns++;
        return data.clone();
    }

    void freeSlot(int slot) {
        slots.remove(slot);
    }

    int activeSlots() {
        return slots.size();
    }

    long usedBytes() {
        return (long) slots.size() * pageSize;
    }

    void reset() {
        slots.clear();
        nextSlot = 0;
        swapOuts = 0;
        swapIns = 0;
    }
}
