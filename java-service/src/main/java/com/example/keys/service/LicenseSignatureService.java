package com.example.keys.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class LicenseSignatureService {
    
    @Value("${app.license.private-key:}")
    private String privateKeyBase64;
    
    @Value("${app.license.public-key:}")
    private String publicKeyBase64;
    
    private Ed25519PrivateKeyParameters privateKey;
    private Ed25519PublicKeyParameters publicKey;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @PostConstruct
    public void init() {
        if (privateKeyBase64.isEmpty() || publicKeyBase64.isEmpty()) {
            generateNewKeyPair();
        } else {
            try {
                loadKeysFromConfig();
            } catch (Exception e) {
                System.err.println("Failed to load keys from config, generating new ones: " + e.getMessage());
                generateNewKeyPair();
            }
        }
    }
    
    private void generateNewKeyPair() {
        try {
            // Generate Ed25519 key pair
            Ed25519PrivateKeyParameters privateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
            Ed25519PublicKeyParameters publicKeyParams = privateKeyParams.generatePublicKey();
            
            this.privateKey = privateKeyParams;
            this.publicKey = publicKeyParams;
            
            // Encode to Base64 for storage
            String privKeyB64 = Base64.getEncoder().encodeToString(privateKeyParams.getEncoded());
            String pubKeyB64 = Base64.getEncoder().encodeToString(publicKeyParams.getEncoded());
            
            System.out.println("Generated new Ed25519 key pair:");
            System.out.println("Private Key (add to application.properties): app.license.private-key=" + privKeyB64);
            System.out.println("Public Key (add to application.properties): app.license.public-key=" + pubKeyB64);
            System.out.println("Public Key (for client verification): " + pubKeyB64);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 key pair", e);
        }
    }
    
    private void loadKeysFromConfig() {
        try {
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64);
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            
            this.privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);
            this.publicKey = new Ed25519PublicKeyParameters(publicKeyBytes, 0);
            
            System.out.println("Loaded Ed25519 keys from configuration");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Ed25519 keys from configuration", e);
        }
    }
    
    public String getCurrentKid() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
    
    public Map<String, Object> signLicense(Map<String, Object> payload) {
        try {
            // Add timestamp and kid to payload
            payload.put("iat", System.currentTimeMillis() / 1000);
            payload.put("kid", getCurrentKid());
            
            // Convert payload to JSON
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadBase64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            
            // Sign the payload
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, privateKey);
            signer.update(payloadBase64.getBytes(StandardCharsets.UTF_8), 0, payloadBase64.length());
            byte[] signature = signer.generateSignature();
            
            String signatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
            
            // Return license object
            Map<String, Object> license = new HashMap<>();
            license.put("payload", payloadBase64);
            license.put("sig", signatureBase64);
            license.put("alg", "Ed25519");
            license.put("kid", getCurrentKid());
            
            return license;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign license", e);
        }
    }
    
    public boolean verifyLicense(String payloadBase64, String signatureBase64) {
        try {
            byte[] signature = Base64.getUrlDecoder().decode(signatureBase64);
            
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, publicKey);
            verifier.update(payloadBase64.getBytes(StandardCharsets.UTF_8), 0, payloadBase64.length());
            
            return verifier.verifySignature(signature);
            
        } catch (Exception e) {
            System.err.println("License verification failed: " + e.getMessage());
            return false;
        }
    }
    
    public Map<String, Object> parseLicensePayload(String payloadBase64) {
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadBase64);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            
            return payload;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse license payload", e);
        }
    }
    
    public String getPublicKeyForClient() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
