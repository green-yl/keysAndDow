package com.example.keys.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DownloadReceiptService {
    private static final Logger log = LoggerFactory.getLogger(DownloadReceiptService.class);

    @Autowired
    private AuthorizationService authorizationService;

    @Async("downloadReceiptExecutor")
    public void submitReceipt(String downloadToken, boolean success, long fileSize,
                              String fileId, String ip, String ua, String method) {
        try {
            Map<String, Object> downloadResult = Map.of(
                    "ok", success,
                    "size", fileSize,
                    "sha256", fileId
            );
            Map<String, Object> clientInfo = Map.of("download_method", method);
            authorizationService.downloadCommit(downloadToken, downloadResult, clientInfo, ip, ua);
        } catch (Exception e) {
            log.error("提交下载回执失败: token={}, error={}", downloadToken, e.getMessage());
        }
    }
}
