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
        assertTrue(minimum.startsWith("-ini:GameUserSettings:[ScalabilityGroups]:sg.ResolutionQuality=50,"));
        assertTrue(minimum.contains(",[ScalabilityGroups]:sg.ShadowQuality=0"));
        // Lanceur à la racine du jeu, à côté du dossier Engine.
        File root = folder.newFolder();
        assertTrue(new File(root, "Engine/Binaries").mkdirs());
        assertTrue(LaunchActivity.isUnreal(new File(root, "Jeu.exe")));
        // Autre moteur : rien.
        assertNull(LaunchActivity.qualityArgs(new File(game("Jeu.exe"), "Jeu.exe"), "low"));
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
