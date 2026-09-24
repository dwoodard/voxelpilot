package dev.dwoodard.voxelpilot.config;

public final class ProviderConfig {
    public String provider = "openai-compatible";
    public String baseUrl = "http://localhost:1234/v1";
    public String apiKey = "";
    public String model = "";
    public int defaultContextRadius = 32;
    public int maxContextRadius = 256;
    public boolean autoExpandContext = true;
}
