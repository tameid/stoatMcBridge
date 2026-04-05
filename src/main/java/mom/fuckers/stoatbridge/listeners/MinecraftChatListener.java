package mom.fuckers.stoatbridge.listeners;

import mom.fuckers.stoatbridge.StoatBridgePlugin;
import mom.fuckers.stoatbridge.api.StoatRESTClient;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class MinecraftChatListener implements Listener {
    private final StoatRESTClient restClient;
    private final StoatBridgePlugin plugin;

    public MinecraftChatListener(StoatRESTClient restClient, StoatBridgePlugin plugin) {
        this.restClient = restClient;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        String playerName = event.getPlayer().getName();
        String messageStr = PlainTextComponentSerializer.plainText().serialize(event.message());
        String replyMessageId = null;

        String content = messageStr;
        Matcher matcher = Pattern.compile("@([a-zA-Z0-9_\\-]+)").matcher(messageStr);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String potentialName = matcher.group(1);
            String userId = plugin.getUserId(potentialName);
            if (userId != null) {
                matcher.appendReplacement(sb, "<@" + userId + ">");
                if (replyMessageId == null) {
                    replyMessageId = plugin.getLastMessageId(potentialName);
                }
            } else {
                matcher.appendReplacement(sb, "@" + potentialName);
            }
        }
        matcher.appendTail(sb);
        content = sb.toString();

        // Async dispatch with Masquerade and register ID for reply support, if permitted
        if (event.getPlayer().hasPermission("stoatbridge.send")) {
            restClient.sendMessage(content, replyMessageId, playerName);
        }
    }
}
