package com.fachat.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String version = "0.2.0";
    private String frontendOrigin = "http://localhost:3000";
    private String fileUploadModelId = "openai/gpt-oss-120b:free";
    private List<String> allowedModelIds = List.of();
    private boolean modelCatalogDynamicEnabled = true;
    private String environment = "local";
    private List<String> corsAllowedOrigins = List.of(
        "http://localhost:3000",
        "http://127.0.0.1:3000"
    );
    private String internalApiSecret = "";

    private boolean rateLimitEnabled = true;
    private int rateLimitChatPerMinute = 10;
    private int rateLimitChatUserPerMinute = 20;
    private int rateLimitGlobalPerMinute = 60;
    private int rateLimitBlockSeconds = 300;

    private long maxRequestBodyBytes = 1_048_576L;
    private int maxMessageChars = 12_000;
    private int maxMessagesPerRequest = 40;
    private int maxTitleChars = 64;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFrontendOrigin() {
        return frontendOrigin;
    }

    public void setFrontendOrigin(String frontendOrigin) {
        this.frontendOrigin = frontendOrigin;
    }

    public String getFileUploadModelId() {
        return fileUploadModelId;
    }

    public void setFileUploadModelId(String fileUploadModelId) {
        this.fileUploadModelId = fileUploadModelId;
    }

    public List<String> getAllowedModelIds() {
        return allowedModelIds;
    }

    public void setAllowedModelIds(List<String> allowedModelIds) {
        this.allowedModelIds = allowedModelIds;
    }

    public boolean isModelCatalogDynamicEnabled() {
        return modelCatalogDynamicEnabled;
    }

    public void setModelCatalogDynamicEnabled(boolean modelCatalogDynamicEnabled) {
        this.modelCatalogDynamicEnabled = modelCatalogDynamicEnabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public String getInternalApiSecret() {
        return internalApiSecret;
    }

    public void setInternalApiSecret(String internalApiSecret) {
        this.internalApiSecret = internalApiSecret;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public int getRateLimitChatPerMinute() {
        return rateLimitChatPerMinute;
    }

    public void setRateLimitChatPerMinute(int rateLimitChatPerMinute) {
        this.rateLimitChatPerMinute = rateLimitChatPerMinute;
    }

    public int getRateLimitChatUserPerMinute() {
        return rateLimitChatUserPerMinute;
    }

    public void setRateLimitChatUserPerMinute(int rateLimitChatUserPerMinute) {
        this.rateLimitChatUserPerMinute = rateLimitChatUserPerMinute;
    }

    public int getRateLimitGlobalPerMinute() {
        return rateLimitGlobalPerMinute;
    }

    public void setRateLimitGlobalPerMinute(int rateLimitGlobalPerMinute) {
        this.rateLimitGlobalPerMinute = rateLimitGlobalPerMinute;
    }

    public int getRateLimitBlockSeconds() {
        return rateLimitBlockSeconds;
    }

    public void setRateLimitBlockSeconds(int rateLimitBlockSeconds) {
        this.rateLimitBlockSeconds = rateLimitBlockSeconds;
    }

    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public int getMaxMessageChars() {
        return maxMessageChars;
    }

    public void setMaxMessageChars(int maxMessageChars) {
        this.maxMessageChars = maxMessageChars;
    }

    public int getMaxMessagesPerRequest() {
        return maxMessagesPerRequest;
    }

    public void setMaxMessagesPerRequest(int maxMessagesPerRequest) {
        this.maxMessagesPerRequest = maxMessagesPerRequest;
    }

    public int getMaxTitleChars() {
        return maxTitleChars;
    }

    public void setMaxTitleChars(int maxTitleChars) {
        this.maxTitleChars = maxTitleChars;
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment);
    }
}
