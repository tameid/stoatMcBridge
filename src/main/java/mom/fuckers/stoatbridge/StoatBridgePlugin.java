package mom.fuckers.stoatbridge;

import mom.fuckers.stoatbridge.api.StoatRESTClient;
import mom.fuckers.stoatbridge.api.StoatWebSocketClient;
import mom.fuckers.stoatbridge.api.StoatDatabase;
import mom.fuckers.stoatbridge.listeners.MinecraftChatListener;
import mom.fuckers.stoatbridge.listeners.PlayerJoinQuitListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.net.http.HttpClient;
import com.google.gson.Gson;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StoatBridgePlugin extends JavaPlugin implements StoatWebSocketClient.MessageHandler {

    private static final Pattern MENTION_PATTERN = Pattern.compile("<@([A-Z0-9]+)>");

    private StoatRESTClient restClient;
    private StoatWebSocketClient webSocketClient;
    private String stoatToMinecraftFormat;
    private HttpClient httpClient;
    private Gson gson;
    private boolean logToConsole;
    private StoatDatabase db;

    private LoadingCache<String, String> userToIdCache;
    private Map<String, String> userToLastMessageIdCache;
    private LoadingCache<String, String> idToUserCache;


    public String getUserId(String username) {
        return userToIdCache.get(username.toLowerCase());
    }

    public String getLastMessageId(String username) {
        return userToLastMessageIdCache.get(username.toLowerCase());
    }

    // Removed loadData and saveData (using SQLite now)

    private void broadcastToMinecraft(Component component) {
        // Broadcast the interactive component to players with permission
        getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("stoatbridge.view"))
                .forEach(p -> p.sendMessage(component));
        
        // Respect the config setting for console logging
        if (logToConsole) {
            getServer().getConsoleSender().sendMessage(component);
        }
    }



    @Override
    public void onEnable() {
        saveDefaultConfig();

        String botToken = getConfig().getString("stoat.bot_token", "");
        String nodeUrl = getConfig().getString("stoat.node_url", "https://api.revolt.chat");
        String nodeWsUrl = getConfig().getString("stoat.node_ws", "wss://ws.revolt.chat");
        String channelId = getConfig().getString("stoat.channel_id", "");
        logToConsole = getConfig().getBoolean("stoat.log_to_console", false);
        stoatToMinecraftFormat = getConfig().getString("formatting.stoat_to_minecraft", "&8[&dStoat&8] &b%author%&f: %message%");

        if (botToken.isEmpty() || channelId.isEmpty()) {
            getLogger().warning("Bot token or Channel ID is missing in config.yml! Plugin will not function fully.");
        }

        // Shared resources
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        restClient = new StoatRESTClient(httpClient, botToken, nodeUrl, channelId, getLogger(), gson);
        webSocketClient = new StoatWebSocketClient(httpClient, botToken, nodeWsUrl, channelId, getLogger(), this, gson);

        getServer().getPluginManager().registerEvents(new MinecraftChatListener(restClient, this), this);
        String joinFormat = getConfig().getString("formatting.player_join", "**%player% joined the game**");
        String quitFormat = getConfig().getString("formatting.player_quit", "**%player% left the game**");
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(restClient, joinFormat, quitFormat), this);

        db = new StoatDatabase(getDataFolder(), getLogger());
        db.initialize();

        userToIdCache = Caffeine.newBuilder()
                .maximumSize(20)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build(key -> db != null ? db.getUserId(key) : null);

        userToLastMessageIdCache = Caffeine.newBuilder()
                .maximumSize(20)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .<String, String>build().asMap();

        idToUserCache = Caffeine.newBuilder()
                .maximumSize(20)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build(key -> db != null ? db.getUsername(key) : null);

        webSocketClient.connect();

        getLogger().info("StoatBridge enabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("localchat")) {
            if (args.length == 0) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /localchat <message>"));
                return true;
            }
            String message = String.join(" ", args);
            String name = sender.getName();
            
            // Broadcast locally only
            Component localMsg = LegacyComponentSerializer.legacyAmpersand().deserialize("&8[&7Local&8] &f" + name + ": " + message);
            getServer().broadcast(localMsg);
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        if (db != null) {
            db.close();
        }
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
        getLogger().info("StoatBridge disabled.");
    }

    @Override
    public void onMessageReceived(String messageId, String replyToMessageId, String authorName, String authorId, String content) {
        // Cache the user's ID and last message ID for replies/mentions
        if (authorName != null && !authorName.isEmpty()) {
            String lowerName = authorName.toLowerCase();
            if (authorId != null) {
                userToIdCache.put(lowerName, authorId);
                idToUserCache.put(authorId, authorName);
                if (db != null) db.updateMapping(authorName, authorId);
            }
            if (messageId != null) {
                userToLastMessageIdCache.put(lowerName, messageId);
            }
        }

        // Resolve mentions in content: Replace <@ID> with @Name
        String tempResolved = content;
        if (content.contains("<@")) {
            Matcher matcher = MENTION_PATTERN.matcher(content);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String id = matcher.group(1);
                String name = idToUserCache.get(id);
                matcher.appendReplacement(sb, name != null ? "@" + name : matcher.group(0));
            }
            matcher.appendTail(sb);
            tempResolved = sb.toString();
        }
        final String resolvedContent = tempResolved;

        // Prepare the author component with click/hover events
        Component authorComponent = LegacyComponentSerializer.legacyAmpersand().deserialize("&b" + authorName)
                .clickEvent(ClickEvent.suggestCommand("@" + authorName + " "))
                .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacyAmpersand().deserialize("&eClick to quick reply to &b@" + authorName)));

        if (replyToMessageId != null) {
            restClient.fetchMessage(replyToMessageId).thenAccept(original -> {
                Component finalComp;
                if (original != null) {
                    Component line1 = LegacyComponentSerializer.legacyAmpersand().deserialize("&8[&dStoat&8] &7*replying to* &b" + original.author + "&7: &f" + original.content);
                    Component line2 = Component.text("        ")
                            .append(LegacyComponentSerializer.legacyAmpersand().deserialize("&8↳ "))
                            .append(authorComponent)
                            .append(LegacyComponentSerializer.legacyAmpersand().deserialize("&f: " + resolvedContent));
                    
                    finalComp = Component.text()
                            .append(line1)
                            .append(Component.newline())
                            .append(line2)
                            .build();
                } else {
                    finalComp = LegacyComponentSerializer.legacyAmpersand().deserialize("&8[&dStoat&8] &b")
                            .append(authorComponent)
                            .append(LegacyComponentSerializer.legacyAmpersand().deserialize(" &7(replying to unknown)&f: " + resolvedContent));
                }
                broadcastToMinecraft(finalComp);
            });
        } else {
            // Normal message formatting
            String[] parts = stoatToMinecraftFormat.split("%author%", 2);
            Component finalComp;
            if (parts.length == 2) {
                Component prefix = LegacyComponentSerializer.legacyAmpersand().deserialize(parts[0]);
                Component suffix = LegacyComponentSerializer.legacyAmpersand().deserialize(parts[1].replace("%message%", resolvedContent));
                finalComp = prefix.append(authorComponent).append(suffix);
            } else {
                String formatted = stoatToMinecraftFormat
                        .replace("%author%", authorName)
                        .replace("%message%", resolvedContent);
                finalComp = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);
            }
            broadcastToMinecraft(finalComp);
        }
    }
}
