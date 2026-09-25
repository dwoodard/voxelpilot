package dev.dwoodard.voxelpilot.config;

public final class ProviderConfig {
    public String provider = "openai-compatible";
    // 127.0.0.1, not "localhost": JDK's HttpClient can throw ClosedChannelException
    // when "localhost" resolves to ::1 first and the local server only binds IPv4.
    public String baseUrl = "http://127.0.0.1:1234/v1";
    public String apiKey = "";
    public String model = "";
    public int defaultContextRadius = 32;
    public int maxContextRadius = 256;
    public boolean autoExpandContext = true;
}
