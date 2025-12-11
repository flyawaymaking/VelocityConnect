package com.flyaway.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class VelocityConnect {
    private final ProxyServer server;
    private final Logger logger;
    private final MinecraftChannelIdentifier channel;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Inject
    public VelocityConnect(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        this.channel = MinecraftChannelIdentifier.create("velocity", "player");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(channel);
        logger.info("VelocityConnect плагин включен и прослушивает канал: velocity:player");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection serverConnection)) {
            return;
        }

        Player player = serverConnection.getPlayer();

        server.getScheduler().buildTask(this, () -> processPluginMessage(player, event.getData()))
                .delay(1, TimeUnit.SECONDS)
                .schedule();
    }

    private void processPluginMessage(Player player, byte[] data) {
        String username = player.getUsername();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String subChannel = in.readUTF();
            String serverName = in.readUTF();

            if (!"Connect".equals(subChannel)) {
                logger.warn("Неизвестный подканал: {}", subChannel);
                return;
            }

            Optional<RegisteredServer> optionalServer = server.getServer(serverName);
            if (optionalServer.isEmpty()) {
                if (player.isActive()) {
                    player.sendMessage(miniMessage.deserialize("<red>❌ Сервер <yellow>" + serverName + "</yellow> не найден!"));
                }
                logger.warn("Сервер {} не найден (игрок: {})", serverName, username);
                return;
            }

            RegisteredServer target = optionalServer.get();

            if (player.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName().equalsIgnoreCase(serverName))
                    .orElse(false)) {
                player.sendMessage(miniMessage.deserialize("<yellow>⚠ Вы уже находитесь на этом сервере!"));
                return;
            }

            logger.info("Подключаем игрока {} к серверу {}", username, serverName);

            player.createConnectionRequest(target)
                    .connect()
                    .thenAccept(result -> {
                        if (!result.isSuccessful()) {
                            String attempted = result.getAttemptedConnection()
                                    .getServerInfo()
                                    .getName();
                            logger.warn("Не удалось подключить игрока {} к серверу {}", username, attempted);

                            if (player.isActive()) {
                                player.sendMessage(miniMessage.deserialize(
                                        "<red>❌ Ошибка подключения к серверу " + attempted
                                ));
                            }
                            return;
                        }

                        logger.info("✅ Игрок {} успешно подключён к серверу {}", username, target.getServerInfo().getName());
                        if (player.isActive()) {
                            player.sendMessage(miniMessage.deserialize(
                                    "<green>✅ Подключено к серверу <yellow>" + target.getServerInfo().getName()
                            ));
                        }
                    });

        } catch (IOException e) {
            logger.warn("Ошибка при обработке PluginMessage от игрока {}: {}", username, e.getMessage());
        }
    }
}
