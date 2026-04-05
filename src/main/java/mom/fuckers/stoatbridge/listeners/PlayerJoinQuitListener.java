package mom.fuckers.stoatbridge.listeners;

import mom.fuckers.stoatbridge.api.StoatRESTClient;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinQuitListener implements Listener {
    private final StoatRESTClient restClient;
    private final String joinFormat;
    private final String quitFormat;

    public PlayerJoinQuitListener(StoatRESTClient restClient, String joinFormat, String quitFormat) {
        this.restClient = restClient;
        this.joinFormat = joinFormat;
        this.quitFormat = quitFormat;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPermission("stoatbridge.send")) return;
        String playerName = event.getPlayer().getName();
        String formattedMessage = joinFormat.replace("%player%", playerName);
        restClient.sendMessage(formattedMessage, null, null);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!event.getPlayer().hasPermission("stoatbridge.send")) return;
        String playerName = event.getPlayer().getName();
        String formattedMessage = quitFormat.replace("%player%", playerName);
        restClient.sendMessage(formattedMessage, null, null);
    }
}
