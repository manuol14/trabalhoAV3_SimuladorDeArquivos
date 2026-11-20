public class ArquivoVirtual extends NoSistema {
    private static final long serialVersionUID = 1L;
    private String conteudo;

    public ArquivoVirtual(String nome, DiretorioVirtual pai, String conteudo) {
        super(nome, pai);
        this.conteudo = conteudo;
    }

    public String getConteudo() { return conteudo; }
    public void setConteudo(String conteudo) { this.conteudo = conteudo; }

    @Override
    public boolean isDiretorio() { return false; }
}