package dev.dwoodard.voxelpilot.ui;

import dev.dwoodard.voxelpilot.ai.ProviderFactory;
import dev.dwoodard.voxelpilot.config.ConfigStore;
import dev.dwoodard.voxelpilot.config.ProviderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Provider/model configuration only. Toggled by Cmd+, separate from the read-only
// InspectorScreen (Cmd+Shift+K).
public final class SettingsScreen extends Screen {
    private EditBox provider;
    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKey;
    private String connectionStatus = "";
    private List<String> discoveredModels = new ArrayList<>();

    public SettingsScreen() { super(Component.literal("VoxelPilot Settings")); }

    @Override
    protected void init() {
        int panelX = Math.max(0, width - Math.min(430, width));
        ProviderConfig c = ConfigStore.get();
        provider = field(panelX + 16, 76, 390, c.provider);
        baseUrl = field(panelX + 16, 118, 390, c.baseUrl);
        model = field(panelX + 16, 160, 390, c.model);
        apiKey = field(panelX + 16, 202, 390, c.apiKey);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(panelX + 16, 238, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> testConnection()).bounds(panelX + 102, 238, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh models"), b -> refreshModels()).bounds(panelX + 188, 238, 118, 20).build());
    }

    private EditBox field(int x, int y, int w, String value) {
        EditBox box = new EditBox(font, x, y, w, 22, Component.empty());
        box.setMaxLength(512);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    private void save() {
        ProviderConfig c = new ProviderConfig();
        c.provider = provider.getValue().trim();
        c.baseUrl = baseUrl.getValue().trim();
        c.model = model.getValue().trim();
        c.apiKey = apiKey.getValue().trim();
        ProviderConfig old = ConfigStore.get();
        c.defaultContextRadius = old.defaultContextRadius;
        c.maxContextRadius = old.maxContextRadius;
        c.autoExpandContext = old.autoExpandContext;
        ConfigStore.save(c);
        connectionStatus = "Saved";
    }

    private void testConnection() {
        save();
        connectionStatus = "Testing…";
        ProviderFactory.current().healthCheck().whenComplete((ok, error) -> Minecraft.getInstance().execute(() ->
            connectionStatus = error == null && Boolean.TRUE.equals(ok) ? "Connected" : "Connection failed"));
    }

    private void refreshModels() {
        save();
        connectionStatus = "Loading models…";
        ProviderFactory.current().listModels().whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                dev.dwoodard.voxelpilot.VoxelPilot.LOGGER.error("VoxelPilot: failed to load models", error);
                connectionStatus = "Could not load models: " + rootMessage(error);
                return;
            }
            discoveredModels = models;
            connectionStatus = models.size() + " models found";
        }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelW = Math.min(430, width);
        int x = width - panelW;
        g.fill(x, 0, width, height, 0xF216181B);
        g.drawString(font, "VOXELPILOT SETTINGS", x + 16, 50, 0xFFB5BAC1, false);
        g.drawString(font, "Provider", x + 16, 64, 0xFF8B949E, false);
        g.drawString(font, "Base URL", x + 16, 106, 0xFF8B949E, false);
        g.drawString(font, "Model", x + 16, 148, 0xFF8B949E, false);
        g.drawString(font, "API key (optional for local providers)", x + 16, 190, 0xFF8B949E, false);
        g.drawString(font, connectionStatus, x + 16, 268, 0xFFB5BAC1, false);
        int y = 286;
        for (String name : discoveredModels.stream().limit(8).toList()) {
            g.drawString(font, "• " + name, x + 20, y, 0xFFE2E5E9, false);
            y += 14;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(dev.dwoodard.voxelpilot.client.ClientEvents.inspectorOpen ? new InspectorScreen() : null);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
