package com.romcloud.wine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class LaunchActivityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File game(String... files) throws IOException {
        File dir = folder.newFolder();
        for (String name : files) assertTrue(new File(dir, name).createNewFile());
        return dir;
    }

    @Test
    public void jeuUnityIl2cppReconnu() throws IOException {
        assertTrue(LaunchActivity.isUnityIl2cpp(game("Jeu.exe", "UnityPlayer.dll", "GameAssembly.dll")));
    }

    @Test
    public void qualiteUnity() throws IOException {
        File exe = new File(game("Jeu.exe", "UnityPlayer.dll"), "Jeu.exe");
        assertEquals("-screen-quality Low", LaunchActivity.qualityArgs(exe, "low"));
        assertEquals("-screen-quality High", LaunchActivity.qualityArgs(exe, "high"));
        assertEquals("-screen-quality \"Very Low\"", LaunchActivity.qualityArgs(exe, "verylow"));
        assertEquals("-screen-quality \"Very Low\"", LaunchActivity.qualityArgs(exe, "minimum"));
        assertNull(LaunchActivity.qualityArgs(exe, null));
        assertNull(LaunchActivity.qualityArgs(exe, "ultra"));
    }

    @Test
    public void qualiteUnreal() throws IOException {
        File binaries = new File(folder.newFolder(), "Jeu/Binaries/Win64");
        assertTrue(binaries.mkdirs());
        String args = LaunchActivity.qualityArgs(new File(binaries, "Jeu-Win64-Shipping.exe"), "medium");
        assertTrue(args.startsWith("-ini:GameUserSettings:[ScalabilityGroups]:sg.ResolutionQuality=90,"));
        assertTrue(args.contains(",[ScalabilityGroups]:sg.ShadowQuality=1"));
        assertFalse(args.contains(" "));
        String minimum = LaunchActivity.qualityArgs(new File(binaries, "Jeu-Win64-Shipping.exe"), "minimum");
        assertTrue(minimum.startsWith("-ini:GameUserSettings:[ScalabilityGroups]:sg.ResolutionQuality=33,"));
        assertTrue(minimum.contains(",[ScalabilityGroups]:sg.ShadowQuality=0"));
        // Lanceur à la racine du jeu, à côté du dossier Engine.
        File root = folder.newFolder();
        assertTrue(new File(root, "Engine/Binaries").mkdirs());
        assertTrue(LaunchActivity.isUnreal(new File(root, "Jeu.exe")));
        // Autre moteur : rien.
        assertNull(LaunchActivity.qualityArgs(new File(game("Jeu.exe"), "Jeu.exe"), "low"));
    }

    @Test
    public void lanceurUnrealRemplaceParLeJeu() throws IOException {
        File root = folder.newFolder();
        assertTrue(new File(root, "Engine/Binaries/Win64").mkdirs());
        assertTrue(new File(root, "Frostpunk2/Binaries/Win64").mkdirs());
        File shipping = new File(root, "Frostpunk2/Binaries/Win64/Frostpunk2-Win64-Shipping.exe");
        assertTrue(shipping.createNewFile());
        assertEquals(shipping, LaunchActivity.unrealShipping(new File(root, "Frostpunk2.exe")));
        // Déjà le jeu, ou autre moteur : inchangé.
        assertEquals(shipping, LaunchActivity.unrealShipping(shipping));
        File other = new File(game("Jeu.exe"), "Jeu.exe");
        assertEquals(other, LaunchActivity.unrealShipping(other));
        // Lanceur Unreal sans exécutable Shipping : inchangé.
        File empty = folder.newFolder();
        assertTrue(new File(empty, "Engine/Binaries").mkdirs());
        assertEquals(new File(empty, "Jeu.exe"), LaunchActivity.unrealShipping(new File(empty, "Jeu.exe")));
    }

    @Test
    public void directX12ParVkd3d() throws IOException {
        // Agility SDK de DirectX 12 à côté de l'exécutable : VKD3D-Proton automatiquement.
        File binaries = new File(folder.newFolder(), "Frostpunk2/Binaries/Win64");
        assertTrue(new File(binaries, "D3D12").mkdirs());
        assertTrue(new File(binaries, "D3D12/D3D12Core.dll").createNewFile());
        File exe = new File(binaries, "Frostpunk2-Win64-Shipping.exe");
        assertTrue(LaunchActivity.useVkd3d(exe, null));
        assertFalse(LaunchActivity.useVkd3d(exe, "wine"));
        // Autre jeu : seulement s'il est choisi.
        File other = new File(game("Jeu.exe"), "Jeu.exe");
        assertFalse(LaunchActivity.useVkd3d(other, null));
        assertTrue(LaunchActivity.useVkd3d(other, "vkd3d"));
        assertEquals("VKD3D_SHADER_MODEL=6_6", LaunchActivity.vkd3dEnv(null));
        assertEquals("VKD3D_SHADER_MODEL=6_6 VKD3D_FRAME_RATE=30", LaunchActivity.vkd3dEnv("30"));
        assertEquals("A=1 VKD3D_SHADER_MODEL=6_6", LaunchActivity.withEnv("A=1 VKD3D_SHADER_MODEL=6_0", "VKD3D_SHADER_MODEL=6_6"));
        assertEquals("B=2", LaunchActivity.withEnv(null, "B=2"));
    }

    @Test
    public void reglagesDansLaConfigurationDeDxvk() {
        assertEquals("version=2.4.1,framerate=30,async=0", LaunchActivity.withConfig("version=2.4.1,framerate=0,async=0", "framerate", "30"));
        assertEquals("version=2.4.1,framerate=45", LaunchActivity.withConfig("version=2.4.1", "framerate", "45"));
        assertEquals("framerate=60,version=1", LaunchActivity.withConfig("framerate=0,version=1", "framerate", "60"));
        assertEquals("framerate=30", LaunchActivity.withConfig("", "framerate", "30"));
        // « async » et « asyncCache » : clés distinctes.
        assertEquals("version=1,async=1,asyncCache=0", LaunchActivity.withConfig("version=1,async=0,asyncCache=0", "async", "1"));
        assertEquals("2.4.1", LaunchActivity.configValue("version=2.4.1,framerate=0", "version"));
    }

    @Test
    public void versionDeDxvkPourLesShadersEnArrierePlan() {
        assertEquals("async-1.10.3", LaunchActivity.asyncDxvk("1.10.1", true));
        assertEquals("async-1.10.3", LaunchActivity.asyncDxvk(null, false));
        assertEquals("2.4.1-gplasync", LaunchActivity.asyncDxvk("2.4.1", false));
        assertEquals("2.3.1-arm64ec-gplasync", LaunchActivity.asyncDxvk("2.3.1-arm64ec", true));
        assertEquals("2.4-gplasync", LaunchActivity.asyncDxvk("2.4-gplasync", false));
    }

    @Test
    public void piloteLuDansLaConfiguration() {
        assertEquals("Turnip - Apr 13, 2026 - 41fecb5",
                LaunchActivity.driverId("version=Turnip - Apr 13, 2026 - 41fecb5;blacklistedExtensions=;adrenotoolsTurnip=1"));
        assertNull(LaunchActivity.driverId("blacklistedExtensions="));
        assertNull(LaunchActivity.driverId(null));
    }

    @Test
    public void autresJeuxNonReconnus() throws IOException {
        // Unity Mono (sans GameAssembly.dll), jeu non Unity, dossier absent.
        assertFalse(LaunchActivity.isUnityIl2cpp(game("Jeu.exe", "UnityPlayer.dll")));
        assertFalse(LaunchActivity.isUnityIl2cpp(game("Jeu.exe", "GameAssembly.dll")));
        assertFalse(LaunchActivity.isUnityIl2cpp(null));
    }
}
