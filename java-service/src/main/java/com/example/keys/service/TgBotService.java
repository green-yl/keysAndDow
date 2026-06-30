package com.example.keys.service;

import com.example.keys.model.LicenseCode;
import com.example.keys.model.Plan;
import com.example.keys.repo.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TgBotService {
    private static final Logger log = LoggerFactory.getLogger(TgBotService.class);
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AdminService adminService;
    private final PlanRepository planRepository;
    private final JdbcTemplate jdbc;
    private final RestTemplate rest = new RestTemplate();
    private final AtomicLong nextOffset = new AtomicLong(0);

    @Value("${app.tg.bot-token:}")
    private String botToken;
    @Value("${app.tg.customer-service:请联系客服处理。}")
    private String customerService;
    @Value("${app.usdt.pay-address:}")
    private String usdtPayAddress;
    @Value("${app.usdt.network:TRC20}")
    private String usdtNetwork;
    @Value("${app.usdt.callback-secret:}")
    private String callbackSecret;
    @Value("${app.tg.price.week-usdt:10}")
    private String weekPrice;
    @Value("${app.tg.price.month-usdt:30}")
    private String monthPrice;
    @Value("${app.tg.price.year-usdt:300}")
    private String yearPrice;
    @Value("${app.tg.price.permanent-usdt:1000}")
    private String permanentPrice;

    public TgBotService(AdminService adminService, PlanRepository planRepository, JdbcTemplate jdbc) {
        this.adminService = adminService;
        this.planRepository = planRepository;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupBotMenu() {
        if (!enabled()) {
            log.info("TG bot 未启用：app.tg.bot-token 为空");
            return;
        }
        try {
            api("deleteWebhook", Map.of("drop_pending_updates", false));
            api("setMyCommands", Map.of("commands", List.of(
                    Map.of("command", "start", "description", "打开菜单"),
                    Map.of("command", "buy", "description", "购买激活码"),
                    Map.of("command", "service", "description", "联系客服")
            )));
            log.info("TG bot 菜单已设置");
        } catch (Exception e) {
            log.warn("TG bot 菜单设置失败：{}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.tg.poll-delay-ms:1500}")
    public void pollUpdates() {
        if (!enabled()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            long offset = nextOffset.get();
            if (offset > 0) body.put("offset", offset);
            body.put("timeout", 0);
            body.put("allowed_updates", List.of("message", "callback_query"));

            Map<?, ?> response = api("getUpdates", body);
            Object result = response.get("result");
            if (!(result instanceof List<?> updates)) return;
            for (Object item : updates) {
                if (!(item instanceof Map<?, ?> update)) continue;
                Long updateId = longValue(update.get("update_id"));
                if (updateId != null) nextOffset.set(updateId + 1);
                handleUpdate(update);
            }
        } catch (Exception e) {
            log.warn("TG bot 轮询失败：{}", e.getMessage());
        }
    }

    @Transactional
    public synchronized Map<String, Object> confirmUsdtPayment(Map<String, Object> request, String headerSecret) {
        if (hasText(callbackSecret)) {
            String suppliedSecret = firstText(headerSecret, request.get("secret"), request.get("callback_secret"));
            if (!callbackSecret.equals(suppliedSecret)) {
                return result(403, false, "回调密钥错误");
            }
        }

        String status = firstText(request.get("status"), request.get("payment_status"), request.get("paymentStatus"));
        if (hasText(status) && !isPaidStatus(status)) {
            Map<String, Object> result = result(402, false, "订单未支付成功");
            result.put("payment_status", status);
            return result;
        }

        String orderId = firstText(request.get("order_id"), request.get("orderId"), request.get("external_order_id"), request.get("externalOrderId"));
        if (!hasText(orderId)) return result(400, false, "缺少 order_id");

        List<Map<String, Object>> orders = jdbc.queryForList("SELECT * FROM tg_payment_orders WHERE order_id=? LIMIT 1", orderId);
        if (orders.isEmpty()) return result(404, false, "订单不存在");
        Map<String, Object> order = orders.get(0);

        String existingCode = firstText(order.get("activation_code"));
        if ("paid".equalsIgnoreCase(firstText(order.get("status"))) && hasText(existingCode)) {
            Map<String, Object> result = result(200, true, null);
            result.put("duplicated", true);
            result.put("order_id", orderId);
            result.put("activation_code", existingCode);
            return result;
        }

        BigDecimal payableAmount = decimalValue(order.get("payable_amount"));
        BigDecimal paidAmount = decimalValue(request.get("amount"), request.get("paid_amount"), request.get("paidAmount"));
        if (paidAmount == null) paidAmount = payableAmount;
        if (payableAmount == null || paidAmount == null) return result(400, false, "订单金额异常");
        if (paidAmount.compareTo(payableAmount) < 0) {
            Map<String, Object> result = result(400, false, "支付金额不足");
            result.put("required_amount", plain(payableAmount));
            result.put("paid_amount", plain(paidAmount));
            return result;
        }

        String txId = firstText(request.get("tx_id"), request.get("txId"), request.get("hash"), request.get("transaction_id"));
        if (hasText(txId)) {
            Integer txCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tg_payment_orders WHERE tx_id=? AND order_id<>? AND status='paid'",
                    Integer.class,
                    txId,
                    orderId
            );
            if (txCount != null && txCount > 0) return result(409, false, "交易哈希已处理");
        }

        String tgUserId = firstText(order.get("tg_user_id"));
        String tgUsername = firstText(order.get("tg_username"));
        Long planId = longValue(order.get("plan_id"));
        if (!hasText(tgUserId) || planId == null) return result(400, false, "订单用户或套餐异常");

        LicenseCode code = adminService.createLicenseCode(
                null,
                planId,
                1,
                LocalDateTime.now().plusYears(10),
                "tg_usdt_order=" + orderId + (hasText(txId) ? ";tx_id=" + txId : "")
        );

        String levelBefore = memberLevel(tgUserId);
        BigDecimal totalPaid = addUserPayment(tgUserId, tgUsername, paidAmount);
        String levelAfter = levelOf(totalPaid);

        jdbc.update("""
                        UPDATE tg_payment_orders
                        SET status='paid', paid_amount=?, tx_id=?, activation_code=?, member_level_after=?, paid_at=?, updated_at=?
                        WHERE order_id=?
                        """,
                plain(paidAmount), txId, code.getCode(), levelAfter, LocalDateTime.now(), LocalDateTime.now(), orderId);

        jdbc.update("""
                        INSERT OR IGNORE INTO tg_activation_orders (
                            external_order_id, tg_user_id, tg_username, plan_id, license_code,
                            amount, currency, payment_provider, payment_payload, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'USDT', 'usdt_callback', ?, ?)
                        """,
                orderId,
                tgUserId,
                tgUsername,
                planId,
                code.getCode(),
                plain(paidAmount),
                firstText(request.get("payment_payload"), request.get("payload"), request.toString()),
                LocalDateTime.now()
        );

        sendPaymentSuccess(tgUserId, orderId, code.getCode(), paidAmount, totalPaid, levelBefore, levelAfter);

        Map<String, Object> result = result(200, true, null);
        result.put("order_id", orderId);
        result.put("activation_code", code.getCode());
        result.put("paid_amount", plain(paidAmount));
        result.put("total_paid_usdt", plain(totalPaid));
        result.put("member_level", levelAfter);
        return result;
    }

    public int statusOf(Map<String, Object> result) {
        Object status = result.remove("http_status");
        Long value = longValue(status);
        return value == null ? 200 : value.intValue();
    }

    private void handleUpdate(Map<?, ?> update) {
        Object message = update.get("message");
        if (message instanceof Map<?, ?> msg) {
            handleMessage(msg);
        }
        Object callback = update.get("callback_query");
        if (callback instanceof Map<?, ?> cb) {
            handleCallback(cb);
        }
    }

    private void handleMessage(Map<?, ?> message) {
        Map<?, ?> chat = asMap(message.get("chat"));
        String chatId = firstText(chat.get("id"));
        String text = firstText(message.get("text"));
        Map<?, ?> from = asMap(message.get("from"));
        String tgUserId = firstText(from.get("id"));
        String username = firstText(from.get("username"));
        if (hasText(tgUserId)) ensureUser(tgUserId, username);
        if (!hasText(chatId)) return;

        if ("购买激活码".equals(text) || "/buy".equals(text)) {
            sendBuyMenu(chatId);
            return;
        }
        if ("联系客服".equals(text) || "/service".equals(text)) {
            sendMessage(chatId, customerService, mainKeyboard());
            return;
        }
        sendMainMenu(chatId, tgUserId);
    }

    private void handleCallback(Map<?, ?> callback) {
        String callbackId = firstText(callback.get("id"));
        String data = firstText(callback.get("data"));
        Map<?, ?> from = asMap(callback.get("from"));
        Map<?, ?> message = asMap(callback.get("message"));
        Map<?, ?> chat = asMap(message.get("chat"));
        String chatId = firstText(chat.get("id"));
        String tgUserId = firstText(from.get("id"));
        String username = firstText(from.get("username"));
        if (hasText(callbackId)) apiQuiet("answerCallbackQuery", Map.of("callback_query_id", callbackId));
        if (!hasText(chatId) || !hasText(tgUserId) || !hasText(data)) return;

        if (data.startsWith("buy:")) {
            createPaymentOrder(chatId, tgUserId, username, data.substring(4));
        }
    }

    private void createPaymentOrder(String chatId, String tgUserId, String username, String type) {
        PlanOption option = planOption(type);
        if (option == null) {
            sendMessage(chatId, "套餐不存在，请重新选择。", null);
            return;
        }
        ensureUser(tgUserId, username);
        Plan plan = ensurePlan(option);
        BigDecimal baseAmount = option.price;
        String level = memberLevel(tgUserId);
        BigDecimal payableAmount = "honor_partner".equals(level)
                ? baseAmount.multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP)
                : baseAmount;
        String orderId = "TG" + LocalDateTime.now().format(ORDER_TIME) + ThreadLocalRandom.current().nextInt(1000, 9999);

        jdbc.update("""
                        INSERT INTO tg_payment_orders (
                            order_id, tg_user_id, tg_username, plan_key, plan_id,
                            original_amount, payable_amount, currency, status, member_level_before, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'USDT', 'pending', ?, ?, ?)
                        """,
                orderId,
                tgUserId,
                username,
                option.key,
                plan.getId(),
                plain(baseAmount),
                plain(payableAmount),
                level,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        StringBuilder text = new StringBuilder();
        text.append("订单已创建\n\n");
        text.append("套餐：").append(option.name).append("\n");
        text.append("订单号：").append(orderId).append("\n");
        text.append("应付：").append(plain(payableAmount)).append(" USDT");
        if (!payableAmount.equals(baseAmount)) {
            text.append("（荣誉合伙人半价，原价 ").append(plain(baseAmount)).append(" USDT）");
        }
        text.append("\n网络：").append(usdtNetwork).append("\n");
        if (hasText(usdtPayAddress)) {
            text.append("收款地址：\n").append(usdtPayAddress).append("\n\n");
            text.append("支付完成后等待系统回调，成功后会自动发送激活码。");
        } else {
            text.append("收款地址未配置，请先联系客服完成支付。\n");
            text.append("支付回调成功后系统会自动发送激活码。");
        }
        sendMessage(chatId, text.toString(), mainKeyboard());
    }

    private void sendMainMenu(String chatId, String tgUserId) {
        String level = hasText(tgUserId) ? memberLevel(tgUserId) : "normal";
        String text = "请选择服务：\n当前会员等级：" + levelName(level);
        if ("honor_partner".equals(level)) text += "\n你已享受终身半价购买激活码。";
        sendMessage(chatId, text, mainKeyboard());
    }

    private void sendBuyMenu(String chatId) {
        Map<String, Object> markup = Map.of("inline_keyboard", List.of(
                List.of(Map.of("text", "周卡", "callback_data", "buy:week"), Map.of("text", "月卡", "callback_data", "buy:month")),
                List.of(Map.of("text", "年卡", "callback_data", "buy:year"), Map.of("text", "永久卡", "callback_data", "buy:permanent"))
        ));
        sendMessage(chatId, "请选择购买周期：", markup);
    }

    private void sendPaymentSuccess(String tgUserId, String orderId, String code, BigDecimal paidAmount,
                                    BigDecimal totalPaid, String levelBefore, String levelAfter) {
        StringBuilder text = new StringBuilder();
        text.append("支付成功，激活码已生成。\n\n");
        text.append("订单号：").append(orderId).append("\n");
        text.append("本次支付：").append(plain(paidAmount)).append(" USDT\n");
        text.append("累计支付：").append(plain(totalPaid)).append(" USDT\n");
        text.append("会员等级：").append(levelName(levelAfter)).append("\n");
        if (!levelAfter.equals(levelBefore)) text.append("等级已升级。\n");
        if ("honor_partner".equals(levelAfter)) text.append("你已享受终身半价购买激活码服务。\n");
        text.append("\n激活码：\n").append(code);
        sendMessage(tgUserId, text.toString(), mainKeyboard());
    }

    private Map<String, Object> mainKeyboard() {
        return Map.of(
                "keyboard", List.of(List.of(Map.of("text", "联系客服"), Map.of("text", "购买激活码"))),
                "resize_keyboard", true
        );
    }

    private void sendMessage(String chatId, String text, Object replyMarkup) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        apiQuiet("sendMessage", body);
    }

    private Plan ensurePlan(PlanOption option) {
        Optional<Plan> existing = planRepository.findAll().stream()
                .filter(plan -> option.name.equals(plan.getName()))
                .findFirst();
        return existing.orElseGet(() -> adminService.createPlan(
                option.name,
                option.durationHours,
                option.quota,
                false,
                "tg_bot=" + option.key
        ));
    }

    private void ensureUser(String tgUserId, String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tg_payment_users WHERE tg_user_id=?", Integer.class, tgUserId);
        if (count != null && count > 0) {
            jdbc.update("UPDATE tg_payment_users SET tg_username=?, updated_at=? WHERE tg_user_id=?", username, LocalDateTime.now(), tgUserId);
            return;
        }
        jdbc.update("""
                        INSERT INTO tg_payment_users (tg_user_id, tg_username, total_paid_usdt, member_level, half_price, created_at, updated_at)
                        VALUES (?, ?, '0', 'normal', 0, ?, ?)
                        """,
                tgUserId, username, LocalDateTime.now(), LocalDateTime.now());
    }

    private BigDecimal addUserPayment(String tgUserId, String username, BigDecimal amount) {
        ensureUser(tgUserId, username);
        BigDecimal current = decimalValue(jdbc.queryForObject(
                "SELECT total_paid_usdt FROM tg_payment_users WHERE tg_user_id=?",
                Object.class,
                tgUserId
        ));
        if (current == null) current = BigDecimal.ZERO;
        BigDecimal total = current.add(amount);
        String level = levelOf(total);
        jdbc.update("""
                        UPDATE tg_payment_users
                        SET tg_username=?, total_paid_usdt=?, member_level=?, half_price=?, updated_at=?
                        WHERE tg_user_id=?
                        """,
                username,
                plain(total),
                level,
                "honor_partner".equals(level) ? 1 : 0,
                LocalDateTime.now(),
                tgUserId
        );
        return total;
    }

    private String memberLevel(String tgUserId) {
        if (!hasText(tgUserId)) return "normal";
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tg_payment_users WHERE tg_user_id=?", Integer.class, tgUserId);
        if (count == null || count == 0) return "normal";
        String level = jdbc.queryForObject("SELECT member_level FROM tg_payment_users WHERE tg_user_id=?", String.class, tgUserId);
        return hasText(level) ? level : "normal";
    }

    private String levelOf(BigDecimal totalPaid) {
        if (totalPaid.compareTo(new BigDecimal("16000")) >= 0) return "honor_partner";
        if (totalPaid.compareTo(new BigDecimal("5000")) >= 0) return "platinum";
        if (totalPaid.compareTo(new BigDecimal("1000")) >= 0) return "gold";
        return "normal";
    }

    private String levelName(String level) {
        return switch (level) {
            case "gold" -> "金会员";
            case "platinum" -> "铂金会员";
            case "honor_partner" -> "荣誉合伙人会员";
            default -> "普通会员";
        };
    }

    private PlanOption planOption(String key) {
        return switch (key) {
            case "week" -> new PlanOption("week", "周卡", 24 * 7, 15, decimalValue(weekPrice));
            case "month" -> new PlanOption("month", "月卡", 24 * 30, 30, decimalValue(monthPrice));
            case "year" -> new PlanOption("year", "年卡", 24 * 365, 365, decimalValue(yearPrice));
            case "permanent" -> new PlanOption("permanent", "永久卡", 24 * 365 * 100, 999999, decimalValue(permanentPrice));
            default -> null;
        };
    }

    private Map<String, Object> result(int status, boolean ok, String error) {
        Map<String, Object> data = new HashMap<>();
        data.put("http_status", status);
        data.put("ok", ok);
        if (error != null) data.put("error", error);
        return data;
    }

    private Map<?, ?> api(String method, Map<String, Object> body) {
        String url = "https://api.telegram.org/bot" + botToken + "/" + method;
        Map<?, ?> response = rest.postForObject(url, body, Map.class);
        return response == null ? Map.of() : response;
    }

    private void apiQuiet(String method, Map<String, Object> body) {
        try {
            api(method, body);
        } catch (Exception e) {
            log.warn("TG bot API {} 调用失败：{}", method, e.getMessage());
        }
    }

    private boolean enabled() {
        return hasText(botToken);
    }

    private boolean isPaidStatus(String status) {
        String normalized = status.trim().toLowerCase();
        return "paid".equals(normalized)
                || "success".equals(normalized)
                || "succeeded".equals(normalized)
                || "captured".equals(normalized)
                || "confirmed".equals(normalized);
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private BigDecimal decimalValue(Object... values) {
        for (Object value : values) {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return new BigDecimal(text.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) return text;
            }
        }
        return null;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String plain(BigDecimal decimal) {
        return decimal.stripTrailingZeros().toPlainString();
    }

    private record PlanOption(String key, String name, int durationHours, int quota, BigDecimal price) {}
}
