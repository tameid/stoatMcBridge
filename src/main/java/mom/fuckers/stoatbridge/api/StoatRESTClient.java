package mom.fuckers.stoatbridge.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import com.google.gson.Gson;

public class StoatRESTClient {
    private final HttpClient httpClient;
    private final String botToken;
    private final String nodeUrl;
    private final String channelId;
    private final Logger logger;
    private final Gson gson;
    private long lastErrorLogTime = 0;
    private static final long ERROR_LOG_COOLDOWN_MS = 60000;

    public StoatRESTClient(HttpClient httpClient, String botToken, String nodeUrl, String channelId, Logger logger, Gson gson) {
        this.httpClient = httpClient;
        this.botToken = botToken;
        this.nodeUrl = nodeUrl.replaceAll("/$", ""); // trim trailing slash
        this.channelId = channelId;
        this.logger = logger;
        this.gson = gson;
    }

    public static class MessageResult {
        public final String author;
        public final String content;

        public MessageResult(String author, String content) {
            this.author = author;
            this.content = content;
        }
    }

    public CompletableFuture<String> sendMessage(String content, String replyMessageId, String masqueradePlayerName) {
        if (botToken == null || botToken.isEmpty() || channelId == null || channelId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);
        
        if (masqueradePlayerName != null && !masqueradePlayerName.isEmpty()) {
            JsonObject masquerade = new JsonObject();
            masquerade.addProperty("name", masqueradePlayerName);
            masquerade.addProperty("avatar", "https://mc-heads.net/avatar/" + masqueradePlayerName + "/128");
            payload.add("masquerade", masquerade);
        }
        
        if (replyMessageId != null) {
            JsonArray replies = new JsonArray();
            JsonObject replyIntent = new JsonObject();
            replyIntent.addProperty("id", replyMessageId);
            replyIntent.addProperty("mention", false);
            replies.add(replyIntent);
            payload.add("replies", replies);
        }
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodeUrl + "/channels/" + channelId + "/messages"))
                .header("x-bot-token", botToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        if (System.currentTimeMillis() - lastErrorLogTime > ERROR_LOG_COOLDOWN_MS) {
                            logger.warning("Failed to send message to Stoat. Status: " + response.statusCode() + " Body: " + response.body());
                            lastErrorLogTime = System.currentTimeMillis();
                        }
                        return null;
                    }
                    try {
                        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
                        if (json.has("_id")) {
                            String messageId = json.get("_id").getAsString();
                            return messageId;
                        }
                    } catch (Exception e) {
                        if (System.currentTimeMillis() - lastErrorLogTime > ERROR_LOG_COOLDOWN_MS) {
                            logger.warning("Error parsing Stoat send response: " + e.getMessage());
                            lastErrorLogTime = System.currentTimeMillis();
                        }
                    }
                    return null;
                })
                .exceptionally(throwable -> {
                    if (System.currentTimeMillis() - lastErrorLogTime > ERROR_LOG_COOLDOWN_MS) {
                        logger.severe("Exception sending message to Stoat: " + throwable.getMessage());
                        lastErrorLogTime = System.currentTimeMillis();
                    }
                    return null;
                });
    }

    public CompletableFuture<MessageResult> fetchMessage(String messageId) {
        if (botToken == null || botToken.isEmpty() || channelId == null || channelId.isEmpty() || messageId == null) {
            return CompletableFuture.completedFuture(null);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodeUrl + "/channels/" + channelId + "/messages/" + messageId))
                .header("x-bot-token", botToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        return null;
                    }
                    try {
                        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
                        String content = json.has("content") ? json.get("content").getAsString() : "";
                        String author = "Unknown";
                        
                        if (json.has("user") && json.get("user").isJsonObject()) {
                            JsonObject userObj = json.getAsJsonObject("user");
                            if (userObj.has("username")) {
                                author = userObj.get("username").getAsString();
                            }
                        } else if (json.has("author")) {
                            author = json.get("author").getAsString();
                        }
                        
                        return new MessageResult(author, content);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .exceptionally(throwable -> null);
    }
}
