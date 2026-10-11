// Barre de titre de la fenêtre : couleur du fond de l'écran affiché (noir pour les systèmes, couleur
// du système pour sa liste de jeux et leur fiche), texte et boutons clairs ou foncés selon elle.
// Windows 11 seulement pour la couleur ; Windows 10 : thème sombre ou clair de la barre.
use crate::error::{AppError, Result};
use std::ffi::c_void;

#[cfg(windows)]
#[link(name = "dwmapi")]
extern "system" {
    fn DwmSetWindowAttribute(hwnd: *mut c_void, attribute: u32, value: *const c_void, size: u32) -> i32;
}

const DWMWA_USE_IMMERSIVE_DARK_MODE: u32 = 20;
const DWMWA_CAPTION_COLOR: u32 = 35;
const DWMWA_TEXT_COLOR: u32 = 36;

/// Couleur « #rrggbb » : composantes rouge, vert, bleu.
pub fn parse(hex: &str) -> Option<(u8, u8, u8)> {
    let h = hex.strip_prefix('#')?;
    if h.len() != 6 || !h.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let byte = |i: usize| u8::from_str_radix(&h[i..i + 2], 16).ok();
    Some((byte(0)?, byte(2)?, byte(4)?))
}

/// Fond foncé (luminance relative sous 0,5) : texte blanc, boutons du thème sombre.
pub fn is_dark((r, g, b): (u8, u8, u8)) -> bool {
    (0.2126 * r as f64 + 0.7152 * g as f64 + 0.0722 * b as f64) / 255.0 < 0.5
}

/// COLORREF de Windows (0x00BBGGRR).
fn colorref((r, g, b): (u8, u8, u8)) -> u32 {
    r as u32 | (g as u32) << 8 | (b as u32) << 16
}

/// Barre de titre de [window] de la couleur [hex] (« #rrggbb »).
pub fn set_color(window: &tauri::WebviewWindow, hex: &str) -> Result<()> {
    let rgb = parse(hex).ok_or_else(|| AppError::msg(format!("couleur invalide : {hex}")))?;
    #[cfg(windows)]
    {
        let hwnd = window.hwnd().map_err(|e| AppError::msg(e.to_string()))?.0 as *mut c_void;
        let dark: i32 = is_dark(rgb) as i32;
        let caption = colorref(rgb);
        let text = if dark != 0 { 0x00FF_FFFF_u32 } else { 0 };
        let set = |attribute: u32, value: *const c_void, size: usize| unsafe { DwmSetWindowAttribute(hwnd, attribute, value, size as u32) };
        // Thème de la barre (boutons) d'abord : seul effet sous Windows 10, où la couleur est refusée.
        set(DWMWA_USE_IMMERSIVE_DARK_MODE, &dark as *const i32 as *const c_void, 4);
        set(DWMWA_CAPTION_COLOR, &caption as *const u32 as *const c_void, 4);
        set(DWMWA_TEXT_COLOR, &text as *const u32 as *const c_void, 4);
    }
    #[cfg(not(windows))]
    let _ = (window, rgb);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn couleur_de_la_barre() {
        assert_eq!(parse("#3a2a5c"), Some((0x3a, 0x2a, 0x5c)));
        assert_eq!(parse("3a2a5c"), None);
        assert_eq!(parse("#3a2a5"), None);
        assert_eq!(colorref((0x11, 0x22, 0x33)), 0x0033_2211);
        assert!(is_dark((0x11, 0x13, 0x18)));
        assert!(!is_dark((0xf0, 0xe0, 0x90)));
    }
}
