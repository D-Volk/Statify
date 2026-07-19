package ru.dvolk.statify.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.dvolk.statify.paper.tracker.PlaytimeTracker;

public final class PlayerSessionListener implements Listener {

    private final PlaytimeTracker tracker;

    public PlayerSessionListener(PlaytimeTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        tracker.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.handleQuit(event.getPlayer());
    }
}
