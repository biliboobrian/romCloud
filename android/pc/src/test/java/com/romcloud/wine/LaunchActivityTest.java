package com.romcloud.wine;

import static org.junit.Assert.assertFalse;
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
    public void autresJeuxNonReconnus() throws IOException {
        // Unity Mono (sans GameAssembly.dll), jeu non Unity, dossier absent.
        assertFalse(LaunchActivity.isUnityIl2cpp(game("Jeu.exe", "UnityPlayer.dll")));
        assertFalse(LaunchActivity.isUnityIl2cpp(game("Jeu.exe", "GameAssembly.dll")));
        assertFalse(LaunchActivity.isUnityIl2cpp(null));
    }
}
