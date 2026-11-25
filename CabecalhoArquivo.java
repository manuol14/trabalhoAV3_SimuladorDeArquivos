import java.time.LocalDateTime;

/**
 * Cabeçalho (atributos) de um arquivo ou diretório. É o equivalente ao inode do Linux.
 *
 * Guarda tudo o que o sistema sabe sobre o item, menos o nome: no Linux o nome
 * fica na entrada do diretório (ver DiretorioVirtual) e o caminho completo é
 * calculado subindo pela árvore (ver NoSistema.getCaminho()).
 */
public class CabecalhoArquivo {
    public static final char TIPO_ARQUIVO = '-';
    public static final char TIPO_DIRETORIO = 'd';

    private final long inode;            // número único do item dentro do disco
    private final char tipo;             // '-' arquivo, 'd' diretório (igual à 1ª letra do ls -l)
    private long tamanho;                // em bytes (UTF-8); diretórios ficam com 0
    private final LocalDateTime dataCriacao;
    private LocalDateTime dataModificacao;
    private final String dono;
    private final String permissoes;     // formato do Linux, ex.: "rw-r--r--"

    /** Cabeçalho de um item novo: criação e modificação = agora. */
    public CabecalhoArquivo(long inode, char tipo, String dono, String permissoes) {
        this(inode, tipo, dono, permissoes, LocalDateTime.now(), null);
    }

    /** Cabeçalho lido do disco, com as datas que estavam gravadas. */
    public CabecalhoArquivo(long inode, char tipo, String dono, String permissoes,
                            LocalDateTime dataCriacao, LocalDateTime dataModificacao) {
        this.inode = inode;
        this.tipo = tipo;
        this.dono = dono;
        this.permissoes = permissoes;
        this.dataCriacao = dataCriacao;
        this.dataModificacao = (dataModificacao != null) ? dataModificacao : dataCriacao;
    }

    public long getInode() { return inode; }
    public char getTipo() { return tipo; }
    public long getTamanho() { return tamanho; }
    public LocalDateTime getDataCriacao() { return dataCriacao; }
    public LocalDateTime getDataModificacao() { return dataModificacao; }
    public String getDono() { return dono; }
    public String getPermissoes() { return permissoes; }

    /** Permissões com o tipo na frente, como o ls -l mostra: "-rw-r--r--" ou "drwxr-xr-x". */
    public String getPermissoesCompletas() { return tipo + permissoes; }

    public void setTamanho(long tamanho) { this.tamanho = tamanho; }

    /** Chamado sempre que o item (ou o conteúdo de um diretório) muda. */
    public void marcarModificacao() { this.dataModificacao = LocalDateTime.now(); }
}
