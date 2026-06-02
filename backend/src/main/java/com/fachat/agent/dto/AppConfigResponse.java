package com.fachat.agent.dto;

import java.util.List;

public record AppConfigResponse(
    String version,
    List<ModelOption> availableModels,
    List<String> defaultSelectedModels,
    String fileUploadModelId
) {
}
