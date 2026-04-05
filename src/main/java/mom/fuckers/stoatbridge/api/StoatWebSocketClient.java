package mom.fuckers.stoatbridge.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class StoatWebSocketClient implements WebSocket.Listener {
    private final String botToken;
    private final String websocketUrl;
    private final String channelId;
    private final Logger logger;
    private final MessageHandler messageHandler;
    private WebSocket webSocket;
    private final Gson gson;
    private final HttpClient httpClient;
    private final StringBuilder messageBuffer = new StringBuilder();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean intentionallyClosed = false;
    private boolean isReconnecting = false;
    private int reconnectAttempts = 0;

    public interface MessageHandler {
        void onMessageReceived(String messageId, String replyToMessageId, String authorName, String authorId, String content);
    }

    public StoatWebSocketClient(HttpClient httpClient, String botToken, String websocketUrl, String channelId, Logger logger, MessageHandler messageHandler, Gson gson) {
        this.httpClient = httpClient;
        this.botToken = botToken;
        this.websocketUrl = websocketUrl;
        this.channelId = channelId;
        this.logger = logger;
        this.messageHandler = messageHandler;
        this.gson = gson;
    }

    public void connect() {
        if (botToken == null || botToken.isEmpty()) {
            logger.warning("Bot token not defined, Stoat WebSocket will not connect.");
            return;
        }

        String wsUrl = websocketUrl.endsWith("/") ? websocketUrl.substring(0, websocketUrl.length() - 1) : websocketUrl;
        wsUrl += "?format=json";

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), this)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    if (reconnectAttempts > 0) {
                        logger.info("Reconnected to Stoat WebSocket after " + reconnectAttempts + " attempts.");
                    } else {
                        logger.info("Connected to Stoat WebSocket");
                    }
                    this.reconnectAttempts = 0;
                    
                    // Send Authentication
                    JsonObject authPayload = new JsonObject();
                    authPayload.addProperty("type", "Authenticate");
                    authPayload.addProperty("token", botToken);
                    ws.sendText(authPayload.toString(), true);
                })
                .exceptionally(throwable -> {
                    if (reconnectAttempts == 0) {
                        logger.severe("Failed to connect to Stoat WebSocket: " + throwable.getMessage());
                    } else if (reconnectAttempts % 10 == 0) {
                        logger.severe("Still failing to connect to Stoat WebSocket after " + reconnectAttempts + " attempts: " + throwable.getMessage());
                    }
                    scheduleReconnect();
                    return null;
                });
    }

    public void disconnect() {
        intentionallyClosed = true;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabling");
        }
        messageBuffer.setLength(0); // clear potential partial messages
        scheduler.shutdownNow();
    }
    
    private synchronized void scheduleReconnect() {
        if (intentionallyClosed || isReconnecting) return;
        isReconnecting = true;
        
        // Exponential backoff: 5, 10, 20, 40, 80, 160, max 300 seconds
        long delay = (long) Math.min(5 * Math.pow(2, Math.min(reconnectAttempts, 6)), 300);
        
        if (reconnectAttempts == 0) {
            logger.info("Will attempt to reconnect in " + delay + " seconds...");
        } else if (reconnectAttempts % 10 == 0) {
            logger.info("Connection still failing, will try again in " + delay + " seconds (Attempt " + reconnectAttempts + ")");
        }

        reconnectAttempts++;

        scheduler.schedule(() -> {
            isReconnecting = false;
            if (reconnectAttempts <= 1 || reconnectAttempts % 10 == 0) {
                logger.info("Attempting to reconnect to Stoat WebSocket...");
            }
            connect();
        }, delay, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String payload = messageBuffer.toString();
            messageBuffer.setLength(0); // clear buffer
            handlePayload(payload);
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    private void handlePayload(String payload) {
        try {
            JsonObject json = gson.fromJson(payload, JsonObject.class);
            if (!json.has("type")) return;

            String type = json.get("type").getAsString();

            if ("Authenticated".equals(type)) {
                // No action needed for successful authentication, but the event is still processed.
            } else if ("Message".equals(type)) {
                if (json.has("channel") && channelId.equals(json.get("channel").getAsString())) {
                    String authorId = json.has("author") ? json.get("author").getAsString() : "Unknown";
                    String username = authorId;
                    String messageId = json.has("_id") ? json.get("_id").getAsString() : null;
                    
                    if (json.has("user")) {
                        JsonObject userObj = json.getAsJsonObject("user");
                        
                        // Ignore messages from bots (including ourselves) to prevent an echo loop!
                        if (userObj.has("bot")) {
                            return;
                        }
                        
                        if (userObj.has("username")) {
                            username = userObj.get("username").getAsString();
                        }
                    }

                    String content = "";
                    if (json.has("content") && json.get("content").isJsonPrimitive()) {
                        content = json.get("content").getAsString();
                    }
                    
                    String replyToMessageId = null;
                    if (json.has("replies") && json.get("replies").isJsonArray()) {
                        JsonArray replies = json.getAsJsonArray("replies");
                        if (replies.size() > 0) {
                            replyToMessageId = replies.get(0).getAsString();
                        }
                    }

                    if (!content.isEmpty()) {
                        messageHandler.onMessageReceived(messageId, replyToMessageId, username, authorId, content);
                    }
                }
            }
        } catch (Exception e) {
            // Silence irrelevant parsing errors
        }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (reconnectAttempts == 0) {
            logger.severe("Stoat WebSocket error: " + error.getMessage());
        }
        scheduleReconnect();
        WebSocket.Listener.super.onError(webSocket, error);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!intentionallyClosed) {
            if (reconnectAttempts == 0) {
                logger.warning("Stoat WebSocket closed unexpectedly (" + statusCode + ": " + reason + ")");
            }
            scheduleReconnect();
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }
}
