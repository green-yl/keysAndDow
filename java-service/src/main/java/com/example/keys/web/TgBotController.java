package com.example.keys.web;

import com.example.keys.model.LicenseCode;
import com.example.keys.model.Plan;
import com.example.keys.repo.LicenseCodeRepository;
import com.example.keys.repo.PlanRepository;
import com.example.keys.service.AdminService;
import com.example.keys.service.TgBotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tg-bot")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TgBotController {
    private final AdminService adminService;
    private final PlanRepository planRepository;
    private final LicenseCodeRepository licenseCodeRepository;
    private final JdbcTemplate jdbc;
    private final TgBotService tgBotService;

    public TgBotController(AdminService adminService,
                           PlanRepository planRepository,
                           LicenseCodeRepository licenseCodeRepository,
                           JdbcTemplate jdbc,
                           TgBotService tgBotService) {
        this.adminService = adminService;
        this.planRepository = planRepository;
        this.licenseCodeRepository = licenseCodeRepository;
        this.jdbc = jdbc;
        this.tgBotService = tgBotService;
    }

    @GetMapping("/plans")
    public ResponseEntity<?> listPlans(HttpServletRequest httpRequest) {
        ResponseEntity<?> authError = requireApiKey(httpRequest);
        if (authError != null) {
            return authError;
        }
        List<Map<String, Object>> plans = planRepository.findAll().stream()
                .map(this::buildPlanResponse)
                .toList();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "plans", plans
        ));
    }

    /**
     * TG bot 支付成功后调用：按套餐发放一个激活码。
     *
     * 必须带 X-API-Key。
     * external_order_id 用于幂等，TG bot 重试同一个订单时返回同一个激活码，不重复发码。
     */
    @PostMapping("/purchase/activation-code")
    public synchronized ResponseEntity<?> createActivationCodeForPurchase(@RequestBody Map<String, Object> request,
                                                                          HttpServletRequest httpRequest) {
        ResponseEntity<?> authError = requireApiKey(httpRequest);
        if (authError != null) {
            return authError;
        }
        Long planId = longValue(request.get("plan_id"), request.get("planId"));
        String externalOrderId = stringValue(request.get("external_order_id"), request.get("externalOrderId"), request.get("order_id"), request.get("orderId"));
        String paymentStatus = stringValue(request.get("payment_status"), request.get("paymentStatus"));

        if (planId == null || externalOrderId == null || externalOrderId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "缺少必要参数：plan_id 和 external_order_id"
            ));
        }

        if (paymentStatus != null && !isPaidStatus(paymentStatus)) {
            return ResponseEntity.status(402).body(Map.of(
                    "ok", false,
                    "error", "订单未支付成功",
                    "payment_status", paymentStatus
            ));
        }

        Optional<Plan> planOpt = planRepository.findById(planId);
        if (planOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "ok", false,
                    "error", "套餐不存在",
                    "plan_id", planId
            ));
        }

        Optional<LicenseCode> existing = findCodeByExternalOrderId(externalOrderId);
        if (existing.isPresent()) {
            return ResponseEntity.ok(buildResponse(existing.get(), planOpt.get(), externalOrderId, true));
        }

        Integer issueLimit = intValue(request.get("issue_limit"), request.get("issueLimit"));
        if (issueLimit == null || issueLimit <= 0) {
            issueLimit = 1;
        }

        Integer codeExpireHours = intValue(request.get("code_expire_hours"), request.get("codeExpireHours"));
        if (codeExpireHours == null || codeExpireHours <= 0) {
            codeExpireHours = 24 * 30;
        }

        String note = buildNote(request, externalOrderId);
        LicenseCode licenseCode = adminService.createLicenseCode(
                null,
                planId,
                issueLimit,
                LocalDateTime.now().plusHours(codeExpireHours),
                note
        );

        insertTgOrder(request, externalOrderId, planId, licenseCode.getCode());
        return ResponseEntity.ok(buildResponse(licenseCode, planOpt.get(), externalOrderId, false));
    }


    /**
     * USDT 支付平台回调：确认订单、累计用户支付额度、升级会员等级并发放激活码。
     */
    @PostMapping("/usdt/callback")
    public ResponseEntity<?> usdtCallback(@RequestHeader(value = "X-TG-PAY-SECRET", required = false) String secret,
                                          @RequestBody Map<String, Object> request) {
        Map<String, Object> result = tgBotService.confirmUsdtPayment(request, secret);
        int status = tgBotService.statusOf(result);
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/purchase/activation-code/{externalOrderId}")
    public ResponseEntity<?> getActivationCodeByOrder(@PathVariable String externalOrderId,
                                                      HttpServletRequest httpRequest) {
        ResponseEntity<?> authError = requireApiKey(httpRequest);
        if (authError != null) {
            return authError;
        }
        Optional<LicenseCode> existing = findCodeByExternalOrderId(externalOrderId);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "ok", false,
                    "error", "订单不存在"
            ));
        }
        Optional<Plan> plan = planRepository.findById(existing.get().getPlanId());
        return ResponseEntity.ok(buildResponse(existing.get(), plan.orElse(null), externalOrderId, true));
    }

    private Optional<LicenseCode> findCodeByExternalOrderId(String externalOrderId) {
        List<String> codes = jdbc.query(
                "SELECT license_code FROM tg_activation_orders WHERE external_order_id=? LIMIT 1",
                (rs, rowNum) -> rs.getString("license_code"),
                externalOrderId
        );
        if (codes.isEmpty()) {
            return Optional.empty();
        }
        return licenseCodeRepository.findByCode(codes.get(0));
    }

    private ResponseEntity<?> requireApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(401).body(Map.of(
                    "ok", false,
                    "error", "缺少 X-API-Key"
            ));
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE api_key=? AND is_active=1",
                Integer.class,
                apiKey
        );
        if (count == null || count <= 0) {
            return ResponseEntity.status(403).body(Map.of(
                    "ok", false,
                    "error", "X-API-Key 无效"
            ));
        }
        return null;
    }

    private void insertTgOrder(Map<String, Object> request, String externalOrderId, Long planId, String licenseCode) {
        jdbc.update("""
                        INSERT INTO tg_activation_orders (
                            external_order_id, tg_user_id, tg_username, plan_id, license_code,
                            amount, currency, payment_provider, payment_payload, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                externalOrderId,
                stringValue(request.get("tg_user_id"), request.get("tgUserId"), request.get("telegram_user_id"), request.get("telegramUserId")),
                stringValue(request.get("tg_username"), request.get("tgUsername"), request.get("username")),
                planId,
                licenseCode,
                stringValue(request.get("amount")),
                stringValue(request.get("currency")),
                stringValue(request.get("payment_provider"), request.get("paymentProvider")),
                stringValue(request.get("payment_payload"), request.get("paymentPayload")),
                LocalDateTime.now()
        );
    }

    private Map<String, Object> buildResponse(LicenseCode licenseCode, Plan plan, String externalOrderId, boolean duplicated) {
        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        response.put("duplicated", duplicated);
        response.put("external_order_id", externalOrderId);
        response.put("activation_code", licenseCode.getCode());
        response.put("code", licenseCode);
        if (plan != null) {
            response.put("plan", buildPlanResponse(plan));
        }
        return response;
    }

    private Map<String, Object> buildPlanResponse(Plan plan) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", plan.getId());
        data.put("name", plan.getName());
        data.put("duration_hours", plan.getDurationHours());
        data.put("init_quota", plan.getInitQuota());
        data.put("allow_grace", Boolean.TRUE.equals(plan.getAllowGrace()));
        data.put("features", plan.getFeatures());
        return data;
    }

    private String buildNote(Map<String, Object> request, String externalOrderId) {
        String customNote = stringValue(request.get("note"));
        String tgUserId = stringValue(request.get("tg_user_id"), request.get("tgUserId"), request.get("telegram_user_id"), request.get("telegramUserId"));
        String username = stringValue(request.get("tg_username"), request.get("tgUsername"), request.get("username"));
        StringBuilder note = new StringBuilder("tg_bot_order=").append(externalOrderId);
        if (tgUserId != null && !tgUserId.isBlank()) {
            note.append(";tg_user_id=").append(tgUserId);
        }
        if (username != null && !username.isBlank()) {
            note.append(";username=").append(username);
        }
        if (customNote != null && !customNote.isBlank()) {
            note.append(";note=").append(customNote);
        }
        return note.toString();
    }

    private boolean isPaidStatus(String status) {
        String normalized = status.trim().toLowerCase();
        return "paid".equals(normalized)
                || "success".equals(normalized)
                || "succeeded".equals(normalized)
                || "captured".equals(normalized)
                || "confirmed".equals(normalized);
    }

    private Long longValue(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private Integer intValue(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private String stringValue(Object... values) {
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }
}
