import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;

/**
 * O arquivo que simula o disco (disco_virtual.dat).
 *
 * Formato do arquivo:
 *
 *   CABEÇALHO DO DISCO (visível, 35 bytes - não revela nada do conteúdo)
 *      5 bytes  número mágico "SIMFS" (identifica o formato, como o 0xEF53 do ext4)
 *      1 byte   versão do formato
 *      1 byte   estado: 0 = LIMPO (desmontado direito), 1 = SUJO (em uso ou o programa caiu)
 *     16 bytes  salt (usado para gerar a chave a partir da senha)
 *     12 bytes  IV (número aleatório, novo a cada gravação)
 *
 *   DADOS (criptografados com AES-256-GCM)
 *      superbloco + árvore inteira: cabeçalho, nome e conteúdo de cada arquivo/diretório
 *
 * Sem a senha não dá para ver nomes, pastas nem conteúdos. O GCM também detecta
 * se algum byte foi alterado ou corrompido.
 */
public class DiscoVirtual {
    public static final byte ESTADO_LIMPO = 0;
    public static final byte ESTADO_SUJO = 1;

    private static final byte[] NUMERO_MAGICO = "SIMFS".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSAO_FORMATO = 1;
    private static final int TAMANHO_SALT = 16;
    private static final int TAMANHO_IV = 12;
    private static final int POSICAO_ESTADO = NUMERO_MAGICO.length + 1;                           // byte 6
    private static final int POSICAO_SALT = POSICAO_ESTADO + 1;                                    // byte 7
    private static final int POSICAO_IV = POSICAO_SALT + TAMANHO_SALT;                             // byte 23
    private static final int TAMANHO_CABECALHO = POSICAO_IV + TAMANHO_IV;                          // 35 bytes
    private static final int BITS_TAG_GCM = 128;
    private static final int ITERACOES_PBKDF2 = 210_000;  // deixa lento, de propósito, testar senhas na força bruta

    private final Path arquivo;
    private final Path arquivoTemporario;
    private final SecureRandom aleatorio = new SecureRandom();
    private SecretKey chave;   // só existe depois de montar ou formatar
    private byte[] salt;

    public DiscoVirtual(String caminhoArquivo) {
        this.arquivo = Paths.get(caminhoArquivo);
        this.arquivoTemporario = Paths.get(caminhoArquivo + ".tmp");
    }

    public boolean existe() { return Files.exists(arquivo); }

    public String getNomeArquivo() { return arquivo.toString(); }

    // ------------------------------------------------------------------
    // Formatar, montar, salvar
    // ------------------------------------------------------------------

    /** Cria um disco novo protegido pela senha informada. */
    public void formatar(char[] senha, Superbloco superbloco) throws IOException {
        salt = new byte[TAMANHO_SALT];
        aleatorio.nextBytes(salt);
        chave = derivarChave(senha, salt);
        salvar(superbloco);
    }

    /** Abre um disco existente. Lança SenhaIncorretaException sem alterar nada se a senha estiver errada. */
    public Superbloco montar(char[] senha) throws IOException, SenhaIncorretaException, FormatoInvalidoException {
        byte[] bytes = Files.readAllBytes(arquivo);
        validarCabecalho(bytes);
        byte[] saltDoDisco = Arrays.copyOfRange(bytes, POSICAO_SALT, POSICAO_SALT + TAMANHO_SALT);
        SecretKey chaveDaSenha = derivarChave(senha, saltDoDisco);
        Superbloco superbloco = decifrarELer(bytes, chaveDaSenha);
        // Só guarda a chave depois que a senha foi confirmada
        this.salt = saltDoDisco;
        this.chave = chaveDaSenha;
        return superbloco;
    }

    /** Confere só o cabeçalho visível (número mágico e versão), antes de pedir a senha. */
    public void verificarFormato() throws IOException, FormatoInvalidoException {
        validarCabecalho(Files.readAllBytes(arquivo));
    }

    /** Lê de novo o que está gravado (usado para desfazer uma alteração que não conseguiu ser salva). */
    public Superbloco recarregar() throws IOException, SenhaIncorretaException, FormatoInvalidoException {
        byte[] bytes = Files.readAllBytes(arquivo);
        validarCabecalho(bytes);
        return decifrarELer(bytes, chave);
    }

    /**
     * Grava o disco inteiro de forma ATÔMICA: escreve num arquivo temporário e só
     * depois troca pelo disco verdadeiro. Se o programa cair no meio, o disco
     * antigo continua inteiro. Enquanto montado, o estado gravado é SUJO.
     */
    public void salvar(Superbloco superbloco) throws IOException {
        byte[] dados = codificar(superbloco);
        byte[] iv = new byte[TAMANHO_IV];
        aleatorio.nextBytes(iv);
        byte[] dadosCifrados = cifrar(dados, iv);

        try (FileOutputStream saida = new FileOutputStream(arquivoTemporario.toFile())) {
            saida.write(NUMERO_MAGICO);
            saida.write(VERSAO_FORMATO);
            saida.write(ESTADO_SUJO);
            saida.write(salt);
            saida.write(iv);
            saida.write(dadosCifrados);
            saida.getFD().sync();   // garante que foi para o disco físico antes da troca
        }
        try {
            Files.move(arquivoTemporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(arquivoTemporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Lê o byte de estado (LIMPO/SUJO) do cabeçalho, sem precisar da senha. */
    public byte lerEstado() throws IOException {
        try (RandomAccessFile disco = new RandomAccessFile(arquivo.toFile(), "r")) {
            disco.seek(POSICAO_ESTADO);
            return disco.readByte();
        }
    }

    /** Muda só o byte de estado no cabeçalho (usado ao desmontar). */
    public void marcarEstado(byte estado) throws IOException {
        try (RandomAccessFile disco = new RandomAccessFile(arquivo.toFile(), "rw")) {
            disco.seek(POSICAO_ESTADO);
            disco.writeByte(estado);
            disco.getFD().sync();
        }
    }

    // ------------------------------------------------------------------
    // Cabeçalho e criptografia
    // ------------------------------------------------------------------

    private static void validarCabecalho(byte[] bytes) throws FormatoInvalidoException {
        if (bytes.length < TAMANHO_CABECALHO + BITS_TAG_GCM / 8) {
            throw new FormatoInvalidoException("arquivo vazio ou pequeno demais");
        }
        if (!Arrays.equals(Arrays.copyOfRange(bytes, 0, NUMERO_MAGICO.length), NUMERO_MAGICO)) {
            throw new FormatoInvalidoException("não começa com o número mágico SIMFS");
        }
        if (bytes[NUMERO_MAGICO.length] != VERSAO_FORMATO) {
            throw new FormatoInvalidoException("versão de formato " + bytes[NUMERO_MAGICO.length] + " não suportada");
        }
    }

    /** Transforma a senha numa chave AES de 256 bits (PBKDF2 com SHA-256). */
    private static SecretKey derivarChave(char[] senha, byte[] salt) {
        PBEKeySpec especificacao = new PBEKeySpec(senha, salt, ITERACOES_PBKDF2, 256);
        try {
            SecretKeyFactory fabrica = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] bytesChave = fabrica.generateSecret(especificacao).getEncoded();
            return new SecretKeySpec(bytesChave, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Este Java não suporta PBKDF2WithHmacSHA256", e);
        } finally {
            especificacao.clearPassword();
        }
    }

    private byte[] cifrar(byte[] dados, byte[] iv) {
        try {
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(BITS_TAG_GCM, iv));
            return cifra.doFinal(dados);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar o disco", e);
        }
    }

    private static Superbloco decifrarELer(byte[] bytes, SecretKey chave)
            throws SenhaIncorretaException, FormatoInvalidoException {
        byte[] iv = Arrays.copyOfRange(bytes, POSICAO_IV, POSICAO_IV + TAMANHO_IV);
        byte[] dados;
        try {
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(BITS_TAG_GCM, iv));
            dados = cifra.doFinal(bytes, TAMANHO_CABECALHO, bytes.length - TAMANHO_CABECALHO);
        } catch (AEADBadTagException e) {
            // O GCM não consegue diferenciar senha errada de arquivo adulterado/corrompido
            throw new SenhaIncorretaException();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar o disco", e);
        }
        try {
            return decodificar(dados);
        } catch (IOException | RuntimeException e) {
            throw new FormatoInvalidoException("dados internos inválidos (" + e.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------
    // Codificação da árvore em bytes (antes de criptografar)
    // ------------------------------------------------------------------

    private static byte[] codificar(Superbloco superbloco) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream saida = new DataOutputStream(buffer)) {
            saida.writeLong(superbloco.getProximoInode());
            escreverTexto(saida, superbloco.getUltimaTransacao());
            escreverTexto(saida, superbloco.getDataFormatacao().toString());
            escreverTexto(saida, superbloco.getUltimaMontagem().toString());
            saida.writeInt(superbloco.getMontagens());
            escreverItem(saida, superbloco.getRaiz());
        }
        return buffer.toByteArray();
    }

    private static Superbloco decodificar(byte[] dados) throws IOException {
        DataInputStream entrada = new DataInputStream(new ByteArrayInputStream(dados));
        long proximoInode = entrada.readLong();
        String ultimaTransacao = lerTexto(entrada);
        LocalDateTime dataFormatacao = LocalDateTime.parse(lerTexto(entrada));
        LocalDateTime ultimaMontagem = LocalDateTime.parse(lerTexto(entrada));
        int montagens = entrada.readInt();
        NoSistema raiz = lerItem(entrada, null);
        if (!raiz.isDiretorio()) {
            throw new IOException("a raiz não é um diretório");
        }
        return new Superbloco((DiretorioVirtual) raiz, proximoInode, ultimaTransacao,
                dataFormatacao, ultimaMontagem, montagens);
    }

    /**
     * Cada item é gravado assim:
     *   tipo, inode, nome, dono, permissões, data de criação, data de modificação,
     *   e depois: o conteúdo (se for arquivo) ou a quantidade de filhos + cada filho (se for diretório).
     */
    private static void escreverItem(DataOutputStream saida, NoSistema item) throws IOException {
        CabecalhoArquivo cabecalho = item.getCabecalho();
        saida.writeByte(cabecalho.getTipo());
        saida.writeLong(cabecalho.getInode());
        escreverTexto(saida, item.getNome());
        escreverTexto(saida, cabecalho.getDono());
        escreverTexto(saida, cabecalho.getPermissoes());
        escreverTexto(saida, cabecalho.getDataCriacao().toString());
        escreverTexto(saida, cabecalho.getDataModificacao().toString());
        if (item.isDiretorio()) {
            Collection<NoSistema> filhos = ((DiretorioVirtual) item).getFilhos().values();
            saida.writeInt(filhos.size());
            for (NoSistema filho : filhos) {
                escreverItem(saida, filho);
            }
        } else {
            escreverTexto(saida, ((ArquivoVirtual) item).getConteudo());
        }
    }

    private static NoSistema lerItem(DataInputStream entrada, DiretorioVirtual pai) throws IOException {
        char tipo = (char) entrada.readByte();
        long inode = entrada.readLong();
        String nome = lerTexto(entrada);
        String dono = lerTexto(entrada);
        String permissoes = lerTexto(entrada);
        LocalDateTime criacao = LocalDateTime.parse(lerTexto(entrada));
        LocalDateTime modificacao = LocalDateTime.parse(lerTexto(entrada));
        CabecalhoArquivo cabecalho = new CabecalhoArquivo(inode, tipo, dono, permissoes, criacao, modificacao);

        if (tipo == CabecalhoArquivo.TIPO_DIRETORIO) {
            DiretorioVirtual diretorio = new DiretorioVirtual(nome, pai, cabecalho);
            int quantidadeFilhos = entrada.readInt();
            for (int i = 0; i < quantidadeFilhos; i++) {
                diretorio.adicionarFilho(lerItem(entrada, diretorio));
            }
            return diretorio;
        }
        if (tipo == CabecalhoArquivo.TIPO_ARQUIVO) {
            return new ArquivoVirtual(nome, pai, lerTexto(entrada), cabecalho);
        }
        throw new IOException("tipo de item desconhecido: " + tipo);
    }

    /** Texto = tamanho em bytes (int) + bytes em UTF-8. */
    private static void escreverTexto(DataOutputStream saida, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        saida.writeInt(bytes.length);
        saida.write(bytes);
    }

    private static String lerTexto(DataInputStream entrada) throws IOException {
        int tamanho = entrada.readInt();
        if (tamanho < 0) {
            throw new IOException("tamanho de texto inválido");
        }
        byte[] bytes = new byte[tamanho];
        entrada.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Erros de montagem
    // ------------------------------------------------------------------

    /** Senha errada, ou arquivo adulterado/corrompido (o GCM não diferencia os dois casos). */
    public static class SenhaIncorretaException extends Exception {
        private static final long serialVersionUID = 1L;
        public SenhaIncorretaException() { super("senha incorreta ou disco corrompido"); }
    }

    /** O arquivo não é um disco deste simulador (ex.: disco da versão antiga, sem criptografia). */
    public static class FormatoInvalidoException extends Exception {
        private static final long serialVersionUID = 1L;
        public FormatoInvalidoException(String mensagem) { super(mensagem); }
    }
}
