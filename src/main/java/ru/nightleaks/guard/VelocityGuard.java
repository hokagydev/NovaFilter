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
    // Игроки, которым уже отправлено сообщение (чтобы не спамить в чат)[cite: 1]
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();
    
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
        logger.info("==================================================");
        logger.info(" VelocityGuard успешно запущен (Java 21 / v4.x)!");
        logger.info(" Целевой домен капчи: https://captcha.novamine.fun");
        logger.info("==================================================");
        
        // Тестируем соединение с сайтом при запуске в отдельном потоке
        server.getScheduler().buildTask(this, this::testWebsiteConnection).schedule();

        // Фоновый таск проверки статуса верификации игроков
        server.getScheduler().buildTask(this, this::checkPendingPlayers)
                .repeat(3, TimeUnit.SECONDS)
                .schedule();
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        
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
        // Если нужно сбрасывать верификацию при выходе игрока, раскомментируй строку ниже:
        // verifiedPlayers.remove(uuid);
    }

    private void testWebsiteConnection() {
        try {
            logger.info("[VelocityGuard] Выполняется проверка подключения к сайту капчи...");
            URL url = URI.create("https://captcha.novamine.fun").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 400) {
                logger.info("[VelocityGuard] Подключение к сайту успешно! Код ответа: " + responseCode);
            } else {
                logger.warn("[VelocityGuard] ВНИМАНИЕ: Сайт ответил с ошибкой, код: " + responseCode);
            }
        } catch (Exception e) {
            logger.error("[VelocityGuard] ОШИБКА: Не удалось подключиться к сайту captcha.novamine.fun! Проверьте интернет или доступность домена. Причина: " + e.getMessage());
        }
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

            // Асинхронный запрос к сайту для проверки прохождения капчи
            server.getScheduler().buildTask(this, () -> {
                if (isVerifiedOnWeb(player.getUsername())) {
                    verifiedPlayers.add(player.getUniqueId());
                    notifiedPlayers.remove(player.getUniqueId());
                    
                    player.sendMessage(Component.text("[!] Капча успешно пройдена! Подключаем к серверу...", NamedTextColor.GREEN));
                    logger.info("[VelocityGuard] Игрок " + player.getUsername() + " успешно прошёл капчу и подключен к серверу " + targetServerName);
                    
                    server.getServer(targetServerName).ifPresent(target -> {
                        player.createConnectionRequest(target).fireAndForget();
                    });
                } else {
                    // Если игрок зашел, но еще не получил уведомление (на всякий случай)
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
            // Ошибка подключения к бэкенду сайта при проверке игрока
        }
        return false;
    }
}
