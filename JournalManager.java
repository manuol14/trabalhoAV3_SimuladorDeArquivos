import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal (log de operações), em texto legível.
 *
 * Cada operação gera uma linha PENDENTE antes de mexer no disco e uma linha
 * COMMIT (ou ABORTADA) depois. Os itens aparecem pelo caminho completo, mas o
 * CONTEÚDO dos arquivos nunca é gravado aqui, para não vazar pelo journal.
 */
public class JournalManager {
    public static final String PENDENTE = "PENDENTE";
    public static final String COMMIT = "COMMIT";
    public static final String ABORTADA = "ABORTADA";

    private static final String CAMPO_TX = "TX:";
    private static final String CAMPO_OP = " | OP:";
    private static final String CAMPO_DETALHES = " | DETALHES:";
    private static final String CAMPO_STATUS = " | STATUS:";

    private final Path caminhoJournal;

    public JournalManager(String arquivoLog) {
        this.caminhoJournal = Paths.get(arquivoLog);
        inicializar();
    }

    private void inicializar() {
        try {
            if (!Files.exists(caminhoJournal)) {
                Files.createFile(caminhoJournal);
            }
        } catch (IOException e) {
            System.err.println("Erro ao inicializar o Journal: " + e.getMessage());
        }
    }

    /**
     * Acrescenta uma linha no journal. A opção SYNC garante que a linha foi para o
     * disco físico antes de continuar (sem isso o PENDENTE poderia se perder numa queda).
     * Retorna false se não conseguiu escrever.
     */
    public boolean registrarOperacao(String idTransacao, String operacao, String detalhes, String status) {
        String linha = String.format("[%s] %s%s%s%s%s%s%s%s%n",
                LocalDateTime.now(), CAMPO_TX, idTransacao, CAMPO_OP, operacao,
                CAMPO_DETALHES, detalhes, CAMPO_STATUS, status);
        try {
            Files.write(caminhoJournal, linha.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
            return true;
        } catch (IOException e) {
            System.err.println("Falha crítica ao escrever no Journal: " + e.getMessage());
            return false;
        }
    }

    /**
     * Recuperação, feita a cada montagem. Procura transações que ficaram só com
     * PENDENTE, ou seja, o programa caiu no meio delas:
     *  - se o ID é o da última transação gravada no disco (anotado no superbloco),
     *    a alteração chegou a ser salva: o journal é completado com COMMIT;
     *  - senão, a alteração não chegou ao disco: a transação é marcada como ABORTADA.
     * Retorna quantas transações pendentes foram resolvidas.
     */
    public int recuperar(String ultimaTransacaoNoDisco) {
        List<String> linhas;
        try {
            linhas = Files.readAllLines(caminhoJournal, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Erro ao ler o Journal: " + e.getMessage());
            return 0;
        }

        // Para cada transação, fica guardada a última linha dela: {operação, detalhes, status}
        Map<String, String[]> transacoes = new LinkedHashMap<>();
        for (String linha : linhas) {
            int inicioTx = linha.indexOf(CAMPO_TX);
            int inicioOp = linha.indexOf(CAMPO_OP);
            int inicioDetalhes = linha.indexOf(CAMPO_DETALHES);
            int inicioStatus = linha.lastIndexOf(CAMPO_STATUS);
            if (inicioTx < 0 || inicioOp < inicioTx || inicioDetalhes < inicioOp || inicioStatus < inicioDetalhes) {
                continue; // linha fora do formato
            }
            String tx = linha.substring(inicioTx + CAMPO_TX.length(), inicioOp);
            String operacao = linha.substring(inicioOp + CAMPO_OP.length(), inicioDetalhes);
            String detalhes = linha.substring(inicioDetalhes + CAMPO_DETALHES.length(), inicioStatus);
            String status = linha.substring(inicioStatus + CAMPO_STATUS.length()).trim();
            transacoes.put(tx, new String[] {operacao, detalhes, status});
        }

        int resolvidas = 0;
        for (Map.Entry<String, String[]> transacao : transacoes.entrySet()) {
            String tx = transacao.getKey();
            String operacao = transacao.getValue()[0];
            String detalhes = transacao.getValue()[1];
            if (!transacao.getValue()[2].equals(PENDENTE)) {
                continue;
            }
            resolvidas++;
            if (tx.equals(ultimaTransacaoNoDisco)) {
                registrarOperacao(tx, operacao, detalhes, COMMIT);
                System.out.println("[JOURNAL] TX " + tx + " (" + operacao + " " + detalhes
                        + "): já estava gravada no disco. Completada com COMMIT.");
            } else {
                registrarOperacao(tx, operacao, detalhes, ABORTADA);
                System.out.println("[JOURNAL] TX " + tx + " (" + operacao + " " + detalhes
                        + "): não chegou ao disco. Marcada como ABORTADA.");
            }
        }
        return resolvidas;
    }
}
