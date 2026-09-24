package dev.dwoodard.voxelpilot.ai;

import dev.dwoodard.voxelpilot.config.ConfigStore;
import dev.dwoodard.voxelpilot.config.ProviderConfig;

public final class ProviderFactory {
    private ProviderFactory() {}

    public static ModelProvider current() {
        ProviderConfig c = ConfigStore.get();
        return switch (c.provider.toLowerCase()) {
            case "ollama" -> new OllamaProvider(c);
            default -> new OpenAiCompatibleProvider(c);
        };
    }
}
