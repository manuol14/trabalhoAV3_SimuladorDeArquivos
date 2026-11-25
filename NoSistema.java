/**
 * Item da árvore do sistema de arquivos (arquivo ou diretório).
 *
 * O nome guardado aqui é só a última parte (ex.: "teste.txt"). O "nome de verdade"
 * para o sistema é o caminho completo (ex.: "/docs/teste.txt"), que NÃO é guardado:
 * é calculado subindo pelos pais até a raiz. Assim, renomear ou mover uma pasta
 * não obriga a atualizar nada do que está dentro dela.
 */
public abstract class NoSistema {
    protected String nome;
    protected DiretorioVirtual pai;
    protected final CabecalhoArquivo cabecalho;

    public NoSistema(String nome, DiretorioVirtual pai, CabecalhoArquivo cabecalho) {
        this.nome = nome;
        this.pai = pai;
        this.cabecalho = cabecalho;
    }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public DiretorioVirtual getPai() { return pai; }
    public void setPai(DiretorioVirtual pai) { this.pai = pai; }
    public CabecalhoArquivo getCabecalho() { return cabecalho; }

    /** Caminho absoluto do item, no padrão do Linux. A raiz é "/". */
    public String getCaminho() {
        if (pai == null) {
            return "/";
        }
        String caminhoDoPai = pai.getCaminho();
        return caminhoDoPai.equals("/") ? "/" + nome : caminhoDoPai + "/" + nome;
    }

    /** true se este item é o diretório informado ou está em algum lugar dentro dele. */
    public boolean estaDentroDe(DiretorioVirtual diretorio) {
        NoSistema atual = this;
        while (atual != null) {
            if (atual == diretorio) {
                return true;
            }
            atual = atual.pai;
        }
        return false;
    }

    public abstract boolean isDiretorio();
}
