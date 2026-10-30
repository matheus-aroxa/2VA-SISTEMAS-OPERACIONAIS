package fsim;

/** Requisição de E/S enfileirada em um disco físico: operação e trilha de destino (uma trilha por bloco físico). */
record IoRequest(Op op, int track) {
    enum Op { LEITURA, GRAVACAO }
}
