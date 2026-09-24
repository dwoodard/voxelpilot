package dev.dwoodard.voxelpilot.ui;

import dev.dwoodard.voxelpilot.ai.ProviderFactory;
import dev.dwoodard.voxelpilot.bridge.BridgeServer;
import dev.dwoodard.voxelpilot.build.BuildExecutor;
import dev.dwoodard.voxelpilot.build.GhostPreviewManager;
import dev.dwoodard.voxelpilot.config.ConfigStore;
import dev.dwoodard.voxelpilot.config.ProviderConfig;
import dev.dwoodard.voxelpilot.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class WorkspaceScreen extends Screen {
    private enum Tab { STATUS, MODELS }
    private Tab tab = Tab.STATUS;
    private EditBox provider;
    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKey;
    private String connectionStatus = "";
    private List<String> discoveredModels = new ArrayList<>();

    public WorkspaceScreen() { super(Component.literal("VoxelPilot Workspace")); }

    @Override
    protected void init() {
        int panelX = Math.max(0, width - Math.min(430, width));
        addRenderableWidget(Button.builder(Component.literal("Status"), b -> { tab = Tab.STATUS; rebuild(); })
            .bounds(panelX + 16, 18, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Models"), b -> { tab = Tab.MODELS; rebuild(); })
            .bounds(panelX + 108, 18, 86, 20).build());
        buildTab(panelX);
    }

    private void rebuild() { clearWidgets(); init(); }

    private void buildTab(int panelX) {
        if (tab != Tab.MODELS) return;
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
            if (error != null) { connectionStatus = "Could not load models"; return; }
            discoveredModels = models;
            connectionStatus = models.size() + " models found";
        }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelW = Math.min(430, width);
        int x = width - panelW;
        g.fill(x, 0, width, height, 0xF216181B);
        g.drawString(font, "VOXELPILOT WORKSPACE", x + 16, 50, 0xFFB5BAC1, false);

        if (tab == Tab.STATUS) renderStatus(g, x);
        else renderModels(g, x);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderStatus(GuiGraphics g, int x) {
        int y = 78;
        var selection = SelectionManager.get().box();
        g.drawString(font, "SELECTION", x + 16, y, 0xFF8B949E, false); y += 15;
        g.drawString(font, selection.map(b -> b.width() + " × " + b.height() + " × " + b.depth() + "  (" + b.volume() + " blocks)").orElse("None"), x + 16, y, 0xFFF0F2F4, false); y += 30;

        g.drawString(font, "PREVIEW", x + 16, y, 0xFF8B949E, false); y += 15;
        var plan = GhostPreviewManager.get().plan();
        if (plan.isPresent()) {
            var p = plan.get();
            g.drawString(font, p.title + " · " + p.changes.size() + " changes", x + 16, y, 0xFFF0F2F4, false); y += 14;
            g.drawString(font, "Mode: " + p.mode + " · Speed: " + p.speed, x + 16, y, 0xFFB5BAC1, false); y += 24;
        } else { g.drawString(font, "None", x + 16, y, 0xFFF0F2F4, false); y += 28; }

        g.drawString(font, "BUILD", x + 16, y, 0xFF8B949E, false); y += 15;
        BuildExecutor be = BuildExecutor.get();
        String build = be.active() ? (be.completed() + " / " + be.total() + (be.paused() ? " · paused" : " · " + be.speed().name().toLowerCase())) : "Idle";
        g.drawString(font, build, x + 16, y, 0xFFF0F2F4, false); y += 30;

        g.drawString(font, "BRIDGE", x + 16, y, 0xFF8B949E, false); y += 15;
        g.drawString(font, BridgeServer.get().isRunning() ? "Connected · localhost:8765" : "Off · starts with Cmd+K", x + 16, y, 0xFFF0F2F4, false);
    }

    private void renderModels(GuiGraphics g, int x) {
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
    }

    @Override public boolean isPauseScreen() { return false; }
}
