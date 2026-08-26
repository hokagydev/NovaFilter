package ru.nightleaks.guard;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "velocityguard",
        name = "VelocityGuard",
        version = "1.0-SNAPSHOT",
        description = "Custom Captcha Web Verification Filter for Velocity 4.x",
        authors = {"NightLeaks"}
)
public class VelocityGuard {

    private final ProxyServer server;
    private final Logger logger;
    
    // Сессия проверенных игроков в памяти
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();
    
    // Настройки URL твоего сайта
    private final String webAuthUrl = "https://captcha.novamine.fun/verify?nick=";
    private final String apiCheckUrl = "https://captcha.novamine.fun/api/check?nick=";
    private final String targetServerName = "limbo"; // Название целевого сервера в velocity.toml

    @Inject
    public VelocityGuard(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("VelocityGuard (v4.x / Java 21) успешно запущен!");
        
        // Фоновый таск проверки статуса верификации
        server.getScheduler().buildTask(this, this::checkPendingPlayers)
                .repeat(3, TimeUnit.SECONDS)
                .schedule();
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            server.getScheduler().buildTask(this, () -> sendVerificationMessage(player))
                    .delay(1, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Очищать ли статус при выходе:
        // verifiedPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void sendVerificationMessage(Player player) {
        String personalUrl = webAuthUrl + player.getUsername();
        
        Component message = Component.text("\n")
                .append(Component.text(" [!] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text("Для входа на сервер 1.21.4 необходимо пройти проверку!\n", NamedTextColor.YELLOW))
                .append(Component.text(" [Кликни сюда, чтобы пройти капчу на сайте]", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(personalUrl)))
                .append(Component.text("\n"));

        player.sendMessage(message);
    }

    private void checkPendingPlayers() {
        for (Player player : server.getAllPlayers()) {
            if (verifiedPlayers.contains(player.getUniqueId())) {
                continue;
            }

            // Асинхронный запрос к сайту
            server.getScheduler().buildTask(this, () -> {
                if (isVerifiedOnWeb(player.getUsername())) {
                    verifiedPlayers.add(player.getUniqueId());
                    
                    player.sendMessage(Component.text("[!] Капча успешно пройдена! Подключаем к серверу...", NamedTextColor.GREEN));
                    
                    server.getServer(targetServerName).ifPresent(target -> {
                        player.createConnectionRequest(target).fireAndForget();
                    });
                } else {
                    sendVerificationMessage(player);
                }
            }).schedule();
        }
    }

    private boolean isVerifiedOnWeb(String username) {
        try {
            URL url = URI.create(apiCheckUrl + username).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String response = reader.readLine();
                    return response != null && response.contains("true");
                }
            }
        } catch (Exception e) {
            // Ошибка подключения к бэкенду сайта
        }
        return false;
    }
}
