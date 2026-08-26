package ru.nightleaks.guard;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
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
import java.util.Optional;
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

    // Сессия верифицированных игроков
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();
    // Список игроков, которым отправлено сообщение
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();

    // Прямые URL для 100% работы на Apache без рерайтов
    private final String webAuthUrl = "https://captcha.novamine.fun/index.php?nick=";
    private final String apiCheckUrl = "https://captcha.novamine.fun/index.php?action=check&nick=";

    // Имена серверов из velocity.toml
    private final String limboServerName = "limbo";     // Сервер сбора/карантина
    private final String mainServerName = "main";       // Основной игровой сервер после капчи

    @Inject
    public VelocityGuard(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("==================================================");
        logger.info(" VelocityGuard успешно запущен (Java 21 / v4.x)!");
        logger.info(" Целевой домен капчи: https://captcha.novamine.fun");
        logger.info("==================================================");

        // Проверка связи с веб-сервером
        server.getScheduler().buildTask(this, this::testWebsiteConnection).schedule();

        // Циклическая проверка прохождения капчи каждые 2 секунды
        server.getScheduler().buildTask(this, this::checkPendingPlayers)
                .repeat(2, TimeUnit.SECONDS)
                .schedule();
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();

        // Если игрок вошел в Limbo и еще не прошел капчу
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            server.getScheduler().buildTask(this, () -> {
                sendVerificationMessage(player);
                notifiedPlayers.add(player.getUniqueId());
            }).delay(1, TimeUnit.SECONDS).schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        notifiedPlayers.remove(uuid);
        // Если требуется повторная капча при каждом перезаходе:
        // verifiedPlayers.remove(uuid);
    }

    private void testWebsiteConnection() {
        try {
            logger.info("[VelocityGuard] Проверка соединения с captcha.novamine.fun...");
            URL url = URI.create("https://captcha.novamine.fun/index.php").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 400) {
                logger.info("[VelocityGuard] Успешное подключение к веб-серверу! Код ответа: " + responseCode);
            } else {
                logger.warn("[VelocityGuard] Сервер ответил с кодом: " + responseCode);
            }
        } catch (Exception e) {
            logger.error("[VelocityGuard] Ошибка подключения к captcha.novamine.fun: " + e.getMessage());
        }
    }

    private void sendVerificationMessage(Player player) {
        String personalUrl = webAuthUrl + player.getUsername();

        Component message = Component.text("\n")
                .append(Component.text(" [!] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text("Для входа на сервер необходимо пройти капчу на сайте!\n", NamedTextColor.YELLOW))
                .append(Component.text(" [НАЖМИ СЮДА, ЧТОБЫ ПРОЙТИ КАПЧУ]", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(personalUrl)))
                .append(Component.text("\n"));

        player.sendMessage(message);
    }

    private void checkPendingPlayers() {
        for (Player player : server.getAllPlayers()) {
            if (verifiedPlayers.contains(player.getUniqueId())) {
                continue;
            }

            // Асинхронный опрос API сайта
            server.getScheduler().buildTask(this, () -> {
                if (isVerifiedOnWeb(player.getUsername())) {
                    verifiedPlayers.add(player.getUniqueId());
                    notifiedPlayers.remove(player.getUniqueId());

                    player.sendMessage(Component.text("[!] Капча успешно пройдена! Перенаправляем на основной сервер...", NamedTextColor.GREEN));
                    logger.info("[VelocityGuard] Игрок " + player.getUsername() + " прошёл капчу. Перевод с " + limboServerName + " на " + mainServerName);

                    Optional<RegisteredServer> targetServer = server.getServer(mainServerName);
                    if (targetServer.isPresent()) {
                        player.createConnectionRequest(targetServer.get()).fireAndForget();
                    } else {
                        logger.error("[VelocityGuard] Сервер '" + mainServerName + "' не найден в velocity.toml!");
                    }
                } else {
                    if (!notifiedPlayers.contains(player.getUniqueId())) {
                        sendVerificationMessage(player);
                        notifiedPlayers.add(player.getUniqueId());
                    }
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
            // Ошибка связи с веб-сервером
        }
        return false;
    }
}
