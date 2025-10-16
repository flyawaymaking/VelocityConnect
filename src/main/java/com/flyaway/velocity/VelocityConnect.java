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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

        // Обработка в отдельном потоке для избежания блокировок
        server.getScheduler().buildTask(this, () -> processPluginMessage(player, event.getData()))
                .delay(50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void processPluginMessage(Player player, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String subChannel = in.readUTF();
            String serverName = in.readUTF();

            if ("Connect".equals(subChannel)) {
                Optional<RegisteredServer> targetServer = server.getServer(serverName);

                if (targetServer.isPresent() && player.isActive()) {
                    RegisteredServer target = targetServer.get();
                    logger.info("Подключаем игрока {} к серверу {}", player.getUsername(), serverName);

                    // Проверка текущего сервера игрока
                    Optional<ServerConnection> currentServer = player.getCurrentServer();
                    if (currentServer.isPresent() && currentServer.get().getServerInfo().getName().equals(serverName)) {
                        player.sendMessage(Component.text("Вы уже находитесь на этом сервере!", NamedTextColor.YELLOW));
                        return;
                    }

                    // Подключение с обработкой результата
                    player.createConnectionRequest(target).connect().whenComplete((result, throwable) -> {
                        if (throwable != null || !result.isSuccessful()) {
                            String errorMessage = throwable != null ?
                                throwable.getMessage() :
                                "Неизвестная ошибка подключения";

                            logger.warn("Не удалось подключить игрока {} к серверу {}: {}",
                                    player.getUsername(), serverName, errorMessage);

                            if (player.isActive()) {
                                player.sendMessage(Component.text("Ошибка подключения к серверу: " + errorMessage, NamedTextColor.RED));
                            }
                        } else {
                            logger.info("Игрок {} успешно подключен к серверу {}", player.getUsername(), serverName);
                        }
                    });

                } else {
                    logger.warn("Сервер {} не найден или игрок {} не активен", serverName, player.getUsername());
                    if (player.isActive()) {
                        player.sendMessage(Component.text("Сервер " + serverName + " не найден или недоступен!", NamedTextColor.RED));
                    }
                }
            } else {
                logger.warn("Неизвестный подканал: {}", subChannel);
            }
        } catch (IOException e) {
            logger.warn("Ошибка при обработке PluginMessage от игрока {}: {}",
                    player.getUsername(), e.getMessage());
        }
    }
}
