import java.time.LocalDateTime;

/**
 * Superbloco: informações gerais do sistema de arquivos.
 *
 * Fica na parte CRIPTOGRAFADA do disco, logo depois do cabeçalho visível
 * (ver DiscoVirtual), e aponta para o diretório raiz "/".
 */
public class Superbloco {
    private long proximoInode;
    private String ultimaTransacao;          // ID da última transação gravada no disco (usado na recuperação do journal)
    private final LocalDateTime dataFormatacao;
    private LocalDateTime ultimaMontagem;
    private int montagens;
    private final DiretorioVirtual raiz;

    /** Superbloco de um disco recém-formatado. */
    public Superbloco(DiretorioVirtual raiz, long proximoInode) {
        this(raiz, proximoInode, "", LocalDateTime.now(), LocalDateTime.now(), 1);
    }

    /** Superbloco lido do disco. */
    public Superbloco(DiretorioVirtual raiz, long proximoInode, String ultimaTransacao,
                      LocalDateTime dataFormatacao, LocalDateTime ultimaMontagem, int montagens) {
        this.raiz = raiz;
        this.proximoInode = proximoInode;
        this.ultimaTransacao = ultimaTransacao;
        this.dataFormatacao = dataFormatacao;
        this.ultimaMontagem = ultimaMontagem;
        this.montagens = montagens;
    }

    public DiretorioVirtual getRaiz() { return raiz; }
    public long getProximoInode() { return proximoInode; }
    public String getUltimaTransacao() { return ultimaTransacao; }
    public LocalDateTime getDataFormatacao() { return dataFormatacao; }
    public LocalDateTime getUltimaMontagem() { return ultimaMontagem; }
    public int getMontagens() { return montagens; }

    /** Entrega um número de inode novo para um arquivo/diretório que está sendo criado. */
    public long gerarInode() { return proximoInode++; }

    public void setUltimaTransacao(String idTransacao) { this.ultimaTransacao = idTransacao; }

    public void registrarMontagem() {
        this.ultimaMontagem = LocalDateTime.now();
        this.montagens++;
    }
}
