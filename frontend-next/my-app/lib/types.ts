export type ModelOption = {
  id: string;
  label: string;
  description: string;
  supportsFiles: boolean;
  recommendedFor: string[];
};

export type AppConfig = {
  version: string;
  availableModels: ModelOption[];
  defaultSelectedModels: string[];
  fileUploadModelId: string;
};
