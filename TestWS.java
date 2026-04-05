import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class TestWS {
    public static void main(String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://ws.revolt.chat?format=json"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    System.out.println("Opened");
                    String auth = "{\"type\":\"Authenticate\",\"token\":\"ivyaPfwWUjlT5SUd74IrfjgC43QQ76t7bDSAtOWoifWhmPh-OxYAd3XNqdIvJOkv\"}";
                    webSocket.sendText(auth, true);
                    WebSocket.Listener.super.onOpen(webSocket);
                }
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    System.out.println("RECV: " + data);
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }
                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    error.printStackTrace();
                }
                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    System.out.println("Closed");
                    latch.countDown();
                    return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                }
            }).join();
        
        System.out.println("Connected. Waiting 5s for ping/auth responses...");
        Thread.sleep(5000);
        System.exit(0);
    }
}
