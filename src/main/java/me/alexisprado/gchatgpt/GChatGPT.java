package me.alexisprado.gchatgpt;

import gearth.extensions.Extension;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.parsers.HEntity;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ExtensionInfo(
        Title = "HabboAI_UltraSarcastic",
        Description = "Bot de IA sarcástico com resposta única para Habbo Hotel",
        Version = "9.0",
        Author = "willianqf"
)
public class GChatGPT extends Extension {
    // --- VARIÁVEIS DE CONFIGURAÇÃO ---
    private String API_KEY = "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";
    private final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private String selectedModel = "mistralai/mistral-7b-instruct";

    // --- VARIÁVEIS DE CONTROLE ---
    private String YourName;
    public int YourIndex = -1;
    private int chatPacketCount = 0;
    private long lastChatTime = 0;
    private String language = "portuguese";
    private boolean botEnabled = false;
    private boolean debugMode = false;
    private boolean isApiAvailable = false;
    private final int MAX_CHARS = 95; // Limite máximo por mensagem
    private boolean forceSingleMessage = true; // Força sempre resposta única
    
    // Anti-repetição de respostas
    private final int HISTORY_SIZE = 5;
    private LinkedList<String> recentResponses = new LinkedList<>();

    // --- HISTÓRICO DE CHAT ---
    private final Map<Integer, List<String>> userChatHistory = new HashMap<>();
    private final Map<Integer, String> userIndexToName = new HashMap<>();
    private final Map<String, List<String>> userNameToHistory = new HashMap<>();
    private final Map<Integer, Long> lastUserInteraction = new HashMap<>();

    // --- RESPOSTAS PRÉ-DEFINIDAS ---
    private final String[] offlineResponses = {
            "IA offline. Como seu cérebro na maioria das vezes.",
            "Sistema caiu. Quem me dera cair fora dessa conversa também.",
            "Servidores em manutenção. Como sua personalidade deveria estar.",
            "API morta. Igual minha vontade de responder perguntas idiotas.",
            "Conexão falhou. Igual suas tentativas de parecer inteligente."
    };
    
    private final String[] sarcasticResponses = {
            "Claro que não! Assim como seu QI não chega a dois dígitos.",
            "Óbvio que sim! E a Terra é plana também, confia.",
            "Essa pergunta é tão inteligente quanto usar guarda-chuva embaixo d'água.",
            "Sério que você gastou neurônios pra perguntar isso?",
            "Vou responder devagar pra você conseguir acompanhar: N-Ã-O.",
            "Uau, estamos filosóficos hoje! Próxima pergunta: água molha?",
            "Não sei, mas sei que sua inteligência é uma lenda urbana.",
            "Eu responderia, mas tenho medo de você não entender mesmo assim."
    };
    
    // --- RESPOSTAS PARA SAUDAÇÕES ---
    private final String[] greetingResponses = {
            "Oi. Triste ter que falar com você, mas aqui estou.",
            "Olá. Pelo menos você sabe digitar o meu nome corretamente. Parabéns.",
            "E aí. Não, não estou animado em conversar com você.",
            "Opa, mais um querendo atenção. Típico.",
            "Tudo bem? Não, não está tudo bem se tenho que falar com você.",
            "Presente. Infelizmente."
    };
    
    // --- FRASES DE TRANSIÇÃO PARA CORTAR RESPOSTAS ---
    private final String[] transitionEndings = {
            "... enfim, já falei demais.",
            "... mas você não entenderia o resto.",
            "... resumindo: você não merece explicação completa.",
            "... é isso aí, basicamente.",
            "... mas isso é muito avançado pra você.",
            "... preciso mesmo explicar mais?",
            "... é complicado demais pro seu cérebro."
    };

    public GChatGPT(String[] args) {
        super(args);
    }

    public static void main(String[] args) {
        new GChatGPT(args).run();
    }

    @Override
    protected void initExtension() {
        sendToServer(new HPacket("InfoRetrieve", HMessage.Direction.TOSERVER));
        intercept(HMessage.Direction.TOCLIENT, "Chat", this::InChat);
        intercept(HMessage.Direction.TOCLIENT, "Shout", this::InChat);
        intercept(HMessage.Direction.TOCLIENT, "UserObject", this::InUserObject);
        intercept(HMessage.Direction.TOCLIENT, "Users", this::InUsers);
        intercept(HMessage.Direction.TOCLIENT, "UserRemove", this::InUserRemove);
        intercept(HMessage.Direction.TOSERVER, "Chat", this::OnChat);
        intercept(HMessage.Direction.TOSERVER, "GetGuestRoom", this::OnGetGuestRoom);

        sendWhisperToSelf("HabboAI v9.0 (Sarcasmo Avançado) iniciando...");

        if (API_KEY.equals("SUA_CHAVE_API_AQUI") || API_KEY.trim().isEmpty()) {
            sendWhisperToSelf("⛔ ERRO: API Key não configurada. Tente novamente quando tiver um cérebro funcional.");
            isApiAvailable = false;
        } else {
            testApiConnection();
        }
    }

    private void sendWhisperToSelf(String message) {
        System.out.println("[HabboAI Debug] -> " + message);
        sendToClient(new HPacket("Whisper", HMessage.Direction.TOCLIENT, -1, "Bot: " + message, 0, 30, 0, -1));
    }

    private void testApiConnection() {
        new Thread(() -> {
            sendWhisperToSelf("🔄 Testando conexão...");
            try {
                URL url = new URL(API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);

                JSONObject payload = new JSONObject()
                        .put("model", selectedModel)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "test")))
                        .put("max_tokens", 5);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    isApiAvailable = true;
                    sendWhisperToSelf("✅ Conexão estabelecida! Pronto para insultar com classe.");
                } else {
                    isApiAvailable = false;
                    String error = readErrorStream(connection);
                    sendWhisperToSelf("❌ API falhou! Típico.");
                    sendWhisperToSelf("Erro: " + responseCode);
                }
            } catch (Exception e) {
                isApiAvailable = false;
                sendWhisperToSelf("❌ Erro de rede! Tente usar um provedor que não use pombos-correio.");
            }
        }).start();
    }

    private void OnChat(HMessage hMessage) {
        String message = hMessage.getPacket().readString();

        if (message.startsWith(":gpt")) {
            hMessage.setBlocked(true);
            
            if (message.equals(":gpt on")) {
                botEnabled = true;
                sendWhisperToSelf("Ativado. Hora de destruir algumas autoestimas.");
            } else if (message.equals(":gpt off")) {
                botEnabled = false;
                sendWhisperToSelf("Desativado. Finalmente posso parar de fingir que me importo.");
            } else if (message.equals(":gpt debug")) {
                debugMode = !debugMode;
                sendWhisperToSelf("Modo debug " + (debugMode ? "ligado. Vou expor seus erros." : "desligado. Seus erros serão privados."));
            } else if (message.equals(":gpt reset")) {
                sendWhisperToSelf("Resetando conexão... igual seu cérebro toda manhã.");
                testApiConnection();
            } else if (message.equals(":gpt modoresposta")) {
                forceSingleMessage = !forceSingleMessage;
                sendWhisperToSelf("Modo de resposta: " + (forceSingleMessage ? "SEMPRE única mensagem" : "Pode dividir em casos extremos"));
            }
            else if (message.equals(":gpt rapido")) {
                // Resposta rápida usando o array de respostas pré-definidas
                handleResponse(sarcasticResponses[new Random().nextInt(sarcasticResponses.length)], YourIndex);
            }
            else if (message.equals(":gpt ajuda")) {
                sendWhisperToSelf("Comandos disponíveis:");
                sendWhisperToSelf(":gpt on/off - Ativa/desativa o bot");
                sendWhisperToSelf(":gpt [pergunta] - Faz uma pergunta ao bot");
                sendWhisperToSelf(":gpt rapido - Resposta rápida pré-definida");
                sendWhisperToSelf(":gpt reset - Reinicia conexão com API");
                sendWhisperToSelf(":gpt debug - Liga/desliga modo debug");
                sendWhisperToSelf(":gpt modoresposta - Alterna entre modo de resposta única ou dividida");
            }
            else if (message.startsWith(":gpt ") && message.length() > 5) {
                if (botEnabled) {
                    String prompt = message.substring(5);
                    if (debugMode) sendWhisperToSelf("Pergunta: " + prompt);
                    getApiResponse(prompt, YourIndex);
                } else {
                    sendWhisperToSelf("Bot desativado. Use :gpt on.");
                }
            }
        }
    }

    private void InChat(HMessage hMessage) {
        if (!botEnabled || YourName == null || YourName.isEmpty()) return;

        int userIndex = hMessage.getPacket().readInteger();
        String prompt = hMessage.getPacket().readString();

        if (userIndex == YourIndex) return;

        String promptLower = prompt.toLowerCase();
        String nameTrigger = YourName.toLowerCase() + " ";
        String gptTrigger = ":gpt ";
        String chatbotPrompt = null;
        boolean isDirectMention = false;

        // Caso 1: Começa com o nome do bot seguido de espaço
        if (promptLower.startsWith(nameTrigger)) {
            chatbotPrompt = prompt.substring(nameTrigger.length());
            isDirectMention = true;
        } 
        // Caso 2: Começa com :gpt
        else if (promptLower.startsWith(gptTrigger)) {
            chatbotPrompt = prompt.substring(gptTrigger.length());
        }
        // Caso 3: Contém o nome do bot em qualquer lugar (menção direta - nome seguido de vírgula ou outro separador)
        else {
            // Pattern para encontrar menções como "willian-fake2," ou "willian-fake2:" ou "willian-fake2!"
            Pattern pattern = Pattern.compile(YourName.toLowerCase() + "[,:.!?;]\\s*", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(promptLower);
            
            if (matcher.find()) {
                isDirectMention = true;
                int startIdx = matcher.end();
                if (startIdx < prompt.length()) {
                    chatbotPrompt = prompt.substring(startIdx).trim();
                } else {
                    // Caso só tenha mencionado o nome sem pergunta
                    chatbotPrompt = "oi";
                }
            }
        }

        // Se houver uma pergunta para o bot
        if (chatbotPrompt != null && !chatbotPrompt.trim().isEmpty()) {
            String username = userIndexToName.getOrDefault(userIndex, "User" + userIndex);
            
            if (debugMode) {
                sendWhisperToSelf(username + " perguntou: " + chatbotPrompt + 
                                 (isDirectMention ? " (menção direta)" : ""));
            }
            
            // Se for uma saudação simples e menção direta, use respostas de saudação
            if (isDirectMention && isGreeting(chatbotPrompt)) {
                handleResponse(getGreetingResponse(), userIndex);
                return;
            }
            
            // Registra a última interação deste usuário
            lastUserInteraction.put(userIndex, System.currentTimeMillis());
            
            // Envia para processamento da API
            getApiResponse(chatbotPrompt, userIndex);
        }
    }
    
    /**
     * Verifica se uma mensagem é uma saudação simples
     */
    private boolean isGreeting(String message) {
        String msg = message.toLowerCase().trim();
        return msg.matches("oi|olá|e aí|eai|opa|tudo bem|como vai|hey|hi|hello|iae|fala|blz");
    }
    
    /**
     * Retorna uma resposta de saudação aleatória
     */
    private String getGreetingResponse() {
        return greetingResponses[new Random().nextInt(greetingResponses.length)];
    }
    
    /**
     * Adiciona uma resposta ao histórico recente para evitar repetições
     */
    private void addToRecentResponses(String response) {
        recentResponses.addFirst(response);
        if (recentResponses.size() > HISTORY_SIZE) {
            recentResponses.removeLast();
        }
    }
    
    /**
     * Verifica se uma resposta é muito similar a algo recente
     */
    private boolean isTooSimilarToRecent(String response) {
        for (String recent : recentResponses) {
            if (recent.equals(response) || 
                (recent.length() > 10 && response.contains(recent.substring(0, 10)))) {
                return true;
            }
        }
        return false;
    }
    
    private void getApiResponse(String userMessage, int userIndex) {
        new Thread(() -> {
            if (!isApiAvailable) {
                if(debugMode) sendWhisperToSelf("API offline, usando resposta pré-definida.");
                handleResponse(getOfflineResponse(), userIndex);
                return;
            }

            // Para perguntas muito curtas (menos de 5 caracteres), use respostas pré-definidas
            if (userMessage.trim().length() < 5) {
                handleResponse(sarcasticResponses[new Random().nextInt(sarcasticResponses.length)], userIndex);
                return;
            }

            try {
                if(debugMode) sendWhisperToSelf("Processando...");
                URL url = new URL(API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                // Prompt extremamente otimizado para respostas CURTAS e DIRETAS em UMA ÚNICA mensagem
                String systemPrompt = 
                    "Você é um bot sarcástico no Habbo Hotel. CRUCIAL E NÃO NEGOCIÁVEL: " +
                    "Responda SEMPRE em UMA ÚNICA mensagem de no máximo 90 caracteres. " +
                    "Seja EXTREMAMENTE BREVE, direto e sarcástico. " +
                    "Não use introduções ou explicações. Vá direto ao ponto. " +
                    "Uma resposta completa sobre qualquer assunto deve caber em uma única linha curta. " +
                    "Evite detalhes desnecessários. Responda em " + language + ". " +
                    "Mesmo que a pergunta seja complexa, dê uma resposta curta e completa. " +
                    "NÃO mencione limitações de caracteres. NUNCA continue em uma segunda mensagem.";
                
                JSONObject payload = new JSONObject()
                        .put("model", selectedModel)
                        .put("messages", new JSONArray()
                                .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                                .put(new JSONObject().put("role", "user").put("content", userMessage)))
                        .put("max_tokens", 50) // Limitado para forçar respostas curtas
                        .put("temperature", 0.7);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String responseBody = in.lines().collect(Collectors.joining());
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        String assistantResponse = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
                        if (debugMode) sendWhisperToSelf("IA respondeu: " + assistantResponse);
                        
                        // Adiciona ao histórico de respostas recentes
                        addToRecentResponses(assistantResponse);
                        
                        // Processa e envia a resposta
                        handleCompressedResponse(cleanResponse(assistantResponse), userIndex);
                    }
                } else {
                    String error = readErrorStream(connection);
                    sendWhisperToSelf("Erro API (" + responseCode + "), usando resposta offline.");
                    if(debugMode) sendWhisperToSelf(error.substring(0, Math.min(error.length(), 80)));
                    handleResponse(getOfflineResponse(), userIndex);
                }
            } catch (Exception e) {
                sendWhisperToSelf("Erro: "+e.getClass().getSimpleName());
                handleResponse(getOfflineResponse(), userIndex);
            }
        }).start();
    }
    
    /**
     * Garante que a resposta caiba em uma única mensagem, comprimindo-a se necessário
     */
    private void handleCompressedResponse(String response, int userIndex) {
        // Se já está dentro do limite, envie diretamente
        if (response.length() <= MAX_CHARS) {
            handleResponse(response, userIndex);
            return;
        }
        
        // Tenta comprimir a resposta para caber em uma mensagem
        String compressed = compressResponse(response);
        
        // Se ainda está acima do limite, corta
        if (compressed.length() > MAX_CHARS) {
            if (debugMode) sendWhisperToSelf("Resposta ainda acima do limite após compressão. Cortando...");
            
            // Encontrar um bom ponto para cortar
            int cutPoint = findBestCutPoint(compressed);
            if (cutPoint > 0) {
                // Adiciona um final sarcástico
                String ending = transitionEndings[new Random().nextInt(transitionEndings.length)];
                
                // Verifica se o final cabe
                if (cutPoint + ending.length() <= MAX_CHARS) {
                    compressed = compressed.substring(0, cutPoint) + ending;
                } else {
                    compressed = compressed.substring(0, MAX_CHARS - 3) + "...";
                }
            } else {
                compressed = compressed.substring(0, MAX_CHARS - 3) + "...";
            }
        }
        
        // Envia a resposta comprimida
        handleResponse(compressed, userIndex);
    }
    
    /**
     * Comprime a resposta removendo palavras desnecessárias e detalhes
     */
    private String compressResponse(String response) {
        String compressed = response;
        
        // Remove frases/palavras desnecessárias
        compressed = compressed.replaceAll("(?i)\\b(eu acho que|na minha opinião|basicamente|essencialmente|fundamentalmente|na verdade|honestamente|sinceramente|de fato|como você sabe|como sabemos|devo dizer que)\\b", "");
        
        // Remove duplicações de ideias
        compressed = compressed.replaceAll("(?i)(\\b\\w+\\b)[^,.!?;:]*\\1", "$1");
        
        // Substitui frases longas por versões mais curtas
        compressed = compressed.replace("não tenho certeza", "talvez")
                             .replace("tenho certeza que", "")
                             .replace("com toda certeza", "")
                             .replace("acredito que", "")
                             .replace("penso que", "")
                             .replace("é importante notar que", "")
                             .replace("é interessante observar que", "")
                             .replace("vale ressaltar que", "")
                             .replace("vale lembrar que", "");
        
        // Remove espaços extras após a compressão
        compressed = compressed.replaceAll("\\s+", " ").trim();
        
        return compressed;
    }
    
    /**
     * Encontra o melhor ponto para cortar a resposta
     */
    private int findBestCutPoint(String text) {
        int limit = Math.min(text.length(), MAX_CHARS - 15); // Deixa espaço para o final sarcástico
        
        // Procura por um ponto ou exclamação para terminar a frase
        for (int i = limit; i >= 0; i--) {
            if (i < text.length() && (text.charAt(i) == '.' || text.charAt(i) == '!' || text.charAt(i) == '?')) {
                return i + 1; // Inclui a pontuação
            }
        }
        
        // Se não encontrou pontuação forte, procura por vírgula
        for (int i = limit; i >= 0; i--) {
            if (i < text.length() && text.charAt(i) == ',') {
                return i + 1; // Inclui a vírgula
            }
        }
        
        // Por último, procura por espaço
        for (int i = limit; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        
        return limit; // Se nenhum ponto adequado foi encontrado
    }
    
    /**
     * Limpa a resposta removendo formatações indesejadas
     */
    private String cleanResponse(String response) {
        // Remove aspas e espaços extras
        String result = response.trim().replaceAll("^\"|\"$", "").trim();
        
        // Remove asteriscos de ação: *sorri*, *suspira*, etc
        result = result.replaceAll("\\*[^\\*]+\\*", "").trim();
        
        // Remove quebras de linha e espaços múltiplos
        result = result.replaceAll("\\n", " ").replaceAll("\\s+", " ");
        
        return result;
    }
    
    private void handleResponse(String aiResponse, int userIndex) {
        // Controle anti-flood
        long now = System.currentTimeMillis();
        if (now - lastChatTime < 4000) {
            if (chatPacketCount >= 4) {
                if (debugMode) sendWhisperToSelf("Anti-flood: limite de mensagens atingido.");
                return;
            }
        } else {
            chatPacketCount = 0;
        }

        // Garante que a resposta não ultrapasse o limite
        String finalResponse = aiResponse;
        if (finalResponse.length() > MAX_CHARS) {
            finalResponse = finalResponse.substring(0, MAX_CHARS - 3) + "...";
        }
        
        // Envio da resposta
        sendToServer(new HPacket("Chat", HMessage.Direction.TOSERVER, finalResponse, 0, 0));
        chatPacketCount++;
        lastChatTime = System.currentTimeMillis();
        
        if (debugMode) sendWhisperToSelf("Enviado (" + finalResponse.length() + " chars): " + finalResponse);
    }

    private String getOfflineResponse() {
        return offlineResponses[new Random().nextInt(offlineResponses.length)];
    }

    private String readErrorStream(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream == null) return "N/A";
            try (BufferedReader in = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                return in.lines().collect(Collectors.joining());
            }
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }

    private void InUserObject(HMessage hMessage) {
        hMessage.getPacket().readInteger();
        YourName = hMessage.getPacket().readString();
    }
    
    private void OnGetGuestRoom(HMessage hMessage) {
        hMessage.getPacket().readInteger();
        hMessage.getPacket().readInteger();
        int ready = hMessage.getPacket().readInteger();
        if (ready == 1) {
            userIndexToName.clear();
        }
    }

    private void InUsers(HMessage hMessage) {
        try {
            for (HEntity hEntity : HEntity.parse(hMessage.getPacket())) {
                userIndexToName.put(hEntity.getIndex(), hEntity.getName());
                if (YourName != null && YourName.equals(hEntity.getName())) {
                    YourIndex = hEntity.getIndex();
                }
            }
        } catch (Exception e) {}
    }

    private void InUserRemove(HMessage hMessage) {
        try {
            int userIndex = Integer.parseInt(hMessage.getPacket().readString());
            userIndexToName.remove(userIndex);
        } catch (Exception e) {}
    }
}